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
    fun `nao apaga outro jogo com o mesmo nome nem deixa temporario`() {
        val dest = tmp.newFolder("roms")
        File(dest, "Jogo.bin").writeText("antigo")
        val out = Archives.extract(zip("jogo.zip", mapOf("Jogo.bin" to "novo")), dest, setOf("Jogo.bin"))
        // Conteúdo diferente: o antigo fica, o novo vai para a subpasta com o nome do compactado.
        assertEquals("antigo", File(dest, "Jogo.bin").readText())
        assertEquals(File(dest, "jogo/Jogo.bin"), out.getValue("Jogo.bin"))
        assertEquals("novo", out.getValue("Jogo.bin").readText())
        assertTrue(dest.walkTopDown().none { it.name.endsWith(Archives.PART_SUFFIX) })
    }

    @Test
    fun `falha no meio nao estraga arquivo que ja existia`() {
        val dest = tmp.newFolder("roms")
        File(dest, "Jogo.bin").writeText("antigo")
        // A segunda entrada escaparia da pasta ("zip slip"): a extração falha depois de gravar a primeira.
        val archive = zip("jogo.zip", mapOf("Jogo.bin" to "novo", "../fora.bin" to "x"))
        assertThrows(Exception::class.java) { Archives.extract(archive, dest, setOf("Jogo.bin", "../fora.bin")) }
        assertEquals("antigo", File(dest, "Jogo.bin").readText())
        assertEquals(listOf("Jogo.bin"), dest.list()!!.toList())
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
    fun `falha no meio nao trunca a rom que ja existia`() {
        val archive = zip("ruim.zip", mapOf("a.bin" to "novo", "../fora.bin" to "x"))
        val dest = tmp.newFolder("roms3")
        val old = File(dest, "a.bin").apply { writeText("antigo") }
        assertThrows(Exception::class.java) { Archives.extract(archive, dest, setOf("a.bin", "../fora.bin")) }
        assertEquals("antigo", old.readText())
        assertTrue(dest.listFiles().orEmpty().none { it.name.endsWith(Archives.PART_SUFFIX) })
    }

    @Test
    fun `mesmo arquivo extraido de novo substitui sem duplicar`() {
        val archive = zip("bom.zip", mapOf("a.bin" to "igual"))
        val dest = tmp.newFolder("roms4")
        File(dest, "a.bin").writeText("igual")
        val out = Archives.extract(archive, dest, setOf("a.bin"))
        assertEquals(File(dest, "a.bin"), out.getValue("a.bin"))
        assertEquals(listOf("a.bin"), dest.list().orEmpty().toList())
    }

    @Test
    fun `outro jogo com o mesmo nome vai inteiro para uma subpasta`() {
        val archive = zip("Outro Jogo.zip", mapOf("jogo.cue" to "FILE \"jogo.bin\" BINARY", "jogo.bin" to "faixa nova"))
        val dest = tmp.newFolder("roms5")
        val oldCue = File(dest, "jogo.cue").apply { writeText("FILE \"jogo.bin\" BINARY") }
        val oldBin = File(dest, "jogo.bin").apply { writeText("faixa antiga") }
        val out = Archives.extract(archive, dest, setOf("jogo.cue", "jogo.bin"))
        // O jogo que já estava lá fica intacto; o novo vai junto (cue e bin) para "Outro Jogo/".
        assertEquals("faixa antiga", oldBin.readText())
        assertTrue(oldCue.exists())
        assertEquals(File(dest, "Outro Jogo/jogo.bin"), out.getValue("jogo.bin"))
        assertEquals(File(dest, "Outro Jogo/jogo.cue"), out.getValue("jogo.cue"))
        assertEquals("faixa nova", out.getValue("jogo.bin").readText())
        assertTrue(dest.walkTopDown().none { it.name.endsWith(Archives.PART_SUFFIX) })
    }

    @Test
    fun `subpasta ocupada ganha numero`() {
        val archive = zip("X.zip", mapOf("a.bin" to "novo"))
        val dest = tmp.newFolder("roms6")
        File(dest, "a.bin").writeText("antigo")
        File(dest, "X").mkdirs()
        val out = Archives.extract(archive, dest, setOf("a.bin"))
        assertEquals(File(dest, "X (2)/a.bin"), out.getValue("a.bin"))
    }

    @Test
    fun `lista entradas com tamanho`() {
        val archive = zip("rom.zip", mapOf("pasta/" to "", "rom.gba" to "12345"))
        assertEquals(listOf(Archives.Entry("rom.gba", 5)), Archives.entries(archive))
    }

    @Test
    fun `zip com senha e detectado e extraido com a senha`() {
        val file = File(tmp.root, "protegido.zip")
        val rom = tmp.newFile("Jogo (USA).nds").apply { writeText("conteudo da rom") }
        val params = net.lingala.zip4j.model.ZipParameters().apply {
            isEncryptFiles = true
            encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD
        }
        net.lingala.zip4j.ZipFile(file, "romsfun-romspure".toCharArray()).use { it.addFile(rom, params) }

        assertTrue(Archives.needsPassword(file))
        assertFalse(Archives.needsPassword(zip("aberto.zip", mapOf("a.nds" to "x"))))
        val out = Archives.extract(file, tmp.newFolder("saida"), setOf("Jogo (USA).nds"), "romsfun-romspure")
        assertEquals("conteudo da rom", out.getValue("Jogo (USA).nds").readText())
    }
}
