package com.retrovika.app.core.storage

import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

/**
 * Pacotes .zip de núcleos e arquivos de sistema. Usa o ZipFile do commons-compress (lê o diretório
 * central): aceita Deflate64, ZIP64 e entradas com "data descriptor", que o ZipInputStream recusa.
 * Uma extração que falha no meio apaga o que já tinha escrito, para não deixar arquivos pela metade.
 */
object Zip {
    /** Extrai todo o conteúdo de [zip] em [destDir], protegendo contra "zip slip". */
    fun extractAll(zip: File, destDir: File): List<File> {
        val out = mutableListOf<File>()
        val canonicalDest = destDir.canonicalPath + File.separator
        try {
            open(zip).use { zf ->
                for (entry in zf.entries.toList()) {
                    val file = File(destDir, entry.name)
                    if (!file.canonicalPath.startsWith(canonicalDest)) throw LocalizedException(R.string.zip_invalid_entry, entry.name)
                    if (entry.isDirectory) { file.mkdirs(); continue }
                    if (!zf.canReadEntryData(entry)) throw LocalizedException(R.string.zip_unsupported_compression, entry.name)
                    file.parentFile?.mkdirs()
                    out += file
                    zf.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            }
        } catch (t: Throwable) {
            out.forEach { it.delete() }
            throw t
        }
        return out
    }

    /** Extrai apenas a primeira entrada que satisfaz [predicate]. */
    fun extractFirst(zip: File, target: File, predicate: (String) -> Boolean): File? = open(zip).use { zf ->
        val entry = zf.entries.toList().firstOrNull { !it.isDirectory && predicate(it.name) } ?: return null
        if (!zf.canReadEntryData(entry)) throw LocalizedException(R.string.zip_unsupported_compression, entry.name)
        target.parentFile?.mkdirs()
        try {
            zf.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
        target
    }

    fun entryNames(zip: File): List<String> = open(zip).use { zf -> zf.entries.toList().filterNot { it.isDirectory }.map { it.name } }

    private fun open(file: File): ZipFile = ZipFile.builder().setFile(file).get()
}
