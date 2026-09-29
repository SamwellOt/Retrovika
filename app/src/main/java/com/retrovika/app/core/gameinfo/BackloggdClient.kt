package com.retrovika.app.core.gameinfo

import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.Urls
import com.retrovika.app.core.net.WebFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Backloggd (backloggd.com): rede de jogadores com nota média, distribuição das notas, quantas
 * pessoas jogaram ou têm o jogo no backlog, tempo de jogo e reviews. Não tem API pública; usamos o
 * mesmo autocompletar em JSON da busca do site e lemos a página do jogo, que é HTML simples.
 */
class BackloggdClient(
    /**
     * GET feito de dentro de um WebView (ver [WebFetcher]), para quando a CDN do site pede a
     * verificação em JavaScript. Nulo nos testes da JVM, onde não há WebView.
     */
    private val web: (suspend (String) -> String)? = null,
) {
    private val base = "https://backloggd.com"

    /** Cabeçalhos de navegador: o site devolve a página completa como a um visitante comum. */
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9",
    )

    /**
     * Até quando os pedidos vão direto pelo WebView depois de uma verificação (o OkHttp só levaria outro
     * 403). Expira: a CDN só pede a verificação a alguns IPs, e a rede do aparelho muda.
     */
    @Volatile
    private var viaWebUntil = 0L

    /** GET pelo OkHttp; se a CDN pedir a verificação (403), passa a usar o WebView por um tempo. */
    private suspend fun get(url: String): String {
        val fetch = web
        if (fetch != null && System.currentTimeMillis() < viaWebUntil) {
            try {
                return fetch(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // O WebView falhou: volta ao OkHttp e tenta uma vez por ele (a CDN pode ter liberado o IP).
                // Se vier outra verificação, o próximo pedido cai no caminho abaixo e reabre o WebView.
                viaWebUntil = 0L
                return Http.getString(url, headers)
            }
        }
        return try {
            Http.getString(url, headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (fetch == null || !WebFetcher.isChallenge(e)) throw e
            viaWebUntil = System.currentTimeMillis() + VIA_WEB_MS
            fetch(url)
        }
    }

    data class Suggestion(val slug: String, val title: String, val year: Int?)

    /**
     * Acha o jogo de [title] no console [systemId]. [igdbSlug] (do Wikidata) resolve direto quando
     * existe. Sem ele, entre os resultados com o mesmo título (há vários "Chrono Trigger": SNES, DS,
     * PS1…), fica o primeiro lançado que saiu no console; se o console não é conhecido, só um título
     * idêntico e único serve, para não mostrar a nota de outro jogo.
     */
    suspend fun find(title: String, systemId: String, igdbSlug: String? = null): BackloggdInfo? {
        // O slug vem do Wikidata, que pode apontar outra versão (a de outro console): só vale se o
        // console bate; senão segue pela busca por título, que escolhe entre as versões.
        igdbSlug?.let { slug ->
            catching { game(slug) }.getOrNull()
                ?.takeIf { !Platforms.knows(systemId) || Platforms.matches(systemId, emptyList(), it.platforms) }
                ?.let { return it }
        }
        val clean = GameTitles.clean(title).ifBlank { return null }
        val candidates = suggestions(clean).filter { GameTitles.sameRomaji(it.title, clean) }
            .sortedWith(compareBy(nullsLast<Int>()) { it.year })
        if (candidates.isEmpty()) return null
        if (!Platforms.knows(systemId)) return if (candidates.size == 1) game(candidates.first().slug) else null
        // Poucos candidatos, na ordem de lançamento (o original costuma ser o do console retrô), todos
        // pedidos ao mesmo tempo: esperar um por um somava quase meio segundo a cada título repetido.
        val top = candidates.take(MAX_CANDIDATES)
        val results = coroutineScope { top.map { c -> async { catching { page(c.slug) } } }.awaitAll() }
        val pages = results.map { it.getOrNull() }
        val index = pages.indexOfFirst { page -> page != null && Platforms.matches(systemId, platformSlugs(page), platformNames(page)) }
        // Alguma página falhou (rede, verificação da CDN) e nenhuma das outras bate: o jogo pode ser justo a
        // que falhou. É erro, não "o jogo não está lá", senão o "não encontrado" ficaria no cache por 15 minutos.
        if (index < 0) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        if (index < 0) return null
        return parseGame(pages[index]!!, top[index].slug).withReviews()
    }

    suspend fun suggestions(query: String): List<Suggestion> {
        val json = Http.json.parseToJsonElement(
            get(Urls.withQuery("$base/autocomplete.json", listOf("query" to query))),
        ).jsonObject
        return json["suggestions"]?.jsonArray.orEmpty().mapNotNull { s ->
            val data = (s as? JsonObject)?.get("data")?.jsonObject ?: return@mapNotNull null
            val slug = data["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Suggestion(
                slug = slug,
                title = data["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                year = data["year"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            )
        }
    }

    suspend fun game(slug: String): BackloggdInfo = parseGame(page(slug), slug).withReviews()

    private suspend fun page(slug: String): Document =
        Jsoup.parse(get("$base/games/${Urls.encode(slug)}/"), base)

    /** As reviews chegam por um pedido à parte (o site as carrega depois da página). */
    private suspend fun BackloggdInfo.withReviews(): BackloggdInfo {
        val reviews = catching {
            parseReviews(get("$base/reviews/preview/${Urls.encode(slug)}/?sort_by=trending"))
        }
        // Sem as reviews a página ainda serve; reviewsFailed evita guardar no cache esse resultado incompleto.
        return copy(reviews = reviews.getOrDefault(emptyList()).take(MAX_REVIEWS), reviewsFailed = reviews.isFailure)
    }

    /** Como runCatching, mas o cancelamento (a página fechou) segue adiante em vez de virar falha. */
    private inline fun <T> catching(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    internal fun parseGame(html: String, slug: String): BackloggdInfo = parseGame(Jsoup.parse(html, base), slug)

    internal fun parseGame(doc: Document, slug: String): BackloggdInfo {
        // O bloco AggregateRating (JSON-LD) traz a média sem arredondar e o total de notas.
        val ld = doc.select("script[type=application/ld+json]").firstNotNullOfOrNull { s ->
            runCatching { Http.json.parseToJsonElement(s.data()).jsonObject }.getOrNull()
                ?.takeIf { it["@type"]?.jsonPrimitive?.contentOrNull == "AggregateRating" }
        }
        val rating = ld?.get("ratingValue")?.jsonPrimitive?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        val ratingCount = ld?.get("ratingCount")?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

        // A distribuição aparece duas vezes (layouts de celular e de computador): basta a primeira.
        val histogram = doc.selectFirst("#ratings-bars-height")?.select("[data-tippy-content]")?.mapNotNull {
            it.attr("data-tippy-content").substringBefore('|').trim().replace(",", "").toIntOrNull()
        }.orEmpty().takeIf { it.size == 10 }.orEmpty()

        fun counter(label: String): String? = doc.select(".log-counter-label").firstOrNull { it.text().trim().equals(label, true) }
            ?.closest("a")?.selectFirst(".log-counter-stat")?.text()?.trim()?.ifBlank { null }

        fun stat(label: String): String? = doc.select(".time-section a p").firstOrNull { it.text().trim().equals(label, true) }
            ?.closest("a")?.selectFirst("h3")?.text()?.trim()?.ifBlank { null }

        fun time(label: String): String? = doc.select(".time-played").firstOrNull { it.selectFirst(".label")?.text()?.trim().equals(label, true) }
            ?.selectFirst(".stat-value.element-revealed")?.text()?.replace(" ", "")?.ifBlank { null }

        val title = doc.selectFirst("#game-profile h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: slug
        val description = doc.selectFirst("#collapseSummary p")?.let { HtmlText.of(it, keepNewlines = true) }?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null }

        return BackloggdInfo(
            slug = slug,
            url = "$base/games/$slug/",
            title = title,
            year = doc.selectFirst("a.game-year")?.text()?.trim()?.ifBlank { null },
            releaseDate = doc.select("a[href*=release_year]").map { it.text().trim() }.firstOrNull { it.length > 4 },
            companies = doc.select(".game-subtitle a[href^=/company/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct(),
            genres = doc.select("a.game-details-value[href*=genre:]").map { it.text().trim() }.distinct(),
            platforms = platformNames(doc),
            description = description,
            coverUrl = doc.selectFirst(".game-cover img")?.let { img -> img.absUrl("data-src").ifBlank { img.absUrl("src") } }?.ifBlank { null },
            backdropUrl = doc.selectFirst("img[data-src*=t_1080p]")?.attr("data-src")?.let(::absolute),
            rating = rating,
            ratingCount = ratingCount,
            histogram = histogram,
            plays = counter("Plays"),
            playing = counter("Playing"),
            backlogs = counter("Backlogs"),
            wishlists = counter("Wishlists"),
            lists = stat("Lists"),
            reviewCount = stat("Reviews"),
            likes = stat("Likes"),
            timeAverage = time("average"),
            timeToFinish = time("to finish"),
            timeToMaster = time("to master"),
            igdbUrl = doc.selectFirst("a[href^=https://www.igdb.com/games/]")?.attr("href"),
        )
    }

    internal fun parseReviews(html: String): List<BackloggdReview> =
        Jsoup.parse(html, base).select(".review-card").mapNotNull { card ->
            val text = card.selectFirst(".card-text")?.let { HtmlText.of(it) }?.trim().orEmpty()
            if (text.isBlank()) return@mapNotNull null
            // A nota é a largura das estrelas preenchidas: 100% = 5★, 50% = 2,5★.
            val width = card.selectFirst(".stars-top")?.attr("style")?.let { Regex("""width:\s*([\d.]+)%""").find(it) }
                ?.groupValues?.get(1)?.toDoubleOrNull()
            BackloggdReview(
                user = card.selectFirst(".username-link")?.text()?.trim().orEmpty().ifBlank { "?" },
                avatarUrl = card.selectFirst("#avatar img")?.absUrl("src")?.ifBlank { null },
                rating = width?.let { Math.round(it / 10.0) / 2.0 }?.takeIf { it > 0 },
                status = card.selectFirst(".play-type")?.text()?.trim()?.ifBlank { null },
                platform = card.selectFirst(".review-platform")?.text()?.trim()?.ifBlank { null },
                date = card.selectFirst("time[datetime]")?.attr("datetime")?.ifBlank { null },
                text = text,
                likes = card.selectFirst(".like-counter")?.text()?.filter(Char::isDigit)?.toIntOrNull(),
                url = card.selectFirst("a.open-review-link")?.absUrl("href")?.ifBlank { null },
            )
        }

    private fun platformNames(doc: Document): List<String> =
        doc.select("#game-page-platforms a.game-page-platform").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

    private fun platformSlugs(doc: Document): List<String> =
        doc.select("#game-page-platforms a.game-page-platform").mapNotNull { a ->
            Regex("""release_platform:([^/]+)""").find(a.attr("href"))?.groupValues?.get(1)
        }.distinct()

    private fun absolute(url: String): String = if (url.startsWith("//")) "https:$url" else url

    private companion object {
        const val MAX_CANDIDATES = 4
        const val MAX_REVIEWS = 6
        const val VIA_WEB_MS = 10 * 60 * 1000L
    }
}
