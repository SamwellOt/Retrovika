package com.retrovika.app.core.library

/**
 * Versões do mesmo jogo na biblioteca: (USA), (Europe), (Japan), revisões, traduções, dumps ruins…
 * Agrupa os arquivos pelo título limpo (o nome No-Intro quando o jogo foi identificado pelo DAT) e
 * aponta a melhor versão para quem joga no idioma do app.
 *
 * Só olha o nome dos arquivos (e a verificação por hash, quando já feita): nada é lido do disco aqui.
 */
object Versions {

    /** Etiquetas que explicam a nota de uma versão, mostradas ao lado dela. */
    enum class Tag { VERIFIED, GOOD_DUMP, BAD_DUMP, HACK, TRAINER, OVERDUMP, PIRATE, PRERELEASE, UNLICENSED, TRANSLATION, REVISION }

    data class Rated(val game: Game, val score: Int, val tags: Set<Tag>)

    /** [best] é a recomendada; [others], as demais da melhor para a pior. */
    data class Group(val title: String, val best: Rated, val others: List<Rated>) {
        val all: List<Rated> get() = listOf(best) + others
    }

    private val discRegex = Regex("""\((?:disc|disk|cd)\s*(\d+)[^)]*\)|\(side\s*([ab])\)""", RegexOption.IGNORE_CASE)
    private val revRegex = Regex("""\(rev\s*([0-9a-z.]+)\)""", RegexOption.IGNORE_CASE)
    private val versionRegex = Regex("""\(v\s*(\d+)(?:\.(\d+))?[^)]*\)""", RegexOption.IGNORE_CASE)
    private val prereleaseRegex = Regex("""\((?:beta|proto|prototype|demo|sample|preview|alpha|kiosk)[^)]*\)""", RegexOption.IGNORE_CASE)

