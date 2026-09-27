package com.retrovika.app.core.gameinfo

import com.retrovika.app.core.catalog.CatalogRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Confere, contra os sites reais, o que a página do jogo mostra: Backloggd (nota, distribuição,
 * contadores, reviews), Wikipedia/Wikidata (resumo em português, ficha, crítica) e a ficha de cada
 * fonte do catálogo. Só roda com `-PnetworkTests=true`; imprime o que achou e o tempo de cada parte.
 */
class GameInfoNetworkTest {
    @Before
    fun network() = assumeTrue("teste de rede: use -PnetworkTests=true", System.getProperty("networkTests") == "true")

    private inline fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        val value = block()
        println(String.format("%-44s %6d ms", label, (System.nanoTime() - start) / 1_000_000))
        return value
    }

    @Test
    fun `backloggd acha o jogo certo entre titulos repetidos`() = runBlocking {
        val client = BackloggdClient()
        val chrono = timed("Backloggd · Chrono Trigger (SNES)") { client.find("Chrono Trigger (USA)", "snes") }
        assertNotNull("Chrono Trigger de SNES não encontrado", chrono)
        assertEquals("chrono-trigger", chrono!!.slug)
        assertTrue(chrono.rating!! in 3.5..5.0)
        assertTrue(chrono.ratingCount!! > 1000)
        assertEquals(10, chrono.histogram.size)
        assertNotNull(chrono.plays)
        assertNotNull(chrono.timeToFinish)
        assertTrue("sem reviews", chrono.reviews.isNotEmpty())
        println("  ${chrono.rating} (${chrono.ratingCount}) · ${chrono.plays} jogaram · ${chrono.timeToFinish} · ${chrono.reviews.size} reviews · ${chrono.platforms}")

        val mario = timed("Backloggd · Super Mario 64 (N64)") { client.find("Super Mario 64 (USA)", "n64") }
        assertEquals("super-mario-64", mario?.slug)
        assertNotNull(mario?.backdropUrl)

        val psx = timed("Backloggd · Crash Bandicoot (PS1)") { client.find("Crash Bandicoot", "psx") }
        assertNotNull("Crash Bandicoot de PS1 não encontrado", psx)
        println("  ${psx!!.slug} · ${psx.rating} · ${psx.platforms}")

        val none = timed("Backloggd · título inexistente") { client.find("Jogo Que Nao Existe Xyzzy", "gba") }
        assertEquals(null, none)
    }

    @Test
    fun `wikidata traz ficha critica e resumo em portugues`() = runBlocking {
        val wiki = WikiClient()
        val mario = timed("Wikidata · Super Mario 64 (IGDB)") { wiki.find("Super Mario 64", "pt", igdbSlug = "super-mario-64") }
        assertNotNull(mario)
        assertEquals("pt", mario!!.articleLang)
        assertNotNull(mario.extract)
        assertTrue(mario.publishers.any { "Nintendo" in it })
        assertTrue(mario.scores.any { it.reviewer.contains("Metacritic") })
        assertTrue(mario.links.any { it.name == "HowLongToBeat" })
        println("  ${mario.releaseDate} · ${mario.developers} · ${mario.series} · ${mario.composers} · ${mario.scores} · ${mario.ageRatings}")
        println("  ${mario.extract!!.take(120)}…")

        val byTitle = timed("Wikidata · Chrono Trigger (título)") { wiki.find("Chrono Trigger (USA)", "en") }
        assertNotNull(byTitle)
        assertTrue(byTitle!!.developers.any { "Square" in it })
    }

    @Test
    fun `cada fonte do catalogo le a propria ficha`() = runBlocking {
        val repo = CatalogRepository()
        for (source in repo.sources) {
            val system = if (source.id == "archive") "snes" else null
            val page = repo.search(source, "", system ?: source.systems.first(), 1, if (source.id == "homebrewhub") "game" else null, null)
            val entry = page.entries.firstOrNull() ?: error("${source.name}: busca vazia")
            val details = timed("${source.name} · ficha de ${entry.title.take(24)}") { source.details(entry) }
            println("  título=${details.title} · lançamento=${details.releaseDate} · gêneros=${details.genres} · idiomas=${details.languages} · região=${details.region} · formato=${details.format} · nota=${details.rating} · capturas=${details.screenshots.size} · descrição=${details.description?.length ?: 0} car.")
            assertNotNull(details.title)
        }
    }
}
