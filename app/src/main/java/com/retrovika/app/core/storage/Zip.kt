package com.retrovika.app.core.storage

import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

/**
 * Pacotes .zip de núcleos e arquivos de sistema. Usa o ZipFile do commons-compress (lê o diretório
 * central): aceita Deflate64, ZIP64 e entradas com "data descriptor", que o ZipInputStream recusa.
 * Uma extração que falha no meio apaga o que já tinha escrito, para não deixar arquivos pela metade (e
 * não apaga o que já existia).
 */
object Zip {
    /**
     * Extrai todo o conteúdo de [zip] em [destDir], protegendo contra "zip slip". Cada entrada vai primeiro para
     * um temporário ao lado do destino, e só com todas gravadas os temporários tomam o lugar dos finais: uma
     * falha no meio apaga só os temporários, nunca um arquivo que já estava lá (a BIOS do usuário em system/).
     */
    fun extractAll(zip: File, destDir: File): List<File> {
        val canonicalRoot = destDir.canonicalPath
        val canonicalDest = canonicalRoot + File.separator
        // Destino final -> temporário onde a entrada é gravada.
        val parts = LinkedHashMap<File, File>()
        try {
            open(zip).use { zf ->
                for (entry in zf.entries.toList()) {
                    val file = File(destDir, entry.name)
                    val canonical = file.canonicalPath
                    // Pasta: pode ser a própria raiz ("./"); só não pode sair dela.
                    if (entry.isDirectory) {
                        if (canonical != canonicalRoot && !canonical.startsWith(canonicalDest)) {
                            throw LocalizedException(R.string.zip_invalid_entry, entry.name)
                        }
                        file.mkdirs()
                        continue
                    }
                    if (!canonical.startsWith(canonicalDest)) throw LocalizedException(R.string.zip_invalid_entry, entry.name)
                    if (!zf.canReadEntryData(entry)) throw LocalizedException(R.string.zip_unsupported_compression, entry.name)
                    file.parentFile?.mkdirs()
                    val part = parts.getOrPut(file) { File(file.path + TEMP_SUFFIX) }
                    zf.getInputStream(entry).use { input -> part.outputStream().use { input.copyTo(it) } }
                }
            }
            for ((file, part) in parts) {
                if (!part.renameTo(file)) {
                    // renameTo não substitui em todo sistema de arquivos: apaga o antigo e tenta de novo.
                    file.delete()
                    if (!part.renameTo(file)) throw LocalizedException(R.string.download_move_failed, file.name)
                }
            }
        } catch (t: Throwable) {
            parts.values.forEach { it.delete() }
            throw t
        }
        return parts.keys.toList()
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

    private const val TEMP_SUFFIX = ".unzip-part"
}
