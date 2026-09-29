package com.retrovika.app.core.gameinfo

import com.retrovika.app.core.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * HowLongToBeat: quanto tempo os jogadores levaram para terminar o jogo. O ID vem do Wikidata (P2816, ver
 * [WikiClient]); a página do jogo (`/game/<id>`) traz tudo no JSON do Next.js (`__NEXT_DATA__`), sem chave e
 * sem a verificação que a busca do site exige.
 */
class HowLongToBeatClient(private val fetch: suspend (String) -> String = { Http.getString(it, HEADERS) }) {

    suspend fun game(id: String): HltbInfo? = parse(id, fetch("$BASE/game/$id"))

    /** Os tempos da página; null quando nenhum tem jogadores suficientes (jogo recém-cadastrado). */
    internal fun parse(id: String, html: String): HltbInfo? {
        val json = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return null
        val game = runCatching {
            Http.json.parseToJsonElement(json).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject["game"]!!
                .jsonObject["data"]!!.jsonObject["game"]!!.jsonArray.first().jsonObject
        }.getOrNull() ?: return null

        fun long(key: String) = game[key]?.jsonPrimitive?.longOrNull ?: 0L
        val times = HltbTime.Kind.entries.mapNotNull { kind ->
            val seconds = long(kind.key)
            val count = long(kind.key + "_count")
            if (seconds > 0 && count > 0) HltbTime(kind, seconds, count) else null
        }
        if (times.isEmpty()) return null
        return HltbInfo(id = id, url = "$BASE/game/$id", title = game.string("game_name"), times = times)
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content?.ifBlank { null }

    private companion object {
        const val BASE = "https://howlongtobeat.com"
        val NEXT_DATA = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
        // Sem um User-Agent de navegador o site responde 403.
        val HEADERS = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
    }
}

@Serializable
data class HltbInfo(val id: String, val url: String, val title: String?, val times: List<HltbTime>)

/** Um tempo do HowLongToBeat: [seconds] é a média que o site mostra, de [count] jogadores. */
@Serializable
data class HltbTime(val kind: Kind, val seconds: Long, val count: Long) {
    /** Na ordem da página do site. [key] é o campo do JSON (a contagem é `<key>_count`). */
    enum class Kind(val key: String) {
        MAIN("comp_main"),
        EXTRAS("comp_plus"),
        COMPLETIONIST("comp_100"),
        ALL_STYLES("comp_all"),
        SPEEDRUN("comp_speed"),
    }
}
