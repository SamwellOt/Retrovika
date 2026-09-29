package com.retrovika.app.core.gameinfo

import kotlinx.serialization.Serializable

/**
 * Informações de um jogo do catálogo, reunidas de três lugares: a própria fonte do download
 * ([SourceDetails]), o Backloggd ([BackloggdInfo]: nota da comunidade, estatísticas, reviews) e a
 * Wikipedia/Wikidata ([WikiInfo]: resumo no idioma do app, ficha técnica, notas da crítica).
 * Cada parte chega por conta própria; a página mostra o que já tiver.
 */

/** Nota de um site: [value] em uma escala até [best] (5 no CDRomance, por exemplo), com [count] votos. */
data class SiteRating(val value: Double, val best: Double, val count: Int)

/** O que a fonte do download sabe sobre o jogo (campos nulos quando o site não informa). */
data class SourceDetails(
    val title: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val screenshots: List<String> = emptyList(),
    /** Data de lançamento como o site escreve (ISO `2002-11-29` no CDRomance, ano no Archive). */
    val releaseDate: String? = null,
    val developers: List<String> = emptyList(),
    val publisher: String? = null,
    val genres: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val region: String? = null,
    /** Código do disco/cartucho (SLUS-00001, por exemplo). */
    val serial: String? = null,
    /** Formato da imagem (BIN/CUE, .gba…). */
    val format: String? = null,
    val downloads: Long? = null,
    val rating: SiteRating? = null,
    val tags: List<String> = emptyList(),
    val website: String? = null,
    /** Quando o jogo entrou no acervo da fonte. */
    val addedDate: String? = null,
    val license: String? = null,
)

/** Uma review da comunidade do Backloggd. [rating] vai de 0,5 a 5 (nulo quando a pessoa só escreveu). */
@Serializable
data class BackloggdReview(
    val user: String,
    val avatarUrl: String?,
    val rating: Double?,
    val status: String?,
    val platform: String?,
    /** Data ISO (`2026-09-23T00:58:55Z`). */
    val date: String?,
    val text: String,
    val likes: Int?,
    val url: String?,
)

/**
 * Página do jogo no Backloggd. Os contadores vêm abreviados como o site mostra ("99K", "1.9K"); as
 * horas, como "20h". [histogram] tem as 10 faixas de nota (0,5★ a 5★), com a quantidade de votos em cada.
 */
@Serializable
data class BackloggdInfo(
    val slug: String,
    val url: String,
    val title: String,
    val year: String?,
    val releaseDate: String?,
    val companies: List<String>,
    val genres: List<String>,
    val platforms: List<String>,
    val description: String?,
    val coverUrl: String?,
    val backdropUrl: String?,
    val rating: Double?,
    val ratingCount: Int?,
    val histogram: List<Int>,
    val igdbUrl: String?,
    val reviews: List<BackloggdReview> = emptyList(),
    /** O pedido das reviews falhou: o resto vale, mas não fica no cache (a próxima visita tenta de novo). */
    val reviewsFailed: Boolean = false,
)

/** Nota da crítica registrada no Wikidata ("94/100" do Metacritic, "39/40" da Famitsu…). */
@Serializable
data class ReviewScore(val score: String, val reviewer: String) {
    /** A nota em 0–100, quando o texto é uma fração ("94/100", "9/10"). */
    val normalized: Int?
        get() {
            val m = Regex("""^\s*([\d.,]+)\s*/\s*([\d.,]+)\s*$""").find(score) ?: return null
            val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val best = m.groupValues[2].replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            return (value / best * 100).toInt().coerceIn(0, 100)
        }
}

/** Link para outra base de dados de jogos (HowLongToBeat, MobyGames, IGDB…). */
@Serializable
data class ExternalLink(val name: String, val url: String)

@Serializable
data class WikiInfo(
    val entityId: String,
    val title: String?,
    /** Primeiro parágrafo do artigo, no idioma do app quando existe artigo nele. */
    val extract: String?,
    val articleUrl: String?,
    val articleLang: String?,
    val imageUrl: String?,
    val releaseDate: String?,
    val developers: List<String>,
    val publishers: List<String>,
    val genres: List<String>,
    val modes: List<String>,
    val series: List<String>,
    val directors: List<String>,
    val composers: List<String>,
    val platforms: List<String>,
    val ageRatings: List<String>,
    val scores: List<ReviewScore>,
    val links: List<ExternalLink>,
    /** Slug do jogo no IGDB, que o Backloggd também usa. */
    val igdbSlug: String?,
    /** ID do jogo no HowLongToBeat (P2816), de onde vêm os tempos de jogo. */
    val hltbId: String? = null,
)
