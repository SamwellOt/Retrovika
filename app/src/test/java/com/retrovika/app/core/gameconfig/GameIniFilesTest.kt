package com.retrovika.app.core.gameconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameIniFilesTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `grava o arquivo na pasta de usuario do dolphin com a marca`() {
        val saves = tmp.newFolder("saves-gc")
        assertTrue(GameIniFiles.sync(saves, "GBLPGL", "[Core]\nSlotA = 1\n"))
        val file = GameIniFiles.file(saves, "GBLPGL")
        assertEquals("${saves.path}/User/GameSettings/GBLPGL.ini", file.path)
        assertEquals(GameIniFiles.MARK + "\n[Core]\nSlotA = 1\n", file.readText())
    }

    @Test
    fun `texto vazio apaga o que o retrovika gravou`() {
        val saves = tmp.newFolder()
        GameIniFiles.sync(saves, "GALE01", "[Core]\nCPUCore = 1")
        assertFalse(GameIniFiles.sync(saves, "GALE01", "  \n"))
        assertFalse(GameIniFiles.file(saves, "GALE01").exists())
    }

    @Test
    fun `arquivo do usuario sem a marca nao e sobrescrito nem apagado`() {
        val saves = tmp.newFolder()
        val mine = GameIniFiles.file(saves, "RSBE01").also { it.parentFile!!.mkdirs(); it.writeText("[Video_Hacks]\nEFBAccessEnable = False\n") }
        assertTrue(GameIniFiles.blockedByUserFile(saves, "RSBE01"))
        assertFalse(GameIniFiles.sync(saves, "RSBE01", "[Core]\nSlotA = 1"))
        assertEquals("[Video_Hacks]\nEFBAccessEnable = False\n", mine.readText())
        assertFalse(GameIniFiles.sync(saves, "RSBE01", ""))
        assertTrue(mine.exists())
    }

    @Test
    fun `regrava por cima do nosso e nao deixa temporario`() {
        val saves = tmp.newFolder()
        GameIniFiles.sync(saves, "GALE01", "[Core]\nSlotA = 1")
        GameIniFiles.sync(saves, "GALE01", "[Core]\nSlotA = 255")
        assertTrue(GameIniFiles.file(saves, "GALE01").readText().endsWith("SlotA = 255\n"))
        assertFalse(java.io.File(GameIniFiles.file(saves, "GALE01").path + ".tmp").exists())
        assertFalse(GameIniFiles.blockedByUserFile(saves, "GALE01"))
    }
}
