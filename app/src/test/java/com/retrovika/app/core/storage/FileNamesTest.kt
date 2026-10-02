package com.retrovika.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test
    fun `nao deixa sair da pasta`() {
        assertEquals("x.zip", FileNames.safe("../../x.zip"))
        assertEquals("x.zip", FileNames.safe("..\\..\\x.zip"))
        assertEquals("jogo", FileNames.safe(".."))
        assertEquals("jogo", FileNames.safe("   "))
    }

    @Test
    fun `troca caracteres invalidos e preserva o resto`() {
        assertEquals("Zelda_ A Link.sfc", FileNames.safe("Zelda: A Link.sfc"))
        assertEquals("Chrono Trigger (USA).sfc", FileNames.safe("Chrono Trigger (USA).sfc"))
    }

    @Test
    fun `nome longo cabe em 200 bytes sem partir caractere e com a extensao`() {
        val jp = "ドラゴンクエスト".repeat(20) + ".sfc"
        val safe = FileNames.safe(jp)
        assertTrue(safe.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(safe.endsWith(".sfc"))
        assertTrue(safe.removeSuffix(".sfc").all { it in "ドラゴンクエスト" })
        val emoji = "🎮".repeat(100) + ".zip"
        val cut = FileNames.safe(emoji)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 200)
        assertEquals(0, cut.removeSuffix(".zip").length % 2)
        assertEquals("Curto.gb", FileNames.safe("Curto.gb"))
    }

    @Test
    fun `reconhece metadados do Mac e ocultos`() {
        assertTrue(FileNames.isJunk("__MACOSX"))
        assertTrue(FileNames.isJunk("._Game.cue"))
        assertTrue(FileNames.isJunk(".DS_Store"))
        assertFalse(FileNames.isJunk("Game.cue"))
    }
}
