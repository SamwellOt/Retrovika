package com.retrovika.app.core.storage

import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** Leitura de ROMs compactadas (.zip e .7z), formatos comuns em sites de download. */
object Archives {
    private val supported = setOf("zip", "7z")

    fun isArchive(file: File): Boolean = file.extension.lowercase() in supported

    fun entryNames(archive: File): List<String> = when (archive.extension.lowercase()) {
        "zip" -> Zip.entryNames(archive)
        "7z" -> sevenZ(archive).use { sz -> sz.entries.filterNot { it.isDirectory }.map { it.name } }
        else -> emptyList()
    }

    /**
     * Extrai as entradas [names] direto em [destDir], sem subpastas (só o nome do arquivo),
     * o que também impede "zip slip". Retorna os arquivos criados, na ordem do arquivo compactado.
     */
    fun extract(archive: File, destDir: File, names: Set<String>): List<File> {
        destDir.mkdirs()
        val out = mutableListOf<File>()
        fun target(name: String) = File(destDir, name.substringAfterLast('/').substringAfterLast('\\'))
        when (archive.extension.lowercase()) {
            "zip" -> ZipInputStream(archive.inputStream().buffered()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in names) continue
                    val file = target(entry.name)
                    file.outputStream().use { zis.copyTo(it) }
                    out += file
                }
            }
            "7z" -> sevenZ(archive).use { sz ->
                while (true) {
                    val entry = sz.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in names) continue
                    val file = target(entry.name)
                    sz.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    out += file
                }
            }
            else -> throw LocalizedException(R.string.download_unsupported_format, archive.name)
        }
        return out
    }

    private fun sevenZ(file: File): SevenZFile = SevenZFile.builder().setFile(file).get()
}
