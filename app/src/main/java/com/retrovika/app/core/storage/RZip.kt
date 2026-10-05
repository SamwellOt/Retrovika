package com.retrovika.app.core.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.stream.IntStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Save states compactados no formato "rzip" do RetroArch: cabeçalho `#RZIPv\u0001#`, tamanho do bloco (uint32 LE),
 * tamanho original (uint64 LE) e, para cada bloco, o tamanho compactado (uint32 LE) seguido de um stream zlib
 * independente. O estado de um núcleo é quase todo memória zerada ou repetida (RAM, VRAM): costuma encolher de
 * 3 a 20 vezes. Os blocos independentes compactam e descompactam em paralelo, e gravar poucos MB no armazenamento
 * (FUSE no Android 11+) sai mais rápido do que gravar o estado cru: o salvamento automático do onPause não fica
 * mais lento, fica mais curto.
 *
 * Estados crus (das versões anteriores, ou de outra fonte) continuam sendo lidos como estão: [decompress] só mexe
 * no que começa com o cabeçalho.
 */
object RZip {
    private val MAGIC = byteArrayOf('#'.code.toByte(), 'R'.code.toByte(), 'Z'.code.toByte(), 'I'.code.toByte(), 'P'.code.toByte(), 'v'.code.toByte(), 1, '#'.code.toByte())
    private const val HEADER = 20
    /** O mesmo bloco padrão do RetroArch: pequeno o bastante para dividir o trabalho entre os núcleos da CPU. */
    const val CHUNK = 128 * 1024
    /** Nível 1: quase todo o ganho vem das longas sequências repetidas, que qualquer nível pega. */
    private const val LEVEL = Deflater.BEST_SPEED
    /** Teto do tamanho original declarado no cabeçalho: um arquivo corrompido não pede gigabytes de memória. */
    const val MAX_SIZE = 1024L * 1024 * 1024
    private const val MAX_RATIO = 1100L

    private val deflaters = ThreadLocal.withInitial { Deflater(LEVEL) }
    private val inflaters = ThreadLocal.withInitial { Inflater() }

    fun isCompressed(data: ByteArray): Boolean =
        data.size >= HEADER && MAGIC.indices.all { data[it] == MAGIC[it] }

    /**
     * [data] compactado, ou ele mesmo quando compactar não diminui (estado já compactado pelo núcleo). [parallel]
     * falso compacta só na thread atual (trabalho de fundo, que não pode disputar a CPU com o jogo).
     */
    fun compress(data: ByteArray, parallel: Boolean = true): ByteArray {
        if (data.isEmpty()) return data
        val count = (data.size + CHUNK - 1) / CHUNK
        val chunks = arrayOfNulls<ByteArray>(count)
        IntStream.range(0, count).let { if (parallel) it.parallel() else it }.forEach { i ->
            val off = i * CHUNK
            chunks[i] = deflateChunk(data, off, minOf(CHUNK, data.size - off))
        }
        val total = HEADER + chunks.sumOf { 4L + it!!.size }
        if (total >= data.size) return data
        val out = ByteArray(total.toInt())
        MAGIC.copyInto(out)
        putLe(out, 8, CHUNK.toLong(), 4)
        putLe(out, 12, data.size.toLong(), 8)
        var pos = HEADER
        for (c in chunks) {
            putLe(out, pos, c!!.size.toLong(), 4)
            c.copyInto(out, pos + 4)
            pos += 4 + c.size
        }
        return out
    }

    /** O estado original de [data]; o que não tem o cabeçalho rzip volta como está. */
    fun decompress(data: ByteArray): ByteArray {
        if (!isCompressed(data)) return data
        val chunkSize = getLe(data, 8, 4)
        val size = getLe(data, 12, 8)
        if (chunkSize <= 0 || chunkSize > Int.MAX_VALUE || size < 0 || size > MAX_SIZE) throw IOException("rzip header")
        // O deflate não passa de ~1032:1: um tamanho maior que isso é cabeçalho corrompido, e alocá-lo pediria
        // centenas de MB à toa.
        if (size > data.size.toLong() * MAX_RATIO) throw IOException("rzip header")
        val count = ((size + chunkSize - 1) / chunkSize).toInt()
        // Primeiro só os limites de cada bloco (sequencial e barato); depois os blocos em paralelo.
        val offsets = IntArray(count)
        val lengths = IntArray(count)
        var pos = HEADER
        for (i in 0 until count) {
            if (pos + 4 > data.size) throw IOException("rzip truncated")
            val len = getLe(data, pos, 4)
            if (len <= 0 || pos + 4 + len > data.size) throw IOException("rzip truncated")
            offsets[i] = pos + 4
            lengths[i] = len.toInt()
            pos += 4 + len.toInt()
        }
        val out = ByteArray(size.toInt())
        val step = chunkSize.toInt()
        IntStream.range(0, count).parallel().forEach { i ->
            val outOff = i * step
            val expected = minOf(step.toLong(), size - outOff).toInt()
            inflateChunk(data, offsets[i], lengths[i], out, outOff, expected)
        }
        return out
    }

    /**
     * Tamanho do estado de [file] já descompactado, lendo só o cabeçalho (20 bytes): quem decide se vale ler o estado
     * antes da hora precisa saber quanta memória ele vai pedir. Arquivo cru (versões anteriores) vale o próprio tamanho;
     * cabeçalho corrompido ou arquivo ilegível, -1.
     */
    fun originalSize(file: File): Long = runCatching {
        val length = file.length()
        if (length < HEADER) return@runCatching length
        val head = ByteArray(HEADER)
        val read = file.inputStream().use { it.read(head) }
        if (read < HEADER || !isCompressed(head)) return@runCatching length
        getLe(head, 12, 8).takeIf { it in 0..MAX_SIZE } ?: -1L
    }.getOrDefault(-1L)

