package com.retrovika.app.core.catalog

import com.retrovika.app.core.gameinfo.HtmlText
import com.retrovika.app.core.gameinfo.SourceDetails
import kotlinx.serialization.json.jsonObject
import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.net.Urls
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.systems.Systems
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Internet Archive (archive.org) — busca no acervo de software do archive.org,
 * focada no console escolhido. Outras fontes (inclusive sites de ROM) podem ser
 * adicionadas implementando [CatalogSource] e registrando-as em [CatalogRepository].
 */
class InternetArchiveSource : CatalogSource {
    override val id = "archive"
    override val name = "Internet Archive"
    override val description = R.string.catalog_archive_description
    override val requiresSystem = true

    /** Termos para focar a busca no console escolhido. */
    private val systemTerms = mapOf(
        "nes" to "Nintendo Entertainment System", "snes" to "Super Nintendo", "n64" to "Nintendo 64",
        "gb" to "Game Boy", "gbc" to "Game Boy Color", "gba" to "Game Boy Advance",
        "genesis" to "Mega Drive Genesis", "sms" to "Master System", "gg" to "Game Gear",
        "pce" to "PC Engine TurboGrafx", "atari2600" to "Atari 2600", "lynx" to "Atari Lynx",
        "psx" to "PlayStation", "dreamcast" to "Dreamcast", "arcade" to "Arcade",
        "sg1000" to "SG-1000", "a5200" to "Atari 5200", "a800" to "Atari 800", "jaguar" to "Atari Jaguar",
        "vectrex" to "Vectrex", "intv" to "Intellivision", "odyssey2" to "Odyssey2 Videopac",
        "3do" to "3DO", "msx" to "MSX", "c64" to "Commodore 64", "amiga" to "Amiga",
        "zxspectrum" to "ZX Spectrum", "cpc" to "Amstrad CPC", "dos" to "MS-DOS",
    )

    override val systems: Set<String> = systemTerms.keys

    private val perPage = 30

    @Serializable private data class Response(val response: Body = Body())
    @Serializable private data class Body(val numFound: Int = 0, val docs: List<Doc> = emptyList())
    @Serializable private data class Doc(
        val identifier: String,
        val title: JsonElement? = null,
        val creator: JsonElement? = null,
    )

    override suspend fun search(query: String, systemId: String?, page: Int, kind: String?, genre: Genre?): CatalogPage {
        // Sem console escolhido não há como classificar/baixar: não retorna nada.
        val system = systemId?.let { systemTerms[it] } ?: return CatalogPage(emptyList(), page, 1, 0)

        val clauses = buildList {
            add("mediatype:(software)")
            add("($system)")
            escape(query).takeIf { it.isNotEmpty() }?.let { add("($it)") }
            // Os itens de software trazem o gênero (quando trazem) nos assuntos livres do acervo.
            genre?.let { g -> add("subject:(" + g.keywords.joinToString(" OR ") { "\"${escape(it)}\"" } + ")") }
        }
        val url = Urls.withQuery("https://archive.org/advancedsearch.php", listOf(
            "q" to clauses.joinToString(" AND "),
            "fl[]" to "identifier",
            "fl[]" to "title",
            "fl[]" to "creator",
            "rows" to perPage.toString(),
            "page" to page.toString(),
            "sort[]" to "downloads desc",
            "output" to "json",
        ))

        val response = Http.json.decodeFromString(Response.serializer(), Http.getString(url))
        val entries = response.response.docs.map { doc ->
            CatalogEntry(
                id = doc.identifier,
                sourceId = id,
                title = text(doc.title) ?: doc.identifier,
                systemId = systemId!!,
                developer = text(doc.creator),
                coverUrl = "https://archive.org/services/img/${Urls.encode(doc.identifier)}",
                screenshots = emptyList(),
                tags = emptyList(),
                website = "https://archive.org/details/${doc.identifier}",
                downloadUrl = doc.identifier, // resolvido em resolve()
                fileName = doc.identifier,
                kind = "game",
            )
        }
        val total = response.response.numFound
        return CatalogPage(entries, page, ((total + perPage - 1) / perPage).coerceAtLeast(1), total)
    }

