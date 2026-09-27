package com.retrovika.app.ui.screens.explore

import androidx.annotation.StringRes
import com.retrovika.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retrovika.app.AppContainer
import com.retrovika.app.core.catalog.CatalogEntry
import com.retrovika.app.core.catalog.Genre
import com.retrovika.app.core.catalog.RomVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** [nameRes] traduz o nome das entradas virtuais ("Todas as fontes"); fontes reais usam o próprio [name]. */
data class SourceInfo(
    val id: String,
    val name: String,
    @StringRes val description: Int,
    val requiresSystem: Boolean,
    @StringRes val nameRes: Int? = null,
)

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
    val entries: List<CatalogEntry> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val loading: Boolean = false,
    @StringRes val error: Int? = null,
) {
    val canLoadMore get() = !loading && error == null && page < totalPages
    val aggregated get() = sourceId == ALL_SOURCES
    /** Primeira página ainda chegando: a tela mostra cartões-esqueleto em vez da lista vazia. */
    val initialLoading get() = loading && entries.isEmpty() && error == null
}

const val ALL_SOURCES = "all"

/** Quantas páginas vazias seguidas buscamos antes de esperar o usuário rolar de novo. */
private const val MAX_EMPTY_PAGES = 3

@OptIn(FlowPreview::class)
class ExploreViewModel(private val app: AppContainer) : ViewModel() {
    private val realSources: List<SourceInfo> =
        app.catalog.sources.map { SourceInfo(it.id, it.name, it.description, it.requiresSystem) }

    /** A busca unificada ("Todas as fontes") só faz sentido quando há mais de uma fonte. */
    val sources: List<SourceInfo> =
        if (realSources.size > 1) {
            listOf(
                SourceInfo(
                    ALL_SOURCES, "All sources", R.string.catalog_all_sources_description,
                    requiresSystem = false, nameRes = R.string.catalog_all_sources,
                ),
            ) + realSources
        } else realSources

    /** Consoles oferecidos pela fonte selecionada (na busca unificada, a união de todas). */
    val systems: List<String>
        get() = if (_state.value.aggregated) app.catalog.allSystems.toList()
        else app.catalog.source(_state.value.sourceId).systems.toList()

    private val _state = MutableStateFlow(ExploreState(sourceId = sources.first().id))
    val state: StateFlow<ExploreState> = _state.asStateFlow()
    val downloads = app.downloads.tasks

    private val _prompt = MutableStateFlow<VariantPrompt?>(null)
    val prompt: StateFlow<VariantPrompt?> = _prompt.asStateFlow()

    private var searchJob: Job? = null

    init { reload(debounce = false) }

    fun sourceName(id: String): String? = realSources.firstOrNull { it.id == id }?.name

    fun setSource(id: String) {
        if (id == _state.value.sourceId) return
        val requiresSystem = sources.first { it.id == id }.requiresSystem
        _state.update {
            it.copy(
                sourceId = id,
                systemId = if (requiresSystem) app.catalog.source(id).systems.firstOrNull() else null,
            )
        }
        reload(debounce = false)
    }

    fun setQuery(q: String) { _state.update { it.copy(query = q) }; reload(debounce = true) }
    fun setSystem(id: String?) { _state.update { it.copy(systemId = id) }; reload(debounce = false) }
    fun setKind(kind: String?) { _state.update { it.copy(kind = kind) }; reload(debounce = false) }

    /** Tocar no gênero já ativo o desmarca. */
    fun toggleGenre(genre: Genre) {
        _state.update { it.copy(genre = if (it.genre == genre) null else genre) }
        reload(debounce = false)
    }

    /** Volta termo, gênero e console ao padrão da fonte atual (fontes que exigem console mantêm o 1º). */
    fun clearFilters() {
        val requiresSystem = sources.first { it.id == _state.value.sourceId }.requiresSystem
        _state.update {
            it.copy(
                query = "", genre = null, kind = "game",
                systemId = if (requiresSystem) app.catalog.source(it.sourceId).systems.firstOrNull() else null,
            )
        }
        reload(debounce = false)
    }

    fun retry() = reload(debounce = false)

    private fun reload(debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(350)
            _state.update { it.copy(entries = emptyList(), page = 0, totalPages = 1, loading = true, error = null) }
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

    private suspend fun fetch(page: Int, emptyStreak: Int = 0) {
        val s = _state.value
        // HTML/JSON das fontes é interpretado fora da thread principal: páginas grandes travavam a rolagem.
        val before = s.entries
        val job = coroutineContext[Job]
        val call = runCatching {
            withContext(Dispatchers.Default) {
                if (s.aggregated) {
                    app.catalog.searchAll(s.query, s.systemId, page, s.genre) { partial ->
                        // Cada site aparece assim que responde; o indicador de carga segue até o último.
                        if (job?.isActive == true) _state.update {
                            it.copy(entries = (before + partial.entries).distinctBy { e -> e.sourceId + e.id })
                        }
                    }
                } else {
                    app.catalog.search(app.catalog.source(s.sourceId), s.query, s.systemId, page, s.kind, s.genre)
                }
            }
        }
        call
            .onSuccess { result ->
                _state.update {
                    it.copy(
                        entries = (before + result.entries).distinctBy { e -> e.sourceId + e.id },
                        page = result.page, totalPages = result.totalPages, totalResults = result.totalResults,
                        loading = false,
                    )
                }
                // Página sem nada deste filtro (ex.: busca do CDRomance só com outros consoles): a lista
                // não cresce, a rolagem não pede mais e tudo parava. Segue para as próximas algumas vezes.
                if (result.entries.isEmpty() && result.page < result.totalPages && emptyStreak < MAX_EMPTY_PAGES) {
                    _state.update { it.copy(loading = true) }
                    fetch(result.page + 1, emptyStreak + 1)
                }
            }
            .onFailure { t ->
                if (t is kotlinx.coroutines.CancellationException) throw t
                android.util.Log.w("Explore", "Falha ao buscar catálogo", t)
                _state.update { it.copy(loading = false, error = R.string.explore_error_network) }
            }
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
