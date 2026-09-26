package com.retrovika.app.core.catalog

import com.retrovika.app.R
import android.net.Uri
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
    private data class Entry(
        val slug: String,
        val title: String,
        /** Pode vir como texto ou como lista de autores. */
        val developer: JsonElement? = null,
        val platform: String,
        val typetag: String? = null,
        val screenshots: List<String> = emptyList(),
        val files: List<FileEntry> = emptyList(),
        val tags: List<String> = emptyList(),
        val website: String? = null,
        val basepath: String,
    )

    @Serializable
    private data class FileEntry(val filename: String, val default: Boolean = false, val playable: Boolean = false)

    override suspend fun search(query: String, systemId: String?, page: Int, kind: String?): CatalogPage {
        val url = Uri.parse("$base/api/search").buildUpon().apply {
            appendQueryParameter("page", page.toString())
            appendQueryParameter("results", "30")
            if (query.isNotBlank()) appendQueryParameter("q", query.trim())
            systemId?.let { platformBySystem[it] }?.let { appendQueryParameter("platform", it) }
            kind?.let { appendQueryParameter("typetag", it) }
            appendQueryParameter("sort", "firstadded_date")
            appendQueryParameter("order", "desc")
        }.build().toString()

        val response = Http.json.decodeFromString(Response.serializer(), Http.getString(url))
        val entries = response.entries.mapNotNull { e ->
            val system = systemByPlatform[e.platform] ?: return@mapNotNull null
            val file = e.files.firstOrNull { it.default && it.playable } ?: e.files.firstOrNull { it.playable } ?: return@mapNotNull null
            val entryBase = "$base/static/${e.basepath}/entries/${e.slug}"
            val shots = e.screenshots.map { "$entryBase/${Uri.encode(it, "/")}" }
            CatalogEntry(
                id = e.slug,
                sourceId = id,
                title = e.title,
                systemId = system,
                developer = developerName(e.developer),
                coverUrl = shots.firstOrNull { it.contains("cover", true) } ?: shots.firstOrNull(),
                screenshots = shots,
                tags = e.tags.filterNot { it.startsWith("event:") }.take(6),
                website = e.website?.takeIf { it.isNotBlank() },
                downloadUrl = "$entryBase/${Uri.encode(file.filename, "/")}",
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
}
