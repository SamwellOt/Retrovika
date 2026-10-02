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
        // Mesma grafia da ordem de regiões do Versions.
        assertEquals("Austrália", RomNaming.region("Game (Australia)"))
        assertEquals("Ásia", RomNaming.region("Game (Asia)"))
        assertEquals("França", RomNaming.region("Game (France)"))
        assertEquals("Alemanha", RomNaming.region("Game (Germany)"))
        assertEquals("Espanha", RomNaming.region("Game (Spain)"))
        assertEquals("Itália", RomNaming.region("Game (Italy)"))
        assertNull(RomNaming.region("Homebrew"))
    }

    @Test
    fun `regiao ignora etiquetas que so comecam com o nome`() {
        assertEquals("EUA", RomNaming.region("Game (Enhanced Colors) (USA)"))
        assertEquals("EUA", RomNaming.region("Game (USA, Europe)"))
        assertNull(RomNaming.region("Game (Japanese Translation)"))
        assertNull(RomNaming.region("Game (En,Ja)"))
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
        assertNull(RomNaming.resolveSystem("Jogo.7z", emptyList()))
        assertNull(RomNaming.resolveSystem("Pokemon.7z", listOf("gba")))
        assertEquals("arcade", RomNaming.resolveSystem("sf2.7z", listOf("arcade"))?.id)
    }

    @Test
    fun `png so vira cartucho de PICO-8 dentro da pasta pico8`() {
        assertNull(RomNaming.resolveSystem("capa.png", listOf("Imagens")))
        assertEquals("pico8", RomNaming.resolveSystem("celeste.p8.png", listOf("pico-8"))?.id)
        assertNull(RomNaming.guessSystem("capa.png", emptyList()))
    }

    @Test
    fun `extensoes genericas de outros programas nao viram jogo fora da pasta do console`() {
        // Uma pasta vinculada com tudo misturado: README, WAD do Doom, BIOS, certificado, código-fonte.
        listOf("README.md", "doom2.wad", "MSX.ROM", "cert.crt", "game.prg", "data.int", "top.sv", "Form1.vb", "paleta.col", "a.cof", "b.abs")
            .forEach { assertNull(it, RomNaming.resolveSystem(it, listOf("Downloads"))) }
        assertEquals("genesis", RomNaming.resolveSystem("Sonic.md", listOf("Mega Drive"))?.id)
        assertEquals("wii", RomNaming.resolveSystem("Canal.wad", listOf("roms", "wii"))?.id)
        assertEquals("msx", RomNaming.resolveSystem("Nemesis.rom", listOf("MSX2"))?.id)
        assertEquals("c64", RomNaming.resolveSystem("Jogo.crt", listOf("Commodore 64"))?.id)
        // Pasta de um console com o arquivo exclusivo de outro (GB e GBC juntos) continua achando o certo.
        assertEquals("gbc", RomNaming.resolveSystem("Jogo.gbc", listOf("gameboy"))?.id)
    }

    @Test
    fun `apelidos de pasta com o nome completo ou do modelo Color`() {
        mapOf(
            "PC Engine CD" to "pce", "TurboGrafx-CD" to "pce", "TG-CD" to "pce", "N3DS" to "3ds", "Sega Genesis" to "genesis",
            "Mega Drive - Genesis" to "genesis", "WonderSwan Color" to "wswan", "Neo Geo Pocket Color" to "ngp",
            "Nintendo Entertainment System" to "nes",
        ).forEach { (folder, id) -> assertEquals(folder, id, RomNaming.systemForFolder(folder)?.id) }
    }

    @Test
    fun `capa do modelo monocromatico vem do acervo dele`() {
        // O acervo, não a URL: o Uri.encode do Android não roda nos testes da JVM.
        val ngp = com.retrovika.app.core.systems.Systems.byId("ngp")!!
        assertEquals("SNK - Neo Geo Pocket", ngp.libretroDbFor("ngp"))
        assertEquals("SNK - Neo Geo Pocket Color", ngp.libretroDbFor("ngc"))
        assertEquals("Bandai - WonderSwan", com.retrovika.app.core.systems.Systems.byId("wswan")!!.libretroDbFor("WS"))
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
        assertEquals("snes", RomNaming.guessSystem("jogo.zip", listOf("Chrono Trigger - Super Nintendo Entertainment System"))?.id)
    }

    @Test
    fun `apelido curto so vale sozinho na URL ou entre parenteses`() {
        assertEquals("nds", RomNaming.guessSystem("jogo.zip", listOf("https://site/roms/ds/mario-kart"))?.id)
        assertEquals("nds", RomNaming.guessSystem("jogo.zip", listOf("Mario Kart (DS)"))?.id)
        // "FC Barcelona", "10 pm", o artigo "o": por acaso, não é console.
        assertNull(RomNaming.guessSystem("jogo.zip", listOf("FC Barcelona Manager")))
        assertNull(RomNaming.guessSystem("jogo.zip", listOf("Atualizado às 10 pm")))
        assertNull(RomNaming.guessSystem("jogo.zip", listOf("Baixe o jogo aqui")))
        assertNull(RomNaming.guessSystem("jogo.zip", listOf("https://site/o/jogo")))
    }
}
