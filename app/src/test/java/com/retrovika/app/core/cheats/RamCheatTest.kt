package com.retrovika.app.core.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RamCheatTest {

    @Test
    fun `o codigo vai e volta`() {
        val cheat = RamCheat(address = 0x1F2A, value = 99, width = 2)
        assertEquals("ram:1f2a:63:2", cheat.code())
        assertEquals(cheat, RamCheat.parse(cheat.code()))
    }

    @Test
    fun `big endian e marcado no fim`() {
        val cheat = RamCheat(0x20, 0xDEADBEEFL, 4, bigEndian = true)
        assertEquals("ram:20:deadbeef:4:be", cheat.code())
        assertEquals(cheat, RamCheat.parse(cheat.code()))
    }

    @Test
    fun `aceita maiusculas e espacos`() {
        assertEquals(RamCheat(0xAB, 0xFF, 1), RamCheat.parse("  RAM:AB:FF:1  "))
    }

    @Test
    fun `so reconhece o prefixo ram`() {
        assertTrue(RamCheat.isRam("ram:0:0:1"))
        assertFalse(RamCheat.isRam("7E0DBF63"))
        assertNull(RamCheat.parse("7E0DBF63"))
    }

    @Test
    fun `recusa codigo malformado`() {
        assertNull(RamCheat.parse("ram:10:5"))            // sem largura
        assertNull(RamCheat.parse("ram:10:5:3"))          // largura que não existe
        assertNull(RamCheat.parse("ram:10:100:1"))        // 0x100 não cabe em 1 byte
        assertNull(RamCheat.parse("ram:10:10000:2"))      // não cabe em 2 bytes
        assertNull(RamCheat.parse("ram:zz:1:1"))          // endereço que não é hexadecimal
        assertNull(RamCheat.parse("ram:-1:1:1"))
        assertNull(RamCheat.parse("ram:10:5:1:le"))       // ordem desconhecida
        assertNull(RamCheat.parse("ram:100000000:1:1"))   // além de 32 bits
        assertNull(RamCheat.parse("ram:80000000:1:1"))    // não cabe em Int (a RAM tem no máximo 64 MB)
    }

    @Test
    fun `valor maximo por largura`() {
        assertEquals(0xFFL, RamCheat.maxValue(1))
        assertEquals(0xFFFFL, RamCheat.maxValue(2))
        assertEquals(0xFFFFFFFFL, RamCheat.maxValue(4))
        assertEquals(RamCheat(0, 0xFFFFFFFFL, 4), RamCheat.parse("ram:0:ffffffff:4"))
    }

    @Test
    fun `palavras invertidas do n64 vao no codigo e na largura nativa`() {
        val cheat = RamCheat(0x100, 99, 1, wordSwap = true)
        assertEquals("ram:100:63:1:sw", cheat.code())
        assertEquals(cheat, RamCheat.parse(cheat.code()))
        assertEquals(1 or 0x200, cheat.nativeWidth())
    }

    @Test
    fun `largura nativa soma big endian e palavras invertidas`() {
        assertEquals(2, RamCheat(0, 1, 2).nativeWidth())
        assertEquals(2 or 0x100, RamCheat(0, 1, 2, bigEndian = true).nativeWidth())
        assertEquals(4 or 0x100 or 0x200, RamCheat(0, 1, 4, bigEndian = true, wordSwap = true).nativeWidth())
        assertEquals(RamCheat(0, 1, 4, bigEndian = true, wordSwap = true), RamCheat.parse("ram:0:1:4:be:sw"))
    }

    @Test
    fun `bandeira repetida ou desconhecida e recusada`() {
        assertNull(RamCheat.parse("ram:0:1:1:be:be"))
        assertNull(RamCheat.parse("ram:0:1:1:sw:sw"))
        assertNull(RamCheat.parse("ram:0:1:1:be:sw:be"))
        assertNull(RamCheat.parse("ram:0:1:1:xx"))
    }

    @Test
    fun `o nucleo vai no codigo e filtra onde vale`() {
        val cheat = RamCheat(0x20, 5, 1, core = "mgba")
        assertEquals("ram:20:5:1:c-mgba", cheat.code())
        assertEquals(cheat, RamCheat.parse(cheat.code()))
        assertTrue(cheat.appliesTo("mgba"))
        assertFalse(cheat.appliesTo("gpsp"))
        assertTrue(RamCheat(0x20, 5, 1).appliesTo("qualquer"))
        assertEquals(RamCheat(0x20, 5, 1, bigEndian = true, wordSwap = true, core = "mupen64plus_next_gles3"),
            RamCheat.parse("ram:20:5:1:be:sw:c-mupen64plus_next_gles3"))
        assertNull(RamCheat.parse("ram:20:5:1:c-"))
        assertNull(RamCheat.parse("ram:20:5:1:c-a:c-b"))
        assertNull(RamCheat.parse("ram:20:5:1:c-a b"))
    }
}
