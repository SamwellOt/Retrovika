package com.retrovika.app.core.gameinfo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Busca e guarda por alguns minutos as informações externas de um jogo (Backloggd, Wikipedia e HowLongToBeat):
 * voltar para uma página já aberta mostra tudo na hora, sem repetir os pedidos.
 */
class GameInfoRepository(
    private val backloggd: BackloggdClient,
    private val wiki: WikiClient = WikiClient(),
    private val hltb: HowLongToBeatClient = HowLongToBeatClient(),
) {
    private class Cached<T>(val value: T, val at: Long)

    private val backloggdCache = lru<Cached<BackloggdInfo?>>()
    private val wikiCache = lru<Cached<WikiInfo?>>()
    private val hltbCache = lru<Cached<HltbInfo?>>()

    /**
     * O jogo no Backloggd, ou nulo quando não há um jogo de mesmo nome no console. O título original
     * vem primeiro (há jogos que só existem com o nome japonês, no site de ROM e no Backloggd); só
     * quando ele não acha nada, tenta o nome em inglês e o slug do IGDB pelo Wikidata/Wikipedia.
     */
    suspend fun backloggd(title: String, systemId: String): BackloggdInfo? =
        cached(backloggdCache, "$systemId|${GameTitles.key(title)}", keep = { it?.reviewsFailed != true }) {
            backloggd.find(title, systemId) ?: otherTitle(title)?.let { backloggd.find(it.title, systemId, it.igdbSlug) }
        }

    /**
     * O nome em inglês pelo Wikidata/Wikipedia. Falha deles vale como "sem outro nome", mas o cancelamento (a
     * página fechou) segue adiante: senão viraria um "não encontrado" guardado no cache.
     */
    private suspend fun otherTitle(title: String): WikiClient.OtherTitle? = try {
        wiki.otherTitle(title)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** O jogo na Wikipedia/Wikidata; [igdbSlug] (do Backloggd) acha o item exato. */
    suspend fun wiki(title: String, lang: String, igdbSlug: String?): WikiInfo? =
        cached(wikiCache, "$lang|${igdbSlug ?: GameTitles.key(title)}") { wiki.find(title, lang, igdbSlug) }

    /** Tempos de jogo do HowLongToBeat pelo ID que o Wikidata registra ([WikiInfo.hltbId]). */
    suspend fun howLongToBeat(id: String): HltbInfo? = cached(hltbCache, id) { hltb.game(id) }

    /** [keep] decide se o resultado vai para o cache (um resultado incompleto é pedido de novo na próxima vez). */
    private suspend fun <T> cached(
        cache: LinkedHashMap<String, Cached<T>>,
        key: String,
        keep: (T) -> Boolean = { true },
        load: suspend () -> T,
    ): T {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key] }?.takeIf { now - it.at < TTL_MS }?.let { return it.value }
        val value = load()
        // Cancelado no meio (a tela fechou), os clientes devolvem um resultado incompleto (sem resenhas, sem
        // resumo): esse não pode ficar no cache por 15 minutos.
        currentCoroutineContext().ensureActive()
        if (keep(value)) synchronized(cache) { cache[key] = Cached(value, now) }
        return value
    }

    private fun <V> lru() = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>) = size > 40
    }

    private companion object {
        const val TTL_MS = 15 * 60 * 1000L
    }
}
