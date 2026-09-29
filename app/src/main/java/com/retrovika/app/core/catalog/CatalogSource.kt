package com.retrovika.app.core.catalog

import androidx.annotation.StringRes
import com.retrovika.app.core.gameinfo.SourceDetails

data class CatalogEntry(
    val id: String,
    val sourceId: String,
    val title: String,
    val systemId: String,
    val developer: String?,
    val coverUrl: String?,
    val screenshots: List<String>,
    val tags: List<String>,
    val website: String?,
    val downloadUrl: String,
    val fileName: String,
    val kind: String,
)

/**
 * Uma página de resultados. [totalResults] é o total da busca inteira (não só desta página);
 * [approximate] marca quando a fonte não informa o total e ele foi estimado pelas páginas.
 */
data class CatalogPage(
    val entries: List<CatalogEntry>,
    val page: Int,
    val totalPages: Int,
    val totalResults: Int,
    val approximate: Boolean = false,
    /** Busca em várias fontes em que alguma falhou: a página está incompleta e deve ser pedida de novo. */
    val partial: Boolean = false,
)

/**
 * Um arquivo baixável concreto de uma [CatalogEntry]. Uma mesma entrada pode oferecer
 * várias ROMs (regiões, revisões, formatos), e é entre elas que o usuário escolhe
 * "qual ROM baixar".
 */
data class RomVariant(
    val fileName: String,
    val downloadUrl: String,
    val label: String = fileName,
    val region: String? = null,
    val sizeBytes: Long? = null,
    val note: String? = null,
)

/** Detecta a região a partir do nome de arquivo no padrão No-Intro/TOSEC, quando presente. */
fun regionOf(name: String): String? {
    val lower = name.lowercase()
    return when {
        "(usa" in lower || "(u)" in lower || "(ntsc-u" in lower -> "EUA"
        "(europe" in lower || "(e)" in lower || "(pal" in lower -> "Europa"
        "(japan" in lower || "(j)" in lower || "(ntsc-j" in lower -> "Japão"
        "(brazil" in lower || "(b)" in lower -> "Brasil"
        "(world" in lower || "(w)" in lower -> "Mundial"
        else -> null
    }
}

/**
 * Uma fonte de jogos para o catálogo online. Novas fontes são adicionadas implementando
 * esta interface e registrando-as em [CatalogRepository].
 */
interface CatalogSource {
    val id: String
    val name: String
    @get:StringRes val description: Int
    val systems: Set<String>

    /** Algumas fontes só fazem sentido com um console escolhido (ex.: Internet Archive). */
    val requiresSystem: Boolean get() = false

    /**
     * Uma página de resultados. [genre] é o filtro especial de gênero: cada fonte o aplica como
     * pode (etiquetas ou busca textual, ver [Genre]), então o resultado é aproximado.
     */
    suspend fun search(query: String, systemId: String?, page: Int, kind: String?, genre: Genre? = null): CatalogPage

    /**
     * Lista os arquivos baixáveis de uma entrada, para o usuário escolher qual ROM baixar.
     * O padrão é uma única variante (o link já embutido na entrada); fontes que expõem
     * várias versões (ex.: Internet Archive) sobrescrevem este método.
     */
    suspend fun variants(entry: CatalogEntry): List<RomVariant> =
        listOf(RomVariant(fileName = entry.fileName, downloadUrl = entry.downloadUrl, label = entry.fileName))

    /**
     * Ficha do jogo como a fonte a conhece (descrição, região, idiomas, data, nota dos usuários…), para
     * a página do jogo. O padrão usa só o que a busca já trouxe; fontes com página de jogo a leem aqui.
     */
    suspend fun details(entry: CatalogEntry): SourceDetails = entry.basicDetails()

    /**
     * Converte um [CatalogEntry] no link final de download. Fontes com URL direto
     * (padrão) devolvem a própria entrada; fontes que só expõem um identificador
     * resolvem o arquivo real aqui, na hora de baixar.
     */
    suspend fun resolve(entry: CatalogEntry): CatalogEntry {
        val variant = variants(entry).firstOrNull() ?: return entry
        return entry.copy(downloadUrl = variant.downloadUrl, fileName = variant.fileName)
    }
}

/** O que a própria entrada da busca já diz sobre o jogo. */
fun CatalogEntry.basicDetails(): SourceDetails = SourceDetails(
    title = title,
    coverUrl = coverUrl,
    screenshots = screenshots,
    developers = listOfNotNull(developer),
    tags = tags,
    website = website,
)
