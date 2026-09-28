package com.retrovika.app.core.catalog

import com.retrovika.app.R
import com.retrovika.app.core.gameinfo.HtmlText
import com.retrovika.app.core.gameinfo.SiteRating
import com.retrovika.app.core.gameinfo.SourceDetails
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.HttpStatusException
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.net.Urls
import com.retrovika.app.core.net.WebFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * RomsFun (romsfun.com): acervo grande, com consoles que o CDRomance não tem (3DS, Wii, arcade,
 * 3DO, Amiga, MSX, Jaguar, WonderSwan, DOS…) e hacks/traduções dos que ele tem.
 *
 * O site inteiro fica atrás da verificação do Cloudflare (o OkHttp recebe 403), então as páginas vêm por
 * um [WebFetcher] próprio. A busca é a `/browse-all-roms/`, que aceita termo (`q`), consoles e gêneros
 * (ids da taxonomia do site) juntos; os cartões, com capa, são lidos com Jsoup.
 *
 * O download tem três passos:
 * 1. a página do jogo aponta para `/download/<slug>-<id>`, que lista os arquivos (regiões, formatos);
 * 2. cada arquivo tem uma página `/download/<slug>-<id>/<n>`, cujo link assinado sai de um AJAX
 *    (`action=k_get_download`, com a página como Referer). O `href` que já vem no HTML é uma isca que o
 *    servidor recusa. O link vale por algumas horas e só para o IP que o pediu, então é gerado dentro do
 *    download ([directLink]), não ao listar;
 * 3. o arquivo vem de outro domínio (`sto*.romsforever.co`, `sto.romsfast.com`), às vezes com verificação
 *    própria. Quem baixa é o OkHttp, com o User-Agent e o `cf_clearance` do WebView e pelo mesmo
 *    protocolo (IPv4/IPv6) que o WebView usou.
 *
 * Os .zip/.7z do site podem ter a senha publicada por ele; o [com.retrovika.app.core.storage.RomExtractor] a conhece.
 */
class RomsFunSource(private val web: WebFetcher) : CatalogSource {
    override val id = "romsfun"
    override val name = "RomsFun"
    override val description = R.string.catalog_romsfun_description

    override val systems: Set<String> = CONSOLES.map { it.systemId }.toSet()

    override fun sorts(systemId: String?): Set<SortOrder> = SORTS.keys

    override suspend fun search(query: String, systemId: String?, page: Int, kind: String?, genre: Genre?, sort: SortOrder): CatalogPage {
        val consoles = if (systemId != null) CONSOLES.filter { it.systemId == systemId } else CONSOLES
        if (consoles.isEmpty()) return CatalogPage(emptyList(), page, 1, 0)
        val res = web.request(BASE, searchUrl(query, consoles.map { it.termId }, genre, page, sort))
        // Página além da última.
        if (res.status == 404 && page > 1) return CatalogPage(emptyList(), page, page, 0)
        if (res.status !in 200..299) throw LocalizedException(R.string.download_http_error, res.status, BASE)
        val doc = Jsoup.parse(res.body, BASE)
        val entries = parseCards(doc).let { list -> if (systemId != null) list.filter { it.systemId == systemId } else list }
        val pages = totalPages(doc, page)
        return CatalogPage(entries, page, pages, totalResults(doc) ?: entries.size, approximate = totalResults(doc) == null)
    }

    /**
     * `/browse-all-roms/` com os filtros. Sem console escolhido vão todos os que o app roda: assim nenhuma
     * página chega cheia de jogos de PS3 ou Xbox que seriam descartados. Sem termo nem [sort], os mais
     * populares; com termo, a relevância do site.
     */
    internal fun searchUrl(query: String, consoleIds: List<Int>, genre: Genre?, page: Int, sort: SortOrder = SortOrder.DEFAULT): String {
        val params = buildList {
            query.trim().takeIf { it.isNotBlank() }?.let { add("q" to it) }
            consoleIds.distinct().forEach { add("consoles[]" to it.toString()) }
            genre?.let { g -> GENRE_TERMS[g].orEmpty().forEach { add("genres[]" to it.toString()) } }
            (SORTS[sort] ?: "popular".takeIf { query.isBlank() })?.let { add("sort" to it) }
        }
        return Urls.withQuery("$BASE/browse-all-roms/" + (if (page > 1) "page/$page/" else ""), params)
    }

