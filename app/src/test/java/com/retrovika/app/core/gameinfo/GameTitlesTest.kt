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
    fun `le os nomes da predefinicao nihongo com outras dentro`() {
        val lead = "{{Nihongo foot|'''''Flower, Sun, and Rain'''''|花と太陽と雨と|Hana to Taiyō to Ame to|lead=yes|" +
            "group=lower-alpha|released on [[Nintendo DS]] as {{nihongo|'''''Flower, Sun, and Rain: Murder and Mystery in Paradise'''''|" +
            "花と太陽と雨と 終わらない楽園|Hana to Taiyō to Ame to: Owaranai Rakuen|{{lit.}} \"''Neverending Paradise''\"}}}} is an " +
            "[[adventure game|adventure]] [[video game]]"
        val names = WikiClient.nihongoNames(lead)
        assertEquals(listOf("Flower, Sun, and Rain", "花と太陽と雨と", "Hana to Taiyō to Ame to"), names.take(3))
        assertTrue("Hana to Taiyō to Ame to: Owaranai Rakuen" in names)
        assertEquals(listOf("トバル No.1", "Tobaru Nanbā Wan"), WikiClient.nihongoNames("'''Tobal No. 1''' ({{nihongo|トバル No.1|Tobaru Nanbā Wan}}) is"))
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
    fun `aceita titulo da base com palavras que o site de rom omitiu`() {
        val rom = "Hajime no Ippo Portable - Victorious Spirits"
        assertEquals(2, GameTitles.extraWords(rom, "Hajime no Ippo: The Fighting! Portable - Victorious Spirits"))
        assertEquals(0, GameTitles.extraWords("Pokemon Red", "Pokémon Red"))
        // Falta uma palavra do título da ROM: é outro jogo.
        assertEquals(null, GameTitles.extraWords(rom, "Hajime no Ippo: The Fighting! 2 - Victorious Road"))
        // Sobra só o número ou o numeral da sequência.
        assertEquals(null, GameTitles.extraWords("Tekken", "Tekken 2"))
        assertEquals(null, GameTitles.extraWords("Final Fantasy Tactics", "Final Fantasy Tactics II"))
        assertEquals(null, GameTitles.extraWords("Super Mario Bros", "Super Mario Bros 3"))
        // Título de uma palavra só aceita o idêntico.
        assertEquals(null, GameTitles.extraWords("Tetris", "Tetris Plus"))
        assertEquals(null, GameTitles.extraWords("Mega Man", "Mega Man X Command Mission Special Edition"))
    }

    @Test
    fun `buscas pelo nome inteiro depois pelos pedacos`() {
        assertEquals(
            listOf("Hajime no Ippo Portable - Victorious Spirits", "Victorious Spirits", "Hajime no Ippo"),
            GameTitles.searchQueries("Hajime no Ippo Portable - Victorious Spirits"),
        )
        assertEquals(listOf("Chrono Trigger"), GameTitles.searchQueries("Chrono Trigger"))
        assertEquals(listOf("Super Mario World 2: Yoshi's Island", "Yoshi's Island", "Super Mario World"), GameTitles.searchQueries("Super Mario World 2: Yoshi's Island"))
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
