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
