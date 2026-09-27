package com.retrovika.app.core.catalog

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Mede, contra os sites reais, quanto os filtros do Explorar levam para mostrar resultados: o tempo da
 * 1ª página e o tempo até a tela encher, com o mesmo [CatalogRepository.fillAfter] que a tela usa.
 * Só roda com `-PnetworkTests=true` (precisa de internet): `./gradlew testDebugUnitTest -PnetworkTests=true
 * --tests '*CatalogSpeedTest*'`. Imprime uma tabela; falha se os primeiros jogos de algum filtro levarem mais
 * que [FIRST_LIMIT_MS] ou a tela cheia mais que [LIMIT_MS].
 */
class CatalogSpeedTest {
    /**
     * [externalLimit]: o Internet Archive leva 1–5 s em qualquer consulta nova, com ou sem gênero
     * (medido no Actions), então sozinho ele tem um limite próprio; nada no app o deixa mais rápido.
     */
    private data class Case(val label: String, val sourceId: String?, val systemId: String?, val genre: Genre?, val kind: String? = null, val externalLimit: Boolean = false)

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
        Case("Internet Archive · SNES + Esportes", "archive", "snes", Genre.SPORTS, externalLimit = true),
        Case("Todas · Ação", null, null, Genre.ACTION),
        Case("Todas · GBA + Esportes", null, "gba", Genre.SPORTS),
        Case("Todas · PS1 + Terror", null, "psx", Genre.HORROR),
    )

    @Test
    fun `filtros do explorar respondem rapido`() = runBlocking {
        assumeTrue("teste de rede: use -PnetworkTests=true", System.getProperty("networkTests") == "true")
        // Aquecimento fora da medição: carga das classes (Jsoup, OkHttp) e a 1ª conexão TLS com cada site.
        // No app isso acontece ao abrir o Explorar, que já carrega a lista inicial.
        CatalogRepository().let { warm -> warm.sources.forEach { src -> runCatching { warm.search(src, "", src.systems.first(), 1, null, null) } } }
        val slow = mutableListOf<String>()
        println(String.format("%-44s %10s %9s %6s %11s %7s %s", "filtro", "1ºs jogos", "1ª pág.", "jogos", "tela cheia", "jogos", "páginas"))
        for (case in cases) {
            val repo = CatalogRepository() // sem cache entre casos: mede o pedido de verdade
            val start = System.nanoTime()
            // Em "Todas as fontes" a tela mostra cada site assim que ele responde (onPartial).
            var firstVisibleMs = -1L
            suspend fun fetch(page: Int): CatalogPage =
                if (case.sourceId == null) repo.searchAll("", case.systemId, page, case.genre) { partial ->
                    if (firstVisibleMs < 0 && partial.entries.isNotEmpty()) firstVisibleMs = (System.nanoTime() - start) / 1_000_000
                }
                else repo.search(repo.source(case.sourceId), "", case.systemId, page, case.kind, case.genre)

            val first = try {
                fetch(1)
            } catch (t: Throwable) {
                println(String.format("%-44s ERRO: %s", case.label, t.message ?: t.javaClass.simpleName))
                continue
            }
            val firstMs = (System.nanoTime() - start) / 1_000_000
            if (firstVisibleMs < 0) firstVisibleMs = firstMs
            var loaded = first.entries.size
            var pages = 1
            repo.fillAfter(first, loaded = { loaded }, fetch = ::fetch, onPage = { loaded += it.entries.size; pages++ })
            val fullMs = (System.nanoTime() - start) / 1_000_000
            println(String.format("%-44s %7d ms %6d ms %6d %9d ms %7d %d", case.label, firstVisibleMs, firstMs, first.entries.size, fullMs, loaded, pages))
            val firstLimit = if (case.externalLimit) ARCHIVE_LIMIT_MS else FIRST_LIMIT_MS
            if (firstVisibleMs > firstLimit) slow += "${case.label}: 1ºs jogos em ${firstVisibleMs} ms"
            // Em "Todas as fontes" a tela cheia espera o Archive; o que conta ali são os primeiros jogos.
            if (case.sourceId != null && !case.externalLimit && fullMs > LIMIT_MS) slow += "${case.label}: tela cheia em ${fullMs} ms"
        }
        check(slow.isEmpty()) { "Filtros lentos: $slow" }
    }

    private companion object {
        /** Até os primeiros jogos aparecerem. */
        const val FIRST_LIMIT_MS = 1_500L
        /** Até a tela encher (todas as fontes responderem). */
        const val LIMIT_MS = 3_000L
        /** Internet Archive sozinho: o tempo é do servidor dele (1–5 s em consulta nova). */
        const val ARCHIVE_LIMIT_MS = 8_000L
    }
}
