package com.retrovika.app.remote

import java.io.ByteArrayOutputStream

/** Quadro H.264 pronto para o MP4: NALs com tamanho na frente, sem SPS/PPS. */
class EncodedFrame(val sample: ByteArray, val key: Boolean, val ptsUs: Long)

/** Unidades NAL do H.264 como o MediaCodec entrega (Annex B, com códigos de início). */
object Avc {
    const val NAL_IDR = 5
    const val NAL_SPS = 7
    const val NAL_PPS = 8
    const val NAL_AUD = 9

    fun type(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    /** Separa nos códigos de início 00 00 01 / 00 00 00 01. */
    fun splitAnnexB(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): List<ByteArray> {
        val end = offset + length
        val starts = mutableListOf<Int>()
        var i = offset
        while (i + 2 < end) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else i++
        }
        if (starts.isEmpty()) return if (length > 0) listOf(data.copyOfRange(offset, end)) else emptyList()
        return starts.mapIndexedNotNull { index, start ->
            var stop = if (index + 1 < starts.size) starts[index + 1] - 3 else end
            // O zero extra de um código de 4 bytes pertence ao próximo código, não a esta NAL.
            if (index + 1 < starts.size && stop > start && data[stop - 1].toInt() == 0) stop--
            if (stop > start) data.copyOfRange(start, stop) else null
        }
    }

