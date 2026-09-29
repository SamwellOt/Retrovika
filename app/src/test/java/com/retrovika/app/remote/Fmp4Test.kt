package com.retrovika.app.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class Fmp4Test {
    // SPS/PPS reais de um encoder (Baseline 3.1, 1280x720).
    private val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0xDA.toByte(), 0x01, 0x40, 0x16, 0xE8.toByte(), 0x40, 0x00, 0x00, 0x03, 0x00, 0x40, 0x00, 0x00, 0x0F, 0x23, 0xC6.toByte(), 0x0C, 0x92.toByte())
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x06, 0xE2.toByte())

    @Test
    fun splitsAnnexBWithThreeAndFourByteStartCodes() {
        val data = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 4, 5, 6)
        val nals = Avc.splitAnnexB(data)
        assertEquals(3, nals.size)
        assertArrayEquals(byteArrayOf(0x67, 1, 2), nals[0])
        assertArrayEquals(byteArrayOf(0x68, 3), nals[1])
        assertArrayEquals(byteArrayOf(0x65, 4, 5, 6), nals[2])
        assertEquals(listOf(Avc.NAL_SPS, Avc.NAL_PPS, Avc.NAL_IDR), nals.map(Avc::type))
    }

    @Test
    fun lengthPrefixesEachNal() {
        val out = Avc.toLengthPrefixed(listOf(byteArrayOf(0x65, 1), byteArrayOf(0x06)))
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 0x65, 1, 0, 0, 0, 1, 0x06), out)
    }

    @Test
    fun codecStringComesFromSps() {
        assertEquals("avc1.42c01f", Avc.codecString(sps))
    }

    @Test
    fun initSegmentHasTheExpectedBoxTree() {
        val init = Fmp4(1280, 720, sps, pps).initSegment()
        val top = boxes(init, 0, init.size)
        assertEquals(listOf("ftyp", "moov"), top.map { it.type })
        val moov = top[1]
        assertEquals(listOf("mvhd", "trak", "mvex"), boxes(init, moov.body, moov.end).map { it.type })
        // O avcC dentro do stsd leva o SPS e o PPS inteiros.
        val avcc = find(init, "avcC")!!
        val body = init.copyOfRange(avcc.body, avcc.end)
        assertEquals(1, body[0].toInt())
        assertEquals(sps.size, ((body[6].toInt() and 0xFF) shl 8) or (body[7].toInt() and 0xFF))
        assertArrayEquals(sps, body.copyOfRange(8, 8 + sps.size))
        assertArrayEquals(pps, body.copyOfRange(8 + sps.size + 3, body.size))
        // Largura e altura no avc1.
        val avc1 = find(init, "avc1")!!
        val b = ByteBuffer.wrap(init, avc1.body + 24, 4)
        assertEquals(1280, b.short.toInt() and 0xFFFF)
        assertEquals(720, b.short.toInt() and 0xFFFF)
    }

    @Test
    fun fragmentDataOffsetPointsAtTheSample() {
        val sample = byteArrayOf(0, 0, 0, 3, 0x65, 9, 8)
        val frag = Fmp4(1280, 720, sps, pps).fragment(7, 123_456L, 1500, sample, key = true)
        val top = boxes(frag, 0, frag.size)
        assertEquals(listOf("moof", "mdat"), top.map { it.type })
        val trun = find(frag, "trun")!!
        val t = ByteBuffer.wrap(frag, trun.body, trun.end - trun.body)
        assertEquals(0x000701, t.int)          // versão 0 + flags
        assertEquals(1, t.int)                 // sample_count
        val dataOffset = t.int
        assertEquals(1500, t.int)              // duração
        assertEquals(sample.size, t.int)
        assertEquals(0x02000000, t.int)        // quadro-chave
        assertArrayEquals(sample, frag.copyOfRange(dataOffset, dataOffset + sample.size))
        val tfdt = find(frag, "tfdt")!!
        val d = ByteBuffer.wrap(frag, tfdt.body, 12)
        assertEquals(0x01000000, d.int)
        assertEquals(123_456L, d.long)
        val mfhd = find(frag, "mfhd")!!
        assertEquals(7, ByteBuffer.wrap(frag, mfhd.body + 4, 4).int)
    }

    @Test
    fun nonKeyFramesAreMarkedNonSync() {
        val frag = Fmp4(1280, 720, sps, pps).fragment(1, 0, 1500, byteArrayOf(0, 0, 0, 1, 0x41), key = false)
        val trun = find(frag, "trun")!!
        assertEquals(0x01010000, ByteBuffer.wrap(frag, trun.body + 20, 4).int)
        assertNull(find(frag, "nope"))
    }

    @Test
    fun timelineHasNoGapsWhateverTheFrameRhythm() {
        val mp4 = Fmp4(1280, 720, sps, pps)
        val track = VideoTrack()
        // Ritmo irregular, como um encoder sob carga: 16, 42, 17, 100 e 16 ms.
        val pts = listOf(5_000_000L, 5_016_000L, 5_058_000L, 5_075_000L, 5_175_000L, 5_191_000L)
        val fragments = pts.mapIndexedNotNull { i, t -> track.fragment(mp4, EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x65), i == 0, t)) }
        // Cada quadro sai quando o próximo chega: o último fica guardado.
        assertEquals(pts.size - 1, fragments.size)
        // Só o primeiro era quadro-chave, e a marca acompanha o fragmento dele (a tela recomeça dali).
        assertEquals(listOf(true, false, false, false, false), fragments.map { it.key })
        val out = fragments.map { it.data }
        var expected = 0L
        out.forEachIndexed { i, frag ->
            val tfdt = find(frag, "tfdt")!!
            val time = ByteBuffer.wrap(frag, tfdt.body + 4, 8).long
            val trun = find(frag, "trun")!!
            val duration = ByteBuffer.wrap(frag, trun.body + 12, 4).int
            // Começa onde o anterior terminou: sem buraco, o MSE não vê corte.
            assertEquals("frame $i", expected, time)
            assertEquals((pts[i + 1] - pts[i]) * 90 / 1000, duration.toLong())
            expected = time + duration
        }
        // Quadro fora de ordem é ignorado.
        assertNull(track.fragment(mp4, EncodedFrame(byteArrayOf(0, 0, 0, 1, 0x41), false, 5_100_000L)))
    }

    private data class Box(val type: String, val start: Int, val body: Int, val end: Int)

    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val out = mutableListOf<Box>()
        var i = from
        while (i + 8 <= to) {
            val size = ByteBuffer.wrap(data, i, 4).int
            val type = String(data, i + 4, 4, Charsets.US_ASCII)
            out += Box(type, i, i + 8, i + size)
            i += size
        }
        assertEquals("box sizes must add up", to, i)
        return out
    }

    /** Busca em profundidade; entra só nos contêineres conhecidos. */
    private fun find(data: ByteArray, type: String, from: Int = 0, to: Int = data.size): Box? {
        for (b in boxes(data, from, to)) {
            if (b.type == type) return b
            val inner = when (b.type) {
                "moov", "trak", "mdia", "minf", "stbl", "moof", "traf", "mvex", "dinf" -> b.body
                "stsd" -> b.body + 8
                "avc1" -> b.body + 78
                else -> -1
            }
            if (inner >= 0) find(data, type, inner, b.end)?.let { return it }
        }
        return null
    }
}
