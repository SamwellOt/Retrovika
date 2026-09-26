package com.retrovika.app.ui.screens.explore

import androidx.annotation.StringRes
import com.retrovika.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retrovika.app.AppContainer
import com.retrovika.app.core.catalog.CatalogEntry
import com.retrovika.app.core.catalog.RomVariant
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val entries: List<CatalogEntry> = emptyList(),
    val page: Int = 0,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val loading: Boolean = false,
    @StringRes val error: Int? = null,
) {
    val canLoadMore get() = !loading && page < totalPages
    val aggregated get() = sourceId == ALL_SOURCES
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
        val call = runCatching {
            if (s.aggregated) app.catalog.searchAll(s.query, s.systemId, page)
            else app.catalog.source(s.sourceId).search(s.query, s.systemId, page, s.kind)
        }
        call
            .onSuccess { result ->
                _state.update {
                    it.copy(
                        entries = (it.entries + result.entries).distinctBy { e -> e.sourceId + e.id },
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
    fun requestDownload(entry: CatalogEntry) {
        viewModelScope.launch {
            _prompt.value = VariantPrompt.Loading(entry)
            val variants = runCatching { app.catalog.variants(entry) }.getOrElse { emptyList() }
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

    fun dismissPrompt() { _prompt.value = null }
}
