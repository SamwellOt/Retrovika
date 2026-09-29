package com.retrovika.app.core.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray

/** Regras de texto da tradução ao vivo, sem Android: o que traduzir, como juntar linhas, como ler a resposta. */
object TranslationText {

    /** Hiragana, katakana (inclusive meia largura) ou kanji. */
    fun hasJapanese(text: String): Boolean = text.any { c ->
        c in '぀'..'ヿ' || c in '一'..'鿿' || c in 'ｦ'..'ﾟ' || c in '㐀'..'䶿'
    }

    /** Palavras em alfabeto latino suficientes para valer a tradução (não "HP 25" ou "x3"). */
    fun hasLatinWords(text: String): Boolean =
        Regex("""[A-Za-z]{2,}""").findAll(text).count() >= 2 || Regex("""[A-Za-z]{4,}""").containsMatchIn(text)

    /**
     * Idioma de origem do trecho, ou nulo se não há o que traduzir para [target] ("pt"/"en"): japonês
     * sempre; inglês só para quem lê em português (menus e diálogos de jogos americanos também contam).
     */
    fun sourceFor(text: String, target: String): String? = when {
        hasJapanese(text) -> "ja"
        target != "en" && hasLatinWords(text) -> "en"
        else -> null
    }

    /** Japonês não separa palavras com espaço: as linhas de um balão se juntam direto; o resto, com espaço. */
    fun joinLines(lines: List<String>): String {
        val cleaned = lines.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return ""
        return cleaned.reduce { acc, line ->
            if (hasJapanese(acc.last().toString()) && hasJapanese(line.first().toString())) acc + line else "$acc $line"
        }
    }

    /** Resposta do tradutor web (`translate_a/single?client=gtx&dt=t`): a tradução de cada frase, em ordem. */
    fun parseWebResponse(body: String): String? = runCatching {
        val root = Json.parseToJsonElement(body).jsonArray
        val sentences = root[0] as? JsonArray ?: return null
        sentences.mapNotNull { s -> ((s as? JsonArray)?.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .joinToString("")
            .trim()
            .ifEmpty { null }
    }.getOrNull()
}
