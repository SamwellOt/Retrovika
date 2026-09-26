package com.retrovika.app.core.catalog

import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import android.net.Uri
import com.retrovika.app.core.net.Http
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * CDRomance (cdromance.org): grande acervo de ROMs/ISOs de vários consoles, com download
 * direto (o arquivo sai de `dl*.cdromance.org`).
 *
 * A busca usa `?s=` do WordPress; os resultados vêm em blocos `.game-container`, cada um
 * trazendo a seção do console (`<div class="console <slug>">`) — é por ela que classificamos
 * o sistema. Sem termo, listamos a categoria do console escolhido (ou os lançamentos recentes
 * da home). Os links de download só aparecem via AJAX, então [variants] os resolve na hora.
 */
class CdRomanceSource : CatalogSource {
    override val id = "cdromance"
    override val name = "CDRomance"
    override val description = R.string.catalog_cdromance_description

    private val base = "https://cdromance.org"
    private val ajaxUrl = "$base/wp-content/plugins/cdr-main/public/ajax.php"

    /** Seção do CDRomance (classe `console`/1º segmento da URL) → id de sistema do Retrovika. */
    private val systemBySection = mapOf(
        "nes-roms" to "nes",
        "snes-rom" to "snes",
        "n64-roms" to "n64",
        "gamecube" to "gc", "gcn-iso" to "gc",
        "gameboy-roms" to "gb",
        "gameboy-color-roms" to "gbc",
        "gba-roms" to "gba",
        "nds-roms" to "nds",
        "psx-iso" to "psx",
        "ps2-iso" to "ps2",
        "psp" to "psp",
        "dc-iso" to "dreamcast",
        "game-gear" to "gg",
        "turbografx-16" to "pce",
        "neo-geo-pocket" to "ngp",
        "wonderswan" to "wswan",
    )

    /** Id de sistema → slug de categoria navegável (quando o CDRomance lista o console nesse layout). */
    private val sectionBySystem = mapOf(
        "nes" to "nes-roms",
        "snes" to "snes-rom",
        "n64" to "n64-roms",
        "gc" to "gamecube",
        "gb" to "gameboy-roms",
        "gbc" to "gameboy-color-roms",
        "gba" to "gba-roms",
        "nds" to "nds-roms",
        "psx" to "psx-iso",
        "ps2" to "ps2-iso",
        "psp" to "psp",
        "dreamcast" to "dc-iso",
        "gg" to "game-gear",
        "pce" to "turbografx-16",
        "ngp" to "neo-geo-pocket",
        "wswan" to "wonderswan",
    )

    /**
     * Consoles oferecidos. Além dos navegáveis por categoria, inclui os sistemas Sega e outros
     * que não têm página de categoria padronizada mas aparecem na busca por título.
     */
    override val systems: Set<String> = sectionBySystem.keys +
        setOf("genesis", "segacd", "32x", "sms", "saturn", "atari2600", "lynx")

    override suspend fun search(query: String, systemId: String?, page: Int, kind: String?): CatalogPage {
        val q = query.trim()
        val url = when {
            q.isNotBlank() -> "$base/${pagePath(page)}?s=${Uri.encode(q)}"
            systemId != null -> sectionBySystem[systemId]?.let { "$base/$it/${pagePath(page)}" }
                ?: return CatalogPage(emptyList(), page, 1, 0) // console sem categoria navegável: peça um termo
            else -> "$base/${pagePath(page)}" // home: lançamentos recentes
        }

        val doc = Jsoup.parse(Http.getString(url), base)
        val entries = doc.select("div.game-container").mapNotNull { parseCard(it) }
            .let { list -> if (systemId != null) list.filter { it.systemId == systemId } else list }
        val totalPages = doc.select("a.page-numbers").mapNotNull { it.text().replace(".", "").toIntOrNull() }
            .maxOrNull()?.coerceAtLeast(page) ?: page
        return CatalogPage(entries, page, totalPages, entries.size.coerceAtLeast(0))
    }