    /** Lê um estado gravado por [write] (ou cru, de versões anteriores). */
    fun read(file: File): ByteArray = decompress(file.readBytes())

    /**
     * Grava [data] compactado em [file] por um temporário + renomear: falta de espaço ou o processo morto no meio
     * não destroem o estado que já estava lá.
     */
    fun write(file: File, data: ByteArray) {
        val packed = compress(data)
        val tmp = File(file.path + ".tmp")
        try {
            tmp.writeBytes(packed)
            synchronized(renameLock) { if (!tmp.renameTo(file)) throw IOException("rename ${tmp.name}") }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** Entre o [write] de um jogo aberto e o [compactInPlace] do mesmo arquivo, só um troca o arquivo por vez. */
    private val renameLock = Any()

    /**
     * Compacta no lugar um estado gravado cru (versões anteriores do app), mantendo a data dele (a lista de
     * slots a mostra). Desiste, sem tocar em nada, se o arquivo mudou enquanto era compactado: um jogo aberto
     * acabou de gravar um estado novo nele.
     */
    fun compactInPlace(file: File): Boolean {
        val modified = file.lastModified()
        val length = file.length()
        if (length < HEADER) return false
        val head = ByteArray(MAGIC.size)
        val read = file.inputStream().use { it.read(head) }
        if (read == head.size && head.contentEquals(MAGIC)) return false
        if (length > MAX_SIZE) return false
        val raw = file.readBytes()
        val packed = compress(raw, parallel = false)
        if (packed === raw) return false
        val tmp = File(file.path + ".rz.tmp")
        try {
            tmp.writeBytes(packed)
            // A data vai no temporário, antes de renomear: depois, fora da trava, ela poderia cair num estado novo
            // que o jogo acabou de gravar no lugar.
            tmp.setLastModified(modified)
            synchronized(renameLock) {
                if (file.lastModified() != modified || file.length() != length) { tmp.delete(); return false }
                if (!tmp.renameTo(file)) throw IOException("rename ${tmp.name}")
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        return true
    }

    /**
     * Compacta os estados crus de [root] (`states/`), um por vez, e deixa um marcador quando termina sem
     * falhas: a varredura só se repete se for interrompida.
     */
    fun compactTree(root: File) {
        val marker = File(root, COMPACTED_MARKER)
        if (marker.exists()) return
        var failed = false
        root.walkTopDown().maxDepth(3)
            .filter { it.isFile && (it.name.endsWith(".state") || it.name.endsWith(".state.bak")) }
            .forEach { f -> runCatching { compactInPlace(f) }.onFailure { failed = true } }
        if (!failed) runCatching { marker.createNewFile() }
    }

    private const val COMPACTED_MARKER = ".rzip-v1"

    /**
     * Área de trabalho de cada thread de compressão: o bloco compactado sai nela e só então vira um array do tamanho
     * exato. Antes cada bloco de 128 KB alocava um ByteArrayOutputStream que crescia por dobras, um buffer de 64 KB e
     * uma cópia final: dezenas de MB de lixo por salvamento, num estado de 30 MB, no momento em que o jogo mais precisa
     * do coletor quieto. O deflate de nível 1 de um bloco de 128 KB não passa disto nem sem compressão nenhuma
     * (o zlib garante len + len/4096 + len/16384 + 13); se algum dia passar, o laço de [deflateChunk] cresce a área.
     */
    private val scratch = ThreadLocal.withInitial { ByteArray(CHUNK + CHUNK / 64 + 1024) }

    private fun deflateChunk(data: ByteArray, off: Int, len: Int): ByteArray {
        val d = deflaters.get()!!
        d.reset()
        d.setInput(data, off, len)
        d.finish()
        var buf = scratch.get()!!
        var n = 0
        while (!d.finished()) {
            if (n == buf.size) {
                buf = buf.copyOf(buf.size * 2)
                scratch.set(buf)
            }
            n += d.deflate(buf, n, buf.size - n)
        }
        return buf.copyOf(n)
    }

    private fun inflateChunk(data: ByteArray, off: Int, len: Int, out: ByteArray, outOff: Int, expected: Int) {
        val inf = inflaters.get()!!
        inf.reset()
        inf.setInput(data, off, len)
        var written = 0
        while (written < expected) {
            val n = try {
                inf.inflate(out, outOff + written, expected - written)
            } catch (e: java.util.zip.DataFormatException) {
                throw IOException("rzip chunk", e)
            }
            // Bloco acabou antes (ou pede dicionário): arquivo corrompido. Nunca fica girando sem avançar.
            if (n == 0) break
            written += n
        }
        if (written != expected) throw IOException("rzip chunk")
        // Com a saída cheia o Inflater ainda não leu o fim do stream, onde está o adler32: sem esta leitura, um bloco
        // corrompido do mesmo tamanho passaria e o núcleo receberia lixo.
        if (!inf.finished()) {
            val extra = try { inf.inflate(ByteArray(1)) } catch (e: java.util.zip.DataFormatException) { throw IOException("rzip chunk", e) }
            if (extra != 0 || !inf.finished()) throw IOException("rzip chunk")
        }
    }

    private fun putLe(b: ByteArray, at: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) b[at + i] = (value ushr (8 * i)).toByte()
    }

    private fun getLe(b: ByteArray, at: Int, bytes: Int): Long {
        var v = 0L
        for (i in 0 until bytes) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }
}
