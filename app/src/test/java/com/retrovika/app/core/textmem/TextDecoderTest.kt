package com.retrovika.app.core.textmem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextDecoderTest {

    private fun ram(size: Int, at: Int, bytes: ByteArray) = ByteArray(size).also { bytes.copyInto(it, at) }

    @Test
    fun `ascii separa os textos pelo byte nulo e ignora vazios`() {
        val r = ram(64, 10, "Hello World!\u0000\u0000Next line\u0000".toByteArray())
        assertEquals(listOf("Hello World!", "Next line"), TextDecoder.read(r, TextHook("d", 10, 40, TextEncoding.ASCII)))
    }

    @Test
    fun `quebra de linha vira espaco e controle some`() {
        val r = ram(64, 0, "Ola\nmundo\u0001!".toByteArray())
        assertEquals(listOf("Ola mundo!"), TextDecoder.read(r, TextHook("d", 0, 20, TextEncoding.ASCII)))
    }

    @Test
    fun `utf16 le e be`() {
        val le = "Hi there".flatMap { listOf(it.code.toByte(), 0.toByte()) }.toByteArray()
        assertEquals(listOf("Hi there"), TextDecoder.read(ram(64, 4, le), TextHook("d", 4, 40, TextEncoding.UTF16LE)))
        val be = "Hi there".flatMap { listOf(0.toByte(), it.code.toByte()) }.toByteArray()
        assertEquals(listOf("Hi there"), TextDecoder.read(ram(64, 4, be), TextHook("d", 4, 40, TextEncoding.UTF16BE)))
    }

    @Test
    fun `shift jis`() {
        val bytes = "こんにちは世界".toByteArray(charset("Shift_JIS"))
        assertEquals(listOf("こんにちは世界"), TextDecoder.read(ram(64, 8, bytes), TextHook("d", 8, 30, TextEncoding.SHIFT_JIS)))
    }

    @Test
    fun `tabela linear estilo pokemon`() {
        val t = LinearTable(upper = 0xBB, lower = 0xD5, digit = 0xA1, space = 0x00, terminator = 0xFF)
        // "Hello 42" + terminador + "Bye" + terminador
        val text = "Hello 42".map { c ->
            when (c) {
                in 'A'..'Z' -> t.upper!! + (c - 'A')
                in 'a'..'z' -> t.lower!! + (c - 'a')
                in '0'..'9' -> t.digit!! + (c - '0')
                else -> t.space!!
            }
        } + 0xFF + listOf(0xBC, 0xD5 + 24, 0xD5 + 4) + 0xFF
        val bytes = text.map { it.toByte() }.toByteArray()
        assertEquals(listOf("Hello 42", "Bye"), TextDecoder.read(ram(64, 2, bytes), TextHook("d", 2, 40, TextEncoding.TABLE, table = t)))
    }

    @Test
    fun `palavras invertidas do n64 leem o byte logico em n xor 3`() {
        // "Hello World!" em palavras de 4 bytes na ordem do processador: lleH oW o !dlr
        val r = ram(64, 16, "lleHoW o!dlr".toByteArray())
        assertEquals(listOf("Hello World!"), TextDecoder.read(r, TextHook("d", 16, 12, TextEncoding.ASCII, wordSwap = true)))
        // sem a inversão sai embaralhado
        assertEquals(listOf("lleHoW o!dlr"), TextDecoder.read(r, TextHook("d", 16, 12, TextEncoding.ASCII, wordSwap = false)))
    }

    @Test
    fun `gancho que sai da ram nao le nada`() {
        assertTrue(TextDecoder.read(ByteArray(16), TextHook("d", 8, 32, TextEncoding.ASCII)).isEmpty())
        assertNull(TextDecoder.slice(ByteArray(16), -1, 4, false))
    }

    @Test
    fun `so numeros e simbolos nao contam como texto`() {
        val r = ram(32, 0, "!!!---\u0000123\u0000".toByteArray())
        assertEquals(listOf("123"), TextDecoder.read(r, TextHook("d", 0, 16, TextEncoding.ASCII)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tabela e obrigatoria`() {
        TextHook("d", 0, 4, TextEncoding.TABLE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tamanho invalido`() {
        TextHook("d", 0, 0, TextEncoding.ASCII)
    }
}
