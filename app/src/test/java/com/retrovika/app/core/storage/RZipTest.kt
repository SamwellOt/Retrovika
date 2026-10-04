package com.retrovika.app.core.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Deflater
import kotlin.random.Random

class RZipTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Parecido com um estado de núcleo: muita memória zerada, trechos repetidos e um pouco de ruído. */
    private fun fakeState(size: Int, seed: Int = 1): ByteArray {
        val r = Random(seed)
        val b = ByteArray(size)
        var i = 0
        while (i < size) {
            val run = r.nextInt(16, 4096).coerceAtMost(size - i)
            when (r.nextInt(4)) {
                0 -> r.nextBytes(b, i, i + run)
                1 -> for (k in 0 until run) b[i + k] = (k % 7).toByte()
                else -> {}
            }
            i += run
        }
        return b
    }

    @Test
    fun `compacta e volta igual, em varios tamanhos de bloco`() {
        for (size in listOf(1, 100, RZip.CHUNK - 1, RZip.CHUNK, RZip.CHUNK + 1, 5 * RZip.CHUNK + 333, 3_000_000)) {
            val state = fakeState(size, seed = size)
            val packed = RZip.compress(state)
            assertArrayEquals("size $size", state, RZip.decompress(packed))
            if (size > 4096) assertTrue("size $size", RZip.isCompressed(packed) && packed.size < state.size)
        }
    }

    @Test
    fun `em serie e em paralelo geram o mesmo arquivo`() {
        val state = fakeState(2_000_000)
        assertArrayEquals(RZip.compress(state, parallel = true), RZip.compress(state, parallel = false))
    }

    @Test
    fun `estado cru antigo e lido como esta`() {
        val raw = fakeState(10_000)
        assertSame(raw, RZip.decompress(raw))
    }

    @Test
    fun `incompressivel fica cru`() {
        val noise = Random(7).nextBytes(500_000)
        assertSame(noise, RZip.compress(noise))
    }

    @Test
    fun `formato do cabecalho do RetroArch`() {
        val state = ByteArray(300_000)
        val packed = RZip.compress(state)
        assertEquals("#RZIPv", String(packed, 0, 6, Charsets.US_ASCII))
        assertEquals(1, packed[6].toInt())
        assertEquals('#'.code, packed[7].toInt())
        assertEquals(RZip.CHUNK.toLong(), le(packed, 8, 4))
        assertEquals(state.size.toLong(), le(packed, 12, 8))
    }

    @Test
    fun `le rzip com outro tamanho de bloco`() {
        // Como o RetroArch grava com outro bloco (ou nível): cada bloco é um stream zlib independente.
        val state = fakeState(100_000)
        val chunk = 32 * 1024
        val out = ByteArrayOutputStream()
        out.write("#RZIPv".toByteArray()); out.write(1); out.write('#'.code)
        out.write(leBytes(chunk.toLong(), 4)); out.write(leBytes(state.size.toLong(), 8))
        var off = 0
        while (off < state.size) {
            val len = minOf(chunk, state.size - off)
            val d = Deflater(6).apply { setInput(state, off, len); finish() }
            val buf = ByteArray(len + 1024)
            val n = d.deflate(buf)
            out.write(leBytes(n.toLong(), 4)); out.write(buf, 0, n)
            off += len
        }
        assertArrayEquals(state, RZip.decompress(out.toByteArray()))
    }

    @Test(expected = IOException::class)
    fun `arquivo cortado falha em vez de devolver lixo`() {
        val packed = RZip.compress(fakeState(600_000))
        RZip.decompress(packed.copyOf(packed.size - 10))
    }

    @Test(expected = IOException::class)
    fun `bloco corrompido falha com IOException`() {
        val packed = RZip.compress(fakeState(600_000))
        for (i in 40 until 60) packed[i] = (packed[i].toInt() xor 0x5A).toByte()
        RZip.decompress(packed)
    }

    @Test(expected = IOException::class)
    fun `checksum errado com o mesmo tamanho falha`() {
        // Bloco "stored" (nível 0): trocar um byte do conteúdo mantém o tamanho, e só o adler32 do fim acusa.
        val state = ByteArray(1000) { it.toByte() }
        val d = Deflater(0).apply { setInput(state); finish() }
        val buf = ByteArray(2000)
        val n = d.deflate(buf)
        buf[100] = (buf[100] + 1).toByte()
        val out = ByteArrayOutputStream()
        out.write("#RZIPv".toByteArray()); out.write(1); out.write('#'.code)
        out.write(leBytes(RZip.CHUNK.toLong(), 4)); out.write(leBytes(state.size.toLong(), 8))
        out.write(leBytes(n.toLong(), 4)); out.write(buf, 0, n)
        RZip.decompress(out.toByteArray())
    }

    @Test(expected = IOException::class)
    fun `cabecalho com tamanho absurdo nao aloca`() {
        val packed = RZip.compress(ByteArray(300_000))
        // Declara 900 MB num arquivo de poucos KB.
        val fake = 900L * 1024 * 1024
        for (i in 0 until 8) packed[12 + i] = (fake ushr (8 * i)).toByte()
        RZip.decompress(packed)
    }

    @Test
    fun `compacta no lugar mantendo a data`() {
        val f = tmp.newFile("slot1.state")
        val state = fakeState(1_000_000)
        f.writeBytes(state)
        f.setLastModified(1_600_000_000_000)
        assertTrue(RZip.compactInPlace(f))
        assertTrue(f.length() < state.size)
        assertEquals(1_600_000_000_000, f.lastModified())
        assertArrayEquals(state, RZip.read(f))
        // Já compactado: nada muda.
        assertFalse(RZip.compactInPlace(f))
    }

    @Test
    fun `varre a pasta de estados uma vez`() {
        val root = tmp.newFolder("states")
        val dir = root.resolve("snes/42").apply { mkdirs() }
        val state = fakeState(400_000)
        dir.resolve("slot0.state").writeBytes(state)
        dir.resolve("slot0.1700000000000.state.bak").writeBytes(state)
        dir.resolve("slot0.png").writeBytes(byteArrayOf(1, 2, 3))
        RZip.compactTree(root)
        assertTrue(RZip.isCompressed(dir.resolve("slot0.state").readBytes()))
        assertTrue(RZip.isCompressed(dir.resolve("slot0.1700000000000.state.bak").readBytes()))
        assertArrayEquals(byteArrayOf(1, 2, 3), dir.resolve("slot0.png").readBytes())
        // Com o marcador, um estado cru que apareça depois não é mais procurado.
        dir.resolve("slot2.state").writeBytes(state)
        RZip.compactTree(root)
        assertFalse(RZip.isCompressed(dir.resolve("slot2.state").readBytes()))
    }

    @Test
    fun `gravar e ler`() {
        val f = tmp.root.resolve("slot3.state")
        val state = fakeState(800_000)
        RZip.write(f, state)
        assertTrue(RZip.isCompressed(f.readBytes()))
        assertArrayEquals(state, RZip.read(f))
        assertFalse(tmp.root.resolve("slot3.state.tmp").exists())
    }

    private fun le(b: ByteArray, at: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = v or ((b[at + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    private fun leBytes(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
}
