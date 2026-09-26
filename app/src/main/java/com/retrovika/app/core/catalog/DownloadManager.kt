package com.retrovika.app.core.catalog

import android.content.Context
import com.retrovika.app.R
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.settings.localized
import com.retrovika.app.core.library.LibraryRepository
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.storage.RomExtractor
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.storage.Archives
import com.retrovika.app.core.storage.FileNames
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.core.library.RomNaming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus { QUEUED, DOWNLOADING, EXTRACTING, DONE, FAILED, CANCELED }

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val systemId: String,
    val coverUrl: String?,
    val progress: Float = 0f,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val gameId: Long? = null,
)

class DownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val paths: StoragePaths,
    private val library: LibraryRepository,
    /** Converte a entrada no link final de download (ex.: Internet Archive resolve o arquivo). */
    private val resolve: suspend (CatalogEntry) -> CatalogEntry = { it },
) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    /** Acessado pela UI (cancelar) e pelas corrotinas dos downloads ao mesmo tempo. */
    private val jobs = ConcurrentHashMap<String, Job>()

    fun enqueue(entry: CatalogEntry) {
        val system = Systems.byId(entry.systemId) ?: return
        if (_tasks.value.any { it.title == entry.title && it.status in ACTIVE }) return
        val task = DownloadTask(title = entry.title, systemId = system.id, coverUrl = entry.coverUrl)
        launchTask(task) {
            val resolved = resolve(entry)
            runDownload(task.id, resolved.downloadUrl, resolved.fileName, system, entry.title, entry.coverUrl, entry.developer, entry.tags.joinToString(" · ").ifBlank { null }, refererOf(entry))
        }
    }

    /** Baixa a variante (região/revisão) escolhida pelo usuário para uma entrada. */
    fun enqueue(entry: CatalogEntry, variant: RomVariant) {
        val system = Systems.byId(entry.systemId) ?: return
        if (_tasks.value.any { it.title == entry.title && it.status in ACTIVE }) return
        val task = DownloadTask(title = entry.title, systemId = system.id, coverUrl = entry.coverUrl)
        launchTask(task) {
            runDownload(task.id, variant.downloadUrl, variant.fileName, system, entry.title, entry.coverUrl, entry.developer, entry.tags.joinToString(" · ").ifBlank { null }, refererOf(entry))
        }
    }

    /** Download a partir de um link fornecido pelo próprio usuário (ex.: link direto na nuvem). */
    fun enqueueUrl(url: String, system: GameSystem) {
        val name = url.substringAfterLast('/').substringBefore('?').ifBlank { context.localized().getString(R.string.download_default_name) }.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val task = DownloadTask(title = name.substringBeforeLast('.'), systemId = system.id, coverUrl = null)
        launchTask(task) { runDownload(task.id, url, name, system, task.title, null, null, null) }
    }

    /**
     * Download capturado no navegador interno. [headers] leva cookies, User-Agent e Referer do WebView,
     * para que o arquivo seja pedido com a mesma sessão que o usuário abriu no site.
     */
    fun enqueueBrowser(url: String, fileName: String, system: GameSystem, headers: Map<String, String>) {
        val task = DownloadTask(title = RomNaming.cleanTitle(fileName.substringBeforeLast('.')), systemId = system.id, coverUrl = null)
        launchTask(task) { runDownload(task.id, url, fileName, system, task.title, null, null, null, headers) }
    }

    /** Alguns servidores (CDRomance, por exemplo) só liberam o arquivo vindo da página do jogo. */
    private fun refererOf(entry: CatalogEntry): Map<String, String> =
        entry.website?.takeIf { it.startsWith("http") }?.let { mapOf("Referer" to it) }.orEmpty()

    private fun launchTask(task: DownloadTask, block: suspend () -> Unit) {
        _tasks.update { listOf(task) + it }
        // LAZY: o job só começa depois de registrado, senão um download muito rápido
        // removeria a entrada antes dela existir e deixaria um job morto no mapa.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                update(task.id) { it.copy(status = DownloadStatus.DOWNLOADING) }
                block()
            } catch (t: kotlinx.coroutines.CancellationException) {
                update(task.id) { it.copy(status = DownloadStatus.CANCELED) }
                throw t
            } catch (t: Throwable) {
                update(task.id) { it.copy(status = DownloadStatus.FAILED, error = t.userMessage(context)) }
            } finally {
                jobs.remove(task.id)
            }
        }
        jobs[task.id] = job
        job.start()
    }

    private suspend fun runDownload(
        taskId: String, url: String, fileName: String, system: GameSystem, title: String,
        cover: String?, developer: String?, description: String?, headers: Map<String, String> = emptyMap(),
    ) {
        val dir = paths.romsFor(system.id)
        val target = File(dir, FileNames.safe(fileName))
        var file = Http.download(url, target, headers) { p -> update(taskId) { it.copy(progress = p) } }
        // Página de erro/aviso salva como se fosse o jogo: melhor avisar do que "extrair" HTML.
        if (withContext(Dispatchers.IO) { Archives.isHtml(file) }) {
            file.delete()
            throw LocalizedException(R.string.download_got_webpage)
        }
        if (Archives.isArchive(file) && !system.keepArchives) {
            update(taskId) { it.copy(status = DownloadStatus.EXTRACTING) }
            file = withContext(Dispatchers.IO) { RomExtractor.extract(file, dir, system) }
        }
        val gameId = library.addDownloaded(system, file, title, cover, developer, description)
        update(taskId) { it.copy(status = DownloadStatus.DONE, progress = 1f, gameId = gameId) }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    fun clearFinished() = _tasks.update { list -> list.filter { it.status in ACTIVE } }

    private fun update(id: String, block: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }

    companion object {
        val ACTIVE = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.EXTRACTING)
    }
}
