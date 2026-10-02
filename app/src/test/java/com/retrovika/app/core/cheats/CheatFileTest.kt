package com.retrovika.app.core.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheatFileTest {

    private val clean: (String) -> String = { it.replace(Regex("""\s*[\(\[][^)\]]*[\)\]]"""), "").trim() }

    @Test
    fun `le o formato cht do retroarch`() {
        val text = """
            cheats = 3

            cheat0_desc = "Infinite Maximum Coins"
            cheat0_code = "7E0DBF63"
            cheat0_enable = false

            cheat1_desc = "Always 999 Time"
            cheat1_code = "7E0F3109+7E0F3209+7E0F3309"
            cheat1_enable = true

            cheat2_desc = "Section title"
            cheat2_code = ""
        """.trimIndent()
        val cheats = CheatFile.parse(text)
        assertEquals(2, cheats.size)
        assertEquals("Infinite Maximum Coins", cheats[0].description)
        assertEquals("7E0F3109+7E0F3209+7E0F3309", cheats[1].code)
        assertTrue(cheats[1].enabled)
    }

    @Test
    fun `entrada sem descricao recebe o nome pelo numero`() {
        val text = """
            cheat0_code = "AAAA"
            cheat1_desc = ""
            cheat1_code = "BBBB"
        """.trimIndent()
        assertEquals(listOf("Cheat 1", "Cheat 2"), CheatFile.parse(text).map { it.description })
        assertEquals(listOf("Trapaça 1", "Trapaça 2"), CheatFile.parse(text) { "Trapaça $it" }.map { it.description })
    }

    @Test
    fun `acha o arquivo do jogo pelo nome ou pelo titulo`() {
        val files = listOf("Super Mario World (USA).cht", "Super Mario World (Europe) (Rev 1).cht", "Super Mario Kart (USA).cht", "Mario Paint (Japan).cht")
        assertEquals("Super Mario World (USA).cht", CheatFile.match("Super Mario World (USA)", files, clean).first())
        val byTitle = CheatFile.match("super mario world (Brazil)", files, clean)
        assertEquals(2, byTitle.size)
        assertTrue(byTitle.all { "World" in it })
    }

    @Test
    fun `busca por palavras em qualquer ordem`() {
        val files = listOf("Chrono Trigger (USA).cht", "Chrono Cross (USA).cht", "Trigger Happy.cht")
        assertEquals(listOf("Chrono Trigger (USA).cht"), CheatFile.search("trigger chrono", files))
        assertTrue(CheatFile.search("  ", files).isEmpty())
    }
}
