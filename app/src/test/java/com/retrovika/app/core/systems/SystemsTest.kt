package com.retrovika.app.core.systems

import com.retrovika.app.core.dat.DatCatalog
import com.retrovika.app.core.library.RomNaming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
