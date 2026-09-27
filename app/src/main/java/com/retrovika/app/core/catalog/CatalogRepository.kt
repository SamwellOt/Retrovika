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

    private data class PageKey(val source: String, val query: String, val system: String?, val page: Int, val kind: String?, val genre: Genre?)
    private class CachedPage(val page: CatalogPage, val at: Long)

    /**
     * Páginas já buscadas, por alguns minutos: alternar entre gêneros ou filtros e voltar mostra
     * o resultado na hora, sem repetir os pedidos aos sites.
     */
    private val pageCache = object : LinkedHashMap<PageKey, CachedPage>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PageKey, CachedPage>) = size > CACHE_PAGES
    }

    /** [CatalogSource.search] com cache das páginas recentes. */
    suspend fun search(source: CatalogSource, query: String, systemId: String?, page: Int, kind: String?, genre: Genre?): CatalogPage {
        val key = PageKey(source.id, query.trim(), systemId, page, kind, genre)
        val now = System.currentTimeMillis()
        synchronized(pageCache) { pageCache[key] }?.takeIf { now - it.at < CACHE_TTL_MS }?.let { return it.page }
        val result = source.search(query, systemId, page, kind, genre)
        synchronized(pageCache) { pageCache[key] = CachedPage(result, now) }
        return result
    }

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
    suspend fun searchAll(
        query: String,
        systemId: String?,
        page: Int,
        genre: Genre? = null,
        /** Resultado parcial a cada fonte que responde: a tela não espera o site mais lento. */
        onPartial: (CatalogPage) -> Unit = {},
    ): CatalogPage = coroutineScope {
        val applicable = sources.filter { src ->
            when {
                src.requiresSystem && systemId == null -> false
                systemId != null -> systemId in src.systems
                else -> true
            }
        }
        val arrived = arrayOfNulls<CatalogPage>(applicable.size)
        val results = applicable
            .mapIndexed { i, src ->
                async {
                    runCatching { search(src, query, systemId, page, null, genre) }.also { r ->
                        r.getOrNull()?.let { p ->
                            // Mesma ordem de fontes do resultado final: o parcial só ganha cartões, nunca troca a lista.
                            val partial = synchronized(arrived) { arrived[i] = p; merge(arrived.filterNotNull(), page) }
                            onPartial(partial)
                        }
                    }
                }
            }
            .awaitAll()
        results.forEach { r -> r.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
        val pages = results.mapNotNull { it.getOrNull() }
        // Uma fonte fora do ar não esconde as outras; mas se todas falharam (sem internet, por
        // exemplo), o motivo precisa chegar à tela em vez de uma lista vazia.
        if (pages.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        merge(pages, page)
    }

    private fun merge(pages: List<CatalogPage>, page: Int) = CatalogPage(
        entries = interleave(pages.map { it.entries }),
        page = page,
        totalPages = pages.maxOfOrNull { it.totalPages } ?: 1,
        totalResults = pages.sumOf { it.totalResults },
    )

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

    private companion object {
        const val CACHE_PAGES = 48
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
