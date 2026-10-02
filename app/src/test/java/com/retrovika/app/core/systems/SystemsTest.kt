package com.retrovika.app.core.systems

import com.retrovika.app.core.dat.DatCatalog
import com.retrovika.app.core.library.RomNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coerência do catálogo de consoles: erros aqui só apareceriam no aparelho, ao abrir um jogo. */
class SystemsTest {

    @Test
    fun `ids unicos e cada console com ao menos um nucleo`() {
        val ids = Systems.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        Systems.all.forEach { assertTrue("${it.id} sem núcleo", it.cores.isNotEmpty()) }
    }

    @Test
    fun `ids de nucleo no formato que a tarefa fetchCores reconhece`() {
        Systems.all.flatMap { it.cores }.forEach { core ->
            assertTrue("núcleo inválido: ${core.id}", core.id.matches(Regex("[a-z0-9_]+")))
        }
    }

    @Test
    fun `extensoes em minusculas e sem ponto`() {
        Systems.all.forEach { s ->
            s.extensions.forEach { assertTrue("${s.id}: .$it", it == it.lowercase() && !it.startsWith(".")) }
            assertTrue("${s.id}: folderOnly fora das extensões", s.extensions.containsAll(s.folderOnlyExtensions))
        }
    }

    @Test
    fun `extensoes genericas so identificam o console dentro da pasta`() {
        listOf(
            "bat", "com", "conf", "o", "app", "img", "hex", "png",
            "md", "wad", "rom", "crt", "prg", "int", "sv", "vb", "col", "abs", "cof",
        ).forEach { ext ->
            assertNull(".$ext", Systems.byUniqueExtension(ext))
        }
        assertEquals("arduboy", RomNaming.resolveSystem("jogo.hex", listOf("Arduboy"))?.id)
        assertEquals("psx", RomNaming.resolveSystem("jogo.img", listOf("roms", "psx"))?.id)
    }

    @Test
    fun `acervos de capas por extensao usam extensoes do proprio console`() {
        Systems.all.forEach { s ->
            assertTrue(s.id, s.extensions.containsAll(s.libretroDbByExtension.keys))
            assertTrue(s.id, s.libretroDbByExtension.isEmpty() || s.libretroDbName != null)
        }
    }

    @Test
    fun `todo console e encontrado pela pasta com o proprio id`() {
        Systems.all.forEach { s -> assertEquals(s.id, RomNaming.systemForFolder(s.id)?.id) }
    }

    @Test
    fun `sistemas com zip executavel mantem o arquivo compactado`() {
        Systems.all.filter { it.keepArchives }.forEach { assertTrue(it.id, "zip" in it.extensions) }
    }

    @Test
    fun `grupos de BIOS sao obrigatorios e com mais de uma opcao`() {
        Systems.all.forEach { s ->
            s.bios.filter { it.group != null }.groupBy { it.group }.forEach { (group, files) ->
                assertTrue("${s.id}/$group", files.size > 1 && files.all { it.required })
            }
        }
    }

    @Test
    fun `sistemas com DAT existem e tem nome de DAT`() {
        DatCatalog.supportedSystems.forEach { id ->
            val system = Systems.byId(id)
            assertNotNull("DAT para sistema inexistente: $id", system)
            assertNotNull(id, DatCatalog.datName(id, system!!.libretroDbName))
        }
    }

    @Test
    fun `nucleos de PlayStation com memory card que o usuario nao desliga`() {
        val expected = mapOf(
            "pcsx_rearmed" to mapOf("pcsx_rearmed_memcard1" to "libretro", "pcsx_rearmed_memcard2" to "shared"),
            "swanstation" to mapOf("swanstation_MemoryCards_Card1Type" to "Libretro", "swanstation_MemoryCards_Card2Type" to "Shared"),
            "pcsx2" to mapOf("pcsx2_shared_memory_cards" to "enabled"),
        )
        val cores = Systems.all.filter { it.id == "psx" || it.id == "ps2" }.flatMap { it.cores }.associateBy { it.id }
        expected.forEach { (id, fixed) -> assertEquals(id, fixed, cores.getValue(id).fixed) }
        // Um valor fixo também nos padrões ou presets seria ignorado: cada chave num lugar só.
        Systems.all.flatMap { it.cores }.forEach { core ->
            val tuned = core.defaults.keys + core.presets.values.flatMap { it.keys }
            assertTrue(core.id, core.fixed.keys.none { it in tuned })
        }
    }
}
