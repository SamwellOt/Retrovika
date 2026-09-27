package com.retrovika.app.core.library

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.settings.SettingsRepository
import com.retrovika.app.core.storage.Archives
import com.retrovika.app.core.storage.RomExtractor
import com.retrovika.app.core.storage.FileNames
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Resultado da importação: arquivos sem console reconhecido e os que falharam, com o motivo. */
data class ImportResult(val unknown: List<String>, val failed: List<Pair<String, String>>)

data class ScanState(val running: Boolean = false, val found: Int = 0, val current: String? = null)

/**
 * Fonte única da biblioteca de jogos. Reúne:
 * - a pasta interna organizada `roms/<sistema>/`
 * - pastas do usuário vinculadas via Storage Access Framework
 */
class LibraryRepository(
    private val context: Context,
    private val dao: GameDao,
    private val paths: StoragePaths,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val resolver: ContentResolver = context.contentResolver

    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    val all = dao.observeAll()
    // Listas das abas mantidas em memória: ao voltar para uma aba os dados já estão prontos,
    // sem refazer a consulta nem desenhar a tela vazia antes do resultado chegar.
    val recent: StateFlow<List<Game>> = dao.observeRecent().cached()
    val favorites: StateFlow<List<Game>> = dao.observeFavorites().cached()
    val newest: StateFlow<List<Game>> = dao.observeNewest().cached()
    private val _countsLoaded = MutableStateFlow(false)
    /** Falso até a primeira resposta do banco: a lista vazia inicial não significa "biblioteca vazia". */
    val countsLoaded: StateFlow<Boolean> = _countsLoaded.asStateFlow()
    val counts: StateFlow<List<SystemCount>> = dao.observeCounts().onEach { _countsLoaded.value = true }.cached()

    private fun <T> Flow<List<T>>.cached() = stateIn(scope, SharingStarted.Eagerly, emptyList())
    fun bySystem(systemId: String) = dao.observeBySystem(systemId)
    fun search(query: String) = dao.search(query)
    fun observe(id: Long) = dao.observe(id)
    suspend fun get(id: Long) = dao.get(id)
    suspend fun toggleFavorite(id: Long) = dao.toggleFavorite(id)
    suspend fun recordSession(id: Long, seconds: Long) = dao.recordSession(id, System.currentTimeMillis(), seconds)
    suspend fun setCoreOverride(id: Long, coreId: String?) = dao.setCoreOverride(id, coreId)
    suspend fun setIdentified(id: Long, name: String, region: String?) = dao.setIdentified(id, name, region)

    suspend fun delete(game: Game, deleteFile: Boolean) = withContext(Dispatchers.IO) {
        if (deleteFile && !game.isContentUri) {
            // Faixas .bin de um .cue (e os discos de um .m3u) também saem; senão reapareceriam
            // como jogos soltos no próximo rescan.
            deleteWithTracks(File(game.uri))
        }
        // Arquivos de pastas vinculadas não são apagados: o jogo fica oculto para não voltar no rescan.
        if (game.isContentUri) settings.hideGame(game.uri)
        dao.delete(game)
    }

    private fun deleteWithTracks(file: File) {
        val ext = file.extension.lowercase()
        if (ext in GameFiles.SHEET_EXTENSIONS) {
            val text = runCatching { file.readText() }.getOrDefault("")
            GameFiles.referencedPaths(ext, text, file.name).forEach { path ->
                val track = File(file.parentFile, path)
                // Um .m3u nunca desce em outro .m3u, para não entrar em laço.
                if (ext == "m3u" && !track.extension.equals("m3u", true)) deleteWithTracks(track) else track.delete()
            }
        }
        file.delete()
    }

    private val startupScanDone = AtomicBoolean(false)

    /** Varredura da abertura do app: só a primeira chamada do processo varre. */
    suspend fun rescanOnStartup() {
        if (startupScanDone.compareAndSet(false, true)) rescan()
    }

    private val scanLock = Mutex()

    /**
     * Varredura de `roms/` e extração nunca ao mesmo tempo: no meio de uma extração a pasta pode ter o
     * .bin sem o .cue ainda, e a varredura cadastraria a faixa como um jogo à parte (que nunca mais sairia).
     */
    private val filesLock = Mutex()

    /** Roda [block] (extração para `roms/` e o cadastro do resultado) sem uma varredura no meio. */
    suspend fun <T> writingRoms(block: suspend () -> T): T = filesLock.withLock { block() }
    @Volatile private var rescanPending = false

    /**
     * Varre todas as fontes, adiciona jogos novos e remove os que sumiram. Um pedido feito durante
     * uma varredura não se perde: a varredura em andamento roda mais uma vez ao terminar, já com as
     * pastas e os jogos ocultos atualizados.
     */
    suspend fun rescan() = withContext(Dispatchers.IO) {
        rescanPending = true
        // Rechecado depois de soltar a trava: um pedido que chegou entre o fim do laço e o unlock
        // encontraria a trava ocupada e seria descartado.
        while (rescanPending) {
            if (!scanLock.tryLock()) return@withContext
            try {
                while (rescanPending) {
                    rescanPending = false
                    scanOnce()
                }
            } finally {
                scanLock.unlock()
            }
        }
    }

    private suspend fun scanOnce() {
        _scan.value = ScanState(running = true)
        lastProgress = 0L
        val found = mutableListOf<Game>()
        try {
            filesLock.withLock { scanInternal(found) }
            val hidden = settings.current().hiddenGames
            // Pastas que não puderam ser lidas (permissão revogada, cartão SD removido…) mantêm os
            // jogos na biblioteca; senão, favoritos e tempo de jogo seriam apagados por engano.
            val unreadable = mutableListOf<String>()
            settings.current().linkedFolders.forEach { tree ->
                runCatching { scanTree(Uri.parse(tree), found) }.onFailure { unreadable += tree }
            }
            val existing = dao.allUris().toSet()
            val foundUris = found.map { it.uri }.toSet()
            dao.insertAll(found.filter { it.uri !in existing && it.uri !in hidden })
            // Remove apenas entradas locais cujo arquivo realmente sumiu.
            val missing = existing.filter { uri ->
                uri !in foundUris && unreadable.none { uri.startsWith("$it/") } &&
                    (uri.startsWith("content://") || !File(uri).exists())
            }
            // Em lotes: o SQLite do Android 8–10 aceita no máximo 999 parâmetros por comando.
            missing.chunked(500).forEach { dao.deleteByUris(it) }
        } finally {
            _scan.value = ScanState(running = false, found = found.size)
        }
    }

    private fun scanInternal(out: MutableList<Game>) {
        Systems.all.forEach { system ->
            val dir = File(paths.roms, system.id)
            if (!dir.exists()) return@forEach
            val folders = dir.walkTopDown().maxDepth(3).filter { it.isDirectory }.toList()
            // Primeiro lê todos os índices (.cue/.gdi/.m3u/.ccd): um .m3u pode citar discos em subpastas.
            val referenced = HashSet<String>()
            val unreadable = HashSet<File>()
            folders.forEach { folder ->
                folder.listFiles()?.filter { it.isFile && it.extension.lowercase() in GameFiles.SHEET_EXTENSIONS }?.forEach { sheet ->
                    runCatching { sheet.readText() }
                        .onSuccess { text ->
                            GameFiles.referencedPaths(sheet.extension.lowercase(), text, sheet.name)
                                .forEach { referenced += File(folder, it).path.lowercase() }
                        }
                        .onFailure { unreadable += folder }
                }
            }
            folders.forEach { folder ->
                val files = folder.listFiles()?.filter { it.isFile }.orEmpty()
                val siblings = files.map { it.name.lowercase() }.toSet()
                files.forEach { file ->
                    val ext = file.extension.lowercase()
                    val auxiliary = RomNaming.isAuxiliaryFile(
                        file.name, siblings,
                        referenced = GameFiles.isReferenced(file.path.lowercase(), referenced),
                        sheetsKnown = folder !in unreadable,
                    )
                    if (ext in system.extensions && !auxiliary) {
                        out += buildGame(system, file.name, file.absolutePath, file.length(), GameSource.IMPORTED)
                        progress(out.size, file.name)
                    }
                }
            }
        }
    }

    /** Varredura rápida de uma árvore SAF usando DocumentsContract (bem mais veloz que DocumentFile). */
    private fun scanTree(treeUri: Uri, out: MutableList<Game>) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootId.substringAfterLast('/').substringAfterLast(':')
        val queue = ArrayDeque(listOf(rootId to listOf(rootName)))
        // Caminhos ("pasta/sub/arquivo", minúsculos) citados por índices já lidos. A busca é em
        // largura, então o .m3u de uma pasta é lido antes das subpastas com os discos que ele cita.
        val referenced = HashSet<String>()
        while (queue.isNotEmpty()) {
            val (docId, folders) = queue.removeFirst()
            if (folders.size > 6) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val entries = mutableListOf<Triple<String, String, Long>>()
            // Null significa que o provedor não achou a pasta (cartão removido, app desinstalado):
            // tratá-la como vazia apagaria os jogos dela, então a árvore é marcada como ilegível.
            resolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) queue += id to (folders + name)
                    else entries += Triple(id, name, c.getLong(3))
                }
            } ?: throw IOException("Pasta inacessível: $docId")
            val folderKey = folders.joinToString("/")
            var sheetsKnown = true
            entries.filter { it.second.substringAfterLast('.', "").lowercase() in GameFiles.SHEET_EXTENSIONS }.forEach { (id, name, size) ->
                val text = if (size in 0..MAX_SHEET_BYTES) runCatching {
                    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))?.bufferedReader()?.use { it.readText() }
                }.getOrNull() else null
                if (text == null) { sheetsKnown = false; return@forEach }
                GameFiles.referencedPaths(name.substringAfterLast('.').lowercase(), text, name)
                    .forEach { referenced += "$folderKey/$it".lowercase() }
            }
            val siblings = entries.map { it.second.lowercase() }.toSet()
            entries.forEach { (id, name, size) ->
                val isReferenced = GameFiles.isReferenced("$folderKey/$name".lowercase(), referenced)
                if (RomNaming.isAuxiliaryFile(name, siblings, isReferenced, sheetsKnown)) return@forEach
                val system = RomNaming.resolveSystem(name, folders) ?: return@forEach
                val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString()
                out += buildGame(system, name, uri, size, GameSource.LOCAL)
                progress(out.size, name)
            }
        }
    }

    private var lastProgress = 0L

    private fun progress(count: Int, name: String) {
        // No máximo ~6 atualizações por segundo: cada uma recompõe as telas que mostram o progresso,
        // e emitir a cada arquivo travava o menu durante a varredura de coleções grandes.
        val now = SystemClock.uptimeMillis()
        if (now - lastProgress < PROGRESS_INTERVAL_MS) return
        lastProgress = now
        _scan.value = _scan.value.copy(found = count, current = name)
    }

    fun buildGame(system: GameSystem, fileName: String, uri: String, size: Long, source: GameSource): Game {
        val raw = fileName.substringBeforeLast('.')
        return Game(
            title = RomNaming.cleanTitle(raw),
            rawName = raw,
            fileName = fileName,
            uri = uri,
            systemId = system.id,
            size = size,
            region = RomNaming.region(raw),
            coverUrl = RomNaming.coverUrl(system, raw),
            source = source,
        )
    }

    /**
     * Copia arquivos escolhidos pelo usuário para `roms/<sistema>/`.
     * Se [forcedSystem] for nulo, o sistema é detectado pela extensão.
     * Retorna os arquivos que não puderam ser identificados.
     */
    suspend fun importFiles(uris: List<Uri>, forcedSystem: GameSystem?): ImportResult = withContext(Dispatchers.IO) {
        val unknown = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val copied = mutableListOf<Pair<GameSystem, File>>()
        uris.forEach { uri ->
            val name = FileNames.safe(runCatching { displayName(uri) }.getOrNull() ?: uri.lastPathSegment ?: return@forEach)
            val system = forcedSystem ?: RomNaming.resolveSystem(name, emptyList())
            if (system == null) { unknown += name; return@forEach }
            val dest = File(paths.romsFor(system.id), name)
            // A cópia vai para um temporário e só substitui o destino no fim: reimportar um jogo que já
            // está na biblioteca e falhar no meio não pode apagar a ROM que já existia.
            val part = File(dest.parentFile, "$name.part")
            // Um arquivo com problema (sem espaço, compactado corrompido…) não derruba os outros:
            // o motivo volta para a tela junto com o nome.
            try {
                val input = resolver.openInputStream(uri) ?: throw LocalizedException(R.string.system_import_unreadable)
                input.use { stream -> part.outputStream().use { stream.copyTo(it) } }
                if (!part.renameTo(dest)) throw IOException("rename ${part.name}")
                val file = if (Archives.isArchive(dest) && !system.keepArchives) {
                    writingRoms { RomExtractor.extract(dest, dest.parentFile!!, system) }
                } else dest
                copied += system to file
            } catch (c: CancellationException) {
                part.delete()
                throw c
            } catch (t: Throwable) {
                part.delete()
                failed += name to t.userMessage(context)
            }
        }
        // Os arquivos escolhidos juntos são irmãos: um .cue com seus .bin vira um jogo só.
        val siblings = copied.map { it.second.name.lowercase() }.toSet()
        val referenced = copied.map { it.second }.filter { it.extension.lowercase() in GameFiles.SHEET_EXTENSIONS }
            .flatMap { sheet -> GameFiles.referencedFiles(sheet.extension.lowercase(), runCatching { sheet.readText() }.getOrDefault(""), sheet.name) }
            .map { it.lowercase() }.toSet()
        copied.forEach { (system, file) ->
            val auxiliary = RomNaming.isAuxiliaryFile(file.name, siblings, GameFiles.isReferenced(file.name.lowercase(), referenced), sheetsKnown = true)
            if (!auxiliary) dao.insert(buildGame(system, file.name, file.absolutePath, file.length(), GameSource.IMPORTED))
        }
        ImportResult(unknown, failed)
    }

    /** Registra um arquivo baixado pelo gerenciador de downloads. */
    suspend fun addDownloaded(system: GameSystem, file: File, title: String?, cover: String?, developer: String?, description: String?): Long {
        val game = buildGame(system, file.name, file.absolutePath, file.length(), GameSource.DOWNLOADED).let {
            it.copy(
                title = title ?: it.title,
                coverUrl = cover ?: it.coverUrl,
                developer = developer,
                description = description,
            )
        }
        // Baixar de novo um arquivo que já está na biblioteca não gera uma entrada nova (a URI é única).
        val id = dao.insert(game)
        return if (id > 0) id else dao.getByUri(game.uri)?.id ?: id
    }

    /** Jogos ocultos (removidos de pastas vinculadas) voltam a aparecer no próximo rescan. */
    suspend fun unhideAll() {
        settings.clearHiddenGames()
        rescan()
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private companion object {
        /** Índices maiores que isso não são .cue/.m3u de verdade; não vale abri-los na varredura. */
        const val MAX_SHEET_BYTES = 256L * 1024
        const val PROGRESS_INTERVAL_MS = 160L
    }
}