    /** Amostra MP4: cada NAL com o tamanho em 4 bytes na frente. */
    fun toLengthPrefixed(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(nals.sumOf { it.size + 4 })
        nals.forEach { nal ->
            out.write(nal.size ushr 24); out.write(nal.size ushr 16); out.write(nal.size ushr 8); out.write(nal.size)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** O "codecs" do MediaSource: avc1.PPCCLL (perfil, restrições e nível, direto do SPS). */
    fun codecString(sps: ByteArray): String = "avc1.%02x%02x%02x".format(sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)

    /**
     * O mesmo SPS dizendo que nenhum quadro sai fora de ordem (VUI com bitstream_restriction e
     * max_num_reorder_frames = 0). Sem isso, o decodificador de hardware do Chrome (e o do Windows no
     * Firefox) segura quadros até encher o DPB do nível, até 9 em 720p: 150 ms de atraso a 60 quadros.
     * Os encoders do Android quase nunca mandam esse campo. Se o SPS tiver algo que não sabemos ler
     * (matriz de quantização), volta o original.
     */
    fun lowDelaySps(sps: ByteArray): ByteArray = runCatching { rewriteSps(sps) }.getOrNull() ?: sps

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    private fun rewriteSps(sps: ByteArray): ByteArray? {
        if (sps.size < 4 || type(sps) != NAL_SPS) return null
        val rbsp = unescape(sps, 1)
        val r = BitReader(rbsp)
        val profile = r.u(8)
        r.u(16) // restrições e nível
        r.ue() // seq_parameter_set_id
        if (profile in HIGH_PROFILES) {
            if (r.ue() == 3) r.u(1)
            r.ue(); r.ue(); r.u(1)
            if (r.u(1) == 1) return null // seq_scaling_matrix_present_flag
        }
        r.ue() // log2_max_frame_num_minus4
        when (r.ue()) {
            0 -> r.ue()
            1 -> {
                r.u(1); r.se(); r.se()
                repeat(r.ue()) { r.se() }
            }
        }
        val refFrames = r.ue()
        r.u(1)
        r.ue(); r.ue()
        if (r.u(1) == 0) r.u(1)
        r.u(1)
        if (r.u(1) == 1) repeat(4) { r.ue() }

        val w = BitWriter()
        // Valores que o padrão supõe quando o campo falta.
        var mvOverBoundaries = 1
        var bytesPerPic = 2
        var bitsPerMb = 1
        var mvHorizontal = 15
        var mvVertical = 15
        var decBuffering = refFrames
        val vuiAt = r.position
        if (r.u(1) == 0) {
            w.copy(rbsp, vuiAt)
            w.u(1, 1)
            // aspect_ratio, overscan, video_signal_type, chroma_loc, timing, nal_hrd, vcl_hrd, pic_struct
            repeat(8) { w.u(1, 0) }
        } else {
            if (r.u(1) == 1 && r.u(8) == 255) r.u(32)
            if (r.u(1) == 1) r.u(1)
            if (r.u(1) == 1) {
                r.u(4)
                if (r.u(1) == 1) r.u(24)
            }
            if (r.u(1) == 1) { r.ue(); r.ue() }
            if (r.u(1) == 1) { r.u(32); r.u(32); r.u(1) }
            val nalHrd = r.u(1) == 1
            if (nalHrd) skipHrd(r)
            val vclHrd = r.u(1) == 1
            if (vclHrd) skipHrd(r)
            if (nalHrd || vclHrd) r.u(1)
            r.u(1) // pic_struct_present_flag
            val restrictionAt = r.position
            if (r.u(1) == 1) {
                mvOverBoundaries = r.u(1)
                bytesPerPic = r.ue(); bitsPerMb = r.ue()
                mvHorizontal = r.ue(); mvVertical = r.ue()
                if (r.ue() == 0) return sps
                decBuffering = r.ue()
            }
            w.copy(rbsp, restrictionAt)
        }
        w.u(1, 1)
        w.u(1, mvOverBoundaries)
        w.ue(bytesPerPic); w.ue(bitsPerMb)
        w.ue(mvHorizontal); w.ue(mvVertical)
        w.ue(0) // max_num_reorder_frames
        w.ue(maxOf(decBuffering, refFrames, 1))
        w.trailingBits()
        return byteArrayOf(sps[0]) + escape(w.toByteArray())
    }

    private fun skipHrd(r: BitReader) {
        val count = r.ue() + 1
        r.u(8) // bit_rate_scale, cpb_size_scale
        repeat(count) { r.ue(); r.ue(); r.u(1) }
        r.u(20)
    }

    /** Tira os bytes de prevenção de emulação (00 00 03 → 00 00). */
    fun unescape(data: ByteArray, offset: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var zeros = 0
        for (i in offset until data.size) {
            val b = data[i].toInt() and 0xFF
            if (zeros >= 2 && b == 3) { zeros = 0; continue }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Põe os bytes de prevenção de emulação: dois zeros seguidos de um byte até 3 ganham um 03 no meio. */
    fun escape(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + 8)
        var zeros = 0
        for (byte in rbsp) {
            val b = byte.toInt() and 0xFF
            if (zeros >= 2 && b <= 3) { out.write(3); zeros = 0 }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        var position = 0
            private set

        fun u(bits: Int): Int {
            var v = 0L
            repeat(bits) {
                if (position >= data.size * 8) throw IllegalArgumentException("SPS truncated")
                val bit = (data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
                v = (v shl 1) or bit.toLong()
                position++
            }
            return v.toInt()
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) if (++zeros > 31) throw IllegalArgumentException("Bad Exp-Golomb")
            return ((1L shl zeros) - 1 + u(zeros).toLong().and(0xFFFFFFFFL)).toInt()
        }

        fun se(): Int {
            val k = ue()
            return if (k and 1 == 1) (k + 1) / 2 else -(k / 2)
        }
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun u(bits: Int, value: Int) {
            for (i in bits - 1 downTo 0) {
                current = (current shl 1) or ((value ushr i) and 1)
                if (++count == 8) { out.write(current); current = 0; count = 0 }
            }
        }

        fun ue(value: Int) {
            val v = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(v)
            u(length - 1, 0)
            u(length, v)
        }

        /** Os primeiros [bits] bits de [data], como estão. */
        fun copy(data: ByteArray, bits: Int) {
            for (i in 0 until bits) u(1, (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1)
        }

        fun trailingBits() {
            u(1, 1)
            while (count != 0) u(1, 0)
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }
}

/**
 * MP4 fragmentado, o formato que o Media Source Extensions do navegador aceita: um segmento de
 * inicialização com o SPS/PPS e depois um fragmento (moof + mdat) por quadro, para a latência ficar
 * em um quadro. Só vídeo: o áudio vai à parte, direto para o Web Audio.
 */
class Fmp4(
    private val width: Int,
    private val height: Int,
    private val sps: ByteArray,
    private val pps: ByteArray,
) {
    val codec: String = Avc.codecString(sps)

    fun initSegment(): ByteArray = ftyp() + moov()

    /** [decodeTime] e [duration] na [TIMESCALE]. */
    fun fragment(sequence: Int, decodeTime: Long, duration: Int, sample: ByteArray, key: Boolean): ByteArray {
        fun moof(dataOffset: Int) = box(
            "moof",
            fullBox("mfhd", 0, 0, u32(sequence)),
            box(
                "traf",
                // default-base-is-moof: o data_offset do trun conta a partir do início do moof.
                fullBox("tfhd", 0, 0x020000, u32(TRACK_ID)),
                fullBox("tfdt", 1, 0, u64(decodeTime)),
                fullBox(
                    "trun", 0, 0x000701,
                    u32(1), u32(dataOffset), u32(duration), u32(sample.size),
                    u32(if (key) SAMPLE_SYNC else SAMPLE_NON_SYNC),
                ),
            ),
        )
        val size = moof(0).size
        return moof(size + 8) + box("mdat", sample)
    }

    private fun ftyp() = box("ftyp", ascii("isom"), u32(0x200), ascii("isom"), ascii("iso6"), ascii("avc1"), ascii("mp41"))

    private fun moov() = box(
        "moov",
        fullBox(
            "mvhd", 0, 0,
            u32(0), u32(0), u32(TIMESCALE), u32(0),
            u32(0x00010000), u16(0x0100), ByteArray(10), MATRIX, ByteArray(24), u32(TRACK_ID + 1),
        ),
        box(
            "trak",
            fullBox(
                "tkhd", 0, 0x000003,
                u32(0), u32(0), u32(TRACK_ID), u32(0), u32(0), ByteArray(8),
                u16(0), u16(0), u16(0), u16(0), MATRIX, u32(width shl 16), u32(height shl 16),
            ),
            box(
                "mdia",
                fullBox("mdhd", 0, 0, u32(0), u32(0), u32(TIMESCALE), u32(0), u16(0x55C4), u16(0)),
                fullBox("hdlr", 0, 0, u32(0), ascii("vide"), ByteArray(12), "Retrovika\u0000".toByteArray()),
                box(
                    "minf",
                    fullBox("vmhd", 0, 1, ByteArray(8)),
                    box("dinf", fullBox("dref", 0, 0, u32(1), fullBox("url ", 0, 1))),
                    box(
                        "stbl",
                        fullBox("stsd", 0, 0, u32(1), avc1()),
                        fullBox("stts", 0, 0, u32(0)),
                        fullBox("stsc", 0, 0, u32(0)),
                        fullBox("stsz", 0, 0, u32(0), u32(0)),
                        fullBox("stco", 0, 0, u32(0)),
                    ),
                ),
            ),
        ),
        box("mvex", fullBox("trex", 0, 0, u32(TRACK_ID), u32(1), u32(0), u32(0), u32(0))),
    )

    private fun avc1() = box(
        "avc1",
        ByteArray(6), u16(1), ByteArray(16),
        u16(width), u16(height), u32(0x00480000), u32(0x00480000), u32(0), u16(1),
        ByteArray(32), u16(0x0018), u16(0xFFFF),
        box(
            "avcC",
            byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte()),
            u16(sps.size), sps,
            byteArrayOf(1), u16(pps.size), pps,
        ),
    )

    companion object {
        const val TIMESCALE = 90_000
        private const val TRACK_ID = 1
        private const val SAMPLE_SYNC = 0x02000000
        private const val SAMPLE_NON_SYNC = 0x01010000

        private val MATRIX = listOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
            .fold(ByteArray(0)) { acc, v -> acc + u32(v) }

        private fun box(type: String, vararg parts: ByteArray): ByteArray {
            val size = 8 + parts.sumOf { it.size }
            val out = ByteArrayOutputStream(size)
            out.write(u32(size)); out.write(ascii(type))
            parts.forEach { out.write(it) }
            return out.toByteArray()
        }

        private fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray) =
            box(type, u32((version shl 24) or flags), *parts)

        private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
        private fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
        private fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
        private fun u64(v: Long) = u32((v ushr 32).toInt()) + u32(v.toInt())
    }
}

/**
 * Linha do tempo do vídeo de uma tela: começa em zero no primeiro quadro que ela recebe (um quadro-chave).
 *
 * Cada quadro sai só quando o seguinte chega, com a duração exata até ele. O Media Source trata um
 * intervalo maior que o dobro da duração anterior como corte e descarta tudo até o próximo quadro-chave:
 * com durações estimadas, qualquer variação de ritmo (o encoder atrasou um quadro) congelava a imagem.
 */
class VideoTrack {
    class Fragment(val data: ByteArray, val key: Boolean)

    @Volatile var needKey = true
    private var sequence = 0
    private var startUs = -1L
    private var held: EncodedFrame? = null

    /** O fragmento do quadro anterior a [frame], ou null se ainda não há um completo. */
    fun fragment(mp4: Fmp4, frame: EncodedFrame): Fragment? {
        val previous = held
        if (previous != null && frame.ptsUs <= previous.ptsUs) return null
        held = frame
        if (previous == null) {
            startUs = frame.ptsUs
            return null
        }
        val time = (previous.ptsUs - startUs) * Fmp4.TIMESCALE / 1_000_000
        val end = (frame.ptsUs - startUs) * Fmp4.TIMESCALE / 1_000_000
        return Fragment(mp4.fragment(++sequence, time, (end - time).toInt().coerceAtLeast(1), previous.sample, previous.key), previous.key)
    }
}
