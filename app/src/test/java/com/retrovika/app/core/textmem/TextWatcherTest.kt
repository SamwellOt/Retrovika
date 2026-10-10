package com.retrovika.app.core.textmem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextWatcherTest {

    private fun ram(vararg parts: Pair<Int, ByteArray>): ByteArray = ramOf(4096, *parts)

    private fun ramOf(size: Int, vararg parts: Pair<Int, ByteArray>): ByteArray {
        val r = ByteArray(size)
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

    @Test
    fun `dialogo que some e volta com outro texto vira fonte`() {
        val w = TextWatcher(null, false)
        w.scan(ram(0x300 to "Hello traveler, welcome!".toByteArray()), emptyList())
        assertTrue(w.scan(ram(), emptyList()).isEmpty())
        val hooks = w.scan(ram(0x300 to "The king awaits you now".toByteArray()), emptyList())
        assertEquals(1, hooks.size)
        assertEquals(0x300, hooks[0].address)
        assertEquals(TextEncoding.ASCII, hooks[0].encoding)
    }

    @Test
    fun `trecho que comeca antes do bloco que mudou e achado no comeco`() {
        val w = TextWatcher(null, false)
        val head = "The old king walked slowly through the quiet halls of the castle while the rain kept falling "
        w.scan(ramOf(8192, 0x1000 to (head + "sleeping in the town").toByteArray()), emptyList())
        val hooks = w.scan(ramOf(8192, 0x1000 to (head + "singing in the wood!").toByteArray()), emptyList())
        assertEquals(1, hooks.size)
        assertEquals(0x1000, hooks[0].address)
    }

    @Test
    fun `varredura incremental acha o mesmo que a completa`() {
        val w = TextWatcher(null, false)
        fun scene(a: String, b: String, c: String) = ramOf(
            16384,
            0x200 to a.toByteArray(),
            0x800 to utf16le(b),
            0x1400 to sjis(c),
            0x2000 to "Static item names table".toByteArray(),
            0x2A00 to utf16le("Quiet village square"),
        )
        w.scan(scene("Hello traveler, welcome!", "Hello there friend", "こんにちは、勇者よ"), emptyList())
        val hooks = w.scan(scene("The king awaits you now", "Good bye my friend", "さようなら、勇者よ"), emptyList())
        assertEquals(3, hooks.size)
        assertEquals(setOf(0x200, 0x800, 0x1400), hooks.map { it.address }.toSet())
    }

    @Test
    fun `ram que muda de tamanho recomeca`() {
        val w = TextWatcher(null, false)
        w.scan(ramOf(4096, 0x300 to "Hello traveler, welcome!".toByteArray()), emptyList())
        assertTrue(w.scan(ramOf(8192, 0x300 to "The king awaits you now".toByteArray()), emptyList()).isEmpty())
        val hooks = w.scan(ramOf(8192, 0x300 to "Dark forest ahead, beware".toByteArray()), emptyList())
        assertEquals(1, hooks.size)
        assertEquals(0x300, hooks[0].address)
    }
}

class TextWatcherTableTest {

    /** Uma tabela como a de vários jogos: minúsculas a partir de 0x80, maiúsculas a partir de 0x60, espaço 0x7F, fim 0x50. */
    private fun encode(text: String): ByteArray {
        val out = ArrayList<Byte>()
        for (c in text) out += when (c) {
            in 'a'..'z' -> (0x80 + (c - 'a')).toByte()
            in 'A'..'Z' -> (0x60 + (c - 'A')).toByte()
            ' ' -> 0x7F.toByte()
            '.' -> 0xE8.toByte()
            ',' -> 0xE9.toByte()
            else -> 0xE0.toByte()
        }
        out += 0x50
        return out.toByteArray()
    }

    private fun ramWith(text: String): ByteArray {
        val r = ByteArray(4096)
        encode(text).copyInto(r, 0x400)
        return r
    }

    @Test
    fun `acha a tabela sozinho quando o dialogo muda`() {
        val w = TextWatcher("gambatte", false)
        assertTrue(w.scan(ramWith("The old king said hello to you."), emptyList()).isEmpty())
        val hooks = w.scan(ramWith("You can find the sword in the cave. Be careful. There is a monster here."), emptyList())
        val hook = hooks.single { it.encoding == TextEncoding.TABLE }
        val table = hook.table!!
        assertEquals(0x80, table.lower)
        assertEquals(0x60, table.upper)
        assertEquals(0x7F, table.space)
        assertEquals(0x50, table.terminator)
        val decoded = TextDecoder.read(ramWith("You can find the sword in the cave. Be careful. There is a monster here."), hook).joinToString(" ")
        assertTrue(decoded, decoded.startsWith("You can find the sword in the cave."))
        assertTrue(decoded, decoded.endsWith("monster here."))
    }

    @Test
    fun `texto parado e ruido nao viram tabela`() {
        val w = TextWatcher(null, false)
        val same = ramWith("The old king said hello to you.")
        w.scan(same, emptyList())
        assertTrue(w.scan(same, emptyList()).none { it.encoding == TextEncoding.TABLE })
        val rnd = java.util.Random(11)
        fun noise() = ByteArray(4096).also { rnd.nextBytes(it) }
        w.scan(noise(), emptyList())
        assertTrue(w.scan(noise(), emptyList()).isEmpty())
    }
}
