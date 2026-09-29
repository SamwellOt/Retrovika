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
    private val wikipedia = "https://en.wikipedia.org/w/api.php"
    private val headers = mapOf("User-Agent" to "Retrovika/0.2 (Android; https://github.com/SamwellOt/Retrovika)")

    /**
     * [igdbSlug] (vindo do Backloggd) acha o item exato pelo ID do IGDB. Sem ele, busca pelo título e
     * aceita só um item de mesmo nome descrito como jogo.
     */
    suspend fun find(title: String, lang: String, igdbSlug: String? = null): WikiInfo? {
        // runCatching engoliria o cancelamento (a página fechou) e seguiria buscando por título à toa.
        val bySlug = igdbSlug?.let {
            try { Result.success(byIgdb(it)) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
        }
        val id = bySlug?.getOrNull()
            ?: GameTitles.clean(title).takeIf { it.isNotBlank() }?.let { byTitle(it) }
            ?: try { otherTitle(title)?.item } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            // Falha de rede no slug sem nada achado pelo título: é erro, não "sem artigo" guardado no cache.
            ?: bySlug?.exceptionOrNull()?.let { throw it }
            ?: return null
        return entity(id, lang)
    }

    /** Outro nome do jogo, com o item do Wikidata e o slug do IGDB (= Backloggd) quando há. */
    data class OtherTitle(val title: String, val item: String?, val igdbSlug: String?)

    /**
     * O nome em inglês de um jogo que o site de ROM escreveu de outro jeito, em geral o título japonês
     * romanizado ("Bokujou Monogatari" é "Harvest Moon"; "Hana to Taiyou to Ame to", "Flower, Sun, and
     * Rain"). Três fontes, da mais segura para a menos, comparadas contra títulos reais no workflow
     * `catalog-speed` (`english_title_probe.py`):
     * 1. Wikidata: os nomes japoneses ficam como apelidos do item do jogo, que também traz o slug do
     *    IGDB. Só vale o apelido ou rótulo inteiro, não o começo ("Rockman X" não é "Rockman X4").
     * 2. Wikipedia: título ou redirecionamento exato ("Seiken Densetsu 3" leva a "Trials of Mana").
     * 3. Wikipedia, busca pela frase: só o artigo de jogo cuja predefinição `{{Nihongo}}` da abertura
     *    traz a romanização pedida ("Hana to Taiyō to Ame to"). A busca solta pegava a continuação ou
     *    o derivado ("Rockman X" virava "Mega Man X DiVE").
     * Nulo quando nada disso acha outro nome nem o slug do IGDB.
     */
    suspend fun otherTitle(title: String): OtherTitle? {
        val clean = GameTitles.clean(title).ifBlank { return null }
        val wanted = GameTitles.romajiKey(clean)
        if (wanted.length < 4) return null
        val variants = GameTitles.romajiVariants(clean)
        fun useful(o: OtherTitle?) = o?.takeIf { !GameTitles.same(it.title, clean) || it.igdbSlug != null }
        return useful(byAlias(variants, wanted))
            ?: useful(byRedirect(variants))
            ?: useful(byPhrase(clean, wanted))
    }

    /** Item de jogo do Wikidata cujo rótulo ou apelido é o título pedido. */
    private suspend fun byAlias(variants: List<String>, wanted: String): OtherTitle? {
        for (query in variants) {
            val url = Urls.withQuery(wikidata, listOf(
                "action" to "wbsearchentities", "search" to query, "language" to "en", "type" to "item",
                "limit" to "7", "format" to "json",
            ))
            val hit = json(url)["search"]?.jsonArray.orEmpty().map { it.jsonObject }.firstOrNull { item ->
                val matched = item["match"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                isGameDescription(item["description"]?.jsonPrimitive?.contentOrNull) && GameTitles.romajiKey(matched) == wanted
            } ?: continue
            val id = hit["id"]?.jsonPrimitive?.contentOrNull ?: continue
            val label = hit["label"]?.jsonPrimitive?.contentOrNull ?: continue
            return OtherTitle(label, id, runCatching { igdbOf(id) }.getOrNull())
        }
        return null
    }

    /** Artigo de jogo da Wikipedia com esse título, ou para onde esse título redireciona. */
    private suspend fun byRedirect(variants: List<String>): OtherTitle? {
        val pages = pages(listOf("titles" to variants.joinToString("|"), "redirects" to "1"))
        val page = pages.firstOrNull { it.isGame } ?: return null
        return OtherTitle(GameTitles.clean(page.title), page.item, page.item?.let { runCatching { igdbOf(it) }.getOrNull() })
    }

    /**
     * Busca pela frase e aceita o artigo de jogo que se declara com esse nome japonês: a predefinição
     * `{{Nihongo|<inglês>|<kanji>|<romaji>}}` da abertura traz a romanização pedida. Achar a frase em
     * qualquer ponto do texto não basta ("Rockman X" aparece no artigo de "Mega Man X DiVE").
     */
    private suspend fun byPhrase(clean: String, wanted: String): OtherTitle? {
        val checked = HashSet<String>()
        // A busca tira acentos ("Taiyō" vira "taiyo") mas não junta "ou": a segunda usa a forma sem
        // vogais longas.
        for (query in listOf(GameTitles.key(clean), wanted).distinct()) {
            val url = Urls.withQuery(wikipedia, listOf(
                "action" to "query", "list" to "search", "srsearch" to "\"$query\"", "srnamespace" to "0",
                "srlimit" to "5", "srprop" to "", "format" to "json", "formatversion" to "2",
            ))
            val titles = json(url)["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty()
                .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }
            if (titles.isEmpty()) continue
            val byTitle = pages(listOf("titles" to titles.joinToString("|"))).associateBy { it.title }
            val games = titles.mapNotNull { byTitle[it]?.takeIf { p -> p.isGame } }.filter { checked.add(it.title) }
            for (page in games.take(MAX_LEADS)) {
                val names = runCatching { nihongoNames(leadWikitext(page.title)) }.getOrDefault(emptyList())
                if (names.any { GameTitles.romajiKey(GameTitles.clean(it)) == wanted }) {
                    return OtherTitle(GameTitles.clean(page.title), page.item, page.item?.let { runCatching { igdbOf(it) }.getOrNull() })
                }
            }
        }
        return null
    }

    /** O wikitexto da abertura (seção 0) do artigo. */
    private suspend fun leadWikitext(title: String): String {
        val url = Urls.withQuery(wikipedia, listOf(
            "action" to "parse", "page" to title, "prop" to "wikitext", "section" to "0",
            "format" to "json", "formatversion" to "2",
        ))
        return json(url)["parse"]?.jsonObject?.get("wikitext")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private class Page(val title: String, val isGame: Boolean, val item: String?)

    /** Páginas da Wikipedia em inglês com a descrição curta e o item do Wikidata. */
    private suspend fun pages(params: List<Pair<String, String>>): List<Page> {
        val url = Urls.withQuery(wikipedia, listOf(
            "action" to "query", "prop" to "description|pageprops", "ppprop" to "wikibase_item",
            "format" to "json", "formatversion" to "2",
        ) + params)
        return json(url)["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty().map { it.jsonObject }
            .filter { it["missing"] == null }
            .mapNotNull { p ->
                Page(
                    title = p["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    isGame = isGameDescription(p["description"]?.jsonPrimitive?.contentOrNull),
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
        val hltb = string(claims, HLTB)
        val links = buildList {
            hltb?.let { add(ExternalLink("HowLongToBeat", "https://howlongtobeat.com/game/$it")) }
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
            hltbId = hltb,
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
    }.minByOrNull { it.padEnd(10, '~') }  // Só o ano ("1994") não pode vencer uma data completa do mesmo ano.

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

    internal companion object {
        /** Descrição curta de um jogo ("1993 video game"); séries, empresas e listas ficam de fora. */
        fun isGameDescription(description: String?): Boolean {
            val d = description.orEmpty().trim().lowercase()
            return "game" in d && listOf("series", "franchise", "company", "developer", "publisher", "list").none { it in d }
        }

        /** Quantos artigos de jogo de cada busca têm a abertura lida. */
        private const val MAX_LEADS = 2

        private val nihongo = Regex("""\{\{\s*nihongo[\w ]*\|""", RegexOption.IGNORE_CASE)
        private val namedParam = Regex("""^\s*[\w-]+\s*=""")
        private val innerTemplate = Regex("""\{\{[^{}]*}}""")
        private val wikiLink = Regex("""\[\[(?:[^|\]]*\|)?([^\]]*)]]""")

        /**
         * Os três primeiros parâmetros (inglês, kanji, romaji) de cada `{{Nihongo…}}` do wikitexto,
         * sem marcação. Conta chaves e colchetes: a predefinição pode trazer outras dentro, como em
         * `{{Nihongo foot|''Flower, Sun, and Rain''|花と太陽と雨と|Hana to Taiyō to Ame to|…{{nihongo|…}}}}`.
         */
        fun nihongoNames(wikitext: String): List<String> {
            val out = mutableListOf<String>()
            var from = 0
            while (true) {
                val m = nihongo.find(wikitext, from) ?: break
                from = m.range.last + 1
                val params = mutableListOf(StringBuilder())
                var braces = 1
                var brackets = 0
                var i = from
                while (i < wikitext.length && braces > 0) {
                    val two = wikitext.substring(i, minOf(i + 2, wikitext.length))
                    when {
                        two == "{{" -> { braces++; params.last().append(two); i += 2 }
                        two == "}}" -> { braces--; if (braces > 0) params.last().append(two); i += 2 }
                        two == "[[" -> { brackets++; params.last().append(two); i += 2 }
                        two == "]]" -> { brackets--; params.last().append(two); i += 2 }
                        wikitext[i] == '|' && braces == 1 && brackets == 0 -> { params.add(StringBuilder()); i++ }
                        else -> { params.last().append(wikitext[i]); i++ }
                    }
                }
                params.map { it.toString() }.filterNot { namedParam.containsMatchIn(it) }.take(3).forEach { raw ->
                    var t = raw
                    repeat(3) { t = t.replace(innerTemplate, "") }
                    t = t.replace(wikiLink, "$1").replace("'''", "").replace("''", "").trim()
                    if (t.isNotEmpty()) out += t
                }
            }
            return out
        }

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
    }
}
