package com.retrovika.app.core.net

import com.retrovika.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlin.coroutines.coroutineContext

/**
 * Tira um arquivo de dentro de um .zip remoto sem baixar o zip inteiro: lê o diretório central no fim do
 * arquivo e pede só os bytes daquela entrada (Range). O runtime do ONNX para uma arquitetura são ~12 MB
 * dentro de um .aar de 53 MB com as quatro.
 */
object RemoteZip {

    data class Entry(val name: String, val method: Int, val crc: Long, val compressedSize: Long, val size: Long, val localHeaderOffset: Long)

    private const val TAIL = 64 * 1024L
    /** Diretório central maior que isso não é de um .aar: recusa em vez de alocar. */
    private const val MAX_DIRECTORY = 16L * 1024 * 1024

    suspend fun extract(url: String, entryName: String, target: File, onBytes: (Long, Long) -> Unit = { _, _ -> }): File = withContext(Dispatchers.IO) {
        val (tailStart, tail) = fetchTail(url)
        val entry = findEntry(tail, tailStart, entryName) { offset, length -> fetchRange(url, offset, length) }
            ?: throw LocalizedException(R.string.remote_zip_missing_entry, entryName.substringAfterLast('/'))
        val header = fetchRange(url, entry.localHeaderOffset, 30)
        val dataStart = entry.localHeaderOffset + localHeaderLength(header)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        val ctx = coroutineContext
        try {
            val request = Request.Builder().url(url).header("Range", "bytes=$dataStart-${dataStart + entry.compressedSize - 1}").build()
            with(Http) { Http.client.newCall(request).executeCancellable { res ->
                if (res.code != 206) throw HttpStatusException(res.code, url)
                val raw = res.body!!.byteStream()
                // O Inflater guarda memória nativa: end() no fim, sempre (o InflaterInputStream com um Inflater
                // de fora não o encerra ao fechar).
                val inflater = if (entry.method == 8) Inflater(true) else null
                try {
                    val input: InputStream = if (inflater != null) InflaterInputStream(raw, inflater, 64 * 1024) else raw
                    val crc = CRC32()
                    var written = 0L
                    input.use { stream ->
                        part.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ctx.ensureActive()
                                val n = stream.read(buffer)
                                if (n < 0) break
                                // Mais do que o tamanho declarado: dado corrompido (ou uma bomba de compressão), para já.
                                if (written + n > entry.size) throw LocalizedException(R.string.remote_zip_corrupt, target.name)
                                out.write(buffer, 0, n)
                                crc.update(buffer, 0, n)
                                written += n
                                onBytes(written, entry.size)
                            }
                        }
                    }
                    if (written != entry.size || crc.value != entry.crc) throw LocalizedException(R.string.remote_zip_corrupt, target.name)
                } finally {
                    inflater?.end()
                }
            } }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw LocalizedException(R.string.download_move_failed, target.name)
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        target
    }

    /** Fim do arquivo (até 64 KB) e a posição em que ele começa. */
    private suspend fun fetchTail(url: String): Pair<Long, ByteArray> {
        val request = Request.Builder().url(url).header("Range", "bytes=-$TAIL").build()
        return with(Http) { Http.client.newCall(request).executeCancellable { res ->
            if (res.code != 206) throw HttpStatusException(res.code, url)
            val total = res.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: throw LocalizedException(R.string.remote_zip_no_range)
            val bytes = res.body!!.bytes()
            (total - bytes.size) to bytes
        } }
    }

    private suspend fun fetchRange(url: String, offset: Long, length: Int): ByteArray {
        val request = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        return with(Http) { Http.client.newCall(request).executeCancellable { res ->
            if (res.code != 206) throw HttpStatusException(res.code, url)
            res.body!!.bytes()
        } }
    }

    /**
     * Procura [name] no diretório central. [tail] são os últimos bytes do zip, começando em [tailStart];
     * se o diretório não couber neles, [read] busca o pedaço que falta.
     */
    suspend fun findEntry(tail: ByteArray, tailStart: Long, name: String, read: suspend (Long, Int) -> ByteArray): Entry? {
        val buf = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) if (buf.getInt(i) == 0x06054b50) { eocd = i; break }
        if (eocd < 0) throw LocalizedException(R.string.remote_zip_invalid)
        val cdSize = buf.getInt(eocd + 12).toLong() and 0xFFFFFFFFL
        val cdOffset = buf.getInt(eocd + 16).toLong() and 0xFFFFFFFFL
        // Diretório que sai do trecho lido ou é grande demais: zip inválido, não um IndexOutOfBounds.
        if (cdSize > MAX_DIRECTORY || (cdOffset >= tailStart && cdOffset - tailStart + cdSize > tail.size)) {
            throw LocalizedException(R.string.remote_zip_invalid)
        }
        val cd = if (cdOffset >= tailStart) {
            ByteBuffer.wrap(tail, (cdOffset - tailStart).toInt(), cdSize.toInt()).slice().order(ByteOrder.LITTLE_ENDIAN)
        } else {
            ByteBuffer.wrap(read(cdOffset, cdSize.toInt())).order(ByteOrder.LITTLE_ENDIAN)
        }
        var p = 0
        while (p + 46 <= cd.limit() && cd.getInt(p) == 0x02014b50) {
            val nameLen = cd.getShort(p + 28).toInt() and 0xFFFF
            val extraLen = cd.getShort(p + 30).toInt() and 0xFFFF
            val commentLen = cd.getShort(p + 32).toInt() and 0xFFFF
            if (p + 46 + nameLen > cd.limit()) throw LocalizedException(R.string.remote_zip_invalid)
            val nameBytes = ByteArray(nameLen)
            for (k in 0 until nameLen) nameBytes[k] = cd.get(p + 46 + k)
            if (String(nameBytes, Charsets.UTF_8) == name) {
                return Entry(
                    name = name,
                    method = cd.getShort(p + 10).toInt() and 0xFFFF,
                    crc = cd.getInt(p + 16).toLong() and 0xFFFFFFFFL,
                    compressedSize = cd.getInt(p + 20).toLong() and 0xFFFFFFFFL,
                    size = cd.getInt(p + 24).toLong() and 0xFFFFFFFFL,
                    localHeaderOffset = cd.getInt(p + 42).toLong() and 0xFFFFFFFFL,
                )
            }
            p += 46 + nameLen + extraLen + commentLen
        }
        return null
    }

    /** Tamanho do cabeçalho local (30 bytes fixos, o nome e o extra, que pode diferir do diretório central). */
    fun localHeaderLength(header: ByteArray): Int {
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        if (header.size < 30 || buf.getInt(0) != 0x04034b50) throw LocalizedException(R.string.remote_zip_invalid)
        return 30 + (buf.getShort(26).toInt() and 0xFFFF) + (buf.getShort(28).toInt() and 0xFFFF)
    }
}
