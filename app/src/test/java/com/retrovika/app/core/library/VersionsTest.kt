package com.retrovika.app.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {

    private var nextId = 1L
    private fun game(raw: String, verified: Boolean = false, datName: String? = null) = Game(
        id = nextId++, title = RomNaming.cleanTitle(raw), rawName = raw, fileName = "$raw.sfc", uri = "/roms/snes/$raw.sfc",
        systemId = "snes", size = 1, verified = verified, datName = datName,
    )

    @Test
    fun `agrupa regioes do mesmo jogo e ignora jogos unicos`() {
        val games = listOf(
            game("Chrono Trigger (USA)"), game("Chrono Trigger (Japan)"), game("Chrono Trigger (Europe) [b1]"),
            game("Super Metroid (USA, Europe)"),
        )
        val groups = Versions.groups(games, "en")
        assertEquals(1, groups.size)
        assertEquals(3, groups.single().all.size)
        assertEquals("Chrono Trigger (USA)", groups.single().best.game.rawName)
        assertEquals("Chrono Trigger (Europe) [b1]", groups.single().others.last().game.rawName)
    }

    @Test
    fun `discos diferentes nao sao versoes um do outro`() {
        val a = game("Final Fantasy VII (USA) (Disc 1)")
        val b = game("Final Fantasy VII (USA) (Disc 2)")
        assertNotEquals(Versions.groupKey(a), Versions.groupKey(b))
        assertTrue(Versions.groups(listOf(a, b), "pt").isEmpty())
    }

    @Test
    fun `em portugues a traducao para o portugues vence o original em ingles`() {
        val games = listOf(game("Mother 3 (Japan)"), game("Mother 3 (Japan) [T+Eng]"), game("Mother 3 (Japan) [T+Por]"))
        assertEquals("Mother 3 (Japan) [T+Por]", Versions.groups(games, "pt").single().best.game.rawName)
        assertEquals("Mother 3 (Japan) [T+Eng]", Versions.groups(games, "en").single().best.game.rawName)
    }

    @Test
    fun `em ingles a versao oficial vence a traducao de fa`() {
        val games = listOf(game("Secret of Mana (USA)"), game("Seiken Densetsu 2 (Japan) [T+Eng]", datName = null))
        // Títulos diferentes não se agrupam: só o nome limpo decide.
        assertTrue(Versions.groups(games, "en").isEmpty())
        val same = listOf(game("Ys (USA)"), game("Ys (Japan) [T+Eng]"))
        assertEquals("Ys (USA)", Versions.groups(same, "en").single().best.game.rawName)
    }

    @Test
    fun `brasil primeiro em portugues e revisao mais nova ganha`() {
        val games = listOf(game("Sonic (USA)"), game("Sonic (Brazil)"), game("Sonic (USA) (Rev 1)"))
        assertEquals("Sonic (Brazil)", Versions.groups(games, "pt").single().best.game.rawName)
        assertEquals("Sonic (USA) (Rev 1)", Versions.groups(games, "en").single().best.game.rawName)
    }

    @Test
    fun `verificado pelo dat e dump bom pesam, hack e beta perdem`() {
        val games = listOf(game("Zelda (USA) [h1C]"), game("Zelda (USA) (Beta)"), game("Zelda (Europe)", verified = true))
        val group = Versions.groups(games, "en").single()
        assertEquals("Zelda (Europe)", group.best.game.rawName)
        assertTrue(Versions.Tag.VERIFIED in group.best.tags)
        assertTrue(group.others.any { Versions.Tag.HACK in it.tags })
        assertTrue(group.others.any { Versions.Tag.PRERELEASE in it.tags })
    }

    @Test
    fun `reconhece traducoes e revisoes`() {
        assertEquals("por", Versions.translationLanguage("Game (Japan) [T+Bra_TransBR]"))
        assertEquals("por", Versions.translationLanguage("Game (Traducao PT-BR)"))
        assertEquals("eng", Versions.translationLanguage("Game [T-Eng1.0]"))
        assertNull(Versions.translationLanguage("Game (USA) [!]"))
        assertEquals(2, Versions.revision("Game (USA) (Rev 2)"))
        assertEquals(1, Versions.revision("Game (USA) (Rev A)"))
        assertEquals(1, Versions.revision("Game (v1.1)"))
        assertNull(Versions.revision("Game (USA)"))
    }

    @Test
    fun `nome do dat agrupa arquivos renomeados`() {
        val a = game("ctrigger", verified = true, datName = "Chrono Trigger (USA)")
        val b = game("Chrono Trigger (Japan)")
        val group = Versions.groupOf(a, listOf(a, b), "en")
        assertEquals(a.id, group?.best?.game?.id)
    }
}
