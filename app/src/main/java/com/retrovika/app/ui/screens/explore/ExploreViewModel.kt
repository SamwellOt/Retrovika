package com.retrovika.app.ui.screens.explore

import androidx.annotation.StringRes
import com.retrovika.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retrovika.app.AppContainer
import com.retrovika.app.core.catalog.CatalogEntry
import com.retrovika.app.core.catalog.Genre
import com.retrovika.app.core.catalog.RomVariant
import com.retrovika.app.core.catalog.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import com.retrovika.app.core.catalog.CatalogPage
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Um filtro de fonte do Explorar. [members] são as fontes reais que ele busca: uma só, ou várias nas
 * entradas virtuais ("Todas as fontes", "Roms"), cujo nome traduzido vem de [nameRes].
 */
data class SourceInfo(
    val id: String,
    val name: String,
    @StringRes val description: Int,
    val requiresSystem: Boolean,
    @StringRes val nameRes: Int? = null,
    val members: Set<String> = setOf(id),
) {
    val aggregated get() = members.size > 1
}

/** Seleção de "qual ROM baixar": estados do modal enquanto carrega e depois lista as variantes. */
sealed interface VariantPrompt {
    val entry: CatalogEntry
    data class Loading(override val entry: CatalogEntry) : VariantPrompt
    data class Ready(override val entry: CatalogEntry, val variants: List<RomVariant>) : VariantPrompt
    data class Failed(override val entry: CatalogEntry) : VariantPrompt
}

data class ExploreState(
    val sourceId: String,
    val query: String = "",
    val systemId: String? = null,
    val kind: String? = "game",
    val genre: Genre? = null,
    val sort: SortOrder = SortOrder.DEFAULT,
    val entries: List<CatalogEntry> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val totalApproximate: Boolean = false,
    val loading: Boolean = false,
    @StringRes val error: Int? = null,
    /** Algum site já respondeu sem jogos e os mais lentos ainda estão buscando: a tela explica a espera. */
    val waitingSlowSources: Boolean = false,
) {
    val canLoadMore get() = !loading && error == null && page < totalPages
    /** Primeira página ainda chegando: a tela mostra cartões-esqueleto em vez da lista vazia. */
    val initialLoading get() = loading && entries.isEmpty() && error == null
}

const val ALL_SOURCES = "all"
const val ROMS_GROUP = "roms"

/** Sites de ROMs comerciais: no Explorar aparecem juntos como "Roms"; o nome de cada um só aparece na hora de baixar. */
private val ROM_SITES = setOf("cdromance", "romsfun")

@OptIn(FlowPreview::class)
class ExploreViewModel(private val app: AppContainer) : ViewModel() {
    private val realSources: List<SourceInfo> =
        app.catalog.sources.map { SourceInfo(it.id, it.name, it.description, it.requiresSystem) }

    /** O grupo "Roms" fica na posição do primeiro site dele; se só um estiver disponível, ele aparece sozinho. */
    private val filters: List<SourceInfo> = run {
        val roms = realSources.filter { it.id in ROM_SITES }
        if (roms.size < 2) return@run realSources
        val group = SourceInfo(
            ROMS_GROUP, "Roms", R.string.catalog_roms_description,
            requiresSystem = false, nameRes = R.string.catalog_roms, members = roms.mapTo(LinkedHashSet()) { it.id },
        )
        realSources.flatMap { src -> if (src.id !in ROM_SITES) listOf(src) else if (src == roms.first()) listOf(group) else emptyList() }
    }

    /** A busca unificada ("Todas as fontes") só faz sentido quando há mais de um filtro. */
    val sources: List<SourceInfo> =
        if (filters.size > 1) {
            listOf(
                SourceInfo(
                    ALL_SOURCES, "All sources", R.string.catalog_all_sources_description,
                    requiresSystem = false, nameRes = R.string.catalog_all_sources,
                    members = realSources.mapTo(LinkedHashSet()) { it.id },
                ),
            ) + filters
        } else filters

    private fun filter(id: String) = sources.first { it.id == id }

