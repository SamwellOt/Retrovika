package com.retrovika.app.core.catalog

import androidx.annotation.StringRes
import com.retrovika.app.R

/**
 * Gêneros dos filtros especiais do Explorar. Nenhuma fonte tem uma taxonomia de gênero
 * padronizada, então cada uma filtra do jeito que consegue:
 * - com etiquetas (Homebrew Hub), comparando-as com [keywords] via [matches];
 * - com busca textual (CDRomance, Internet Archive), usando [searchTerm] e [keywords];
 * - com a taxonomia de gênero do próprio site (RomsFun, `RomsFunSource.GENRE_TERMS`).
 *
 * [searchTerm] é o termo em inglês que os sites usam nas descrições; [keywords] inclui
 * sinônimos e subgêneros (em minúsculas) que contam como o mesmo gênero.
 */
enum class Genre(@StringRes val label: Int, val searchTerm: String, val keywords: List<String>) {
    ACTION(R.string.genre_action, "action", listOf("action", "beat 'em up", "beat em up", "beat-em-up", "brawler", "hack and slash", "hack & slash")),
    ADVENTURE(R.string.genre_adventure, "adventure", listOf("adventure", "point and click", "point & click", "point-and-click", "exploration")),
    RPG(R.string.genre_rpg, "rpg", listOf("rpg", "jrpg", "role-playing", "role playing", "roleplaying", "dungeon crawler", "roguelike", "rogue-like")),
    PLATFORM(R.string.genre_platform, "platformer", listOf("platform", "platformer", "platforming", "metroidvania")),
    PUZZLE(R.string.genre_puzzle, "puzzle", listOf("puzzle", "logic", "match-3", "match 3", "falling blocks")),
    SHOOTER(R.string.genre_shooter, "shooter", listOf("shooter", "shmup", "shoot 'em up", "shoot em up", "shoot-em-up", "run and gun", "run 'n gun", "fps", "bullet hell")),
    RACING(R.string.genre_racing, "racing", listOf("racing", "racer", "driving", "kart")),
    SPORTS(R.string.genre_sports, "sports", listOf("sports", "sport", "football", "soccer", "basketball", "baseball", "tennis", "golf", "hockey", "wrestling", "boxing")),
    FIGHTING(R.string.genre_fighting, "fighting", listOf("fighting", "fighter", "versus fighting")),
    STRATEGY(R.string.genre_strategy, "strategy", listOf("strategy", "tactics", "tactical", "rts", "turn-based strategy", "wargame")),
    HORROR(R.string.genre_horror, "horror", listOf("horror", "survival horror")),
    MUSIC(R.string.genre_music, "rhythm", listOf("rhythm", "music", "musical", "dance")),
    ;

    /** Padrões por palavra inteira: "sport" não casa com "transport", nem "rts" com "parts". */
    private val patterns: List<Regex> by lazy {
        keywords.map { Regex("(?<![a-z0-9])" + Regex.escape(it) + "(?![a-z0-9])") }
    }

    /** Verdadeiro se alguma etiqueta (ou texto curto) cita este gênero ou um sinônimo dele. */
    fun matches(tags: Collection<String>): Boolean =
        tags.any { tag -> val t = tag.lowercase(); patterns.any { it.containsMatchIn(t) } }

    companion object {
        /** Gêneros citados nas etiquetas, na ordem do enum (para mostrar no cartão). */
        fun of(tags: Collection<String>): List<Genre> = entries.filter { it.matches(tags) }
    }
}
