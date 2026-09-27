package com.retrovika.app.core.catalog

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
    private data class FileEntry(val filename: String, val default: Boolean = false, val playable: Boolean = false)

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
            val file = e.files.firstOrNull { it.default && it.playable } ?: e.files.firstOrNull { it.playable } ?: return@mapNotNull null
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