    internal fun parseCards(html: String): List<CatalogEntry> = parseCards(Jsoup.parse(html, BASE))

    /**
     * Cartões de jogo: `h3 > a` com o título e o link `/roms/<pasta>/<jogo>.html`, a capa num `a > img` com o
     * mesmo link e o selo do console (`a[href=/roms/<console>/]`). Consoles que o app não roda ficam de fora.
     */
    private fun parseCards(doc: Document): List<CatalogEntry> =
        doc.select("h3 > a[href*=/roms/]").mapNotNull { link ->
            val gameUrl = link.absUrl("href").takeIf { GAME_URL.matches(it) } ?: return@mapNotNull null
            // O cartão é o menor bloco que tem também o link do jogo com a capa. Há dois layouts (lista e
            // "Latest ROMs" em carrossel), então não dá para confiar nas classes.
            val coverImg = link.parents().firstNotNullOfOrNull { p ->
                p.select("a[href] > img").firstOrNull { it.parent()!!.absUrl("href") == gameUrl }
            }
            val card = coverImg?.let { img -> img.parents().first { it.select("h3 > a").contains(link) } } ?: link.parent()!!
            val badge = card.select("a[href]").firstOrNull { CONSOLE_URL.matches(it.absUrl("href")) }
            val console = badge?.absUrl("href")?.let { CONSOLE_URL.find(it)?.groupValues?.get(1) }
            val folder = GAME_URL.find(gameUrl)!!.groupValues[1]
            val systemId = console?.let { SYSTEM_BY_SLUG[it] } ?: SYSTEM_BY_SLUG[folder] ?: return@mapNotNull null
            val title = link.text().trim().ifBlank { gameUrl.substringAfterLast('/').removeSuffix(".html").replace('-', ' ') }
            CatalogEntry(
                id = gameUrl,
                sourceId = id,
                title = title,
                systemId = systemId,
                developer = null,
                coverUrl = coverImg?.absUrl("src")?.takeIf { it.isNotBlank() },
                screenshots = emptyList(),
                tags = listOfNotNull(badge?.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() }),
                website = gameUrl,
                downloadUrl = gameUrl, // resolvido em variants()/directLink()
                fileName = gameUrl.substringAfterLast('/').removeSuffix(".html") + ".7z",
                kind = "game",
            )
        }

    override suspend fun details(entry: CatalogEntry): SourceDetails = parseDetails(gamePage(entry.id), entry)

    /**
     * Cabeçalho (nota, downloads, gênero), a tabela de informações (lançamento, publicadora, desenvolvedora,
     * região), a descrição e a galeria de capturas.
     */
    internal fun parseDetails(html: String, entry: CatalogEntry): SourceDetails {
        val doc = Jsoup.parse(html, BASE)
        val rows = doc.select("table tr").mapNotNull { tr ->
            val cells = tr.select("td")
            if (cells.size != 2) return@mapNotNull null
            cells[0].text().trim().lowercase() to cells[1]
        }.toMap()
        fun text(vararg names: String): String? = names.firstNotNullOfOrNull { rows[it] }?.text()?.trim()?.ifBlank { null }
        fun list(vararg names: String): List<String> = names.firstNotNullOfOrNull { rows[it] }
            ?.let { td -> td.select("a").map { it.text().trim() }.ifEmpty { td.text().split(',') } }
            ?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()

        val header = doc.selectFirst("h1")?.parent()
        val headerText = header?.text().orEmpty()
        val rating = doc.selectFirst(".rating[data-rateyo-rating]")?.attr("data-rateyo-rating")?.toDoubleOrNull()?.let { value ->
            val count = Regex("""\((\d[\d,]*) reviews?\)""").find(headerText)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            if (count != null && count > 0) SiteRating(value, 5.0, count) else null
        }
        return SourceDetails(
            title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: entry.title,
            description = doc.selectFirst(".page-content")?.let { HtmlText.of(it) }?.ifBlank { null },
            // Algumas páginas trazem a capa em http://, que o Android bloqueia (o site responde igual em https).
            coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }
                ?.replaceFirst(Regex("^http://"), "https://") ?: entry.coverUrl,
            screenshots = doc.select("#romGallery a[data-src]").mapNotNull { it.absUrl("data-src").ifBlank { null } },
            releaseDate = text("release date", "year"),
            developers = list("developer", "manufacturer"),
            publisher = text("publisher"),
            genres = header?.select("a[href*=genres]")?.map { it.text().trim() }?.filter { it.isNotBlank() }.orEmpty(),
            region = text("region"),
            downloads = Regex("""([\d,]+)\s+downloads""").find(headerText)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull(),
            rating = rating,
            tags = entry.tags,
            website = entry.website,
        )
    }

    /** Página do jogo → página de download → tabela de arquivos (uma variante por linha). */
    override suspend fun variants(entry: CatalogEntry): List<RomVariant> {
        val game = Jsoup.parse(gamePage(entry.id), BASE)
        val indexUrl = game.select("a[href*=/download/]").map { it.absUrl("href") }.firstOrNull { DOWNLOAD_INDEX.matches(it) }
            ?: throw LocalizedException(R.string.catalog_no_downloads, entry.id)
        val html = page(indexUrl)
        val variants = parseVariants(html, entry.systemId)
        if (variants.isNotEmpty()) return variants
        // Tabela só com .cia (temas e alguns jogos do 3DS): nada que o núcleo rode.
        if (Jsoup.parse(html, BASE).selectFirst("tr a[href*=/download/]") != null) throw LocalizedException(R.string.romsfun_only_cia)
        // Sem tabela: jogo de um arquivo só, e a própria página de download já é a do arquivo.
        return listOf(RomVariant(fileName = entry.fileName, downloadUrl = "${indexUrl.trimEnd('/')}/1", label = entry.title))
    }

    internal fun parseVariants(html: String, systemId: String): List<RomVariant> {
        val doc = Jsoup.parse(html, BASE)
        return doc.select("tr:has(a[href*=/download/])").mapNotNull { row ->
            val a = row.selectFirst("a[href*=/download/]") ?: return@mapNotNull null
            val url = a.absUrl("href").takeIf { DOWNLOAD_FILE.matches(it) } ?: return@mapNotNull null
            val cells = row.select("td")
            val name = a.text().trim().ifBlank { return@mapNotNull null }
            val type = cells.getOrNull(1)?.text()?.trim()?.takeIf { it.isNotBlank() }
            // O núcleo de 3DS não instala .cia: só as cópias "Decrypted" (.3ds) rodam. As do tipo "eShop" (e os
            // temas) também vêm em .cia, dentro do .zip, sem nada no nome que avise.
            if (systemId == "3ds" && (CIA_TYPES.any { type?.contains(it, ignoreCase = true) == true } ||
                    name.endsWith(".cia", ignoreCase = true) || name.contains("(Theme)", ignoreCase = true))
            ) {
                return@mapNotNull null
            }
            RomVariant(
                fileName = name,
                downloadUrl = url,
                label = name,
                region = regionOf(name),
                sizeBytes = parseSize(cells.getOrNull(2)?.text()),
                note = type,
            )
        }.distinctBy { it.downloadUrl }
    }

    override suspend fun directLink(entry: CatalogEntry, variant: RomVariant): DirectLink = try {
        resolveLink(entry, variant)
    } catch (e: HttpStatusException) {
        // Verificação que não passou (do site ou do servidor de arquivos): mensagem legível, não "HTTP 403: url".
        throw LocalizedException(R.string.web_check_blocked, URI(e.url).host.orEmpty())
    } catch (e: java.net.SocketTimeoutException) {
        throw LocalizedException(R.string.romsfun_link_failed, e.message.orEmpty())
    }

    private suspend fun resolveLink(entry: CatalogEntry, variant: RomVariant): DirectLink {
        val pageUrl = variant.downloadUrl.takeIf { DOWNLOAD_FILE.matches(it) }
            ?: throw LocalizedException(R.string.catalog_no_downloadable_file, entry.id)
        // O AJAX descobre o arquivo pelo Referer; abrir a página antes é o que o navegador faz.
        web.request(BASE, pageUrl)
        val res = web.request(
            BASE,
            "$BASE/wp-admin/admin-ajax.php",
            method = "POST",
            body = "action=k_get_download",
            contentType = "application/x-www-form-urlencoded; charset=UTF-8",
            referrer = pageUrl,
        )
        val json = runCatching { Http.json.parseToJsonElement(res.body).jsonObject }.getOrNull()
        val data = json?.get("data") as? JsonObject
        val fileUrl = data?.get("download_url")?.jsonPrimitive?.contentOrNull
        if (json?.get("success")?.jsonPrimitive?.booleanOrNull != true || fileUrl.isNullOrBlank()) {
            val message = data?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP ${res.status}"
            throw LocalizedException(R.string.romsfun_link_failed, message)
        }
        val host = URI(fileUrl).host.orEmpty()
        val fileName = decodePath(fileUrl.substringBefore('?').substringAfterLast('/')).ifBlank { null }
        val ipv6 = webViewUsesIpv6()
        var link = linkFor(fileUrl, fileName, ipv6)
        when (probe(link)) {
            Probe.OK -> {}
            Probe.CHALLENGE -> {
                // Servidor de arquivos com verificação própria: o WebView a resolve e o cookie vai no pedido.
                // O cookie recém-emitido às vezes leva um instante para valer, daí as novas tentativas.
                web.solve("https://$host/")
                var passed = false
                for (wait in longArrayOf(0, 1_500, 3_000, 5_000)) {
                    delay(wait)
                    link = linkFor(fileUrl, fileName, ipv6)
                    if (probe(link) != Probe.CHALLENGE) { passed = true; break }
                }
                if (!passed) throw LocalizedException(R.string.web_check_blocked, host)
            }
            // Link preso a outro IP: tenta pelo outro protocolo antes de desistir.
            Probe.INVALID -> {
                val other = link.copy(ipv6 = ipv6?.not() ?: true)
                // Numa rede só IPv4 (ou só IPv6) o outro protocolo nem resolve o endereço: conta como recusa.
                val retried = try { probe(other) } catch (e: java.io.IOException) { Probe.INVALID }
                if (retried == Probe.OK) link = other else throw LocalizedException(R.string.romsfun_link_invalid)
            }
            // Ocupado agora: o download espera e tenta de novo sozinho (Http.download).
            Probe.BUSY -> {}
        }
        return link
    }

    private fun linkFor(url: String, fileName: String?, ipv6: Boolean?): DirectLink {
        val headers = buildMap {
            put("User-Agent", web.userAgent)
            put("Referer", "$BASE/")
            web.cookies(url)?.let { put("Cookie", it) }
        }
        return DirectLink(url, fileName, headers, ipv6)
    }

    private enum class Probe { OK, CHALLENGE, INVALID, BUSY }

    /** Pede o primeiro byte para saber, antes de começar, se o servidor vai entregar o arquivo. */
    private suspend fun probe(link: DirectLink): Probe = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(link.url)
            .apply { link.headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-0")
            .build()
        with(Http) { Http.clientFor(link.ipv6).newCall(request).executeCancellable { res ->
            when {
                res.isSuccessful -> Probe.OK
                res.header("cf-mitigated") == "challenge" -> Probe.CHALLENGE
                res.code == 503 || res.code == 429 -> Probe.BUSY
                else -> Probe.INVALID
            }
        } }
    }

    /** O link é assinado com o IP de quem pediu: o do WebView. O `/cdn-cgi/trace` do Cloudflare diz qual foi. */
    private suspend fun webViewUsesIpv6(): Boolean? {
        val trace = runCatching { web.request(BASE, "$BASE/cdn-cgi/trace").body }.getOrNull() ?: return null
        val ip = trace.lineSequence().firstOrNull { it.startsWith("ip=") }?.removePrefix("ip=") ?: return null
        return ':' in ip
    }

    private suspend fun page(url: String): String {
        val res = web.request(BASE, url)
        if (res.status !in 200..299) throw LocalizedException(R.string.download_http_error, res.status, url)
        return res.body
    }

    /** A página do jogo serve aos detalhes e às variantes: guardada por alguns minutos para não pedir duas vezes. */
    private suspend fun gamePage(url: String): String {
        val now = System.currentTimeMillis()
        synchronized(gamePages) { gamePages[url] }?.takeIf { now - it.first < PAGE_TTL_MS }?.let { return it.second }
        val html = page(url)
        synchronized(gamePages) { gamePages[url] = now to html }
        return html
    }

    private val gamePages = object : LinkedHashMap<String, Pair<Long, String>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>) = size > 6
    }

    /** Maior número entre os links `/page/N/` (a paginação só mostra alguns números e a última). */
    private fun totalPages(doc: Document, page: Int): Int {
        val max = doc.select("a[href*=/page/]").mapNotNull { PAGE_NUMBER.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return maxOf(page, max)
    }

    /** "1,693 ROM found" (a `/browse-all-roms/` escreve assim, com ou sem filtro). */
    private fun totalResults(doc: Document): Int? {
        val text = doc.select("h2").joinToString(" ") { it.text() }
        return Regex("""([\d,]+)\s+ROMs?\s+found""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
    }

    /** "219.92 M" / "1.53 G" / "512 K" (o site omite o "B"). */
    internal fun parseSize(text: String?): Long? {
        val m = Regex("""([\d.]+)\s*([KMG])""", RegexOption.IGNORE_CASE).find(text ?: return null) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2].uppercase()) {
            "G" -> 1024L * 1024 * 1024
            "M" -> 1024L * 1024
            else -> 1024L
        }
        return (value * unit).toLong()
    }

    /** `%20` e companhia de volta a texto (sem trocar "+" por espaço, que num nome de arquivo é "+"). */
    private fun decodePath(s: String): String = java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    /**
     * Um console do site: [slugs] aparecem no selo dos cartões (`/roms/<slug>/`) e na pasta das URLs dos jogos
     * (iguais, exceto no 3DS: jogos em `/roms/3ds/…`); [termId] é o id do filtro `consoles[]`.
     */
    private data class Console(val systemId: String, val termId: Int, val slugs: List<String>)

    companion object {
        /** Valores de `sort` da `/browse-all-roms/` (o site não ordena por nota). "newest" é a data em que o jogo entrou. */
        internal val SORTS = mapOf(
            SortOrder.POPULAR to "popular",
            SortOrder.RECENT to "newest",
            SortOrder.TITLE to "alphabetical",
        )

        private const val BASE = "https://romsfun.com"
        private const val PAGE_TTL_MS = 10 * 60 * 1000L
        private val GAME_URL = Regex("""^https://romsfun\.com/roms/([a-z0-9-]+)/[^/]+\.html$""")
        private val CONSOLE_URL = Regex("""^https://romsfun\.com/roms/([a-z0-9-]+)/?$""")
        private val DOWNLOAD_INDEX = Regex("""^https://romsfun\.com/download/[^/]+-\d+/?$""")
        private val DOWNLOAD_FILE = Regex("""^https://romsfun\.com/download/[^/]+-\d+/\d+/?$""")
        private val PAGE_NUMBER = Regex("""/page/(\d+)/""")
        /** Tipos de arquivo do 3DS que o site entrega em .cia. */
        private val CIA_TYPES = listOf("CIA", "eShop")

        /** Consoles do site que o app roda (ids conferidos no formulário da `/browse-all-roms/`). */
        private val CONSOLES = listOf(
            Console("3ds", 91, listOf("nintendo-3ds", "3ds")),
            Console("wii", 10, listOf("nintendo-wii")),
            Console("gc", 13, listOf("gamecube")),
            Console("n64", 9, listOf("nintendo-64")),
            Console("nds", 3, listOf("nintendo-ds")),
            Console("gba", 4, listOf("game-boy-advance")),
            Console("gbc", 11, listOf("game-boy-color")),
            Console("gb", 95, listOf("game-boy")),
            Console("snes", 6, listOf("super-nintendo")),
            Console("nes", 12, listOf("nes")),
            Console("vb", 16610, listOf("nintendo-virtual-boy")),
            Console("pokemini", 19197, listOf("nintendo-pokemon-mini")),
            Console("psx", 7, listOf("playstation")),
            Console("ps2", 8, listOf("playstation-2")),
            Console("psp", 5, listOf("playstation-portable")),
            Console("genesis", 14, listOf("sega-genesis")),
            Console("sms", 9806, listOf("sega-master-system")),
            Console("gg", 9476, listOf("sega-game-gear")),
            Console("segacd", 9814, listOf("sega-cd")),
            Console("32x", 9692, listOf("32x")),
            Console("saturn", 92, listOf("sega-saturn")),
            Console("dreamcast", 2390, listOf("dreamcast")),
            Console("sg1000", 18711, listOf("sega-sg-1000")),
            Console("pce", 12922, listOf("turbografx")),
            Console("pce", 12977, listOf("turbografx-cd")),
            Console("pcfx", 18060, listOf("nec-pc-fx")),
            Console("ngp", 18735, listOf("snk-neo-geo-pocket-color")),
            Console("neocd", 17973, listOf("neo-geo-cd")),
            Console("arcade", 14211, listOf("mame")),
            Console("arcade", 9922, listOf("neo-geo")),
            Console("wswan", 19606, listOf("wonderswan")),
            Console("wswan", 13034, listOf("wonderswan-color")),
            Console("atari2600", 16629, listOf("atari-2600")),
            Console("atari7800", 28299, listOf("atari-7800")),
            Console("jaguar", 20428, listOf("atari-jaguar")),
            Console("3do", 9174, listOf("3do")),
            Console("amiga", 15341, listOf("amiga")),
            Console("msx", 15524, listOf("microsoft-msx")),
            Console("msx", 13048, listOf("microsoft-msx2")),
            Console("dos", 19282, listOf("ms-dos")),
        )

        private val SYSTEM_BY_SLUG: Map<String, String> = CONSOLES.flatMap { c -> c.slugs.map { it to c.systemId } }.toMap()

        /**
         * [Genre] → ids da taxonomia de gênero do site (filtro `genres[]`, que soma os ids). O site repete
         * gêneros com grafias diferentes ("Sport"/"Sports", "RPG"/"Role-Playing"), então vão todos.
         */
        internal val GENRE_TERMS: Map<Genre, List<Int>> = mapOf(
            Genre.ACTION to listOf(2337, 2355),
            Genre.ADVENTURE to listOf(2351),
            Genre.RPG to listOf(2361, 7422, 5494),
            Genre.PLATFORM to listOf(2392, 2354),
            Genre.PUZZLE to listOf(2338),
            Genre.SHOOTER to listOf(2399, 2364),
            Genre.RACING to listOf(2357),
            Genre.SPORTS to listOf(2353, 2401),
            Genre.FIGHTING to listOf(2365),
            Genre.STRATEGY to listOf(2347),
            Genre.HORROR to listOf(2416),
            Genre.MUSIC to listOf(2466),
        )
    }
}