    /**
     * Ordens que ao menos uma fonte do filtro aplica com o console escolhido, na ordem do enum ([SortOrder.DEFAULT]
     * primeiro). Só o padrão: a tela esconde a linha de ordenação.
     */
    fun sorts(s: ExploreState): List<SortOrder> {
        val available = filter(s.sourceId).members.flatMapTo(HashSet()) { app.catalog.source(it).sorts(s.systemId) }
        return SortOrder.entries.filter { it == SortOrder.DEFAULT || it in available }
    }

    /** Trocar de fonte ou de console pode tirar a ordem escolhida das disponíveis: volta à padrão. */
    private fun ExploreState.validSort() = if (sort in sorts(this)) this else copy(sort = SortOrder.DEFAULT)

    /** Consoles oferecidos pelo filtro selecionado (num filtro de várias fontes, a união delas). */
    val systems: List<String>
        get() = filter(_state.value.sourceId).members.flatMapTo(LinkedHashSet()) { app.catalog.source(it).systems }.toList()

    private val _state = MutableStateFlow(ExploreState(sourceId = sources.first().id))
    val state: StateFlow<ExploreState> = _state.asStateFlow()
    val downloads = app.downloads.tasks

    private val _prompt = MutableStateFlow<VariantPrompt?>(null)
    val prompt: StateFlow<VariantPrompt?> = _prompt.asStateFlow()

    private var searchJob: Job? = null

    /** Maior posição da grade que já apareceu na tela desde a última busca; os parciais chegam de outras threads. */
    @Volatile private var seenUntil = -1

    init { reload(debounce = false) }

    /** A tela avisa até onde a rolagem já mostrou (índice da grade, que fica à frente do da lista por causa do cabeçalho). */
    fun onSeen(index: Int) { if (index > seenUntil) seenUntil = index }

    /**
     * Cada site que responde reintercala a página inteira; aplicado direto, isso trocava de lugar cartões que o
     * usuário já via, e a grade pulava junto com o cartão que segura a rolagem. Os cartões até o último já visto
     * ficam onde estão; só os de baixo, ainda fora da tela, seguem a ordem nova.
     */
    private fun stable(current: List<CatalogEntry>, target: List<CatalogEntry>): List<CatalogEntry> {
        val keep = (seenUntil + 1).coerceIn(0, current.size)
        return app.catalog.mergeDuplicates(current.subList(0, keep) + target)
    }

    fun sourceName(id: String): String? = realSources.firstOrNull { it.id == id }?.name

    /**
     * Etiquetas de fonte do cartão em "Todas as fontes": os filtros de que a entrada veio. CDRomance e RomsFun
     * aparecem como "Roms"; o site de cada arquivo só é mostrado na hora de baixar.
     */
    fun sourceLabels(entry: CatalogEntry): List<SourceInfo> =
        entry.sourceIds.mapNotNull { id -> filters.firstOrNull { id in it.members } }.distinct()

    fun setSource(id: String) {
        if (id == _state.value.sourceId) return
        val requiresSystem = filter(id).requiresSystem
        _state.update {
            it.copy(
                sourceId = id,
                systemId = if (requiresSystem) app.catalog.source(id).systems.firstOrNull() else null,
            ).validSort()
        }
        reload(debounce = false)
    }

    fun setQuery(q: String) { _state.update { it.copy(query = q) }; reload(debounce = true) }
    fun setSystem(id: String?) { _state.update { it.copy(systemId = id).validSort() }; reload(debounce = false) }
    fun setSort(sort: SortOrder) {
        if (sort == _state.value.sort) return
        _state.update { it.copy(sort = sort) }
        reload(debounce = false)
    }
    fun setKind(kind: String?) { _state.update { it.copy(kind = kind) }; reload(debounce = false) }

    /** Tocar no gênero já ativo o desmarca. */
    fun toggleGenre(genre: Genre) {
        _state.update { it.copy(genre = if (it.genre == genre) null else genre) }
        reload(debounce = false)
    }

    /** Volta termo, gênero e console ao padrão da fonte atual (fontes que exigem console mantêm o 1º). */
    fun clearFilters() {
        val requiresSystem = filter(_state.value.sourceId).requiresSystem
        _state.update {
            it.copy(
                query = "", genre = null, kind = "game",
                systemId = if (requiresSystem) app.catalog.source(it.sourceId).systems.firstOrNull() else null,
            ).validSort()
        }
        reload(debounce = false)
    }

