package com.retrovika.app.core.textmem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class TableDetectorTest {

    /** Codifica [text] sem terminador: letras a partir de [lower] e [upper], espaço em [space], o resto pelo [map]. */
    private fun encode(
        text: String,
        lower: Int,
        upper: Int,
        space: Int,
        map: Map<Char, Int> = emptyMap(),
    ): ByteArray = ByteArray(text.length) { i ->
        val c = text[i]
        val b = when (c) {
            in 'a'..'z' -> lower + (c - 'a')
            in 'A'..'Z' -> upper + (c - 'A')
            ' ' -> space
            else -> requireNotNull(map[c]) { "sem código para '$c'" }
        }
        b.toByte()
    }

    /** Janela com zeros dos dois lados, o texto e o terminador. */
    private fun window(encoded: ByteArray, terminator: Int): ByteArray {
        val w = ByteArray(encoded.size + 1 + 2 * 16)
        encoded.copyInto(w, 16)
        w[16 + encoded.size] = terminator.toByte()
        return w
    }

    @Test
    fun `tabela de pokemon gen 3 com ponto e fim 0xFF`() {
        val text = "Hello there. Welcome to the world of Pokemon. My name is Birch."
        val enc = encode(text, lower = 0xD5, upper = 0xBB, space = 0x00, map = mapOf('.' to 0xAD))
        val d = TableDetector.detect(window(enc, 0xFF))
        assertNotNull(d)
        val t = d!!.table
        assertEquals(0xD5, t.lower)
        assertEquals(0xBB, t.upper)
        assertEquals(0x00, t.space)
        assertEquals(0xFF, t.terminator)
    }

    @Test
    fun `ascii deslocado de 0x40 aprende digitos e pontuacao`() {
        val text = "You found 25 gold coins in the old chest, hero."
        // Todo caractere que não é letra segue o ASCII somado a 0x40 (espaço incluído, que vira 0x60).
        val map = (' '..'~').filterNot { it.isLetter() }.associateWith { it.code + 0x40 }
        val enc = encode(text, lower = 0xA1, upper = 0x81, space = 0x60, map = map)
        val d = TableDetector.detect(window(enc, 0x00))
        assertNotNull(d)
        val t = d!!.table
        assertEquals(0xA1, t.lower)
        assertEquals(0x81, t.upper)
        assertEquals(0x60, t.space)
        assertEquals(0x70, t.digit)
        assertEquals(0x6C, t.extra[","])
        assertEquals(0x6E, t.extra["."])
        assertEquals(text, TextDecoder.decodeTable(enc, t))
    }

    @Test
    fun `texto em ascii puro nao vira tabela`() {
        val text = "The old king said hello to you. Be careful, there is a monster here."
        val map = (' '..'~').filterNot { it.isLetter() }.associateWith { it.code }
        val enc = encode(text, lower = 0x61, upper = 0x41, space = 0x20, map = map)
        assertNull(TableDetector.detect(window(enc, 0x00)))
    }

    @Test
    fun `ruido aleatorio nao vira tabela`() {
        val noise = ByteArray(512).also { java.util.Random(3).nextBytes(it) }
        assertNull(TableDetector.detect(noise))
    }

    @Test
    fun `janela menor que 12 bytes nao vira tabela`() {
        assertNull(TableDetector.detect(ByteArray(11) { (0x80 + it).toByte() }))
    }
}
