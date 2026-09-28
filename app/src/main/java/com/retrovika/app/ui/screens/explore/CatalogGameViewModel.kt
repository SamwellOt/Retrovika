package com.retrovika.app.ui.screens.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.retrovika.app.AppContainer
import com.retrovika.app.core.catalog.CatalogEntry
import com.retrovika.app.core.catalog.RomVariant
import com.retrovika.app.core.catalog.basicDetails
import com.retrovika.app.core.gameinfo.BackloggdInfo
import com.retrovika.app.core.gameinfo.SourceDetails
import com.retrovika.app.core.gameinfo.WikiInfo
import com.retrovika.app.core.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uma parte da página que chega por conta própria: carregando, pronta (talvez vazia) ou com falha. */
sealed interface Part<out T> {
    data object Loading : Part<Nothing>
    data class Ready<T>(val value: T) : Part<T>
    data object Failed : Part<Nothing>
}

val <T> Part<T>.value: T? get() = (this as? Part.Ready)?.value

fun <T, R> Part<T>.map(transform: (T) -> R): Part<R> = when (this) {
    is Part.Ready -> Part.Ready(transform(value))
    Part.Loading -> Part.Loading
    Part.Failed -> Part.Failed
}

data class CatalogGameState(
    val entry: CatalogEntry,
    val details: Part<SourceDetails> = Part.Loading,
    /** Fonte que deu a ficha: a principal da entrada ou, se ela falhou, uma das páginas mescladas. */
    val detailsSourceId: String = entry.sourceId,
    val variants: Part<List<RomVariant>> = Part.Loading,
    val backloggd: Part<BackloggdInfo?> = Part.Loading,
    val wiki: Part<WikiInfo?> = Part.Loading,
) {
    /** A ficha da fonte, ou o que a busca já trouxe enquanto ela carrega (ou se falhou). */
    val source: SourceDetails get() = details.value ?: entry.basicDetails()

    /** Tamanho do download: o único arquivo, ou a faixa do menor ao maior quando há vários. */
    val sizes: List<Long> get() = variants.value.orEmpty().mapNotNull { it.sizeBytes?.takeIf { s -> s > 0 } }
}

/**
 * Página de um jogo do catálogo: a ficha da fonte, as ROMs para baixar e, em paralelo, o Backloggd
 * (nota, estatísticas, reviews) e a Wikipedia/Wikidata (resumo, ficha técnica, crítica). Cada parte
 * aparece quando chega; a falha de uma não esconde as outras.
 */
class CatalogGameViewModel(private val app: AppContainer, entry: CatalogEntry, private val lang: String) : ViewModel() {
    private val _state = MutableStateFlow(CatalogGameState(entry))
    val state: StateFlow<CatalogGameState> = _state.asStateFlow()

    private val _prompt = MutableStateFlow<VariantPrompt?>(null)
    val prompt: StateFlow<VariantPrompt?> = _prompt.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private var loadJob: Job? = null

    private fun load() {
        val entry = _state.value.entry
        // Um "tentar de novo" cancela a carga anterior: uma resposta atrasada dela não sobrescreve a nova.
        loadJob?.cancel()
        _state.update { CatalogGameState(entry) }
        loadJob = viewModelScope.launch { loadParts(entry) }
    }

    private fun CoroutineScope.loadParts(entry: CatalogEntry) {
        launch {
            val details = attempt { app.catalog.details(entry) }
            _state.update { it.copy(details = details.map { d -> d.second }, detailsSourceId = details.value?.first?.sourceId ?: entry.sourceId) }
        }
        launch {
            val variants = attempt { withSizes(app.catalog.variants(entry)) }
            _state.update { it.copy(variants = variants) }
        }
        launch {
            val backloggd = attempt { app.gameInfo.backloggd(entry.title, entry.systemId) }
            _state.update { it.copy(backloggd = backloggd) }
            // O slug do Backloggd é o do IGDB, que o Wikidata registra: acha o artigo certo mesmo com
            // títulos repetidos. Sem ele, a busca vai pelo nome.
            val wiki = attempt { app.gameInfo.wiki(entry.title, lang, backloggd.value?.slug) }
            _state.update { it.copy(wiki = wiki) }
        }
    }

    /** Arquivos sem tamanho na listagem (Homebrew Hub): pergunta ao servidor com um HEAD, sem baixar. */
    private suspend fun withSizes(variants: List<RomVariant>): List<RomVariant> {
        if (variants.none { it.sizeBytes == null } || variants.size > MAX_HEAD) return variants
        return coroutineScope {
            variants.map { v ->
                async { if (v.sizeBytes != null) v else v.copy(sizeBytes = runCatching { Http.contentLength(v.downloadUrl) }.getOrNull()) }
            }.awaitAll()
        }
    }

    private suspend fun <T> attempt(block: suspend () -> T): Part<T> = try {
        Part.Ready(withContext(Dispatchers.Default) { block() })
    } catch (c: CancellationException) {
        // Um tempo esgotado lá dentro também é CancellationException: só repassa se esta corrotina foi cancelada.
        currentCoroutineContext().ensureActive()
        android.util.Log.w("CatalogGame", "Tempo esgotado ao carregar parte da página", c)
        Part.Failed
    } catch (t: Throwable) {
        android.util.Log.w("CatalogGame", "Falha ao carregar parte da página", t)
        Part.Failed
    }

    private var promptJob: Job? = null

    /**
     * Botão principal: com um arquivo só, baixa direto; com vários, abre o seletor. As variantes já
     * carregadas pela página são reaproveitadas, então o seletor abre sem esperar.
     */
    fun download() {
        val entry = _state.value.entry
        val ready = _state.value.variants.value
        when {
            ready != null && ready.size == 1 -> app.downloads.enqueue(entry, ready.first())
            ready != null && ready.size > 1 -> _prompt.value = VariantPrompt.Ready(entry, ready)
            else -> {
                promptJob?.cancel()
                promptJob = viewModelScope.launch {
                    _prompt.value = VariantPrompt.Loading(entry)
                    val variants = runCatching { withContext(Dispatchers.Default) { app.catalog.variants(entry) } }
                        .onFailure { if (it is CancellationException) throw it }.getOrDefault(emptyList())
                    when {
                        variants.isEmpty() -> { _prompt.value = null; app.downloads.enqueue(entry) }
                        variants.size == 1 -> { _prompt.value = null; app.downloads.enqueue(entry, variants.first()) }
                        else -> _prompt.value = VariantPrompt.Ready(entry, variants)
                    }
                }
            }
        }
    }

    fun download(variant: RomVariant) {
        app.downloads.enqueue(_state.value.entry, variant)
        _prompt.value = null
    }

    fun dismissPrompt() {
        promptJob?.cancel()
        _prompt.value = null
    }

    private companion object {
        const val MAX_HEAD = 4
    }
}
