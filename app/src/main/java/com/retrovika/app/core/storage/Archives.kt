package com.retrovika.app.core.storage

import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import net.lingala.zip4j.ZipFile as ZipFile4j

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

    fun entries(archive: File, password: String? = null): List<Entry> = when (formatOf(archive)) {
        Format.ZIP -> zip(archive).use { zf -> zf.entries.toList().filterNot { it.isDirectory }.map { Entry(it.name, it.size) } }
        // 7z criado da entrada padrão ("7z a -si") não guarda nome: usa o do próprio arquivo.
        Format.SEVEN_Z -> sevenZ(archive, password).use { sz -> sz.entries.filterNot { it.isDirectory }.map { Entry(it.name ?: archive.nameWithoutExtension, if (it.hasStream()) it.size else 0L) } }
        null -> emptyList()
    }

    /**
     * Verdadeiro se o conteúdo está protegido por senha: entradas cifradas no .zip, AES no .7z ou o próprio
     * índice do .7z cifrado (aí nem a lista de arquivos abre sem a senha).
     */
    fun needsPassword(archive: File): Boolean = when (formatOf(archive)) {
        Format.ZIP -> zip(archive).use { zf -> zf.entries.toList().any { it.generalPurposeBit.usesEncryption() } }
        Format.SEVEN_Z -> try {
            sevenZ(archive, null).use { sz -> sz.entries.any { e -> e.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true } }
        } catch (_: PasswordRequiredException) {
            true
        }
        null -> false
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
    fun extract(archive: File, destDir: File, names: Set<String>, password: String? = null): Map<String, File> {
        destDir.mkdirs()
        val out = LinkedHashMap<String, File>()
        val canonicalDest = destDir.canonicalPath + File.separator
        val prefix = commonFolder(names)
        // Entrada cujo destino já existe (jogo já na biblioteca) é gravada num temporário ao lado e só
        // substitui o original no fim: uma falha no meio não pode deixar o arquivo antigo truncado.
        val staged = LinkedHashMap<File, File>()
        val written = ArrayList<File>()
        fun target(name: String): File {
            val relative = name.replace('\\', '/').removePrefix(prefix).trimStart('/')
            val file = File(destDir, relative)
            if (relative.isEmpty() || !file.canonicalPath.startsWith(canonicalDest)) {
                throw LocalizedException(R.string.download_extract_failed, name)
            }
            file.parentFile?.mkdirs()
            out[name] = file
            val dest = if (file.exists()) File(file.parentFile, ".${file.name}.extracting").also { staged[it] = file } else file
            written += dest
            return dest
        }
        try {
            when (formatOf(archive)) {
                // ZipFile lê o diretório central: aceita Deflate64, ZIP64 e entradas com "data descriptor",
                // que o ZipInputStream do Java recusa (zips grandes feitos no Windows, por exemplo).
                // Cifrado: o commons-compress não descriptografa zip, o zip4j sim (ZipCrypto e AES).
                Format.ZIP -> if (password != null) ZipFile4j(archive, password.toCharArray()).use { zf ->
                    for (header in zf.fileHeaders) {
                        if (header.isDirectory || header.fileName !in names) continue
                        val file = target(header.fileName)
                        zf.getInputStream(header).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                } else zip(archive).use { zf ->
                    for (entry in zf.entries.toList()) {
                        if (entry.isDirectory || entry.name !in names) continue
                        if (!zf.canReadEntryData(entry)) throw LocalizedException(R.string.download_unsupported_format, entry.name)
                        val file = target(entry.name)
                        zf.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                }
                // O 7z nativo não decifra AES: com senha, direto no commons-compress.
                Format.SEVEN_Z -> if (password != null || !extractSevenZNative(archive, names, ::target) { out.clear(); staged.clear(); written.clear() }) sevenZ(archive, password).use { sz ->
                    while (true) {
                        val entry = sz.nextEntry ?: break
                        val name = entry.name ?: archive.nameWithoutExtension
                        if (entry.isDirectory || name !in names) continue
                        val file = target(name)
                        sz.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                    }
                }
                null -> throw LocalizedException(R.string.download_unsupported_format, archive.name)
            }
            for ((temp, file) in staged) {
                file.delete()
                if (!temp.renameTo(file)) throw LocalizedException(R.string.download_extract_failed, file.name)
            }
        } catch (t: Throwable) {
            // Só o que esta extração gravou: os originais que ela ia substituir ficam intactos.
            written.forEach { it.delete() }
            throw t
        }
        return out
    }

    /**
     * Extrai pelo [SevenZipNative], com o dicionário fora do heap Java. Falso quando ele não serve (biblioteca
     * ausente, método que ele não decodifica): aí nada foi gravado e o commons-compress tenta.
     */
    private fun extractSevenZNative(archive: File, names: Set<String>, target: (String) -> File, forget: () -> Unit): Boolean {
        if (!SevenZipNative.available) return false
        val all = SevenZipNative.list(archive.path) ?: return false
        val targets = arrayOfNulls<String>(all.size)
        all.forEachIndexed { i, raw ->
            // Entrada sem nome ("7z a -si"): o mesmo nome que [entries] deu a ela.
            val name = raw.ifEmpty { archive.nameWithoutExtension }
            if (!name.endsWith('/') && name in names) targets[i] = target(name).path
        }
        return when (SevenZipNative.extract(archive.path, targets)) {
            SevenZipNative.OK -> true
            // Nada foi gravado: só esquece os destinos (apagá-los levaria arquivos que já existiam).
            SevenZipNative.UNSUPPORTED, SevenZipNative.OPEN -> {
                forget()
                false
            }
            // Nem na memória nativa coube: o RomExtractor transforma isso na mensagem de memória.
            SevenZipNative.MEMORY -> throw OutOfMemoryError("7z: ${archive.name}")
            SevenZipNative.DATA -> throw LocalizedException(R.string.download_archive_corrupt, archive.name)
            else -> throw LocalizedException(R.string.download_extract_failed, archive.name)
        }
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
    /**
     * O LZMA/LZMA2 aloca o dicionário inteiro no heap (7z "ultra" chega a 256 MB ou mais). Com um teto
     * abaixo do heap, um dicionário grande demais falha com [org.apache.commons.compress.MemoryLimitException]
     * antes de alocar, em vez de um OutOfMemoryError que pode derrubar outras threads.
     */
    private fun sevenZ(file: File, password: String?): SevenZFile = SevenZFile.builder()
        .setFile(file)
        .setMaxMemoryLimitKb((Runtime.getRuntime().maxMemory() / 4 * 3 / 1024).toInt())
        .apply { if (password != null) setPassword(password.toCharArray()) }
        .get()
}
