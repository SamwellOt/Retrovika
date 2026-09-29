package com.retrovika.app.core.gameinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameTitlesTest {
    @Test
    fun `limpa regiao revisao e sufixos de formato`() {
        assertEquals("Chrono Trigger", GameTitles.clean("Chrono Trigger (USA) (Rev 1)"))
        assertEquals("Digimon World 2003", GameTitles.clean("Digimon World 2003 (RB Select) PSX ISO"))
        assertEquals("super mario 64", GameTitles.clean("super_mario_64 [!]"))
        assertEquals("Kamen Rider", GameTitles.clean("Kamen Rider (Japan) - PSX"))
    }

    @Test
    fun `desfaz a ordem de catalogo do artigo`() {
        assertEquals("The Legend of Zelda - A Link to the Past", GameTitles.clean("Legend of Zelda, The - A Link to the Past (USA)"))
    }

    @Test
    fun `compara ignorando pontuacao acentos e caixa`() {
        assertTrue(GameTitles.same("The Legend of Zelda: A Link to the Past", "the legend of zelda - a link to the past"))
        assertTrue(GameTitles.same("Pokémon Red", "Pokemon Red"))
        assertTrue(GameTitles.same("Sonic & Knuckles", "Sonic and Knuckles"))
        assertFalse(GameTitles.same("Super Mario 64", "Super Mario 64 DS"))
    }

    @Test
    fun `junta as vogais longas do japones romanizado`() {
        assertEquals(GameTitles.romajiKey("Hana to Taiyou to Ame to"), GameTitles.romajiKey("Hana to Taiyō to Ame to"))
        assertEquals("hana to taiyo to ame to", GameTitles.romajiKey("Hana to Taiyoo to Ame to"))
        assertEquals("ryu ga gotoku", GameTitles.romajiKey("Ryuu ga Gotoku"))
        assertEquals(GameTitles.romajiKey("Shinpan"), GameTitles.romajiKey("Shimpan"))
        assertTrue(GameTitles.sameRomaji("Ganbare Goemon - Yuki Hime Kyuushutsu Emaki", "Ganbare Goemon: Yukihime Kyūshutsu Emaki"))
        assertFalse(GameTitles.sameRomaji("Rockman X", "Rockman X4"))
    }

    @Test
    fun `gera as grafias de busca do romaji`() {
        assertEquals(
            listOf("Mother 2 - Gyiyg no Gyakushuu", "Mother 2 - Gyiyg no Gyakushū", "Mother 2: Gyiyg no Gyakushuu", "Mother 2: Gyiyg no Gyakushū"),
            GameTitles.romajiVariants("Mother 2 - Gyiyg no Gyakushuu"),
        )
        assertEquals(listOf("Tobal 2"), GameTitles.romajiVariants("Tobal 2"))
    }

    @Test
    fun `reconhece o nome japones no trecho da busca`() {
        val m = { s: String -> "<span class=\"searchmatch\">$s</span>" }
        assertTrue(WikiClient.namesJapaneseTitle("also known by its Japanese title ${m("Seiken")} ${m("Densetsu")} ${m("3")}, is a"))
        assertTrue(WikiClient.namesJapaneseTitle("(Japanese: 花と太陽と雨と, Hepburn: ${m("Hana")} ${m("to")} ${m("Taiyō")} ${m("to")} ${m("Ame")} ${m("to")}) is"))
        // O nome continua: é outro jogo.
        assertFalse(WikiClient.namesJapaneseTitle("known in Japan as ${m("Rockman")} ${m("X")} DiVE, was a 2020 mobile game"))
        // Sem menção ao Japão por perto.
        assertFalse(WikiClient.namesJapaneseTitle("It is a spinoff of the ${m("Akumajou")} ${m("Dracula")} series."))
    }

    @Test
    fun `so descricao de jogo conta como jogo`() {
        assertTrue(WikiClient.isGameDescription("1993 video game"))
        assertTrue(WikiClient.isGameDescription("1997 Satellaview game"))
        assertFalse(WikiClient.isGameDescription("video game series"))
        assertFalse(WikiClient.isGameDescription("Japanese video game company"))
        assertFalse(WikiClient.isGameDescription(null))
    }

    @Test
    fun `reconhece a plataforma do console pelo slug ou pelo nome`() {
        assertTrue(Platforms.matches("snes", listOf("sfam"), emptyList()))
        assertTrue(Platforms.matches("psx", emptyList(), listOf("PlayStation")))
        assertTrue(Platforms.matches("genesis", emptyList(), listOf("Sega Mega Drive/Genesis")))
        assertFalse(Platforms.matches("gb", emptyList(), listOf("Game Boy Advance")))
        assertFalse(Platforms.matches("psx", listOf("ps2"), listOf("PlayStation 2")))
    }

    @Test
    fun `normaliza notas da critica`() {
        assertEquals(94, ReviewScore("94/100", "Metacritic").normalized)
        assertEquals(97, ReviewScore("39/40", "Famitsu").normalized)
        assertEquals(85, ReviewScore("8,5/10", "IGN").normalized)
        assertEquals(null, ReviewScore("A+", "1UP").normalized)
    }
}
