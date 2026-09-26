package com.retrovika.app.core.dat

import org.junit.Assert.assertEquals
import org.junit.Test

class DatParserTest {

    /** Trecho no formato real da libretro-database (metadat/no-intro), com CRLF. */
    private val clrMamePro = """
        clrmamepro (
        	name "Nintendo - Game Boy"
        )

        game (
        	name "Tetris (World) (Rev 1)"
        	description "Tetris (World) (Rev 1)"
        	rom ( name "Tetris (World) (Rev 1).gb" size 32768 crc 46DF91AD md5 982ED5D2B12A0377EB14BCDC4123744E sha1 74591CC9501AF93873F9A5D3EB12DA12C0723BBC )
        )

        game (
        	name "Pokemon - Red Version (USA, Europe) (SGB Enhanced)"
        	rom ( name "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb" size 1048576 crc 9F7FDD53 md5 3D45C1EE9ABD5738DF46D2BDDA8B57DC )
        )
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `le crc, md5 e tamanho mesmo com parenteses no nome da rom`() {
        val roms = DatParser.parse(clrMamePro)
        assertEquals(2, roms.size)
        with(roms[0]) {
            assertEquals("Tetris (World) (Rev 1)", gameName)
            assertEquals(32768L, size)
            assertEquals("46df91ad", crc)
            assertEquals("982ed5d2b12a0377eb14bcdc4123744e", md5)
        }
        assertEquals("9f7fdd53", roms[1].crc)
    }

    @Test
    fun `completa crc curto com zeros a esquerda`() {
        val dat = "game (\n\tname \"X\"\n\trom ( name \"X.nes\" size 1 crc ABC )\n)\n"
        assertEquals("00000abc", DatParser.parse(dat).single().crc)
    }
}
