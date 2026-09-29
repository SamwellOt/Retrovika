package com.retrovika.app.core.catalog

import com.retrovika.app.core.gameinfo.HtmlText
import com.retrovika.app.core.gameinfo.SourceDetails
import com.retrovika.app.R
import com.retrovika.app.core.net.Urls
import com.retrovika.app.core.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Homebrew Hub (hh.gbdev.io): arquivo comunitário com milhares de jogos homebrew
 * de Game Boy, Game Boy Color, Game Boy Advance e NES.
 */
class HomebrewHubSource : CatalogSource {
    override val id = "homebrewhub"
    override val name = "Homebrew Hub"
    override val description = R.string.catalog_homebrewhub_description
    override val systems = setOf("gb", "gbc", "gba", "nes")

    private val base = "https://hh3.gbdev.io"

    private val platformBySystem = mapOf("gb" to "GB", "gbc" to "GBC", "gba" to "GBA", "nes" to "NES")
    private val systemByPlatform = platformBySystem.entries.associate { it.value to it.key }

    @Serializable
    private data class Response(
        val results: Int = 0,
        val page_total: Int = 1,
        val page_current: Int = 1,
        val entries: List<Entry> = emptyList(),
    )

    @Serializable
    // Campos com padrão: uma entrada incompleta é pulada, em vez de fazer a página inteira falhar.
    private data class Entry(
        val slug: String = "",
        val title: String = "",
        /** Pode vir como texto ou como lista de autores. */
        val developer: JsonElement? = null,
        val platform: String = "",
        val typetag: String? = null,
        val screenshots: List<String> = emptyList(),
        val files: List<FileEntry> = emptyList(),
        val tags: List<String> = emptyList(),
        val website: String? = null,
        val basepath: String = "",
    )

    @Serializable
    private data class FileEntry(val filename: String = "", val default: Boolean = false, val playable: Boolean = false)

    override suspend fun search(query: String, systemId: String?, page: Int, kind: String?, genre: Genre?): CatalogPage {
        val url = Urls.withQuery("$base/api/search", buildList {
            add("page" to page.toString())
            add("results" to "30")
            // O gênero vai para a API como etiqueta: filtrar aqui páginas sem filtro deixava quase todas
            // vazias (Esportes são 3 de ~1600 jogos) e a busca encadeava pedido atrás de pedido.
            genre?.let { add("tags" to hubTag(it)) }
            if (query.isNotBlank()) add("q" to query.trim())
            systemId?.let { platformBySystem[it] }?.let { add("platform" to it) }
            kind?.let { add("typetag" to it) }
            add("sort" to "firstadded_date")
            add("order" to "desc")
        })

        val response = Http.json.decodeFromString(Response.serializer(), Http.getString(url))
        val entries = response.entries.mapNotNull { e ->
            if (e.slug.isBlank() || e.basepath.isBlank()) return@mapNotNull null
            val system = systemByPlatform[e.platform] ?: return@mapNotNull null
            // Arquivo sem nome não tem link: fica de fora da escolha.
            val files = e.files.filter { it.filename.isNotBlank() }
            val file = files.firstOrNull { it.default && it.playable } ?: files.firstOrNull { it.playable } ?: return@mapNotNull null
            val entryBase = "$base/static/${e.basepath}/entries/${e.slug}"
            val shots = e.screenshots.map { "$entryBase/${Urls.encode(it, "/")}" }
            CatalogEntry(
                id = e.slug,
                sourceId = id,
                title = e.title.ifBlank { e.slug },
                systemId = system,
                developer = developerName(e.developer),
                coverUrl = shots.firstOrNull { it.contains("cover", true) } ?: shots.firstOrNull(),
                screenshots = shots,
                tags = e.tags.filterNot { it.startsWith("event:") }.take(6),
                website = e.website?.takeIf { it.isNotBlank() },
                downloadUrl = "$entryBase/${Urls.encode(file.filename, "/")}",
                fileName = file.filename.substringAfterLast('/'),
                kind = e.typetag ?: "game",
            )
        }
        return CatalogPage(entries, response.page_current, response.page_total, response.results)
    }

    @Serializable
    private data class Detail(
        val slug: String = "",
        val title: String = "",
        val developer: JsonElement? = null,
        val platform: String = "",
        val typetag: String? = null,
        val screenshots: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val website: String? = null,
        val basepath: String = "",
        val description: String? = null,
        val license: String? = null,
        val date: String? = null,
        val firstadded_date: String? = null,
    )

    /** A ficha completa da entrada (a busca não traz descrição, licença nem datas). */
    override suspend fun details(entry: CatalogEntry): SourceDetails {
        val d = Http.json.decodeFromString(Detail.serializer(), Http.getString("$base/api/entry/${Urls.encode(entry.id)}.json"))
        val entryBase = "$base/static/${d.basepath}/entries/${d.slug}"
        val shots = if (d.basepath.isBlank()) entry.screenshots else d.screenshots.map { "$entryBase/${Urls.encode(it, "/")}" }
        return SourceDetails(
            title = d.title.ifBlank { entry.title },
            description = d.description?.let { HtmlText.of(it) }?.ifBlank { null },
            coverUrl = entry.coverUrl,
            screenshots = shots.ifEmpty { entry.screenshots },
            releaseDate = d.date?.let(::isoDate),
            developers = developerName(d.developer)?.split(", ").orEmpty(),
            genres = d.tags.filterNot { it.startsWith("event:") },
            tags = d.tags.filter { it.startsWith("event:") }.map { it.removePrefix("event:") },
            website = d.website?.takeIf { it.isNotBlank() },
            addedDate = d.firstadded_date?.let(::isoDate),
            license = d.license?.takeIf { it.isNotBlank() },
            format = entry.fileName.substringAfterLast('.', "").uppercase().ifBlank { null },
        )
    }

    /** "2026-7-19" (sem zeros, como algumas entradas vêm) → "2026-07-19"; a hora é descartada. */
    private fun isoDate(raw: String): String? {
        val m = Regex("""^(\d{4})-(\d{1,2})(?:-(\d{1,2}))?""").find(raw.trim()) ?: return raw.take(10).ifBlank { null }
        val (y, mo, d) = m.destructured
        return if (d.isEmpty()) "$y-${mo.padStart(2, '0')}" else "$y-${mo.padStart(2, '0')}-${d.padStart(2, '0')}"
    }

    private fun developerName(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        else -> null
    }?.takeIf { it.isNotBlank() }

    /** Etiqueta usada pelo Homebrew Hub para cada gênero (as mais comuns no acervo, com a grafia de lá). */
    internal fun hubTag(genre: Genre): String = when (genre) {
        Genre.ACTION -> "Action"
        Genre.ADVENTURE -> "Adventure"
        Genre.RPG -> "Role Playing"
        Genre.PLATFORM -> "Platformer"
        Genre.PUZZLE -> "Puzzle"
        Genre.SHOOTER -> "Shooter"
        Genre.RACING -> "Racing"
        Genre.SPORTS -> "Sports"
        Genre.FIGHTING -> "Fighting"
        Genre.STRATEGY -> "Strategy"
        Genre.HORROR -> "Horror"
        Genre.MUSIC -> "Music"
    }
}