    private fun parseCard(card: Element): CatalogEntry? {
        val link = card.selectFirst("a.cover-link") ?: return null
        val gameUrl = link.absUrl("href").ifBlank { link.attr("href") }.takeIf { it.isNotBlank() } ?: return null
        val consoleEl = card.selectFirst("div.console")
        val section = consoleEl?.classNames()?.firstOrNull { it != "console" }
        val systemId = section?.let { classify(it) } ?: return null
        val img = card.selectFirst("img")
        val title = card.selectFirst("div.game-title")?.text()?.takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: gameUrl.trimEnd('/').substringAfterLast('/').replace('-', ' ')
        return CatalogEntry(
            id = gameUrl,
            sourceId = id,
            title = title,
            systemId = systemId,
            developer = null,
            coverUrl = img?.let { it.absUrl("src").ifBlank { it.attr("src") } }?.takeIf { it.isNotBlank() },
            screenshots = emptyList(),
            tags = consoleEl?.text()?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
            website = gameUrl,
            downloadUrl = gameUrl, // resolvido em variants()/resolve()
            fileName = gameUrl.trimEnd('/').substringAfterLast('/') + ".zip",
            kind = "game",
        )
    }

    /** Classifica a seção pelo mapa explícito e, se for uma seção nova, por palavras-chave no slug. */
    private fun classify(section: String): String? {
        systemBySection[section]?.let { return it }
        val s = section.lowercase()
        return when {
            "gameboy-color" in s || "gbc" in s -> "gbc"
            "gameboy" in s || s == "gb-roms" -> "gb"
            "gba" in s || "advance" in s -> "gba"
            "n64" in s || "nintendo-64" in s -> "n64"
            "gamecube" in s || "gcn" in s -> "gc"
            "nes" in s -> "nes"
            "snes" in s || "super-nintendo" in s -> "snes"
            "nds" in s || "nintendo-ds" in s -> "nds"
            "megadrive" in s || "mega-drive" in s || "genesis" in s -> "genesis"
            "master-system" in s || s == "sms" -> "sms"
            "sega-cd" in s || "segacd" in s || "mega-cd" in s -> "segacd"
            "32x" in s -> "32x"
            "saturn" in s -> "saturn"
            "dreamcast" in s || s == "dc-iso" -> "dreamcast"
            "game-gear" in s -> "gg"
            "turbografx" in s || "pc-engine" in s -> "pce"
            "neo-geo-pocket" in s -> "ngp"
            "wonderswan" in s -> "wswan"
            "atari-2600" in s || "atari2600" in s -> "atari2600"
            "lynx" in s -> "lynx"
            s == "psx-iso" || s == "playstation" -> "psx"
            s == "ps2-iso" -> "ps2"
            s == "psp" -> "psp"
            else -> null
        }
    }

    /**
     * Os links de download ficam atrás de um botão "Show Links" que dispara um AJAX. Aqui pegamos
     * o `post_id` da página do jogo e chamamos o mesmo endpoint (exige o cabeçalho de XHR), que
     * devolve a tabela de arquivos — uma variante por arquivo/região.
     */
    override suspend fun variants(entry: CatalogEntry): List<RomVariant> {
        val page = Jsoup.parse(Http.getString(entry.id), base)
        val postId = page.selectFirst("#acf-content-wrapper")?.attr("data-id")?.takeIf { it.isNotBlank() }
            ?: throw LocalizedException(R.string.catalog_no_downloads, entry.id)

        val html = Http.postForm(
            ajaxUrl,
            form = mapOf("post_id" to postId),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to entry.id),
        )
        val doc = Jsoup.parse(html, base)
        val variants = doc.select("a[id^=dl-btn-]").mapNotNull { a ->
            val downloadUrl = a.absUrl("href").ifBlank { a.attr("href") }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fileName = a.text().trim().ifBlank { downloadUrl.substringAfterLast('/').substringBefore('?') }
            val sizeText = a.closest("div.tr")?.select("div.td")?.getOrNull(1)?.text()
            RomVariant(
                fileName = fileName,
                downloadUrl = downloadUrl,
                label = fileName,
                region = regionOf(fileName),
                sizeBytes = parseSize(sizeText),
            )
        }
        if (variants.isEmpty()) throw LocalizedException(R.string.catalog_no_downloadable_file, entry.id)
        return variants
    }

    private fun pagePath(page: Int): String = if (page > 1) "page/$page/" else ""

    /** Converte "9.14 MB" / "512 KB" / "1.2 GB" em bytes. */
    private fun parseSize(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val m = Regex("""([\d.]+)\s*(KB|MB|GB)""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = when (m.groupValues[2].uppercase()) {
            "GB" -> 1024L * 1024 * 1024
            "MB" -> 1024L * 1024
            else -> 1024L
        }
        return (value * unit).toLong()
    }
}