    fun retry() = reload(debounce = false)

    private fun reload(debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            seenUntil = -1
            _state.update { it.copy(entries = emptyList(), page = 0, totalPages = 1, loading = true, error = null, waitingSlowSources = false) }
            fetch(1)
        }
    }

    fun loadMore() {
        val s = _state.value
        if (!s.canLoadMore || searchJob?.isActive == true) return
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            fetch(s.page + 1)
        }
    }

    /** Uma página do filtro atual (uma fonte ou várias), interpretada fora da thread principal. */
    private suspend fun fetchPage(s: ExploreState, page: Int, onPartial: ((CatalogPage) -> Unit)? = null): CatalogPage =
        withContext(Dispatchers.Default) {
            val f = filter(s.sourceId)
            if (f.aggregated) app.catalog.searchAll(s.query, s.systemId, page, s.genre, s.sort, only = f.members) { onPartial?.invoke(it) }
            else app.catalog.search(app.catalog.source(s.sourceId), s.query, s.systemId, page, s.kind, s.genre, s.sort)
        }

    /**
     * Acrescenta uma página ao estado; o total vem da 1ª e as seguintes só o corrigem para cima. Um jogo que
     * já estava na tela (de outra fonte ou noutra página da mesma) se junta ao cartão existente.
     */
    private fun appendPage(result: CatalogPage) = _state.update {
        it.copy(
            entries = app.catalog.mergeDuplicates(it.entries + result.entries),
            page = maxOf(it.page, result.page), totalPages = maxOf(result.totalPages, result.page),
            totalResults = if (result.page == 1) result.totalResults else maxOf(it.totalResults, result.totalResults),
            totalApproximate = if (result.page == 1) result.approximate else it.totalApproximate && result.approximate,
        )
    }

    private suspend fun fetch(page: Int) {
        val s = _state.value
        val before = s.entries
        val job = coroutineContext[Job]
        val first = try {
            fetchPage(s, page) { partial ->
                // Cada site aparece assim que responde; o indicador de carga segue até o último.
                if (job?.isActive == true) _state.update {
                    val entries = stable(it.entries, before + partial.entries)
                    it.copy(entries = entries, waitingSlowSources = entries.isEmpty())
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            android.util.Log.w("Explore", "Falha ao buscar catálogo", t)
            _state.update { it.copy(loading = false, error = R.string.explore_error_network, waitingSlowSources = false) }
            return
        }
        // O resultado final contém todos os parciais: aqui também nada que já apareceu muda de lugar.
        _state.update { it.copy(entries = stable(it.entries, before + first.entries), waitingSlowSources = false) }
        appendPage(first)

        // Filtro que rende pouco por página: as próximas vêm em paralelo até encher a tela.
        app.catalog.fillAfter(first, loaded = { _state.value.entries.size }, fetch = { p -> fetchPage(s, p) }, onPage = ::appendPage)
        _state.update { it.copy(loading = false) }
    }

    /**
     * Ponto de entrada do download: descobre quais ROMs a entrada oferece. Se houver só
     * uma, baixa direto; se houver várias, abre o seletor para o usuário escolher.
     */
    private var promptJob: Job? = null

    fun requestDownload(entry: CatalogEntry) {
        // Toque duplo ou nova escolha: o pedido anterior não abre o seletor nem baixa por cima.
        promptJob?.cancel()
        promptJob = viewModelScope.launch {
            _prompt.value = VariantPrompt.Loading(entry)
            val variants = try {
                withContext(Dispatchers.Default) { app.catalog.variants(entry) }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                emptyList()
            }
            when {
                variants.isEmpty() -> { _prompt.value = null; app.downloads.enqueue(entry) }
                variants.size == 1 -> { _prompt.value = null; app.downloads.enqueue(entry, variants.first()) }
                else -> _prompt.value = VariantPrompt.Ready(entry, variants)
            }
        }
    }

    fun confirmVariant(entry: CatalogEntry, variant: RomVariant) {
        app.downloads.enqueue(entry, variant)
        _prompt.value = null
    }

    /** Fechar o seletor enquanto as variantes carregam cancela o pedido: nada é baixado. */
    fun dismissPrompt() {
        promptJob?.cancel()
        _prompt.value = null
    }
}
