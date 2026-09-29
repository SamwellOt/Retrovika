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
    }

    @Test
    fun `le os outros nomes da abertura do artigo`() {
        val lead = "Flower, Sun, and Rain (Japanese: 花と太陽と雨と, Hepburn: Hana to Taiyō to Ame to) is a 2001 adventure game."
        assertTrue("Hana to Taiyō to Ame to" in GameTitles.knownAs(lead))
        assertTrue("Ganbare Goemon" in GameTitles.knownAs("Mystical Ninja, known in Japan as Ganbare Goemon (がんばれゴエモン), is"))
        // Um nome que só aparece dentro de outro não vale: a continuação não é o jogo pedido.
        val sequel = "Doraemon 3 (Japanese: ドラえもん3, Hepburn: Doraemon Surī) is a sequel to Doraemon."
        assertFalse(GameTitles.knownAs(sequel).any { GameTitles.romajiKey(it) == "doraemon" })
        val wikitext = "''Flower, Sun, and Rain''{{efn|{{Nihongo|Japanese|花と太陽と雨と|Hana to Taiyō to Ame to}}}} is an [[adventure game]]"
        assertTrue("Hana to Taiyō to Ame to" in GameTitles.wikiNames(wikitext))
        assertTrue(GameTitles.wikiNames("{{nihongo foot|''X''|エックス|Ekkusu|lead=yes}}").containsAll(listOf("X", "Ekkusu")))
        assertTrue("Hana to Taiyō to Ame to" in GameTitles.wikiNames("released in Japan as ''Hana to Taiyō to Ame to'' in 2001"))
        // "or" só é rótulo como palavra inteira.
        assertEquals(listOf("Orochi"), GameTitles.knownAs("X (Orochi) is"))
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
