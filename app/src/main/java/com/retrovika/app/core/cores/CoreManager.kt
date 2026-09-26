package com.retrovika.app.core.cores

import com.retrovika.app.R
import com.retrovika.app.core.settings.localized
import android.content.Context
import android.os.Build
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.storage.Zip
import com.retrovika.app.core.systems.CoreInfo
import com.retrovika.app.core.systems.SystemAsset
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

    val abi: String = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS } ?: "arm64-v8a"

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
                setState(core.id, CoreState.Failed(t.userMessage(context)))
                throw t
            }
            // Tira o estado "Baixando" deixado pelo progresso dos assets.
            refresh()
            return path
        }
        setState(core.id, CoreState.Downloading(0f))
        return try {
            val zip = File(paths.downloadsTmp, "${core.id}.zip")
            Http.download(downloadUrl(core.id), zip) { p -> setState(core.id, CoreState.Downloading(p * 0.9f)) }
            val target = downloadedFile(core.id)
            val tmp = File(paths.cores, "${core.id}.tmp")
            Zip.extractFirst(zip, tmp) { it.endsWith(".so") } ?: error(context.localized().getString(R.string.cores_no_library))
            zip.delete()
            target.delete()
            tmp.renameTo(target)
            // Bibliotecas carregadas dinamicamente devem ser somente leitura (exigência do Android 14+).
            target.setWritable(false, false)
            target.setReadOnly()
            installAssets(core)
            refresh()
            target.absolutePath
        } catch (t: Throwable) {
            setState(core.id, CoreState.Failed(t.userMessage(context)))
            throw t
        }
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
        _states.update { it - coreId }
        refresh()
    }

    private fun setState(coreId: String, state: CoreState) = _states.update { it + (coreId to state) }

    companion object {
        val SUPPORTED_ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
    }
}
