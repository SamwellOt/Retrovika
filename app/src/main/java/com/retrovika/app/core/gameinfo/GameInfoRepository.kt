package com.retrovika.app.core.gameinfo

/**
 * Busca e guarda por alguns minutos as informações externas de um jogo (Backloggd e Wikipedia):
 * voltar para uma página já aberta mostra tudo na hora, sem repetir os pedidos.
 */
class GameInfoRepository(
    private val backloggd: BackloggdClient = BackloggdClient(),
    private val wiki: WikiClient = WikiClient(),
) {
    private class Cached<T>(val value: T, val at: Long)

    private val backloggdCache = lru<Cached<BackloggdInfo?>>()
    private val wikiCache = lru<Cached<WikiInfo?>>()

    /** O jogo no Backloggd, ou nulo quando não há um jogo de mesmo nome no console. */
    suspend fun backloggd(title: String, systemId: String): BackloggdInfo? =
        cached(backloggdCache, "$systemId|${GameTitles.key(title)}") { backloggd.find(title, systemId) }

    /** O jogo na Wikipedia/Wikidata; [igdbSlug] (do Backloggd) acha o item exato. */
    suspend fun wiki(title: String, lang: String, igdbSlug: String?): WikiInfo? =
        cached(wikiCache, "$lang|${igdbSlug ?: GameTitles.key(title)}") { wiki.find(title, lang, igdbSlug) }

    private suspend fun <T> cached(cache: LinkedHashMap<String, Cached<T>>, key: String, load: suspend () -> T): T {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[key] }?.takeIf { now - it.at < TTL_MS }?.let { return it.value }
        val value = load()
        synchronized(cache) { cache[key] = Cached(value, now) }
        return value
    }

    private fun <V> lru() = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>) = size > 40
    }

    private companion object {
        const val TTL_MS = 15 * 60 * 1000L
    }
}
