package com.retrovika.app.core.catalog

import android.content.Context
import android.webkit.CookieManager
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
import com.retrovika.app.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
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
    /** Fonte + entrada do catálogo ("fonte|id"), para ligar o download ao card certo no Explorar. */
    val entryKey: String? = null,
    /** Bytes já recebidos e o tamanho total (-1 quando o servidor não informa). */
    val bytesDone: Long = 0,
    val bytesTotal: Long = -1,
    /** Velocidade média recente, em bytes por segundo. */
    val speed: Long = 0,
    /** Servidor ocupado: hora (epoch ms) da próxima tentativa; 0 quando não está esperando. */
    val retryAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Segundos até terminar, quando dá para estimar. */
    val secondsLeft: Long? get() =
        if (status == DownloadStatus.DOWNLOADING && bytesTotal > 0 && speed > 0) (bytesTotal - bytesDone).coerceAtLeast(0) / speed else null
}

/**
 * Chave que liga uma entrada do catálogo aos seus downloads e à página do jogo: a do jogo ([dedupKey], console +
 * título), para que ela não mude quando a entrada é mesclada com outras páginas do mesmo jogo. Títulos sem
 * letras nem números usam a própria página.
 */
val CatalogEntry.downloadKey: String get() = dedupKey(this) ?: "$sourceId|$id"

class DownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val paths: StoragePaths,
    private val library: LibraryRepository,
    /** Lido a cada vaga aberta na fila: mudar o limite nos ajustes vale na hora. */
    private val settings: StateFlow<AppSettings>,
    /** Converte a entrada no link final de download (ex.: Internet Archive resolve o arquivo). */
    private val resolve: suspend (CatalogEntry) -> CatalogEntry = { it },
    /** Pedido final do arquivo da variante, gerado dentro do download (links assinados que expiram, como no RomsFun). */
    private val link: suspend (CatalogEntry, RomVariant) -> DirectLink = { _, v -> DirectLink(v.downloadUrl) },
) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    /** Só a quantidade em andamento: muda raramente, então quem só mostra o contador não recompõe a cada aviso de progresso. */
    val activeCount: StateFlow<Int> = _tasks.map { list -> list.count { it.status in ACTIVE } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 0)
    /** Acessado pela UI (cancelar) e pelas corrotinas dos downloads ao mesmo tempo. */
    private val jobs = ConcurrentHashMap<String, Job>()
    /** O trabalho de cada tarefa, guardado para "tentar de novo" refazer o mesmo pedido. */
    private val work = ConcurrentHashMap<String, suspend (String) -> Unit>()
    /**
     * O .part de cada tarefa que falhou no meio, para "tentar de novo" continuar dali (ver [Http.ResumePoint]).
     * Apagado quando a tarefa é cancelada, concluída ou sai da lista.
     */
    private val resumes = ConcurrentHashMap<String, Http.ResumePoint>()

    // Fila: no máximo AppSettings.maxDownloads baixando; os outros ficam QUEUED até abrir uma vaga.
    // Extrair também ocupa a vaga, porque disputa o mesmo disco.
    // A vaga vai sempre para o primeiro da fila de espera, na ordem em que os downloads foram pedidos.
    private val slotLock = Any()
    private var running = 0
    private val waiting = ArrayDeque<String>()
    private val slotFreed = MutableStateFlow(0L)

    /**
     * Limpa os .part deixados por downloads, importações e extrações interrompidos (app encerrado à
     * força, aparelho reiniciado). Roda uma vez ao criar o gerenciador; cada download espera por ela
     * antes de começar, para a limpeza nunca apagar o temporário de um download em andamento.
     */
    private val sweep: Job = scope.launch(Dispatchers.IO) { runCatching { library.sweepStaleParts() } }

    init {
        // Serviço em primeiro plano enquanto a fila não está vazia: sem ele o Android mata o processo, e o
        // download junto, pouco depois de o usuário sair do app. Ele mesmo se encerra quando a fila esvazia.
        scope.launch { activeCount.collect { n -> if (n > 0) DownloadService.start(context) } }
    }

    /** Apaga o .part guardado de uma tarefa que não vai mais continuar. */
    private fun discardResume(id: String) {
        resumes.remove(id)?.let { point -> scope.launch(Dispatchers.IO) { point.discard() } }
    }

    fun enqueue(entry: CatalogEntry) {
        val system = Systems.byId(entry.systemId) ?: return
        if (isActive(entry)) return
        val task = DownloadTask(title = entry.title, systemId = system.id, coverUrl = entry.coverUrl, entryKey = entry.downloadKey)
        launchTask(task) { id ->
            val resolved = resolve(entry)
            runCatalogDownload(id, entry, RomVariant(fileName = resolved.fileName, downloadUrl = resolved.downloadUrl), system)
        }
    }

    /** Baixa a variante (região/revisão) escolhida pelo usuário para uma entrada. */
    fun enqueue(entry: CatalogEntry, variant: RomVariant) {
        val system = Systems.byId(entry.systemId) ?: return
        if (isActive(entry)) return
        val task = DownloadTask(title = entry.title, systemId = system.id, coverUrl = entry.coverUrl, entryKey = entry.downloadKey)
        launchTask(task) { id -> runCatalogDownload(id, entry, variant, system) }
    }

    private suspend fun runCatalogDownload(taskId: String, entry: CatalogEntry, variant: RomVariant, system: GameSystem) {
        var refreshes = 0
        while (true) {
            val direct = link(entry, variant)
            var waited = false
            try {
                runDownload(
                    taskId, direct.url, direct.fileName ?: variant.fileName, system, entry.title, entry.coverUrl, entry.developer,
                    entry.tags.joinToString(" · ").ifBlank { null }, refererOf(variant.origin ?: entry) + direct.headers, ipv6 = direct.ipv6,
                    parallel = if ((variant.origin ?: entry).sourceId in SINGLE_CONNECTION_SOURCES) 1 else PARALLEL_CONNECTIONS,
                    onWaited = { waited = true },
                )
                return
            } catch (e: LocalizedException) {
                // Depois de minutos esperando o servidor ocupado, a liberação da verificação (cf_clearance) do servidor
                // de arquivos já venceu e ele recusa com 403: gera o link de novo (o que refaz a verificação) e continua.
                if (!waited || refreshes++ >= MAX_LINK_REFRESHES || !isRefusal(e)) throw e
                update(taskId) { it.copy(retryAt = 0, bytesDone = 0, bytesTotal = -1, progress = 0f) }
            }
        }
    }

    private fun isActive(entry: CatalogEntry) = _tasks.value.any { it.entryKey == entry.downloadKey && it.status in ACTIVE }

    /** Download a partir de um link fornecido pelo próprio usuário (ex.: link direto na nuvem). */
    fun enqueueUrl(url: String, system: GameSystem) {
        // Uri.decode não lança exceção com um "%" solto (URLDecoder derrubaria o app) nem troca "+" por espaço.
        val name = url.substringAfterLast('/').substringBefore('?').substringBefore('#').let { android.net.Uri.decode(it) }
            .ifBlank { context.localized().getString(R.string.download_default_name) }
        val task = DownloadTask(title = name.substringBeforeLast('.'), systemId = system.id, coverUrl = null)
        // O nome do fim do link pode não ser o do arquivo (o "uc" do Google Drive): vale o que o servidor disser.
        launchTask(task) { id -> runDownload(id, url, name, system, task.title, null, null, null, serverName = true, parallel = PARALLEL_CONNECTIONS) }
    }

    /**
     * Download capturado no navegador interno. [headers] leva cookies, User-Agent e Referer do WebView,
     * para que o arquivo seja pedido com a mesma sessão que o usuário abriu no site.
     */
    fun enqueueBrowser(url: String, fileName: String, system: GameSystem, headers: Map<String, String>) {
        val task = DownloadTask(title = RomNaming.cleanTitle(fileName.substringBeforeLast('.')), systemId = system.id, coverUrl = null)
        // Num redirecionamento para outro site, os cookies daquele site vêm do próprio WebView.
        val cookiesFor: (String) -> String? = { hop -> runCatching { CookieManager.getInstance().getCookie(hop) }.getOrNull() }
        launchTask(task) { id -> runDownload(id, url, fileName, system, task.title, null, null, null, headers, cookiesFor) }
    }

    /** Alguns servidores (CDRomance, por exemplo) só liberam o arquivo vindo da página do jogo. */
    private fun refererOf(entry: CatalogEntry): Map<String, String> =
        entry.website?.takeIf { it.startsWith("http") }?.let { mapOf("Referer" to it) }.orEmpty()

    private fun launchTask(task: DownloadTask, block: suspend (String) -> Unit) {
        work[task.id] = block
        // Pedir de novo um jogo que tinha falhado substitui a tentativa antiga, em vez de deixá-la na lista.
        val stale = _tasks.value.filter { task.entryKey != null && it.entryKey == task.entryKey && it.status in RETRYABLE }.map { it.id }.toSet()
        stale.forEach { work.remove(it); discardResume(it) }
        _tasks.update { list -> listOf(task) + list.filterNot { it.id in stale } }
        start(task.id)
    }

    private fun start(id: String) {
        val block = work[id] ?: return
        // Entra na fila aqui, e não dentro da corrotina: a ordem de espera é a ordem dos pedidos.
        synchronized(slotLock) { waiting.addLast(id) }
        // LAZY: o job só começa depois de registrado, senão um download muito rápido
        // removeria a entrada antes dela existir e deixaria um job morto no mapa.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var holdsSlot = false
            try {
                sweep.join()
                awaitSlot(id)
                holdsSlot = true
                update(id) { it.copy(status = DownloadStatus.DOWNLOADING) }
                block(id)
                // Concluído não se refaz: soltar o pedido libera a entrada, os cookies e os cabeçalhos guardados.
                work.remove(id)
                discardResume(id)
            } catch (t: kotlinx.coroutines.CancellationException) {
                update(id) { it.copy(status = DownloadStatus.CANCELED, speed = 0, retryAt = 0) }
                // Cancelado pelo usuário: nada a continuar depois.
                discardResume(id)
                throw t
            } catch (t: Throwable) {
                update(id) { it.copy(status = DownloadStatus.FAILED, error = t.userMessage(context), speed = 0, retryAt = 0) }
            } finally {
                if (holdsSlot) releaseSlot()
            }
        }
        jobs[id] = job
        // Também roda quando o job é cancelado antes de começar (o corpo acima nem chega a executar).
        job.invokeOnCompletion { cause ->
            val wasWaiting = synchronized(slotLock) { waiting.remove(id) }
            if (wasWaiting) {
                if (cause is kotlinx.coroutines.CancellationException) {
                    update(id) { if (it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.CANCELED) else it }
                }
                // Saiu da fila sem vaga: o próximo confere se agora é a vez dele.
                slotFreed.update { it + 1 }
            }
            // Por último, e só a própria entrada: "tentar de novo" espera até aqui para registrar o job novo.
            jobs.remove(id, job)
        }
        job.start()
    }

    /**
     * Espera a vez na fila: só o primeiro da espera pega uma vaga livre. Acorda quando um download libera a
     * sua, quando alguém sai da fila ou quando o limite muda nos ajustes.
     */
    private suspend fun awaitSlot(id: String) {
        while (true) {
            val tick = slotFreed.value
            val limit = settings.value.maxDownloads
            val acquired = synchronized(slotLock) {
                if (waiting.firstOrNull() == id && running < limit) { waiting.removeFirst(); running++; true } else false
            }
            if (acquired) {
                // Pode sobrar vaga para o próximo da fila (limite maior que 1).
                slotFreed.update { it + 1 }
                return
            }
            combine(slotFreed, settings) { t, s -> t != tick || s.maxDownloads != limit }.first { it }
        }
    }

    private fun releaseSlot() {
        synchronized(slotLock) { running-- }
        slotFreed.update { it + 1 }
    }

    private suspend fun runDownload(
        taskId: String, url: String, fileName: String, system: GameSystem, title: String,
        cover: String?, developer: String?, description: String?, headers: Map<String, String> = emptyMap(),
        cookiesFor: ((String) -> String?)? = null,
        /** O nome veio do fim do link, não da fonte: o do servidor (Content-Disposition, URL final) é melhor. */
        serverName: Boolean = false,
        ipv6: Boolean? = null,
        /** Conexões ao mesmo tempo (ver [Http.download]); os downloads do navegador levam cookies e ficam em 1. */
        parallel: Int = 1,
        onWaited: () -> Unit = {},
    ) {
        val dir = paths.romsFor(system.id)
        val target = File(dir, FileNames.safe(fileName))
        // Velocidade suavizada: a média móvel evita que o número pule a cada aviso.
        // -1: ainda sem amostra. Continuando um .part guardado, o primeiro aviso já traz o que veio antes, e
        // contar isso como baixado agora mostraria uma velocidade absurda.
        var lastBytes = -1L
        var lastTime = System.nanoTime()
        var saved: File? = null
        val file = try {
            // keepExisting: outro jogo com o mesmo nome de arquivo (dois "rom.gb") não é apagado.
            // resume: uma falha no meio deixa o .part guardado, e "tentar de novo" continua de onde parou.
            val resume = resumes.getOrPut(taskId) { Http.ResumePoint() }
            Http.download(url, target, headers, cookiesFor, keepExisting = true, serverName = serverName, http = Http.clientFor(ipv6), parallel = parallel, resume = resume, onSaved = { saved = it }, onWait = { until ->
                if (until > 0) onWaited()
                update(taskId) { it.copy(retryAt = until, speed = 0) }
            }, onBytes = { read, total ->
                val now = System.nanoTime()
                val elapsed = (now - lastTime) / 1e9
                val instant = if (lastBytes >= 0 && elapsed > 0) ((read - lastBytes) / elapsed).toLong() else 0L
                lastBytes = read
                lastTime = now
                update(taskId) {
                    val speed = if (it.speed == 0L) instant else (it.speed * 0.7 + instant * 0.3).toLong()
                    it.copy(bytesDone = read, bytesTotal = total, speed = speed, progress = if (total > 0) read.toFloat() / total else -1f)
                }
            })
        } catch (c: kotlinx.coroutines.CancellationException) {
            // Cancelado entre salvar o arquivo e voltar: não deixa na pasta um jogo que a próxima varredura incluiria.
            saved?.delete()
            throw c
        }
        // Com o nome do servidor, o título da tarefa (e do jogo) passa a ser o do arquivo salvo.
        val finalTitle = if (serverName) file.name.substringBeforeLast('.').ifBlank { file.name } else title
        if (finalTitle != title) update(taskId) { it.copy(title = finalTitle) }
        // Daqui em diante o arquivo já está baixado: extrair e registrar vão até o fim, senão um cancelamento
        // no meio da extração deixaria arquivos soltos e a tarefa marcada como cancelada.
        withContext(NonCancellable) { finishDownload(taskId, file, dir, system, finalTitle, cover, developer, description) }
    }

    private suspend fun finishDownload(
        taskId: String, downloaded: File, dir: File, system: GameSystem, title: String,
        cover: String?, developer: String?, description: String?,
    ) {
        var file = downloaded
        // Página de erro/aviso salva como se fosse o jogo: melhor avisar do que "extrair" HTML.
        if (withContext(Dispatchers.IO) { Archives.isHtml(file) }) {
            file.delete()
            throw LocalizedException(R.string.download_got_webpage)
        }
        // Extração e cadastro sem varredura no meio: ela veria arquivos pela metade (ver LibraryRepository.writingRoms).
        val gameId = library.writingRoms {
            if (Archives.isArchive(file) && !system.keepArchives) {
                update(taskId) { it.copy(status = DownloadStatus.EXTRACTING, speed = 0) }
                file = withContext(Dispatchers.IO) { RomExtractor.extract(file, dir, system) }
            }
            // Sem um arquivo do console (um .rar, ou um .zip sem ROM dele, que o extrator devolve como veio):
            // o jogo nunca abriria. Melhor falhar com o motivo do que cadastrar algo que não roda.
            if (!system.keepArchives && file.extension.lowercase() !in system.extensions) {
                val name = file.name
                withContext(Dispatchers.IO) { file.delete() }
                throw LocalizedException(R.string.download_unplayable, name, system.name)
            }
            library.addDownloaded(system, file, title, cover, developer, description)
        }
        update(taskId) { it.copy(status = DownloadStatus.DONE, progress = 1f, gameId = gameId, speed = 0) }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    fun cancelAll() = jobs.values.forEach { it.cancel() }

    /** Refaz um download que falhou ou foi cancelado, com o mesmo pedido e no mesmo lugar da lista. */
    fun retry(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        if (task.status !in RETRYABLE || jobs.containsKey(id)) return
        // Um pedido do catálogo que já foi refeito por outro caminho não é baixado duas vezes.
        if (task.entryKey != null && _tasks.value.any { it.id != id && it.entryKey == task.entryKey && it.status in ACTIVE }) return
        // Volta ao fim da fila, como um pedido novo: sem o tamanho da tentativa anterior e com a hora de agora.
        update(id) {
            it.copy(
                status = DownloadStatus.QUEUED, error = null, progress = 0f, bytesDone = 0, bytesTotal = -1, speed = 0,
                createdAt = System.currentTimeMillis(),
            )
        }
        start(id)
    }

    /** Tira da lista uma tarefa já encerrada. */
    fun remove(id: String) {
        if (jobs.containsKey(id)) return
        work.remove(id)
        discardResume(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /** Limpa da lista os downloads concluídos; os que falharam continuam lá para tentar de novo. */
    fun clearCompleted() {
        val done = _tasks.value.filter { it.status == DownloadStatus.DONE }.map { it.id }.toSet()
        done.forEach { work.remove(it); discardResume(it) }
        _tasks.update { list -> list.filterNot { it.id in done } }
    }

    private fun update(id: String, block: (DownloadTask) -> DownloadTask) =
        _tasks.update { list -> list.map { if (it.id == id) block(it) else it } }

    companion object {
        val ACTIVE = setOf(DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.EXTRACTING)
        val RETRYABLE = setOf(DownloadStatus.FAILED, DownloadStatus.CANCELED)
        private const val MAX_LINK_REFRESHES = 2
        /** Pedaços baixados ao mesmo tempo quando o servidor aceita Range. */
        private const val PARALLEL_CONNECTIONS = 4
        /**
         * Fontes cujos servidores de arquivo bloqueiam o IP por minutos (até uma hora) com pedidos demais: o
         * RomsFun (ver CLAUDE.md). Nelas o arquivo vem por uma conexão só, como antes.
         */
        private val SINGLE_CONNECTION_SOURCES = setOf("romsfun")
        /** Recusas que um link novo resolve: sessão/verificação vencida (403) ou link expirado. */
        /** Link vencido ou recusado (403/410), o que um link novo resolve; 404, 500 etc. não valem outra rodada. */
        private fun isRefusal(e: LocalizedException): Boolean = e.messageRes == R.string.download_forbidden ||
            (e.messageRes == R.string.download_http_error && e.args.firstOrNull() in REFUSED_CODES)

        private val REFUSED_CODES = setOf(403, 410)
    }
}
