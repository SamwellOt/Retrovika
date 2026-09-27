package com.retrovika.app.core.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Mede, contra os sites reais, quanto os filtros do Explorar levam para mostrar resultados: o tempo da
 * 1ª página e o tempo até a tela encher, com o mesmo [CatalogRepository.fillAfter] que a tela usa.
 * Só roda com `-PnetworkTests=true` (precisa de internet): `./gradlew testDebugUnitTest -PnetworkTests=true
 * --tests '*CatalogSpeedTest*'`. Imprime uma tabela; falha se a 1ª tela de algum filtro passar de [LIMIT_MS].
 */
class CatalogSpeedTest {
    private data class Case(val label: String, val sourceId: String?, val systemId: String?, val genre: Genre?, val kind: String? = null)

    private val cases = listOf(
        Case("CDRomance · Ação", "cdromance", null, Genre.ACTION),
        Case("CDRomance · GBA + Ação", "cdromance", "gba", Genre.ACTION),
        Case("CDRomance · PS1 + Esportes", "cdromance", "psx", Genre.SPORTS),
        Case("CDRomance · SNES + RPG", "cdromance", "snes", Genre.RPG),
        Case("CDRomance · N64 + Corrida", "cdromance", "n64", Genre.RACING),
        Case("CDRomance · GBA (sem gênero)", "cdromance", "gba", null),
        Case("CDRomance · Mega Drive + Ação (sem seção)", "cdromance", "genesis", Genre.ACTION),
        Case("Homebrew Hub · Quebra-cabeça", "homebrewhub", null, Genre.PUZZLE, "game"),
        Case("Homebrew Hub · Esportes", "homebrewhub", null, Genre.SPORTS, "game"),
        Case("Homebrew Hub · GB + Ação", "homebrewhub", "gb", Genre.ACTION, "game"),
        Case("Internet Archive · SNES + Esportes", "archive", "snes", Genre.SPORTS),
        Case("Todas · Ação", null, null, Genre.ACTION),
        Case("Todas · GBA + Esportes", null, "gba", Genre.SPORTS),
        Case("Todas · PS1 + Terror", null, "psx", Genre.HORROR),
    )

    @Test
    fun `filtros do explorar respondem rapido`() = runBlocking {
        assumeTrue("teste de rede: use -PnetworkTests=true", System.getProperty("networkTests") == "true")
        val slow = mutableListOf<String>()
        println(String.format("%-44s %9s %6s %11s %7s %s", "filtro", "1ª pág.", "jogos", "tela cheia", "jogos", "páginas"))
        for (case in cases) {
            val repo = CatalogRepository() // sem cache entre casos: mede o pedido de verdade
            suspend fun fetch(page: Int): CatalogPage =
                if (case.sourceId == null) repo.searchAll("", case.systemId, page, case.genre)
                else repo.search(repo.source(case.sourceId), "", case.systemId, page, case.kind, case.genre)

            val start = System.nanoTime()
            val first = try {
                fetch(1)
            } catch (t: Throwable) {
                println(String.format("%-44s ERRO: %s", case.label, t.message ?: t.javaClass.simpleName))
                continue
            }
            val firstMs = (System.nanoTime() - start) / 1_000_000
            var loaded = first.entries.size
            var pages = 1
            repo.fillAfter(first, loaded = { loaded }, fetch = ::fetch, onPage = { loaded += it.entries.size; pages++ })
            val fullMs = (System.nanoTime() - start) / 1_000_000
            println(String.format("%-44s %7d ms %6d %9d ms %7d %d", case.label, firstMs, first.entries.size, fullMs, loaded, pages))
            if (fullMs > LIMIT_MS) slow += "${case.label}: ${fullMs} ms"
        }
        check(slow.isEmpty()) { "Filtros lentos (> $LIMIT_MS ms até a tela encher): $slow" }
    }

    private companion object {
        const val LIMIT_MS = 3_000L
    }
}
