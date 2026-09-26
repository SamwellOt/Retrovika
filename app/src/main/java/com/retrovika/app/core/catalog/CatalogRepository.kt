package com.retrovika.app.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class CatalogRepository {
    // A ordem aqui é a dos filtros no Explorar e a do rodízio em "Todas as fontes".
    val sources: List<CatalogSource> = listOf(
        CdRomanceSource(),
        HomebrewHubSource(),
        InternetArchiveSource(),
    )

    val supportedSystems: Set<String> get() = sources.flatMap { it.systems }.toSet()

    fun source(id: String) = sources.first { it.id == id }

    /** Consoles cobertos por ao menos uma fonte (usado pela busca unificada). */
    val allSystems: Set<String> get() = supportedSystems

    /** Resolve o link final de download pela fonte que originou a entrada. */
    suspend fun resolve(entry: CatalogEntry): CatalogEntry = source(entry.sourceId).resolve(entry)

    /** Arquivos baixáveis de uma entrada (para escolher qual ROM baixar). */
    suspend fun variants(entry: CatalogEntry): List<RomVariant> = source(entry.sourceId).variants(entry)

    /**
     * Busca o mesmo título em todas as fontes aplicáveis ao mesmo tempo e mescla os
     * resultados, intercalando as fontes para que nenhuma domine o topo. É assim que a
     * busca por título mostra, de uma vez, "de quais sites dá para baixar esta ROM".
     */
    suspend fun searchAll(query: String, systemId: String?, page: Int): CatalogPage = coroutineScope {
        val applicable = sources.filter { src ->
            when {
                src.requiresSystem && systemId == null -> false
                systemId != null -> systemId in src.systems
                else -> true
            }
        }
        val results = applicable
            .map { src -> async { runCatching { src.search(query, systemId, page, null) } } }
            .awaitAll()
        results.forEach { r -> r.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
        val pages = results.mapNotNull { it.getOrNull() }
        // Uma fonte fora do ar não esconde as outras; mas se todas falharam (sem internet, por
        // exemplo), o motivo precisa chegar à tela em vez de uma lista vazia.
        if (pages.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        CatalogPage(
            entries = interleave(pages.map { it.entries }),
            page = page,
            totalPages = pages.maxOfOrNull { it.totalPages } ?: 1,
            totalResults = pages.sumOf { it.totalResults },
        )
    }

    /** Intercala listas em rodízio: 1ª de cada fonte, depois 2ª de cada, e assim por diante. */
    private fun interleave(lists: List<List<CatalogEntry>>): List<CatalogEntry> {
        val result = ArrayList<CatalogEntry>()
        var i = 0
        while (true) {
            var added = false
            for (list in lists) if (i < list.size) { result.add(list[i]); added = true }
            if (!added) break
            i++
        }
        return result
    }
}
