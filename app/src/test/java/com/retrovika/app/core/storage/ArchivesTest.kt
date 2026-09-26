package com.retrovika.app.core.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `extrai sem subpastas e so as entradas pedidas`() {
        val archive = zip(
            "disco.zip",
            mapOf("Jogo (USA)/Jogo (USA).cue" to "FILE \"Jogo (USA).bin\" BINARY", "Jogo (USA)/Jogo (USA).bin" to "dados", "leia.txt" to "oi"),
        )
        val dest = tmp.newFolder("roms")
        val out = Archives.extract(archive, dest, setOf("Jogo (USA)/Jogo (USA).cue", "Jogo (USA)/Jogo (USA).bin"))
        assertEquals(listOf("Jogo (USA).cue", "Jogo (USA).bin"), out.map { it.name })
        assertTrue(out.all { it.parentFile == dest && it.exists() })
        assertFalse(File(dest, "leia.txt").exists())
    }

    @Test
    fun `lista entradas com tamanho`() {
        val archive = zip("rom.zip", mapOf("pasta/" to "", "rom.gba" to "12345"))
        assertEquals(listOf(Archives.Entry("rom.gba", 5)), Archives.entries(archive))
    }
}
