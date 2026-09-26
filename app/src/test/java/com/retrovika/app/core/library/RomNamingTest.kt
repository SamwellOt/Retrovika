package com.retrovika.app.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomNamingTest {

    @Test
    fun `limpa tags e move o artigo para a frente`() {
        assertEquals("Final Fantasy VII", RomNaming.cleanTitle("Final Fantasy VII (USA) (Disc 1) [!]"))
        assertEquals("The Legend of Zelda", RomNaming.cleanTitle("Legend of Zelda, The (USA)"))
        assertEquals("Super Mario World", RomNaming.cleanTitle("Super_Mario_World"))
    }

    @Test
    fun `traduz a regiao`() {
        assertEquals("EUA", RomNaming.region("Chrono Trigger (USA)"))
        assertEquals("Brasil", RomNaming.region("Sonic (Brazil)"))
        assertNull(RomNaming.region("Homebrew"))
    }

    @Test
    fun `pasta do console vence a extensao`() {
        assertEquals("psx", RomNaming.resolveSystem("Jogo.bin", listOf("Roms", "ps1"))?.id)
        assertEquals("genesis", RomNaming.resolveSystem("Jogo.bin", listOf("megadrive"))?.id)
        assertEquals("arcade", RomNaming.resolveSystem("sf2.zip", listOf("mame"))?.id)
        assertEquals("dos", RomNaming.resolveSystem("doom.zip", listOf("MS-DOS"))?.id)
        assertEquals("zxspectrum", RomNaming.resolveSystem("manic.tap", listOf("ZX Spectrum"))?.id)
    }

    @Test
    fun `extensao exclusiva identifica sem pasta, ambigua nao`() {
        assertEquals("snes", RomNaming.resolveSystem("Jogo.sfc", emptyList())?.id)
        assertEquals("c64", RomNaming.resolveSystem("Jogo.d64", emptyList())?.id)
        assertNull(RomNaming.resolveSystem("Jogo.bin", emptyList()))
        assertNull(RomNaming.resolveSystem("Jogo.zip", emptyList()))
    }

    @Test
    fun `png so vira cartucho de PICO-8 dentro da pasta pico8`() {
        assertNull(RomNaming.resolveSystem("capa.png", listOf("Imagens")))
        assertEquals("pico8", RomNaming.resolveSystem("celeste.p8.png", listOf("pico-8"))?.id)
        assertNull(RomNaming.guessSystem("capa.png", emptyList()))
    }

    @Test
    fun `faixa citada por um indice e auxiliar`() {
        val siblings = setOf("jogo.cue", "jogo (track 1).bin", "jogo (track 2).bin")
        assertTrue(RomNaming.isAuxiliaryFile("Jogo (Track 1).bin", siblings, referenced = true, sheetsKnown = true))
        assertFalse(RomNaming.isAuxiliaryFile("Jogo.cue", siblings, referenced = false, sheetsKnown = true))
    }

    @Test
    fun `rom bin de outro console ao lado de um cue continua sendo jogo`() {
        val siblings = setOf("ff7.cue", "ff7.bin", "sonic.bin")
        // Com os índices lidos, só o que eles citam é auxiliar.
        assertFalse(RomNaming.isAuxiliaryFile("Sonic.bin", siblings, referenced = false, sheetsKnown = true))
        // Sem conseguir ler o .cue, a heurística pelo nome também não esconde o Sonic.
        assertFalse(RomNaming.isAuxiliaryFile("Sonic.bin", siblings))
        assertTrue(RomNaming.isAuxiliaryFile("FF7.bin", siblings))
        assertTrue(RomNaming.isAuxiliaryFile("Outro (Track 02).bin", siblings))
    }

    @Test
    fun `discos de um m3u sao auxiliares, o m3u nao`() {
        val siblings = setOf("jogo.m3u", "jogo (disc 1).cue", "jogo (disc 2).cue")
        assertTrue(RomNaming.isAuxiliaryFile("Jogo (Disc 1).cue", siblings, referenced = true, sheetsKnown = true))
        assertFalse(RomNaming.isAuxiliaryFile("Jogo.m3u", siblings, referenced = true, sheetsKnown = true))
        assertTrue(RomNaming.isAuxiliaryFile("Jogo (Disc 12).cue", siblings))
    }

    @Test
    fun `palpite do navegador usa pistas da pagina`() {
        assertEquals("gba", RomNaming.guessSystem("jogo.zip", listOf("https://site/game-boy-advance/jogo"))?.id)
        assertEquals("nds", RomNaming.guessSystem("jogo.nds", emptyList())?.id)
    }
}
