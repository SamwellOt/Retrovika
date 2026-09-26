package com.retrovika.app.core.storage

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

object Zip {
    /** Extrai todo o conteúdo de [zip] em [destDir], protegendo contra "zip slip". */
    fun extractAll(zip: File, destDir: File): List<File> {
        val out = mutableListOf<File>()
        val canonicalDest = destDir.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val file = File(destDir, entry.name)
                if (!file.canonicalPath.startsWith(canonicalDest)) throw IOException("Entrada inválida: ${entry.name}")
                if (entry.isDirectory) file.mkdirs() else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { zis.copyTo(it) }
                    out += file
                }
            }
        }
        return out
    }

    /** Extrai apenas a primeira entrada que satisfaz [predicate]. */
    fun extractFirst(zip: File, target: File, predicate: (String) -> Boolean): File? {
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: return null
                if (!entry.isDirectory && predicate(entry.name)) {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                    return target
                }
            }
        }
    }

    fun entryNames(zip: File): List<String> = ZipInputStream(zip.inputStream().buffered()).use { zis ->
        generateSequence { zis.nextEntry }.filterNot { it.isDirectory }.map { it.name }.toList()
    }
}
