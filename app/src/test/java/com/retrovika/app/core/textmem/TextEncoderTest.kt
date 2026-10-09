package com.retrovika.app.core.textmem

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEncoderTest {

    private val ascii = TextHook("a", 0, 64, TextEncoding.ASCII)

    @Test
    fun `acentos viram letras simples em ascii`() {
        val e = TextEncoder.encode("Ação, é só você!", ascii, 40)!!
        assertEquals("Acao, e so voce!", String(e.bytes, Charsets.US_ASCII))
        assertFalse(e.truncated)
    }

    @Test
    fun `aspas e reticencias bonitas viram simples`() {
        assertEquals("\"oi\" - ...", String(TextEncoder.encode("“oi” – …", ascii, 40)!!.bytes, Charsets.US_ASCII))
    }

    @Test
    fun `utf16 guarda os acentos`() {
        val e = TextEncoder.encode("Olá", ascii.copy(encoding = TextEncoding.UTF16LE), 20)!!
        assertArrayEquals("Olá".toByteArray(Charsets.UTF_16LE), e.bytes)
    }

    @Test
    fun `texto que nao cabe e cortado em palavra inteira com reticencias`() {
        val e = TextEncoder.encode("uma frase bem comprida demais", ascii, 16)!!
        assertTrue(e.truncated)
        assertTrue(e.bytes.size <= 16)
        assertTrue(String(e.bytes).endsWith("..."))
        assertEquals("uma frase bem...", String(e.bytes))
    }

    @Test
    fun `sem espaco nenhum nao codifica`() {
        assertNull(TextEncoder.encode("oi", ascii, 0))
    }

    @Test
    fun `tabela sem byte para o caractere usa o espaco ou omite`() {
        val t = LinearTable(upper = 0x10, lower = 0x30, digit = 0x50, space = 0x01, terminator = 0xFF)
        val h = TextHook("t", 0, 32, TextEncoding.TABLE, table = t)
        val e = TextEncoder.encode("Ab 7?", h, 20)!!
        // ? não existe na tabela: vira espaço
        assertArrayEquals(byteArrayOf(0x10, 0x31, 0x01, 0x57, 0x01), e.bytes)
    }

    @Test
    fun `quebra de linha pela largura`() {
        assertEquals("uma frase\nbem curta", TextEncoder.wrap("uma frase bem curta", 10))
        assertEquals("extraordinariamente\nlonga", TextEncoder.wrap("extraordinariamente longa", 10))
    }

    @Test
    fun `terminador por codificacao`() {
        assertArrayEquals(byteArrayOf(0), TextEncoder.terminator(ascii))
        assertArrayEquals(byteArrayOf(0, 0), TextEncoder.terminator(ascii.copy(encoding = TextEncoding.UTF16BE)))
        val t = LinearTable(upper = 0, lower = 26, terminator = 0xFF)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), TextEncoder.terminator(TextHook("t", 0, 8, TextEncoding.TABLE, table = t)))
    }

    @Test
    fun `tabela so com maiusculas dobra as minusculas`() {
        val t = LinearTable(upper = 1, lower = null, digit = 48, space = 32, terminator = 0xFF, extra = mapOf("." to 46))
        val h = TextHook("c64", 0, 64, TextEncoding.TABLE, table = t)
        val e = TextEncoder.encode("Pronto 64.", h, 20)!!
        assertArrayEquals(byteArrayOf(16, 18, 15, 14, 20, 15, 32, 54, 52, 46), e.bytes)
    }

    @Test
    fun `tabela so com minusculas dobra as maiusculas`() {
        val t = LinearTable(upper = null, lower = 0xD5, space = 0)
        val h = TextHook("t", 0, 64, TextEncoding.TABLE, table = t)
        assertArrayEquals(byteArrayOf(0xD6.toByte(), 0xD5.toByte()), TextEncoder.encode("BA", h, 8)!!.bytes)
    }

    @Test
    fun `preenchimento de linha por codificacao`() {
        val t = LinearTable(upper = 1, space = 32, terminator = 0xFF)
        assertArrayEquals(byteArrayOf(32), TextEncoder.fill(TextHook("c", 0, 40, TextEncoding.TABLE, table = t, gridWidth = 40)))
        assertArrayEquals(byteArrayOf(0x20), TextEncoder.fill(ascii))
        assertArrayEquals(byteArrayOf(0x20, 0), TextEncoder.fill(ascii.copy(encoding = TextEncoding.UTF16LE)))
    }
}
