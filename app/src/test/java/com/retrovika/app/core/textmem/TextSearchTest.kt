package com.retrovika.app.core.textmem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private fun ram(size: Int, at: Int, bytes: ByteArray) = ByteArray(size).also { bytes.copyInto(it, at) }

    @Test
    fun `acha ascii e devolve o endereco e a previa`() {
        val r = ram(4096, 0x123, "Hello World!\u0000".toByteArray())
        val m = TextSearch.find(r, "Hello World!").single { it.encoding == TextEncoding.ASCII }
        assertEquals(0x123, m.address)
        assertEquals("Hello World!", m.preview)
    }

    @Test
    fun `acha utf16 e shift jis`() {
        val utf = "Hello".flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
        assertEquals(0x40, TextSearch.find(ram(512, 0x40, utf), "Hello").single { it.encoding == TextEncoding.UTF16LE }.address)
        val sj = "こんにちは".toByteArray(charset("Shift_JIS"))
        assertEquals(0x80, TextSearch.find(ram(512, 0x80, sj), "こんにちは").single { it.encoding == TextEncoding.SHIFT_JIS }.address)
    }

    @Test
    fun `busca relativa acha texto numa tabela que nao e ascii`() {
        // Tabela estilo Pokémon: A=0xBB, a=0xD5, 0=0xA1, espaço=0x00.
        fun enc(c: Char) = when (c) { in 'A'..'Z' -> 0xBB + (c - 'A'); in 'a'..'z' -> 0xD5 + (c - 'a'); in '0'..'9' -> 0xA1 + (c - '0'); else -> 0x00 }
        val bytes = "Hello World 42".map { enc(it).toByte() }.toByteArray() + 0xFF.toByte()
        val r = ram(2048, 0x300, bytes)
        val found = TextSearch.find(r, "Hello World 42").single { it.encoding == TextEncoding.TABLE }
        assertEquals(0x300, found.address)
        val table = found.table!!
        assertEquals(0xBB, table.upper)
        assertEquals(0xD5, table.lower)
        assertEquals(0xA1, table.digit)
        assertEquals(0x00, table.space)
        assertEquals("Hello World 42", found.preview)
    }

    @Test
    fun `texto so em minusculas acha a tabela e deixa as maiusculas de fora`() {
        fun enc(c: Char) = 0xD5 + (c - 'a')
        val r = ram(1024, 0x10, "world".map { enc(it).toByte() }.toByteArray() + 0xFF.toByte())
        val m = TextSearch.find(r, "world").single { it.encoding == TextEncoding.TABLE }
        assertEquals(0xD5, m.table!!.lower)
        assertNull(m.table!!.upper)
    }

    @Test
    fun `n64 com palavras invertidas acha no endereco logico`() {
        val r = ram(8192, 0x1174, "lleHoW o!dlr".toByteArray())
        val m = TextSearch.find(r, "Hello World!", wordSwap = true).single { it.encoding == TextEncoding.ASCII }
        assertEquals(0x1174, m.address)
        assertTrue(TextSearch.find(r, "Hello World!", wordSwap = false).none { it.encoding == TextEncoding.ASCII })
    }

    @Test
    fun `texto curto demais nao procura`() {
        assertTrue(TextSearch.find(ByteArray(64), "ab").isEmpty())
        assertTrue(TextSearch.find(ByteArray(64), "  a  ").isEmpty())
    }

    @Test
    fun `nao acha o que nao esta la e limita a quantidade`() {
        assertTrue(TextSearch.find(ByteArray(1024), "Nada por aqui").isEmpty())
        val many = ByteArray(8192).also { r -> for (k in 0 until 100) "abc!".toByteArray().copyInto(r, k * 40) }
        assertEquals(12, TextSearch.find(many, "abc!").count { it.encoding == TextEncoding.ASCII })
    }

    @Test
    fun `o gancho montado a partir do achado le o mesmo texto`() {
        val r = ram(4096, 0x200, "Quest log\u0000".toByteArray())
        val m = TextSearch.find(r, "Quest log").first { it.encoding == TextEncoding.ASCII }
        val hook = TextHook("achado", m.address, m.length, m.encoding, table = m.table)
        assertEquals(listOf("Quest log"), TextDecoder.read(r, hook))
    }

    private fun c64Screen(): ByteArray {
        val ram = ByteArray(4096)
        fun code(c: Char): Int = if (c in 'A'..'Z') c - 'A' + 1 else c.code
        fun row(r: Int, text: String) { java.util.Arrays.fill(ram, 0x400 + r * 40, 0x400 + (r + 1) * 40, 32); text.forEachIndexed { i, c -> ram[0x400 + r * 40 + i] = code(c).toByte() } }
        for (r in 0 until 25) row(r, "")
        row(0, "**** COMMODORE 64 BASIC V2 ****")
        row(1, "64K RAM SYSTEM  38911 BASIC BYTES FREE")
        row(3, "READY.")
        row(4, "RUN")
        for (i in 0 until 40) { ram[0x400 + 5 * 40 + i] = (77 + i % 2).toByte() }     // labirinto do 10 PRINT
        return ram
    }

    @Test
    fun `tela de texto do c64 completa espaco e ascii baixo e lista so as linhas de texto`() {
        val ram = c64Screen()
        val m = TextSearch.find(ram, "READY", gridWidth = 40).first { it.encoding == TextEncoding.TABLE && it.address == 0x400 + 3 * 40 }
        assertEquals(1, m.table!!.upper)
        assertEquals(32, m.table!!.space)
        assertTrue(m.table!!.asciiLow)
        val hook = TextHook("tela", 0x400, 25 * 40, TextEncoding.TABLE, table = m.table, gridWidth = 40)
        val texts = TextDecoder.segments(ram.copyOfRange(0x400, 0x400 + 1000), hook).map { it.text }
        // "RUN" (curto, sem espaço) e o labirinto (gráficos) ficam de fora
        assertEquals(listOf("**** COMMODORE 64 BASIC V2 ****", "64K RAM SYSTEM 38911 BASIC BYTES FREE", "READY."), texts)
    }

    @Test
    fun `sem dominio de um byte nao inventa espaco`() {
        val ram = ByteArray(512) { (it * 7 % 251).toByte() }
        "READY".forEachIndexed { i, c -> ram[100 + i] = (c - 'A' + 1).toByte() }
        val m = TextSearch.find(ram, "READY", gridWidth = 40).first { it.encoding == TextEncoding.TABLE && it.address == 100 }
        assertNull(m.table!!.space)
    }
}
