package com.retrovika.app.core.catalog

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

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

    /** Ficha do jogo pela fonte que originou a entrada (para a página do jogo). */
    suspend fun details(entry: CatalogEntry) = source(entry.sourceId).details(entry)

    /**
     * Entradas abertas na página do jogo, pela [downloadKey]: a rota leva só a chave, e a entrada
     * (que veio de uma busca) fica aqui enquanto a página existir.
     */
    private val opened = object : LinkedHashMap<String, CatalogEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogEntry>) = size > 64
    }

    fun open(entry: CatalogEntry): String = synchronized(opened) { opened[entry.downloadKey] = entry; entry.downloadKey }

    fun opened(key: String): CatalogEntry? = synchronized(opened) { opened[key] }

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

    /**
     * Filtros restritos (gênero raro, console + busca) rendem poucos jogos por página. Em vez de pedir
     * a próxima só depois da anterior chegar, busca as seguintes em paralelo ([PARALLEL_PAGES] por vez)
     * até ter [MIN_FILL] jogos ou esgotar [MAX_FILL_ROUNDS] rodadas. [onPage] recebe cada página em ordem.
     */
    suspend fun fillAfter(
        first: CatalogPage,
        loaded: () -> Int,
        fetch: suspend (Int) -> CatalogPage,
        onPage: (CatalogPage) -> Unit,
    ) {
        var next = first.page + 1
        var lastPage = maxOf(first.totalPages, first.page)
        var rounds = 0
        while (loaded() < MIN_FILL && next <= lastPage && rounds < MAX_FILL_ROUNDS) {
            val batch = (next until next + PARALLEL_PAGES).filter { it <= lastPage }
            val pages = coroutineScope { batch.map { p -> async { runCatching { fetch(p) }.getOrNull() } }.awaitAll() }
            // Filtro trocado no meio: a busca nova assume (os pedidos daqui foram cancelados).
            currentCoroutineContext().ensureActive()
            if (pages.all { it == null }) break
            pages.filterNotNull().sortedBy { it.page }.forEach { page ->
                lastPage = maxOf(lastPage, page.totalPages)
                onPage(page)
            }
            next = batch.last() + 1
            rounds++
        }
    }

    private fun merge(pages: List<CatalogPage>, page: Int) = CatalogPage(
        entries = interleave(pages.map { it.entries }),
        page = page,
        totalPages = pages.maxOfOrNull { it.totalPages } ?: 1,
        totalResults = pages.sumOf { it.totalResults },
        approximate = pages.any { it.approximate },
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
        /** Com menos jogos que isso na tela, as próximas páginas vêm sem esperar a rolagem. */
        const val MIN_FILL = 12
        const val PARALLEL_PAGES = 3
        /** Rodadas em paralelo antes de esperar o usuário rolar de novo (até 9 páginas extras). */
        const val MAX_FILL_ROUNDS = 3
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
