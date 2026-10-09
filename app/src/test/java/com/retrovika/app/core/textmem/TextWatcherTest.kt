package com.retrovika.app.core.textmem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWatcherTest {

    private fun ram(vararg parts: Pair<Int, ByteArray>): ByteArray {
        val r = ByteArray(4096)
        parts.forEach { (at, bytes) -> bytes.copyInto(r, at) }
        return r
    }

    private fun utf16le(s: String) = s.toByteArray(Charsets.UTF_16LE)
    private fun sjis(s: String) = s.toByteArray(charset("Shift_JIS"))

    @Test
    fun `primeira varredura so registra e o que muda no mesmo lugar vira fonte`() {
        val w = TextWatcher("gambatte", false)
        assertTrue(w.scan(ram(0x300 to "Hello traveler, welcome!".toByteArray()), emptyList()).isEmpty())
        val hooks = w.scan(ram(0x300 to "The king awaits you now".toByteArray()), emptyList())
        assertEquals(1, hooks.size)
        assertEquals(0x300, hooks[0].address)
        assertEquals(TextEncoding.ASCII, hooks[0].encoding)
        assertEquals("gambatte", hooks[0].core)
        assertTrue(hooks[0].name.startsWith(TextWatcher.AUTO_PREFIX))
    }

    @Test
    fun `texto parado na memoria nunca vira fonte`() {
        val w = TextWatcher(null, false)
        val same = ram(0x100 to "Static table of item names".toByteArray())
        w.scan(same, emptyList())
        assertTrue(w.scan(same, emptyList()).isEmpty())
        assertTrue(w.scan(same, emptyList()).isEmpty())
    }

    @Test
    fun `dados binarios que mudam nao viram fonte`() {
        val w = TextWatcher(null, false)
        val rnd = java.util.Random(7)
        fun noise() = ByteArray(4096).also { rnd.nextBytes(it) }
        w.scan(noise(), emptyList())
        assertTrue(w.scan(noise(), emptyList()).isEmpty())
    }

    @Test
    fun `fonte ja coberta nao se repete`() {
        val w = TextWatcher(null, false)
        w.scan(ram(0x300 to "Hello traveler, welcome!".toByteArray()), emptyList())
        val cover = TextHook("meu", 0x2F0, 256, TextEncoding.ASCII)
        assertTrue(w.scan(ram(0x300 to "The king awaits you now".toByteArray()), listOf(cover)).isEmpty())
    }

    @Test
    fun `utf16 e shift-jis`() {
        val w = TextWatcher(null, false)
        w.scan(ram(0x200 to utf16le("Hello there friend"), 0x600 to sjis("こんにちは、勇者よ")), emptyList())
        val hooks = w.scan(ram(0x200 to utf16le("Good bye my friend"), 0x600 to sjis("さようなら、勇者よ")), emptyList())
        assertEquals(setOf(TextEncoding.UTF16LE, TextEncoding.SHIFT_JIS), hooks.map { it.encoding }.toSet())
        assertEquals(setOf(0x200, 0x600), hooks.map { it.address }.toSet())
    }

    @Test
    fun `n64 com palavras invertidas acha na ordem logica`() {
        val logical = ram(0x300 to "Hello traveler, welcome!".toByteArray())
        fun swapped(l: ByteArray) = ByteArray(l.size) { l[it xor 3] }
        val w = TextWatcher("mupen", true)
        w.scan(swapped(logical), emptyList())
        val next = ram(0x300 to "The king awaits you now".toByteArray())
        val hooks = w.scan(swapped(next), emptyList())
        assertEquals(1, hooks.size)
        assertEquals(0x300, hooks[0].address)
        assertTrue(hooks[0].wordSwap)
    }
}
