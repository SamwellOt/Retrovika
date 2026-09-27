package com.retrovika.app.emulation

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.retrovika.app.core.library.Game
import com.swordfish.libretrodroid.VirtualFile
import java.io.FileNotFoundException

/**
 * Prepara jogos vindos de pastas SAF (content://) para o LibretroDroid via arquivos virtuais.
 * Jogos multi-arquivo (.cue/.gdi/.m3u) também recebem as faixas/discos que referenciam.
 */
object GameFiles {
    private const val VFS_DIR = "/retrovika_vfs"

    /** Extensões de arquivos-índice que apontam para outros arquivos do jogo. */
    val SHEET_EXTENSIONS = setOf("cue", "gdi", "m3u", "ccd")

    /** Abre os descritores do jogo; se algo falhar no meio, os já abertos são fechados. */
    fun virtualFiles(resolver: ContentResolver, game: Game): List<VirtualFile> {
        val files = mutableListOf<VirtualFile>()
        try {
            collect(resolver, game, files)
        } catch (t: Throwable) {
            files.forEach { runCatching { it.fileDescriptor.close() } }
            throw t
        }
        return files
    }

    private fun collect(resolver: ContentResolver, game: Game, files: MutableList<VirtualFile>) {
        val main = Uri.parse(game.uri)
        files += VirtualFile("$VFS_DIR/${game.fileName}", resolver.openFileDescriptor(main, "r") ?: throw FileNotFoundException(game.uri))
        val ext = game.fileName.substringAfterLast('.').lowercase()
        if (ext in SHEET_EXTENSIONS) {
            val text = resolver.openInputStream(main)?.bufferedReader()?.use { it.readText() }.orEmpty()
            val referenced = referencedPaths(ext, text, game.fileName).toMutableSet()
            // Discos de um .m3u costumam ser .cue que por sua vez referenciam .bin (relativos ao .cue).
            if (ext == "m3u") {
                referenced.toList().filter { it.endsWith(".cue", true) }.forEach { cue ->
                    sibling(main, cue)?.let { uri ->
                        val cueText = runCatching { resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                        val cueDir = cue.substringBeforeLast('/', "")
                        referenced += referencedPaths("cue", cueText, cue).map { if (cueDir.isEmpty()) it else "$cueDir/$it" }
                    }
                }
            }
            // O núcleo pede o caminho exatamente como está no índice, então o arquivo virtual usa o mesmo.
            referenced.forEach { path ->
                // Faixa citada sem extensão ("Jogo" para "Jogo.bin"): o arquivo real tem a extensão, mas o
                // núcleo pede o nome como está no índice.
                val candidates = if (hasExtension(path)) listOf(path) else listOf(path, "$path.bin")
                candidates.firstNotNullOfOrNull { candidate ->
                    sibling(main, candidate)?.let { uri -> runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull() }
                }?.let { fd -> files += VirtualFile("$VFS_DIR/$path", fd) }
            }
        }
    }

    /**
     * Caminhos relativos (com "/") citados por um arquivo-índice. Caminhos absolutos ou que sobem
     * de pasta ("..") viram só o nome do arquivo, procurado ao lado do índice.
     */
    fun referencedPaths(ext: String, content: String, selfName: String): List<String> = referencedRaw(ext, content.removePrefix("\uFEFF"), selfName).map { raw ->
        val path = raw.replace('\\', '/').trimStart('/')
        if (path.split('/').any { it == ".." } || raw.startsWith("/") || ':' in path) path.substringAfterLast('/') else path
    }.filter { it.isNotBlank() }

    // Arquivos salvos no Bloco de Notas começam com BOM, que o trim() não remove. No .cue o nome pode vir sem aspas.
    private fun referencedRaw(ext: String, content: String, selfName: String): List<String> = when (ext) {
        "cue" -> Regex("""^\s*FILE\s+(?:"([^"]+)"|(\S+))""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .findAll(content).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        "gdi" -> content.lines().drop(1).mapNotNull { line ->
            Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+("[^"]+"|\S+)""").find(line)?.groupValues?.get(1)?.trim('"')
        }
        "m3u" -> content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        "ccd" -> selfName.substringBeforeLast('.').let { listOf("$it.img", "$it.sub") }
        else -> emptyList()
    }

    /**
     * Se o arquivo em [path] (minúsculo, com a mesma base dos caminhos de [referenced]) é citado por um
     * índice. Há .cue que citam a faixa sem extensão ("Jogo" para "Jogo.bin"); os núcleos acham o arquivo
     * mesmo assim, então ele também conta. Índices nunca contam como citados só pela base do nome.
     */
    fun isReferenced(path: String, referenced: Set<String>): Boolean {
        if (path in referenced) return true
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        return ext.isNotEmpty() && ext !in SHEET_EXTENSIONS && path.substringBeforeLast('.') in referenced
    }

    private fun hasExtension(path: String) = '.' in path.substringAfterLast('/')

    /** Só os nomes dos arquivos citados (sem pastas). */
    fun referencedFiles(ext: String, content: String, selfName: String): List<String> =
        referencedPaths(ext, content, selfName).map { it.substringAfterLast('/') }

    /**
     * Resolve um arquivo relativo dentro da mesma árvore SAF. Funciona com provedores cujo ID de
     * documento é um caminho (armazenamento externo), que é o caso das pastas vinculadas.
     */
    private fun sibling(document: Uri, relativePath: String): Uri? = runCatching {
        val treeId = DocumentsContract.getTreeDocumentId(document)
        val docId = DocumentsContract.getDocumentId(document)
        val parent = docId.substringBeforeLast('/', docId.substringBefore(':') + ":")
        val separator = if (parent.endsWith(":")) "" else "/"
        val tree = DocumentsContract.buildTreeDocumentUri(document.authority, treeId)
        DocumentsContract.buildDocumentUriUsingTree(tree, "$parent$separator$relativePath")
    }.getOrNull()
}
