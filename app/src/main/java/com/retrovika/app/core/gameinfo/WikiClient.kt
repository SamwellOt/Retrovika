package com.retrovika.app.core.gameinfo

import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.Urls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wikidata + Wikipedia: ficha técnica do jogo (publicadora, série, direção, trilha sonora,
 * classificação etária), notas da crítica (Metacritic, Famitsu…), links para outras bases
 * (HowLongToBeat, MobyGames, IGDB) e o resumo do artigo no idioma do app. APIs abertas, sem chave.
 */
class WikiClient {
    private val wikidata = "https://www.wikidata.org/w/api.php"
    private val headers = mapOf("User-Agent" to "Retrovika/0.2 (Android; https://github.com/SamwellOt/Retrovika)")

    /**
     * [igdbSlug] (vindo do Backloggd) acha o item exato pelo ID do IGDB. Sem ele, busca pelo título e
     * aceita só um item de mesmo nome descrito como jogo.
     */
    suspend fun find(title: String, lang: String, igdbSlug: String? = null): WikiInfo? {
        val id = igdbSlug?.let { runCatching { byIgdb(it) }.getOrNull() }
            ?: GameTitles.clean(title).takeIf { it.isNotBlank() }?.let { byTitle(it) }
            ?: try { otherTitle(title)?.item } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            ?: return null
        return entity(id, lang)
    }

    /** Como a Wikipedia em inglês chama o jogo: título, item do Wikidata e slug do IGDB (= Backloggd). */
    data class OtherTitle(val title: String, val item: String?, val igdbSlug: String?)

    /**
     * Acha o nome em inglês de um jogo que o site de ROM escreveu de outro jeito, em geral o título
     * japonês romanizado ("Hana to Taiyou to Ame to" é "Flower, Sun, and Rain"). O artigo da Wikipedia
     * em inglês cita a romanização logo na abertura ("Hepburn: Hana to Taiyō to Ame to"); só vale um
     * artigo de jogo em que o título pedido é um desses outros nomes inteiro, não só uma parte do
     * texto: "Doraemon" não pode virar um "Doraemon 3" que cita o primeiro. Nulo quando não acha ou
     * quando o nome é o mesmo.
     */
    suspend fun otherTitle(title: String): OtherTitle? {
        val clean = GameTitles.clean(title).ifBlank { return null }
        val wanted = GameTitles.romajiKey(clean)
        if (wanted.length < 4) return null
        // A busca da Wikipedia tira acentos ("Taiyō" vira "taiyo") mas não junta "ou": a segunda
        // tentativa usa a forma sem vogais longas.
        for (query in listOf(clean, wanted).distinctBy { it.lowercase() }) {
            val page = searchArticles(query).firstOrNull { p ->
                val isGame = "game" in p.description.lowercase() || "video game" in p.intro.lowercase()
                isGame && GameTitles.knownAs(p.intro.take(INTRO_CHARS)).any { GameTitles.romajiKey(it) == wanted }
            } ?: continue
            val name = GameTitles.clean(page.title)
            if (GameTitles.same(name, clean)) return null
            val igdb = page.item?.let { runCatching { igdbOf(it) }.getOrNull() }
            return OtherTitle(name, page.item, igdb)
        }
        return null
    }

    private class Article(val title: String, val description: String, val intro: String, val item: String?)

