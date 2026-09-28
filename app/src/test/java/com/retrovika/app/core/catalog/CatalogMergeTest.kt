package com.retrovika.app.core.catalog

import android.content.ContextWrapper
import com.retrovika.app.core.net.WebFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogMergeTest {
    // O WebView do RomsFun só nasce no primeiro pedido: aqui só a ordem das fontes importa.
    private val repo = CatalogRepository(RomsFunSource(WebFetcher(ContextWrapper(null))))

    private fun e(source: String, id: String, title: String, system: String = "ps2", cover: String? = null) =
        CatalogEntry(id, source, title, system, null, cover, emptyList(), emptyList(), "https://$source/$id", "", "", "game")

    @Test
    fun `mesmo jogo em duas fontes vira um cartao com a fonte preferida na frente`() {
        val merged = repo.mergeDuplicates(
            listOf(e("romsfun", "gow", "God of War", cover = "capa-rf"), e("homebrewhub", "x", "Outro"), e("cdromance", "gow-cd", "God of War (USA)")),
        )
        assertEquals(listOf("God of War (USA)", "Outro"), merged.map { it.title })
        val gow = merged.first()
        assertEquals("cdromance", gow.sourceId)
        assertEquals(listOf("romsfun"), gow.alternates.map { it.sourceId })
        assertEquals(listOf("cdromance", "romsfun"), gow.sourceIds)
        // A fonte preferida não tinha capa: vale a da outra página.
        assertEquals("capa-rf", gow.coverUrl)
    }

    @Test
    fun `paginas repetidas da mesma fonte se juntam`() {
        val merged = repo.mergeDuplicates(
            listOf(
                e("romsfun", "re4", "Resident Evil 4 (Biohazard 4)"),
                e("romsfun", "re4-5", "Resident Evil 4"),
                e("romsfun", "gow", "God of War"),
                e("romsfun", "gow-dvd5", "God of War DVD5"),
                e("romsfun", "sp", "Spartan – Total Warrior"),
                e("romsfun", "sp2", "Spartan: Total Warrior"),
            ),
        )
        assertEquals(listOf("re4", "gow", "sp"), merged.map { it.id })
        assertEquals(listOf("re4", "re4-5"), merged[0].members.map { it.id })
        assertEquals(listOf("romsfun"), merged[0].sourceIds)
        assertEquals(listOf("gow", "gow-dvd5"), merged[1].members.map { it.id })
    }

    @Test
    fun `consoles diferentes e titulos diferentes continuam separados`() {
        val merged = repo.mergeDuplicates(
            listOf(e("romsfun", "a", "God of War II", "ps2"), e("romsfun", "b", "God of War II", "nes"), e("romsfun", "c", "God of War 1 Vampire Edition")),
        )
        assertEquals(3, merged.size)
        assertTrue(merged.all { it.alternates.isEmpty() })
    }

    @Test
    fun `mesclar de novo com pagina nova da o mesmo que mesclar tudo`() {
        val page1 = listOf(e("romsfun", "gow", "God of War"), e("romsfun", "sotc", "Shadow of the Colossus"))
        val page2 = listOf(e("cdromance", "gow-cd", "God of War"), e("romsfun", "gow2", "God of War"), e("romsfun", "ico", "ICO"))
        val incremental = repo.mergeDuplicates(repo.mergeDuplicates(page1) + page2)
        assertEquals(repo.mergeDuplicates(page1 + page2), incremental)
        // O cartão fica onde apareceu primeiro e a chave dos downloads não muda com a mescla.
        assertEquals(listOf("gow-cd", "sotc", "ico"), incremental.map { it.id })
        assertEquals(listOf("gow-cd", "gow", "gow2"), incremental.first().members.map { it.id })
        assertEquals(page1.first().downloadKey, incremental.first().downloadKey)
    }
}
