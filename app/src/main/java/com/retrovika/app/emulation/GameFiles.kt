package com.retrovika.app.emulation

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.retrovika.app.core.library.Game
import com.swordfish.libretrodroid.VirtualFile

/**
 * Prepara jogos vindos de pastas SAF (content://) para o LibretroDroid via arquivos virtuais.
 * Jogos multi-arquivo (.cue/.gdi/.m3u) também recebem as faixas/discos que referenciam.
 */
object GameFiles {
    private const val VFS_DIR = "/retrovika_vfs"

    /** Extensões de arquivos-índice que apontam para outros arquivos do jogo. */
    val SHEET_EXTENSIONS = setOf("cue", "gdi", "m3u", "ccd")

    fun virtualFiles(resolver: ContentResolver, game: Game): List<VirtualFile> {
        val main = Uri.parse(game.uri)
        val files = mutableListOf(VirtualFile("$VFS_DIR/${game.fileName}", resolver.openFileDescriptor(main, "r")!!))
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
                sibling(main, path)?.let { uri ->
                    runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()?.let { fd ->
                        files += VirtualFile("$VFS_DIR/$path", fd)
                    }
                }
            }
        }
        return files
    }

    /**
     * Caminhos relativos (com "/") citados por um arquivo-índice. Caminhos absolutos ou que sobem
     * de pasta ("..") viram só o nome do arquivo, procurado ao lado do índice.
     */
    fun referencedPaths(ext: String, content: String, selfName: String): List<String> = when (ext) {
        "cue" -> Regex("""FILE\s+"([^"]+)"""", RegexOption.IGNORE_CASE).findAll(content).map { it.groupValues[1] }.toList()
        "gdi" -> content.lines().drop(1).mapNotNull { line ->
            Regex("""^\s*\d+\s+\d+\s+\d+\s+\d+\s+("[^"]+"|\S+)""").find(line)?.groupValues?.get(1)?.trim('"')
        }
        "m3u" -> content.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        "ccd" -> selfName.substringBeforeLast('.').let { listOf("$it.img", "$it.sub") }
        else -> emptyList()
    }.map { raw ->
        val path = raw.replace('\\', '/').trimStart('/')
        if (path.split('/').any { it == ".." } || raw.startsWith("/") || ':' in path) path.substringAfterLast('/') else path
    }.filter { it.isNotBlank() }

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
