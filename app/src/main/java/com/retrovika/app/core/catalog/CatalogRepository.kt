package com.retrovika.app.core.catalog

import com.retrovika.app.core.gameinfo.SourceDetails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [romsFun] é opcional porque depende de um WebView: os testes da JVM montam o repositório sem ele.
 */
class CatalogRepository(romsFun: RomsFunSource? = null) {
    // A ordem aqui é a dos filtros no Explorar, a do rodízio em "Todas as fontes" e a de preferência
    // quando o mesmo jogo aparece em duas fontes (veja [mergeDuplicates]).
    val sources: List<CatalogSource> = listOfNotNull(
        CdRomanceSource(),
        romsFun,
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
    suspend fun search(
        source: CatalogSource, query: String, systemId: String?, page: Int, kind: String?, genre: Genre?,
    ): CatalogPage {
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

    /**
     * Ficha do jogo para a página do jogo, com a página que a deu: a da fonte preferida e, se ela falhar,
     * a das outras páginas mescladas na entrada, na ordem.
     */
    suspend fun details(entry: CatalogEntry): Pair<CatalogEntry, SourceDetails> {
        var failure: Throwable? = null
        for (member in entry.members) {
            try {
                return member to source(member.sourceId).details(member)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                if (failure == null) failure = t
            }
        }
        throw failure ?: IllegalStateException(entry.id)
    }

    /**
     * Entradas abertas na página do jogo, pela [downloadKey]: a rota leva só a chave, e a entrada
     * (que veio de uma busca) fica aqui enquanto a página existir.
     */
    private val opened = object : LinkedHashMap<String, CatalogEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CatalogEntry>) = size > 64
    }

    fun open(entry: CatalogEntry): String = synchronized(opened) { opened[entry.downloadKey] = entry; entry.downloadKey }

    fun opened(key: String): CatalogEntry? = synchronized(opened) { opened[key] }

    /**
     * Arquivos baixáveis de uma entrada (para escolher qual ROM baixar): os de todas as páginas mescladas nela,
     * pedidos ao mesmo tempo, cada um marcado com a página de origem. Uma fonte que falha não esconde as
     * outras; só se todas falharem o erro (o da fonte preferida) sobe.
     */
    suspend fun variants(entry: CatalogEntry): List<RomVariant> = coroutineScope {
        val results = entry.members.map { member ->
            async { runCatching { source(member.sourceId).variants(member).map { it.copy(origin = member) } } }
        }.awaitAll()
        results.forEach { r -> r.exceptionOrNull()?.let { if (it is CancellationException) throw it } }
        val found = results.mapNotNull { it.getOrNull() }
        if (found.isEmpty()) throw results.first().exceptionOrNull()!!
        found.flatten().distinctBy { it.downloadUrl }
    }

    /** Pedido final do arquivo da variante, gerado na hora do download pela fonte da página que o oferece. */
    suspend fun directLink(entry: CatalogEntry, variant: RomVariant): DirectLink {
        val from = variant.origin ?: entry
        return source(from.sourceId).directLink(from, variant)
    }

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
        /** Limita a busca a estas fontes (o grupo "Roms" do Explorar); `null` busca em todas. */
        only: Collection<String>? = null,
        /** Resultado parcial a cada fonte que responde: a tela não espera o site mais lento. */
        onPartial: (CatalogPage) -> Unit = {},
    ): CatalogPage = coroutineScope {
        val applicable = sources.filter { src ->
            when {
                only != null && src.id !in only -> false
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
                            // Entregue dentro da trava: um parcial mais antigo (com menos fontes) nunca chega depois de um mais novo.
                            synchronized(arrived) { arrived[i] = p; onPartial(merge(arrived.filterNotNull(), page)) }
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
            // Só as páginas até a primeira falha: pular uma deixaria um buraco que o "carregar mais" nunca pede de novo.
            val arrived = pages.takeWhile { it != null }.filterNotNull()
            if (arrived.isEmpty()) break
            arrived.forEach { page ->
                lastPage = maxOf(lastPage, page.totalPages)
                onPage(page)
            }
            next = batch[arrived.size - 1] + 1
            if (arrived.size < pages.size) break
            rounds++
        }
    }

    /** Junta as páginas de várias fontes, intercaladas, cada uma na ordem dela (não há um critério comum entre os sites). */
    private fun merge(pages: List<CatalogPage>, page: Int) = CatalogPage(
        entries = mergeDuplicates(interleave(pages.map { it.entries })),
        page = page,
        totalPages = pages.maxOfOrNull { it.totalPages } ?: 1,
        totalResults = pages.sumOf { it.totalResults },
        approximate = pages.any { it.approximate },
    )

    /**
     * Junta num cartão só as páginas do mesmo jogo (mesmo console e título, [dedupKey]): de fontes diferentes
     * (CDRomance e RomsFun) ou repetidas na mesma fonte (o RomsFun tem "Resident Evil 4" e "Resident Evil 4
     * (Biohazard 4)"). O cartão fica na posição da primeira que apareceu; a principal é a da fonte que vem
     * antes em [sources] (o RomsFun depende de um WebView e tem limite de downloads), e as outras vão para
     * [CatalogEntry.alternates], de onde a página do jogo tira os arquivos de todas. Aplicar de novo sobre a
     * lista com páginas novas dá o mesmo resultado que aplicar tudo de uma vez.
     */
    fun mergeDuplicates(entries: List<CatalogEntry>): List<CatalogEntry> {
        val rank = sources.withIndex().associate { (i, s) -> s.id to i }
        val groups = LinkedHashMap<Any, MutableList<CatalogEntry>>()
        entries.forEach { e ->
            val key: Any = dedupKey(e) ?: (e.sourceId to e.id)
            groups.getOrPut(key) { ArrayList() }.addAll(e.members)
        }
        return groups.values.map { group ->
            val members = group.distinctBy { it.sourceId to it.id }.map { it.copy(alternates = emptyList()) }
                .sortedBy { rank[it.sourceId] ?: Int.MAX_VALUE }
            val main = members.first()
            main.copy(
                coverUrl = main.coverUrl ?: members.firstNotNullOfOrNull { it.coverUrl },
                developer = main.developer ?: members.firstNotNullOfOrNull { it.developer },
                alternates = members.drop(1),
            )
        }
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

/**
 * Título sem região, pontuação e caixa: "Pokémon: X (USA)" e "pokemon x" viram a mesma chave.
 * Letras de outros alfabetos (japonês, cirílico) ficam; um título sem nenhuma letra ou número dá null
 * e não é comparado, senão todos eles virariam o mesmo jogo.
 */
internal fun dedupKey(e: CatalogEntry): String? {
    val base = java.text.Normalizer.normalize(e.title.substringBefore(" (").substringBefore(" ["), java.text.Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase()
        // "God of War DVD5": o RomsFun publica a cópia comprimida (DVD de camada única) numa página à parte.
        .replace(DISC_LAYER_SUFFIX, "")
        .replace(NON_ALNUM, "")
    return if (base.isEmpty()) null else e.systemId + "|" + base
}

// Compiladas uma vez: a chave é calculada para cada cartão a cada página que chega.
private val DIACRITICS = Regex("\\p{M}+")
private val DISC_LAYER_SUFFIX = Regex("\\s+dvd[59]$")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")
