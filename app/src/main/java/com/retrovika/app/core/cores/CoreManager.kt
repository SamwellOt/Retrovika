package com.retrovika.app.core.cores

import com.retrovika.app.R
import android.content.Context
import android.os.Build
import android.os.Process
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.storage.Zip
import com.retrovika.app.core.systems.CoreInfo
import com.retrovika.app.core.systems.SystemAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

sealed interface CoreState {
    data object NotInstalled : CoreState
    data class Downloading(val progress: Float) : CoreState
    data class Installed(val bundled: Boolean, val size: Long) : CoreState
    data class Failed(val message: String) : CoreState
}

/**
 * Gerencia os núcleos libretro.
 *
 * Um núcleo pode vir de duas fontes:
 * 1. **Embutido no APK** (jniLibs), quando o app é compilado com `-PbundleCores=...`.
 * 2. **Baixado sob demanda** do buildbot oficial do libretro na primeira vez que o sistema é usado.
 */
class CoreManager(private val context: Context, private val paths: StoragePaths) {

    private val _states = MutableStateFlow<Map<String, CoreState>>(emptyMap())
    val states: StateFlow<Map<String, CoreState>> = _states.asStateFlow()

    /**
     * ABI do processo, não do aparelho: um .so só carrega se for da mesma arquitetura em que o app roda
     * (num x86 que roda o APK ARM por tradução, por exemplo, o núcleo precisa ser ARM).
     */
    val abi: String = (if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
        .firstOrNull { it in SUPPORTED_ABIS } ?: "arm64-v8a"

    init { refresh() }

    fun refresh() {
        val bundled = File(context.applicationInfo.nativeLibraryDir)
            .listFiles { f -> f.name.endsWith("_libretro_android.so") }.orEmpty()
            .associate { it.name.removePrefix("lib").removeSuffix("_libretro_android.so") to CoreState.Installed(true, it.length()) }
        val downloaded = paths.cores.listFiles { f -> f.name.endsWith("_libretro_android.so") }.orEmpty()
            .associate { it.name.removeSuffix("_libretro_android.so") to CoreState.Installed(false, it.length()) }
        _states.update { current ->
            val transient = current.filterValues { it is CoreState.Downloading || it is CoreState.Failed }
            transient + downloaded + bundled
        }
    }

    fun state(coreId: String): CoreState = _states.value[coreId] ?: CoreState.NotInstalled

    fun isInstalled(coreId: String) = state(coreId) is CoreState.Installed

    /** Caminho absoluto do .so pronto para o dlopen, ou null se ainda não instalado. */
    fun corePath(coreId: String): String? {
        val bundled = File(context.applicationInfo.nativeLibraryDir, "lib${coreId}_libretro_android.so")
        if (bundled.exists()) return bundled.absolutePath
        val downloaded = downloadedFile(coreId)
        return downloaded.takeIf { it.exists() }?.absolutePath
    }

    private fun downloadedFile(coreId: String) = File(paths.cores, "${coreId}_libretro_android.so")

    fun downloadUrl(coreId: String) =
        "https://buildbot.libretro.com/nightly/android/latest/$abi/${coreId}_libretro_android.so.zip"

    /** Instala o núcleo e os assets de sistema que ele exige. Retorna o caminho do .so. */
    suspend fun install(core: CoreInfo): String = withContext(Dispatchers.IO) {
        // Dois pedidos simultâneos (tela de núcleos + abertura do jogo) disputariam o mesmo .zip temporário.
        lockFor(core.id).withLock { installLocked(core) }
    }

    /** Verdadeiro se falta o .so ou algum pacote de assets do núcleo. */
    fun needsInstall(core: CoreInfo): Boolean =
        corePath(core.id) == null || core.systemAssets.any { !assetMarker(it).exists() }

    private fun assetMarker(asset: SystemAsset) = File(paths.system, ".asset-${asset.name}")

    private val locks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(coreId: String) = locks.getOrPut(coreId) { Mutex() }

    private suspend fun installLocked(core: CoreInfo): String {
        corePath(core.id)?.let { path ->
            if (core.systemAssets.none { !assetMarker(it).exists() }) return path
            try {
                installAssets(core)
            } catch (t: Throwable) {
                fail(core.id, t)
            }
            // Tira o estado "Baixando" deixado pelo progresso dos assets.
            refresh()
            return path
        }
        setState(core.id, CoreState.Downloading(0f))
        // A versão do buildbot, para saber depois se saiu uma mais nova ([stageUpdate]); sem ela, vale a data do arquivo.
        val version = runCatching { Http.fileVersion(downloadUrl(core.id))?.first }.getOrNull()
        return try {
            val zip = File(paths.downloadsTmp, "${core.id}.zip")
            val target = downloadedFile(core.id)
            val tmp = File(paths.cores, "${core.id}.tmp")
            try {
                try {
                    Http.download(downloadUrl(core.id), zip) { p -> setState(core.id, CoreState.Downloading(p * 0.9f)) }
                } catch (e: LocalizedException) {
                    // O buildbot não compila todo núcleo para toda arquitetura (Citra, Panda3DS e LRPS2 não
                    // têm armeabi-v7a): o 404 vira um motivo que o usuário entende.
                    if (e.messageRes == R.string.download_http_error && e.args.firstOrNull() == 404) {
                        throw LocalizedException(R.string.cores_unavailable_abi, core.displayName, abi)
                    }
                    throw e
                }
                Zip.extractFirst(zip, tmp) { it.endsWith(".so") } ?: throw LocalizedException(R.string.cores_no_library)
                // Uma página de erro ou um .so de outra arquitetura só falharia no dlopen, já com o jogo abrindo
                // e sem dizer por quê: confere o cabeçalho ELF antes de instalar.
                if (!elfMatches(readHeader(tmp), abi)) {
                    tmp.delete()
                    throw LocalizedException(R.string.cores_invalid_library, core.displayName, abi)
                }
            } finally {
                // Um .zip que falhou na extração não serve para nada e ocuparia o cache.
                zip.delete()
            }
            target.delete()
            if (!tmp.renameTo(target)) throw LocalizedException(R.string.download_move_failed, tmp.name)
            // Bibliotecas carregadas dinamicamente devem ser somente leitura (exigência do Android 14+).
            target.setWritable(false, false)
            target.setReadOnly()
            version?.let { writeVersion(versionFile(core.id), it) }
            installAssets(core)
            refresh()
            target.absolutePath
        } catch (t: Throwable) {
            fail(core.id, t)
        }
    }

    /** Cancelamento (o usuário saiu da tela) não é falha: o estado volta ao que está no disco. */
    private fun fail(coreId: String, t: Throwable): Nothing {
        if (t is CancellationException) {
            _states.update { it - coreId }
            refresh()
        } else {
            setState(coreId, CoreState.Failed(t.userMessage(context)))
        }
        throw t
    }

    private suspend fun installAssets(core: CoreInfo) {
        for (asset in core.systemAssets) {
            // Marcador gravado só após a extração completa: uma pasta não vazia não prova nada
            // (a BIOS do PS2 importada em pcsx2/bios, por exemplo, ou uma extração interrompida).
            val marker = assetMarker(asset)
            if (marker.exists()) continue
            val zip = File(paths.downloadsTmp, "${asset.name}-assets.zip")
            try {
                Http.download(asset.url, zip) { p -> setState(core.id, CoreState.Downloading(0.9f + p * 0.1f)) }
                Zip.extractAll(zip, paths.system)
            } finally {
                zip.delete()
            }
            marker.createNewFile()
        }
    }

    fun uninstall(coreId: String) {
        downloadedFile(coreId).let { it.setWritable(true); it.delete() }
        stagedFile(coreId).let { it.setWritable(true); it.delete() }
        versionFile(coreId).delete()
        stagedVersionFile(coreId).delete()
        _states.update { it - coreId }
        refresh()
    }

    // region Atualização

    /** Versão do núcleo baixado (ETag do buildbot) ao lado do .so; a versão nova à espera da próxima abertura. */
    private fun versionFile(coreId: String) = File(paths.cores, "$coreId.version")
    private fun stagedFile(coreId: String) = File(paths.cores, "$coreId.next")
    private fun stagedVersionFile(coreId: String) = File(paths.cores, "$coreId.next.version")
    private fun checkedFile(coreId: String) = File(paths.cores, "$coreId.checked")

    private fun writeVersion(file: File, version: String) = runCatching { file.writeText(version) }

    private fun bundled(coreId: String) = File(context.applicationInfo.nativeLibraryDir, "lib${coreId}_libretro_android.so").exists()

    /**
     * Identifica a versão instalada de [coreId]: muda quando o núcleo é atualizado. O que foi aprendido sobre uma
     * versão (o Vulkan dela derruba o app, por exemplo) não precisa valer para a seguinte.
     */
    fun buildId(coreId: String): String {
        if (bundled(coreId)) return "apk:" + runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return runCatching { versionFile(coreId).readText() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "file:${downloadedFile(coreId).lastModified()}"
    }

    /**
     * Baixa em segundo plano a versão nova de [core], se o buildbot tiver uma, para valer na próxima abertura
     * ([applyStagedUpdate]): o .so em uso não é trocado no meio do jogo. Só para núcleos baixados (os do APK vêm com o
     * app), no máximo a cada [UPDATE_CHECK_MS] e fora de rede limitada (dados móveis). Os núcleos experimentais (o
     * LRPS2, por exemplo) recebem correções de quedas quase todo dia, e o que foi baixado na primeira vez ficava para
     * sempre. Verdadeiro se uma versão nova ficou pronta.
     */
    suspend fun stageUpdate(core: CoreInfo): Boolean = withContext(Dispatchers.IO) {
        val installed = downloadedFile(core.id)
        if (bundled(core.id) || !installed.exists() || metered()) return@withContext false
        val checked = checkedFile(core.id)
        if (System.currentTimeMillis() - checked.lastModified() < UPDATE_CHECK_MS) return@withContext false
        lockFor(core.id).withLock {
            val url = downloadUrl(core.id)
            val (remote, modified) = Http.fileVersion(url) ?: return@withLock false
            runCatching { checked.writeText(remote) }
            val staged = runCatching { stagedVersionFile(core.id).readText() }.getOrNull()
            if (staged == remote && stagedFile(core.id).exists()) return@withLock false
            val current = runCatching { versionFile(core.id).readText() }.getOrNull()
            // Sem a versão gravada (instalado por uma versão anterior do app): mais nova que o download dele.
            val newer = if (current != null) current != remote else modified != null && modified > installed.lastModified()
            if (!newer) return@withLock false

            val zip = File(paths.downloadsTmp, "${core.id}-update.zip")
            val tmp = File(paths.cores, "${core.id}.next.tmp")
            try {
                Http.download(url, zip)
                Zip.extractFirst(zip, tmp) { it.endsWith(".so") } ?: return@withLock false
                if (!elfMatches(readHeader(tmp), abi)) return@withLock false
                val target = stagedFile(core.id)
                target.setWritable(true)
                target.delete()
                if (!tmp.renameTo(target)) return@withLock false
                target.setWritable(false, false)
                target.setReadOnly()
                writeVersion(stagedVersionFile(core.id), remote)
                true
            } finally {
                zip.delete()
                tmp.delete()
            }
        }
    }

    /**
     * Põe no lugar a versão que [stageUpdate] deixou pronta. Chamar antes de abrir o núcleo: o arquivo antigo pode
     * continuar mapeado por um núcleo que o Android não descarregou, e segue valendo para ele (o novo é outro inode).
     * Verdadeiro se trocou.
     */
    suspend fun applyStagedUpdate(coreId: String): Boolean = withContext(Dispatchers.IO) {
        val staged = stagedFile(coreId)
        if (!staged.exists() || bundled(coreId)) return@withContext false
        lockFor(coreId).withLock {
            val target = downloadedFile(coreId)
            target.setWritable(true)
            if (target.exists() && !target.delete()) return@withLock false
            if (!staged.renameTo(target)) return@withLock false
            target.setWritable(false, false)
            target.setReadOnly()
            val version = stagedVersionFile(coreId)
            if (!version.renameTo(versionFile(coreId))) versionFile(coreId).delete()
            refresh()
            true
        }
    }

    private fun metered(): Boolean = runCatching {
        context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(true)

    // endregion

    private fun setState(coreId: String, state: CoreState) = _states.update { it + (coreId to state) }

    companion object {
        /** As mesmas de `abiFilters` no build.gradle.kts. */
        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

        /** Intervalo mínimo entre duas consultas ao buildbot por versão nova de um núcleo. */
        private const val UPDATE_CHECK_MS = 24 * 60 * 60 * 1000L

        /** Classe ELF (1 = 32 bits, 2 = 64 bits) e e_machine de cada ABI. */
        private val ELF_TARGETS = mapOf(
            "arm64-v8a" to (2 to 183),
            "armeabi-v7a" to (1 to 40),
            "x86_64" to (2 to 62),
            "x86" to (1 to 3),
        )

        private fun readHeader(file: File): ByteArray = runCatching {
            file.inputStream().use { input ->
                val b = ByteArray(20)
                b.copyOf(input.readNBytesCompat(b))
            }
        }.getOrDefault(ByteArray(0))

        private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
            var total = 0
            while (total < buf.size) {
                val n = read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            return total
        }

        /**
         * [header] (os primeiros 20 bytes) é de uma biblioteca ELF little-endian para [abi]: assinatura
         * 0x7F 'E' 'L' 'F', a classe (32/64 bits) e a máquina. ABI desconhecida confere só a assinatura.
         */
        internal fun elfMatches(header: ByteArray, abi: String): Boolean {
            if (header.size < 20) return false
            if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() || header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) return false
            if (header[5] != 1.toByte()) return false
            val (elfClass, machine) = ELF_TARGETS[abi] ?: return true
            val eMachine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
            return header[4].toInt() == elfClass && eMachine == machine
        }
    }
}