    @Serializable private data class Meta(val files: List<MetaFile> = emptyList())
    @Serializable private data class MetaFile(val name: String, val format: String? = null, val size: String? = null)

    /**
     * Lista cada ROM baixável do item: primeiro os arquivos já no formato do console
     * (uma variante por região/revisão), depois os pacotes .zip/.7z como alternativa.
     */
    override suspend fun variants(entry: CatalogEntry): List<RomVariant> {
        val identifier = entry.id
        val exts = Systems.byId(entry.systemId)?.extensions.orEmpty()
        val meta = Http.json.decodeFromString(Meta.serializer(), Http.getString("https://archive.org/metadata/$identifier"))
        // Em consoles de disco, folha (.cue/.gdi…) e trilhas soltas não jogam sozinhas: baixar só uma delas
        // não serve. Nesses casos ficam os pacotes, que são extraídos inteiros.
        val discSheets = exts.any { it in DISC_SHEETS }
        val direct = meta.files.filter { f ->
            val ext = f.name.substringAfterLast('.', "").lowercase()
            ext in exts && !(discSheets && ext in MULTI_FILE)
        }
        val archives = meta.files.filter { it.name.endsWith(".zip", true) || it.name.endsWith(".7z", true) }
        val files = (direct + archives).distinctBy { it.name }
        if (files.isEmpty()) throw LocalizedException(R.string.catalog_no_compatible_file, identifier)
        return files.map { file ->
            val short = file.name.substringAfterLast('/')
            RomVariant(
                fileName = short,
                downloadUrl = "https://archive.org/download/$identifier/${Urls.encode(file.name, "/")}",
                label = short,
                region = regionOf(short),
                sizeBytes = file.size?.toLongOrNull(),
            )
        }
    }

    /**
     * Metadados do item: título, descrição (HTML), data ou ano, autor, publicadora, assuntos e idioma.
     * Cada item é enviado por um usuário diferente, então os campos variam muito; os ausentes ficam nulos.
     */
    override suspend fun details(entry: CatalogEntry): SourceDetails {
        val json = Http.json.parseToJsonElement(Http.getString("https://archive.org/metadata/${Urls.encode(entry.id)}")).jsonObject
        val meta = json["metadata"]?.jsonObject ?: return entry.basicDetails()
        fun all(key: String): List<String> = when (val v = meta[key]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> v.contentOrNull?.split(';')?.map { it.trim() }.orEmpty()
            else -> emptyList()
        }.filter { it.isNotBlank() }
        // Campos de texto livre não se dividem no ";": a descrição é HTML (&amp;nbsp;) e frases têm ponto e vírgula.
        fun one(key: String): String? = when (val v = meta[key]) {
            is JsonArray -> v.firstNotNullOfOrNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null } }
            is JsonPrimitive -> v.contentOrNull?.trim()?.ifBlank { null }
            else -> null
        }
        val subjects = all("subject").distinct()
        return SourceDetails(
            title = one("title") ?: entry.title,
            description = one("description")?.let { HtmlText.of(it) }?.ifBlank { null },
            coverUrl = entry.coverUrl,
            releaseDate = (one("date") ?: one("year"))?.take(10),
            developers = all("creator").distinct(),
            publisher = one("publisher"),
            languages = all("language").distinct(),
            tags = subjects.take(12),
            website = entry.website,
            addedDate = one("addeddate")?.take(10),
            license = one("licenseurl"),
        )
    }

    private companion object {
        val DISC_SHEETS = setOf("cue", "gdi", "ccd")
        val MULTI_FILE = setOf("cue", "gdi", "ccd", "toc", "m3u", "bin", "img", "sub", "raw")
    }

    // Só letras, números, espaços e apóstrofo: "/", "\\", "!", "+", "-"… são sintaxe da busca do archive.org
    // e uma consulta como "AC/DC" virava erro ou lista vazia.
    private fun escape(q: String): String = q.replace(Regex("""[^\p{L}\p{N}\s']"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun text(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(", ")
        else -> null
    }?.takeIf { it.isNotBlank() }
}