    /**
     * Chave de agrupamento: o título limpo, sem pontuação nem caixa. Discos e lados diferentes do mesmo
     * jogo não são versões um do outro (sugerir apagar o disco 2 seria um desastre): o número entra na chave.
     */
    fun groupKey(game: Game): String {
        val name = game.datName ?: game.rawName
        val base = RomNaming.cleanTitle(name).lowercase().filter { it.isLetterOrDigit() }
        val disc = discRegex.find(game.rawName)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2].lowercase() } }
        return if (disc != null) "$base#$disc" else base
    }

    fun rate(game: Game, language: String): Rated {
        val raw = game.rawName
        val lower = raw.lowercase()
        val name = game.datName ?: raw
        val tags = mutableSetOf<Tag>()
        var score = 0

        if (game.verified) { score += 120; tags += Tag.VERIFIED }
        // Etiquetas do GoodTools: [!] dump conferido; [b] ruim; [h] hack; [t] trainer; [o] overdump; [p] pirata; [f] corrigido.
        if ("[!]" in raw) { score += 60; tags += Tag.GOOD_DUMP }
        if (goodTool(lower, 'b')) { score -= 1000; tags += Tag.BAD_DUMP }
        if (goodTool(lower, 'h') || "(hack)" in lower) { score -= 400; tags += Tag.HACK }
        if (goodTool(lower, 't')) { score -= 300; tags += Tag.TRAINER }
        if (goodTool(lower, 'o')) { score -= 250; tags += Tag.OVERDUMP }
        if (goodTool(lower, 'p') || "(pirate)" in lower) { score -= 250; tags += Tag.PIRATE }
        if (goodTool(lower, 'f')) score -= 20
        if (prereleaseRegex.containsMatchIn(name)) { score -= 200; tags += Tag.PRERELEASE }
        if ("(unl)" in lower) { score -= 15; tags += Tag.UNLICENSED }

        val translation = translationLanguage(raw)
        if (translation != null) tags += Tag.TRANSLATION
        score += regionScore(RomNaming.region(name), translation, language)

        revision(name)?.let { rev -> if (rev > 0) { score += (rev * 4).coerceAtMost(40); tags += Tag.REVISION } }
        return Rated(game, score, tags)
    }

    /**
     * Pontos pela região no idioma do app. Em português, uma tradução para o português vale mais que o
     * original em inglês (é o que torna jogáveis os títulos que só saíram no Japão); em inglês, uma
     * versão oficial americana ou europeia vence a tradução de fã. O Japão fica por último: é a versão
     * que precisa de tradução.
     */
    private fun regionScore(region: String?, translation: String?, language: String): Int {
        val ownTranslation = when (translation) {
            null -> false
            "por" -> language == "pt"
            "eng" -> language == "en"
            else -> false
        }
        if (ownTranslation) return if (language == "pt") 95 else 60
        // Tradução para outro idioma: o jogo continua ilegível, mas não é pior que o japonês original.
        if (translation != null) return 5
        val order = if (language == "pt") {
            listOf("Brasil", "EUA", "Mundo", "Europa", "Austrália", "Coreia", "China", "Japão")
        } else {
            listOf("EUA", "Mundo", "Europa", "Austrália", "Brasil", "Coreia", "China", "Japão")
        }
        val index = order.indexOf(region)
        return when {
            region == null -> 30
            index < 0 -> 25
            else -> 90 - index * 10
        }
    }

    /** "[T+Por]", "[T-Eng]", "(Traducao PT-BR)"… -> "por"/"eng"/outro código em minúsculas. */
    fun translationLanguage(raw: String): String? {
        Regex("""\[t[+-]([a-z]{2,3})""", RegexOption.IGNORE_CASE).find(raw)?.let { m ->
            return when (val code = m.groupValues[1].lowercase()) {
                "por", "pt", "bra", "br" -> "por"
                "eng", "en" -> "eng"
                else -> code
            }
        }
        val lower = raw.lowercase()
        if (Regex("""pt[-_ ]?br|tradu[cç][aã]o|traduzido""").containsMatchIn(lower)) return "por"
        if (Regex("""\((?:english translation|translated|eng translation)[^)]*\)""").containsMatchIn(lower)) return "eng"
        return null
    }

    /** "(Rev 2)" -> 2, "(Rev A)" -> 1, "(v1.1)" -> 1; sem revisão, nulo. */
    fun revision(name: String): Int? {
        revRegex.find(name)?.let { m ->
            val v = m.groupValues[1]
            v.toIntOrNull()?.let { return it }
            v.toDoubleOrNull()?.let { return (it * 10).toInt() }
            if (v.length == 1 && v[0].isLetter()) return v[0].lowercaseChar() - 'a' + 1
        }
        versionRegex.find(name)?.let { m ->
            val major = m.groupValues[1].toIntOrNull() ?: 0
            val minor = m.groupValues[2].toIntOrNull() ?: 0
            return (major - 1).coerceAtLeast(0) * 10 + minor
        }
        return null
    }

    /** Etiqueta GoodTools "[x]", "[x1]", "[h1c]"… (não confunde com "[t+eng]", que é tradução). */
    private fun goodTool(lower: String, flag: Char): Boolean = Regex("""\[$flag[0-9a-z]*]""").containsMatchIn(lower)

    /** Os grupos com mais de uma versão, do maior para o menor; empates pela melhor nota. */
    fun groups(games: List<Game>, language: String): List<Group> =
        games.groupBy(::groupKey).values
            .filter { it.size > 1 }
            .map { versions ->
                val rated = versions.map { rate(it, language) }
                    .sortedWith(compareByDescending<Rated> { it.score }.thenByDescending { it.game.playTimeSeconds }.thenBy { it.game.id })
                Group(RomNaming.cleanTitle(rated.first().game.datName ?: rated.first().game.rawName), rated.first(), rated.drop(1))
            }
            .sortedWith(compareByDescending<Group> { it.all.size }.thenBy { it.title.lowercase() })

    /** O grupo do jogo [game] dentro de [games], ou nulo se não houver outra versão dele. */
    fun groupOf(game: Game, games: List<Game>, language: String): Group? {
        val key = groupKey(game)
        val same = games.filter { groupKey(it) == key }
        return if (same.size > 1) groups(same, language).firstOrNull() else null
    }
}
