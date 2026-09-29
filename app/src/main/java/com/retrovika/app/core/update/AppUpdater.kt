package com.retrovika.app.core.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import com.retrovika.app.R
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.settings.localized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/** Uma release publicada no GitHub com o APK anexado. */
data class AppRelease(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    /** [progress] é -1 enquanto o tamanho não é conhecido. */
    data class Downloading(val release: AppRelease, val progress: Float) : UpdateState
    /** APK baixado e conferido; falta o usuário confirmar a instalação. */
    data class Downloaded(val release: AppRelease) : UpdateState
    data class Installing(val release: AppRelease) : UpdateState
    /**
     * [release] é nulo quando a falha foi ao procurar a versão nova. [retry] é falso quando baixar de
     * novo não resolve (assinatura diferente, arquivo que não é do app).
     */
    data class Failed(val release: AppRelease?, val message: String, val retry: Boolean = true) : UpdateState
}

/**
 * Atualiza o app pelas releases do GitHub: procura a última, baixa o APK e o entrega ao instalador
 * do Android (PackageInstaller). O Android só aceita o APK se a assinatura for a mesma do app
 * instalado, então isso é conferido antes, para dar um aviso claro em vez do erro genérico do sistema.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private val dir get() = File(context.cacheDir, "updates")

    val currentVersion: String by lazy {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"
    }

    @Volatile
    private var startupChecked = false

    /** Uma vez por processo, como a varredura inicial: recriar a Activity não repete a consulta. */
    fun checkOnStartup(enabled: Boolean) {
        if (startupChecked) return
        startupChecked = true
        if (enabled) check(manual = false)
    }

    /**
     * Procura uma versão nova. A verificação automática ([manual] falso) não mostra "verificando" nem
     * erro de rede: sem internet ao abrir o app, simplesmente não aparece nada.
     */
    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Downloaded, is UpdateState.Installing -> return
            else -> {}
        }
        job = scope.launch {
            if (manual) _state.value = UpdateState.Checking
            _state.value = try {
                val release = parseRelease(Http.getString(LATEST_URL, mapOf("Accept" to "application/vnd.github+json")))
                if (release != null && Versions.newer(release.version, currentVersion)) UpdateState.Available(release)
                else {
                    // Nada novo: o APK de uma atualização já instalada não serve mais.
                    withContext(Dispatchers.IO) { dir.deleteRecursively() }
                    UpdateState.UpToDate
                }
            } catch (e: Exception) {
                if (manual) UpdateState.Failed(null, e.userMessage(context)) else UpdateState.Idle
            }
        }
    }

    /** Baixa o APK de [release] e, conferido, abre a instalação. */
    fun download(release: AppRelease) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, -1f)
            try {
                val apk = withContext(Dispatchers.IO) {
                    dir.deleteRecursively()
                    Http.download(release.apkUrl, apkFile(release)) { p -> _state.value = UpdateState.Downloading(release, p) }
                }
                verify(apk)
                _state.value = UpdateState.Downloaded(release)
                install(release)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val final = e is LocalizedException && e.messageRes in setOf(R.string.update_error_signature, R.string.update_error_invalid, R.string.update_error_version)
                _state.value = UpdateState.Failed(release, e.userMessage(context), retry = !final)
            }
        }
    }

    /**
     * Entrega o APK baixado ao instalador. Sem a permissão de instalar apps, abre a tela do sistema
     * para concedê-la; de volta ao app, o botão "Instalar" continua ali.
     */
    fun install(release: AppRelease) {
        val apk = apkFile(release)
        if (!apk.exists()) return download(release)
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(settings) }
            _state.value = UpdateState.Downloaded(release)
            return
        }
        _state.value = UpdateState.Installing(release)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(apk) }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(release, e.userMessage(context))
            }
        }
    }

    /**
     * O instalador pediu a confirmação do usuário. O botão "Instalar" volta já: se a tela de confirmação
     * não abrir (app em segundo plano) ou for deixada pelo Home, nenhum resultado chega e o estado
     * "Instalando" ficaria preso sem saída.
     */
    internal fun onConfirmRequested() {
        (_state.value as? UpdateState.Installing)?.let { _state.value = UpdateState.Downloaded(it.release) }
    }

    /** Resposta do instalador (ver [UpdateInstallReceiver]). */
    internal fun onInstallResult(status: Int, message: String?) {
        val release = when (val s = _state.value) {
            is UpdateState.Installing -> s.release
            is UpdateState.Downloaded -> s.release
            else -> return
        }
        _state.value = when (status) {
            // Cancelou a confirmação: o botão "Instalar" volta.
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateState.Downloaded(release)
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed(release, context.localized().getString(R.string.common_error_no_space))
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed(release, context.localized().getString(R.string.update_error_signature), retry = false)
            else -> UpdateState.Failed(release, context.localized().getString(R.string.update_error_install, message ?: status.toString()))
        }
    }

    private fun apkFile(release: AppRelease) = File(dir, "Retrovika-${release.version}.apk")

    /** O APK precisa ser deste app e ter a mesma assinatura, senão o Android recusa a instalação. */
    private fun verify(apk: File) {
        val pm = context.packageManager
        val archive = packageInfo { flags -> pm.getPackageArchiveInfo(apk.path, flags) }
            ?: throw LocalizedException(R.string.update_error_invalid)
        if (archive.packageName != context.packageName) throw LocalizedException(R.string.update_error_invalid)
        val installed = packageInfo { flags -> pm.getPackageInfo(context.packageName, flags) }
        val theirs = signers(archive)
        // Sem assinatura legível o instalador recusaria de qualquer jeito, com uma mensagem genérica.
        if (theirs.isEmpty()) throw LocalizedException(R.string.update_error_invalid)
        if (installed != null && signers(installed).intersect(theirs).isEmpty()) {
            throw LocalizedException(R.string.update_error_signature)
        }
        // O Android recusa um versionCode que não seja maior (seria um downgrade), com o mesmo status de chave errada.
        if (installed != null && PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) {
            throw LocalizedException(R.string.update_error_version)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(get: (Int) -> PackageInfo?): PackageInfo? =
        get(if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)

    private fun Array<out Signature>?.orEmpty(): Array<out Signature> = this ?: emptyArray()

    /** Certificados de assinatura, incluindo os anteriores de uma rotação de chave. */
    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val s = info.signingInfo ?: return emptySet()
            s.apkContentsSigners.orEmpty().toList() + s.signingCertificateHistory.orEmpty().toList()
        } else info.signatures.orEmpty().toList()
        return sigs.map { it.toCharsString() }.toSet()
    }

    private fun commit(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Quando o próprio Retrovika instalou a versão atual, o Android 12+ atualiza sem pedir confirmação.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("retrovika.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // Mutável: o instalador preenche o status e a tela de confirmação nos extras.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, UpdateInstallReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }

    companion object {
        const val RELEASES_URL = "https://github.com/SamwellOt/Retrovika/releases"
        private const val LATEST_URL = "https://api.github.com/repos/SamwellOt/Retrovika/releases/latest"

        /** Lê a resposta de `releases/latest`; nulo se não há APK anexado. */
        internal fun parseRelease(json: String): AppRelease? {
            val o = Http.json.parseToJsonElement(json).jsonObject
            if (o.flag("draft") || o.flag("prerelease")) return null
            val tag = o.text("tag_name") ?: return null
            val apk = o["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
                .firstOrNull { it.text("name").orEmpty().endsWith(".apk", ignoreCase = true) } ?: return null
            return AppRelease(
                version = tag.removePrefix("v"),
                notes = notesOf(o.text("body").orEmpty()),
                apkUrl = apk.text("browser_download_url") ?: return null,
                apkSize = apk["size"]?.jsonPrimitive?.longOrNull ?: 0,
                pageUrl = o.text("html_url") ?: RELEASES_URL,
            )
        }

        /**
         * As notas da release em texto simples: sem a linha de instalação manual do topo, sem os
         * títulos ("Novidades", que o cartão já diz) e sem a marcação de citação, negrito e código.
         */
        internal fun notesOf(markdown: String): String = markdown.lines()
            .filterNot { it.startsWith("#") || it.startsWith("**Instala") || it.startsWith("**Install") }
            .map { it.trim().removePrefix(">").trim().replace("**", "").replace("`", "") }
            .joinToString("\n").replace(Regex("""\n{3,}"""), "\n\n").trim()

        private fun JsonObject.text(key: String) = get(key)?.jsonPrimitive?.contentOrNull
        private fun JsonObject.flag(key: String) = get(key)?.jsonPrimitive?.booleanOrNull == true
    }
}

object Versions {
    /** Verdadeiro se [remote] ("0.3.10") é maior que [local] ("0.3.9"); partes não numéricas são ignoradas. */
    fun newer(remote: String, local: String): Boolean {
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
}