    /** Busca na Wikipedia em inglês; cada resultado já vem com a abertura, a descrição curta e o item. */
    private suspend fun searchArticles(query: String): List<Article> {
        val url = Urls.withQuery("https://en.wikipedia.org/w/api.php", listOf(
            "action" to "query", "generator" to "search", "gsrsearch" to query, "gsrnamespace" to "0",
            "gsrlimit" to "5", "prop" to "extracts|description|pageprops", "exintro" to "1",
            "explaintext" to "1", "exlimit" to "max", "ppprop" to "wikibase_item", "redirects" to "1",
            "format" to "json", "formatversion" to "2",
        ))
        val pages = json(url)["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty().map { it.jsonObject }
        // A lista não vem na ordem da busca: "index" é a posição do resultado.
        return pages.sortedBy { it["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE }.mapNotNull { p ->
            Article(
                title = p["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                description = p["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                intro = p["extract"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                item = p["pageprops"]?.jsonObject?.get("wikibase_item")?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private suspend fun igdbOf(item: String): String? {
        val url = Urls.withQuery(wikidata, listOf(
            "action" to "wbgetentities", "ids" to item, "props" to "claims", "format" to "json",
        ))
        val claims = json(url)["entities"]?.jsonObject?.get(item)?.jsonObject?.get("claims")?.jsonObject ?: return null
        return string(claims, IGDB)
    }

    private suspend fun byIgdb(slug: String): String? {
        val url = Urls.withQuery(wikidata, listOf(
            "action" to "query", "list" to "search", "srsearch" to "haswbstatement:P5794=$slug",
            "srlimit" to "1", "format" to "json",
        ))
        return json(url)["query"]?.jsonObject?.get("search")?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun byTitle(title: String): String? {
        val url = Urls.withQuery(wikidata, listOf(
            "action" to "wbsearchentities", "search" to title, "language" to "en", "type" to "item",
            "limit" to "7", "format" to "json",
        ))
        return json(url)["search"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { item ->
            val label = item["label"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val description = item["description"]?.jsonPrimitive?.contentOrNull.orEmpty().lowercase()
            GameTitles.same(label, title) && "game" in description
        }?.get("id")?.jsonPrimitive?.contentOrNull
    }

    private suspend fun entity(id: String, lang: String): WikiInfo? = coroutineScope {
        val url = Urls.withQuery(wikidata, listOf(
            "action" to "wbgetentities", "ids" to id, "props" to "claims|sitelinks|labels",
            "languages" to "$lang|en", "sitefilter" to "${lang}wiki|enwiki", "format" to "json",
        ))
        val entity = json(url)["entities"]?.jsonObject?.get(id)?.jsonObject ?: return@coroutineScope null
        val claims = entity["claims"]?.jsonObject ?: JsonObject(emptyMap())

        // O artigo no idioma do app, ou o inglês quando não há.
        val sitelinks = entity["sitelinks"]?.jsonObject
        val article = listOf(lang, "en").firstNotNullOfOrNull { l ->
            sitelinks?.get("${l}wiki")?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull?.let { l to it }
        }
        val summary = article?.let { (l, t) -> async { runCatching { summary(l, t) }.getOrNull() } }

        val itemProps = listOf(DEVELOPER, PUBLISHER, GENRE, MODE, SERIES, DIRECTOR, COMPOSER, PLATFORM, ESRB, PEGI, CERO, USK)
        val scores = claims[REVIEW_SCORE]?.jsonArray.orEmpty().mapNotNull { claim ->
            val score = value(claim)?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val reviewer = qualifierIds(claim, REVIEWER).firstOrNull() ?: return@mapNotNull null
            score to reviewer
        }
        val ids = (itemProps.flatMap { itemIds(claims, it) } + scores.map { it.second }).distinct()
        val labels = labels(ids, lang)
        fun names(prop: String) = itemIds(claims, prop).mapNotNull { labels[it] }.distinct()

        val igdb = claims[IGDB]?.jsonArray?.firstNotNullOfOrNull { value(it)?.jsonPrimitive?.contentOrNull }
        val links = buildList {
            string(claims, HLTB)?.let { add(ExternalLink("HowLongToBeat", "https://howlongtobeat.com/game/$it")) }
            (string(claims, MOBYGAMES)?.let { "https://www.mobygames.com/game/$it" }
                ?: string(claims, MOBYGAMES_OLD)?.let { "https://www.mobygames.com/game/$it" })
                ?.let { add(ExternalLink("MobyGames", it)) }
            (string(claims, METACRITIC)?.let { "https://www.metacritic.com/game/$it/" }
                ?: string(claims, METACRITIC_OLD)?.let { "https://www.metacritic.com/$it" })
                ?.let { add(ExternalLink("Metacritic", it)) }
            igdb?.let { add(ExternalLink("IGDB", "https://www.igdb.com/games/$it")) }
        }
        val ages = listOf(ESRB to "ESRB", PEGI to "PEGI", CERO to "CERO", USK to "USK").flatMap { (prop, board) ->
            names(prop).map { label -> if (label.contains(board, true)) label else "$board $label" }
        }

        val page = summary?.await()
        WikiInfo(
            entityId = id,
            title = page?.title ?: labelOf(entity, lang),
            extract = page?.extract,
            articleUrl = page?.url,
            articleLang = article?.first,
            imageUrl = page?.image,
            releaseDate = releaseDate(claims),
            developers = names(DEVELOPER),
            publishers = names(PUBLISHER),
            genres = names(GENRE),
            modes = names(MODE),
            series = names(SERIES),
            directors = names(DIRECTOR),
            composers = names(COMPOSER),
            platforms = names(PLATFORM),
            ageRatings = ages,
            scores = scores.mapNotNull { (score, reviewer) -> labels[reviewer]?.let { ReviewScore(score, it) } }.distinctBy { it.reviewer },
            links = links,
            igdbSlug = igdb,
        )
    }

    private class Summary(val title: String?, val extract: String?, val url: String?, val image: String?)

    private suspend fun summary(lang: String, title: String): Summary {
        val url = "https://$lang.wikipedia.org/api/rest_v1/page/summary/${Urls.encode(title.replace(' ', '_'))}"
        val page = json(url)
        val urls = page["content_urls"]?.jsonObject
        return Summary(
            title = page["title"]?.jsonPrimitive?.contentOrNull,
            extract = page["extract"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null },
            url = (urls?.get("mobile") ?: urls?.get("desktop"))?.jsonObject?.get("page")?.jsonPrimitive?.contentOrNull,
            image = page["originalimage"]?.jsonObject?.get("source")?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Nomes dos itens [ids] no idioma do app (ou em inglês), em lotes de até 50, o limite da API. */
    private suspend fun labels(ids: List<String>, lang: String): Map<String, String> {
        val out = HashMap<String, String>()
        ids.chunked(50).forEach { chunk ->
            val url = Urls.withQuery(wikidata, listOf(
                "action" to "wbgetentities", "ids" to chunk.joinToString("|"), "props" to "labels",
                "languages" to "$lang|en|mul", "format" to "json",
            ))
            json(url)["entities"]?.jsonObject?.forEach { (qid, e) -> labelOf(e.jsonObject, lang)?.let { out[qid] = it } }
        }
        return out
    }

    private fun labelOf(entity: JsonObject, lang: String): String? {
        val labels = entity["labels"]?.jsonObject ?: return null
        return listOf(lang, "en", "mul").firstNotNullOfOrNull { labels[it]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull }
            ?: labels.values.firstNotNullOfOrNull { it.jsonObject["value"]?.jsonPrimitive?.contentOrNull }
    }

    /** Primeira data de lançamento, com a precisão que o Wikidata tem (dia, mês ou só ano). */
    private fun releaseDate(claims: JsonObject): String? = claims[RELEASE]?.jsonArray.orEmpty().mapNotNull { claim ->
        val v = value(claim)?.jsonObject ?: return@mapNotNull null
        val time = v["time"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val precision = v["precision"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 11
        val date = Regex("""^\+?(\d{4})-(\d{2})-(\d{2})""").find(time)?.groupValues ?: return@mapNotNull null
        when {
            precision >= 11 -> "${date[1]}-${date[2]}-${date[3]}"
            precision == 10 -> "${date[1]}-${date[2]}"
            else -> date[1]
        }
    }.minOrNull()

    private fun value(claim: JsonElement): JsonElement? =
        claim.jsonObject["mainsnak"]?.jsonObject?.get("datavalue")?.jsonObject?.get("value")

    private fun itemIds(claims: JsonObject, prop: String): List<String> =
        claims[prop]?.jsonArray.orEmpty().mapNotNull { (value(it) as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull }

    private fun qualifierIds(claim: JsonElement, prop: String): List<String> =
        (claim.jsonObject["qualifiers"]?.jsonObject?.get(prop) as? JsonArray).orEmpty().mapNotNull {
            (it.jsonObject["datavalue"]?.jsonObject?.get("value") as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }

    private fun string(claims: JsonObject, prop: String): String? =
        claims[prop]?.jsonArray?.firstNotNullOfOrNull { value(it)?.jsonPrimitive?.contentOrNull }

    private suspend fun json(url: String): JsonObject = Http.json.parseToJsonElement(Http.getString(url, headers)).jsonObject

    private companion object {
        const val DEVELOPER = "P178"
        const val PUBLISHER = "P123"
        const val GENRE = "P136"
        const val MODE = "P404"
        const val SERIES = "P179"
        const val DIRECTOR = "P57"
        const val COMPOSER = "P86"
        const val PLATFORM = "P400"
        const val RELEASE = "P577"
        const val REVIEW_SCORE = "P444"
        const val REVIEWER = "P447"
        const val ESRB = "P852"
        const val PEGI = "P908"
        const val CERO = "P853"
        const val USK = "P914"
        const val HLTB = "P2816"
        const val MOBYGAMES = "P11688"
        const val MOBYGAMES_OLD = "P1933"
        const val IGDB = "P5794"
        const val METACRITIC = "P12054"
        const val METACRITIC_OLD = "P1712"

        /** Quanto da abertura do artigo conta para achar o título (a primeira frase, com folga). */
        const val INTRO_CHARS = 600
    }
}
