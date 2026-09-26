package com.retrovika.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ArchivesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, String>): File {
        val file = tmp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zos ->
            entries.forEach { (path, content) ->
                zos.putNextEntry(ZipEntry(path))
                zos.write(content.toByteArray())
                zos.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `reconhece o formato pelos bytes e nao pela extensao`() {
        val disguised = zip("jogo.7z", mapOf("a.bin" to "x"))
        assertEquals(Archives.Format.ZIP, Archives.formatOf(disguised))
        val rom = tmp.newFile("jogo.zip").apply { writeBytes(byteArrayOf(0x4E, 0x45, 0x53, 0x1A)) }
        assertNull(Archives.formatOf(rom))
    }

    @Test
    fun `detecta pagina html salva no lugar do arquivo`() {
        val page = tmp.newFile("jogo.zip").apply { writeText("\n  <!DOCTYPE html><html><body>Erro</body></html>") }
        assertTrue(Archives.isHtml(page))
        assertFalse(Archives.isHtml(zip("ok.zip", mapOf("a.bin" to "x"))))
    }

    @Test
    fun `extrai sem a pasta comum e so as entradas pedidas`() {
        val archive = zip(
            "disco.zip",
            mapOf("Jogo (USA)/Jogo (USA).cue" to "FILE \"Jogo (USA).bin\" BINARY", "Jogo (USA)/Jogo (USA).bin" to "dados", "leia.txt" to "oi"),
        )
        val dest = tmp.newFolder("roms")
        val out = Archives.extract(archive, dest, setOf("Jogo (USA)/Jogo (USA).cue", "Jogo (USA)/Jogo (USA).bin"))
        assertEquals(listOf("Jogo (USA).cue", "Jogo (USA).bin"), out.values.map { it.name })
        assertTrue(out.values.all { it.parentFile == dest && it.exists() })
        assertFalse(File(dest, "leia.txt").exists())
    }

    @Test
    fun `mantem subpastas de discos citadas pelo m3u`() {
        val archive = zip(
            "multi.zip",
            mapOf(
                "Jogo.m3u" to "Disco 1/Jogo.cue\nDisco 2/Jogo.cue",
                "Disco 1/Jogo.cue" to "1",
                "Disco 2/Jogo.cue" to "2",
            ),
        )
        val dest = tmp.newFolder("psx")
        val out = Archives.extract(archive, dest, setOf("Jogo.m3u", "Disco 1/Jogo.cue", "Disco 2/Jogo.cue"))
        assertEquals(File(dest, "Jogo.m3u"), out["Jogo.m3u"])
        assertEquals("1", File(dest, "Disco 1/Jogo.cue").readText())
        assertEquals("2", File(dest, "Disco 2/Jogo.cue").readText())
    }

    @Test
    fun `recusa entradas que escapam da pasta`() {
        val archive = zip("mal.zip", mapOf("../fora.bin" to "x", "ok.bin" to "y"))
        val dest = tmp.newFolder("roms2")
        assertThrows(Exception::class.java) { Archives.extract(archive, dest, setOf("../fora.bin", "ok.bin")) }
        assertFalse(File(dest.parentFile, "fora.bin").exists())
    }

    @Test
    fun `lista entradas com tamanho`() {
        val archive = zip("rom.zip", mapOf("pasta/" to "", "rom.gba" to "12345"))
        assertEquals(listOf(Archives.Entry("rom.gba", 5)), Archives.entries(archive))
    }
}
