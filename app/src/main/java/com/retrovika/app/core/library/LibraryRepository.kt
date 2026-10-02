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
import com.retrovika.app.emulation.GameActivity
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
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * URIs removidas durante uma varredura: ela pode ter visto o arquivo antes da remoção e o cadastraria de
     * novo ao terminar. Cada varredura tira daqui só as que já estavam no começo dela.
     */
    private val deletedUris: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Tira o jogo da biblioteca e apaga o que era só dele (estados, trapaças escolhidas, controle e nível de
     * qualidade próprios). Os saves (.srm) ficam: o nome é o do RetroArch e servem ao mesmo jogo importado de novo.
     * Retorna falso, sem mexer em nada além das faixas, quando o arquivo interno não pôde ser apagado: a
     * entrada fica, para o jogo não sumir da tela com a ROM ainda ocupando espaço.
     */
    suspend fun delete(game: Game, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        deletedUris += game.uri
        if (deleteFile && !game.isContentUri) {
            // Faixas .bin de um .cue (e os discos de um .m3u) também saem; senão reapareceriam
            // como jogos soltos no próximo rescan.
            val file = File(game.uri)
            deleteWithTracks(file)
            if (file.exists()) {
                deletedUris -= game.uri
                return@withContext false
            }
        }
        // Arquivos de pastas vinculadas não são apagados: o jogo fica oculto para não voltar no rescan.
        if (game.isContentUri) settings.hideGame(game.uri)
        dao.delete(game)
        forget(game)
        true
    }

    /** O que fica guardado pelo id do jogo: um jogo reimportado ganha outro id, então nada disso voltaria a servir. */
    private suspend fun forget(game: Game) {
        runCatching { paths.statesDir(game.systemId, game.id).deleteRecursively() }
        runCatching { paths.cheatsFor(game.systemId, game.id).delete() }
        // Progresso do teste de qualidade por jogo (o GameActivity grava com este nome).
        runCatching { File(File(context.filesDir, GameActivity.BENCH_DIR), "benchmark_game_${game.id}.json").delete() }
        runCatching { settings.forgetGame(game.id) }
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

    private val partsSwept = AtomicBoolean(false)

    /**
     * Apaga os temporários ".part" que sobraram em `roms/` (downloads, importações e extrações
     * interrompidos pelo app encerrado à força ou pelo aparelho reiniciado: nenhum finally rodou).
     * Uma vez por processo, com a trava das extrações; downloads e importações esperam por ela antes
     * de criar o próprio temporário, então ela nunca apaga o de um trabalho em andamento.
     */
    suspend fun sweepStaleParts() {
        if (partsSwept.get()) return
        filesLock.withLock {
            if (partsSwept.get()) return
            withContext(Dispatchers.IO) {
                runCatching {
                    paths.roms.walkTopDown().maxDepth(6)
                        .filter { it.isFile && it.name.endsWith(Archives.PART_SUFFIX) }
                        .forEach { it.delete() }
                }
            }
            partsSwept.set(true)
        }
    }
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
        val deletedBefore = deletedUris.toList()
        val found = mutableListOf<Game>()
        try {
            // Arquivos de `roms/` que a varredura viu e julgou auxiliares (o .bin de um .cue importado depois).
            val auxiliary = HashSet<String>()
            // Retrato do banco tirado junto com a varredura, sob a mesma trava: um download cadastrado depois
            // dela não está aqui e não corre o risco de ser tomado por auxiliar.
            val before = filesLock.withLock {
                dao.allUris().toSet().also { scanInternal(found, auxiliary) }
            }
            // Pastas que não puderam ser lidas (permissão revogada, cartão SD removido…) mantêm os
            // jogos na biblioteca; senão, favoritos e tempo de jogo seriam apagados por engano.
            val unreadable = mutableListOf<String>()
            // Pastas vinculadas uma dentro da outra veem os mesmos documentos: cada um entra uma vez só.
            val seenDocs = HashSet<String>()
            settings.current().linkedFolders.forEach { tree ->
                runCatching { scanTree(Uri.parse(tree), found, seenDocs) }.onFailure { unreadable += tree }
            }
            // Lido depois da varredura das pastas (que pode ser demorada): um jogo removido durante ela já
            // está oculto aqui e não volta.
            val hidden = settings.current().hiddenGames
            val existing = dao.allUris().toSet()
            val foundUris = found.map { it.uri }.toSet()
            dao.insertAll(found.filter { it.uri !in existing && it.uri !in hidden && it.uri !in deletedUris })
            // Removido enquanto outra varredura já o reinseria: sai agora.
            existing.filter { it in hidden }.chunked(500).forEach { dao.deleteByUris(it) }
            // Remove entradas cujo arquivo realmente sumiu e as internas que viraram auxiliares
            // (o arquivo continua lá, como faixa de outro jogo; só a entrada sai).
            // Sem o armazenamento externo (raiz caída no interno), `roms/` de verdade não foi varrida: os jogos
            // dela parecem sumidos, mas só estão inacessíveis agora.
            val internalReadable = paths.onExternal
            val missing = existing.filter { uri ->
                uri !in foundUris && unreadable.none { uri.startsWith("$it/") } &&
                    (uri.startsWith("content://") || (internalReadable && (!File(uri).exists() || (uri in auxiliary && uri in before))))
            }
            // Em lotes: o SQLite do Android 8–10 aceita no máximo 999 parâmetros por comando.
            missing.chunked(500).forEach { dao.deleteByUris(it) }
            deletedUris.removeAll(deletedBefore.toSet())
        } finally {
            _scan.value = ScanState(running = false, found = found.size)
        }
    }

    private fun scanInternal(out: MutableList<Game>, auxiliaryOut: MutableSet<String>) {
        Systems.all.forEach { system ->
            val dir = File(paths.roms, system.id)
            if (!dir.exists()) return@forEach
            // Pastas ocultas e o __MACOSX de compactados feitos no Mac ficam de fora, como na pasta vinculada.
            val folders = dir.walkTopDown().maxDepth(3)
                .onEnter { it == dir || !FileNames.isJunk(it.name) }
                .filter { it.isDirectory }.toList()
            // Primeiro lê todos os índices (.cue/.gdi/.m3u/.ccd): um .m3u pode citar discos em subpastas.
            // Só os que este console abre: um .ccd na pasta do PS1 (que não lê .ccd) esconderia o .img
            // que o PS1 abre, e o jogo sumiria.
            val referenced = HashSet<String>()
            val unreadable = HashSet<File>()
            folders.forEach { folder ->
                folder.listFiles()?.filter { it.isFile && isPlayableSheet(it.extension.lowercase(), system) }?.forEach { sheet ->
                    runCatching { sheet.readText() }
                        .onSuccess { text ->
                            GameFiles.referencedPaths(sheet.extension.lowercase(), text, sheet.name)
                                .forEach { referenced += File(folder, it).path.lowercase() }
                        }
                        .onFailure { unreadable += folder }
                }
            }
            folders.forEach { folder ->
                val files = folder.listFiles()?.filter { it.isFile && !FileNames.isJunk(it.name) }.orEmpty()
                // Índices que o console não abre também ficam fora da heurística por nome.
                val siblings = files.map { it.name.lowercase() }
                    .filter { name -> name.substringAfterLast('.', "").let { it !in GameFiles.SHEET_EXTENSIONS || isPlayableSheet(it, system) } }
                    .toSet()
                files.forEach { file ->
                    val ext = file.extension.lowercase()
                    if (ext !in system.extensions) return@forEach
                    val auxiliary = RomNaming.isAuxiliaryFile(
                        file.name, siblings,
                        referenced = GameFiles.isReferenced(file.path.lowercase(), referenced),
                        sheetsKnown = folder !in unreadable,
                    )
                    if (auxiliary) {
                        auxiliaryOut += file.absolutePath
                    } else {
                        out += buildGame(system, file.name, file.absolutePath, file.length(), GameSource.IMPORTED)
                        progress(out.size, file.name)
                    }
                }
            }
        }
    }

    /** Índice (.cue/.ccd/…) que [system] abre como jogo; os outros não escondem as faixas que citam. */
    private fun isPlayableSheet(ext: String, system: GameSystem) = ext in GameFiles.SHEET_EXTENSIONS && ext in system.extensions

    /**
     * Varredura rápida de uma árvore SAF usando DocumentsContract (bem mais veloz que DocumentFile).
     * [seenDocs] guarda "autoridade + id do documento" dos arquivos já cadastrados por outra árvore
     * vinculada (uma pasta dentro da outra), para o mesmo arquivo não virar dois jogos.
     */
    private fun scanTree(treeUri: Uri, out: MutableList<Game>, seenDocs: MutableSet<String>) {
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
            // Só índices que algum console abre nesta pasta: um .ccd na pasta "psx" (o PS1 não lê .ccd)
            // não pode esconder o .img que o PS1 abre.
            val sheets = entries.filter { (_, name, _) ->
                name.substringAfterLast('.', "").lowercase() in GameFiles.SHEET_EXTENSIONS && RomNaming.resolveSystem(name, folders) != null
            }
            sheets.forEach { (id, name, size) ->
                val text = if (size in 0..MAX_SHEET_BYTES) runCatching {
                    resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))?.bufferedReader()?.use { it.readText() }
                }.getOrNull() else null
                if (text == null) { sheetsKnown = false; return@forEach }
                GameFiles.referencedPaths(name.substringAfterLast('.').lowercase(), text, name)
                    .forEach { referenced += "$folderKey/$it".lowercase() }
            }
            val sheetNames = sheets.map { it.second }.toSet()
            // Índices que nenhum console abre aqui também ficam fora da heurística por nome.
            val siblings = entries.map { it.second }
                .filter { it.substringAfterLast('.', "").lowercase() !in GameFiles.SHEET_EXTENSIONS || it in sheetNames }
                .map { it.lowercase() }.toSet()
            entries.forEach { (id, name, size) ->
                val isReferenced = GameFiles.isReferenced("$folderKey/$name".lowercase(), referenced)
                if (RomNaming.isAuxiliaryFile(name, siblings, isReferenced, sheetsKnown)) return@forEach
                val system = RomNaming.resolveSystem(name, folders) ?: return@forEach
                if (!seenDocs.add("${treeUri.authority}\u0000$id")) return@forEach
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
            coverUrl = RomNaming.coverUrl(system, raw, fileName.substringAfterLast('.', "")),
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
        // Antes de criar o primeiro .part (e fora da trava, que a limpeza também pega): a limpeza da
        // abertura do app não pode apagá-lo no meio da cópia.
        sweepStaleParts()
        // Tudo dentro da trava das ROMs: uma varredura no meio da cópia veria as faixas .bin antes do .cue
        // e as registraria como jogos soltos.
        writingRoms { uris.forEach { uri ->
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
                if (!part.renameTo(dest)) throw LocalizedException(R.string.system_import_rename_failed, name)
                val file = if (Archives.isArchive(dest) && !system.keepArchives) {
                    RomExtractor.extract(dest, dest.parentFile!!, system)
                } else dest
                // Como no download: o compactado sem jogo deste console (ou num formato que não abrimos)
                // voltaria da extração como veio e entraria na biblioteca como um jogo que nunca roda.
                if (!system.keepArchives && file.extension.lowercase() !in system.extensions) {
                    file.delete()
                    throw LocalizedException(R.string.download_unplayable, file.name, system.name)
                }
                copied += system to file
            } catch (c: CancellationException) {
                part.delete()
                throw c
            } catch (t: Throwable) {
                part.delete()
                failed += name to t.userMessage(context)
            }
        } }
        // Os arquivos escolhidos juntos são irmãos: um .cue com seus .bin vira um jogo só.
        val siblings = copied.map { it.second.name.lowercase() }.toSet()
        val referenced = copied.filter { (system, file) -> file.extension.lowercase().let { it in GameFiles.SHEET_EXTENSIONS && it in system.extensions } }.map { it.second }
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
        // Uma remoção recente ainda guardada para a varredura seguinte não pode segurar o jogo reexibido.
        deletedUris.clear()
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
