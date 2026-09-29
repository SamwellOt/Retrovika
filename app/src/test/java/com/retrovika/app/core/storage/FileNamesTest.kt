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
    fun `reconhece metadados do Mac e ocultos`() {
        assertTrue(FileNames.isJunk("__MACOSX"))
        assertTrue(FileNames.isJunk("._Game.cue"))
        assertTrue(FileNames.isJunk(".DS_Store"))
        assertFalse(FileNames.isJunk("Game.cue"))
    }
}
