package com.retrovika.app.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameFilesTest {

    @Test
    fun `cue lista as faixas`() {
        val cue = """
            FILE "Jogo (Track 1).bin" BINARY
              TRACK 01 MODE2/2352
            FILE "Jogo (Track 2).bin" BINARY
              TRACK 02 AUDIO
        """.trimIndent()
        assertEquals(listOf("Jogo (Track 1).bin", "Jogo (Track 2).bin"), GameFiles.referencedPaths("cue", cue, "Jogo.cue"))
    }

    @Test
    fun `m3u mantem subpastas e normaliza barras`() {
        val m3u = "#EXTM3U\ndisc1\\Jogo (Disc 1).cue\r\ndisc2/Jogo (Disc 2).cue\n\n"
        assertEquals(listOf("disc1/Jogo (Disc 1).cue", "disc2/Jogo (Disc 2).cue"), GameFiles.referencedPaths("m3u", m3u, "Jogo.m3u"))
        assertEquals(listOf("Jogo (Disc 1).cue", "Jogo (Disc 2).cue"), GameFiles.referencedFiles("m3u", m3u, "Jogo.m3u"))
    }

    @Test
    fun `caminhos absolutos ou que sobem de pasta viram so o nome`() {
        val m3u = "../fora/Disco.cue\nC:\\Jogos\\Disco2.cue\n/sdcard/Disco3.cue"
        assertEquals(listOf("Disco.cue", "Disco2.cue", "Disco3.cue"), GameFiles.referencedPaths("m3u", m3u, "x.m3u"))
    }

    @Test
    fun `cue sem aspas e arquivos com BOM`() {
        val cue = "\uFEFFFILE track01.bin BINARY\n  TRACK 01 MODE1/2352\nFILE \"track 02.bin\" BINARY"
        assertEquals(listOf("track01.bin", "track 02.bin"), GameFiles.referencedPaths("cue", cue, "jogo.cue"))
        assertEquals(listOf("disc1.cue"), GameFiles.referencedPaths("m3u", "\uFEFFdisc1.cue\n", "jogo.m3u"))
    }

    @Test
    fun `gdi e ccd`() {
        val gdi = "3\n1 0 4 2352 track01.bin 0\n2 756 0 2352 \"track 02.raw\" 0\n3 45000 4 2352 track03.bin 0"
        assertEquals(listOf("track01.bin", "track 02.raw", "track03.bin"), GameFiles.referencedPaths("gdi", gdi, "jogo.gdi"))
        assertEquals(listOf("Jogo.img", "Jogo.sub"), GameFiles.referencedPaths("ccd", "", "Jogo.ccd"))
    }

    @Test
    fun `faixa citada sem extensao conta como citada, mas o indice nao`() {
        val referenced = setOf("psx/brave fencer musashi (usa)(ptbr)", "psx/outro.bin")
        assertTrue(GameFiles.isReferenced("psx/brave fencer musashi (usa)(ptbr).bin", referenced))
        assertTrue(GameFiles.isReferenced("psx/outro.bin", referenced))
        assertFalse(GameFiles.isReferenced("psx/brave fencer musashi (usa)(ptbr).cue", referenced))
        assertFalse(GameFiles.isReferenced("psx/outro.iso", referenced))
        assertFalse(GameFiles.isReferenced("psx/sem extensao", setOf("psx/sem")))
    }
}
