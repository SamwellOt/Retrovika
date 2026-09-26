package com.retrovika.app.core.storage

import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File

/** Leitura de ROMs compactadas (.zip e .7z), formatos comuns em sites de download. */
object Archives {
    private val supported = setOf("zip", "7z")

    enum class Format { ZIP, SEVEN_Z }

    /** Uma entrada do arquivo compactado; [size] é o tamanho descompactado (-1 se desconhecido). */
    data class Entry(val name: String, val size: Long)

    fun isArchive(file: File): Boolean = file.extension.lowercase() in supported

    /**
     * Formato real pelos primeiros bytes, não pela extensão: sites às vezes servem um .7z com nome
     * .zip (ou o contrário). Null quando não é ZIP nem 7z.
     */
    fun formatOf(file: File): Format? {
        val head = readHead(file, 6)
        return when {
            head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> Format.ZIP
            head.size >= 6 && head.contentEquals(SEVEN_Z_MAGIC) -> Format.SEVEN_Z
            else -> null
        }
    }

    /** Verdadeiro se o arquivo é uma página HTML (o site devolveu erro/aviso em vez do arquivo). */
    fun isHtml(file: File): Boolean {
        val text = String(readHead(file, 512), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\n', '\r', '\t').lowercase()
        return text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("<head")
    }

    fun entries(archive: File): List<Entry> = when (formatOf(archive)) {
        Format.ZIP -> zip(archive).use { zf -> zf.entries.toList().filterNot { it.isDirectory }.map { Entry(it.name, it.size) } }
        Format.SEVEN_Z -> sevenZ(archive).use { sz -> sz.entries.filterNot { it.isDirectory }.map { Entry(it.name, if (it.hasStream()) it.size else 0L) } }
        null -> emptyList()
    }

    fun entryNames(archive: File): List<String> = entries(archive).map { it.name }

    /**
     * Extrai as entradas [names] em [destDir] e devolve, na ordem do arquivo compactado, o arquivo
     * criado para cada entrada. A pasta comum a todas as entradas (o "Jogo (USA)/" que muitos sites
     * põem por fora) é descartada; subpastas abaixo dela ficam, porque um .m3u cita os discos pelo
     * caminho relativo ("Disco 1/Jogo.cue") e achatar tudo quebraria a referência (ou faria discos de
     * mesmo nome se sobrescreverem). Caminhos que escapariam de [destDir] ("zip slip") são recusados.
     * Se algo falhar no meio, os arquivos já extraídos são apagados.
     */
    fun extract(archive: File, destDir: File, names: Set<String>): Map<String, File> {
        destDir.mkdirs()
        val out = LinkedHashMap<String, File>()
        val canonicalDest = destDir.canonicalPath + File.separator
        val prefix = commonFolder(names)
        fun target(name: String): File {
            val relative = name.replace('\\', '/').removePrefix(prefix).trimStart('/')
            val file = File(destDir, relative)
            if (relative.isEmpty() || !file.canonicalPath.startsWith(canonicalDest)) {
                throw LocalizedException(R.string.download_extract_failed, name)
            }
            file.parentFile?.mkdirs()
            return file
        }
        try {
            when (formatOf(archive)) {
                // ZipFile lê o diretório central: aceita Deflate64, ZIP64 e entradas com "data descriptor",
                // que o ZipInputStream do Java recusa (zips grandes feitos no Windows, por exemplo).
                Format.ZIP -> zip(archive).use { zf ->
                    for (entry in zf.entries.toList()) {
                        if (entry.isDirectory || entry.name !in names) continue
                        if (!zf.canReadEntryData(entry)) throw LocalizedException(R.string.download_unsupported_format, entry.name)
                        val file = target(entry.name)
                        out[entry.name] = file
                        zf.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                }
                Format.SEVEN_Z -> sevenZ(archive).use { sz ->
                    while (true) {
                        val entry = sz.nextEntry ?: break
                        if (entry.isDirectory || entry.name !in names) continue
                        val file = target(entry.name)
                        out[entry.name] = file
                        sz.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                }
                null -> throw LocalizedException(R.string.download_unsupported_format, archive.name)
            }
        } catch (t: Throwable) {
            out.values.forEach { it.delete() }
            throw t
        }
        return out
    }

    /** Pasta de primeiro nível comum a todas as entradas ("Jogo (USA)/"), ou "" se não houver. */
    internal fun commonFolder(names: Collection<String>): String {
        val firsts = names.map { it.replace('\\', '/') }.map { if ('/' in it) it.substringBefore('/') + "/" else "" }.toSet()
        return firsts.singleOrNull().orEmpty()
    }

    private fun readHead(file: File, n: Int): ByteArray = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(n)
            val read = input.read(buf)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
    }.getOrDefault(ByteArray(0))

    private val SEVEN_Z_MAGIC = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    private fun zip(file: File): ZipFile = ZipFile.builder().setFile(file).get()
    private fun sevenZ(file: File): SevenZFile = SevenZFile.builder().setFile(file).get()
}
