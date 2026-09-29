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

    /**
     * Junta as linhas do OCR em trechos (um balão de diálogo, uma placa): linhas de altura parecida, uma logo
     * abaixo da outra e alinhadas. Linhas curtas ficam sozinhas, porque em menus cada item é uma opção
     * ("つづきから" / "はじめから"); juntar faria o tradutor ler uma frase sem sentido.
     */
    fun groupLines(lines: List<OcrLine>): List<OcrBlock> {
        val sorted = lines.filter { !it.box.isEmpty && it.text.isNotBlank() }
            .sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val group = if (line.vertical) null else groups.lastOrNull { canJoin(it, line) }
            if (group != null) group += line else groups += mutableListOf(line)
        }
        return groups.map { group ->
            val box = group.drop(1).fold(group.first().box) { acc, l -> acc.union(l.box) }
            OcrBlock(box, joinLines(group.map { it.text }), group)
        }
    }

    private fun canJoin(group: List<OcrLine>, line: OcrLine): Boolean {
        val upper = group.last()
        if (upper.vertical) return false
        val low = minOf(upper.box.height, line.box.height).coerceAtLeast(1)
        val high = maxOf(upper.box.height, line.box.height)
        if (high > low * 1.6f) return false
        val gap = line.box.top - upper.box.bottom
        if (gap > high * 0.9f || gap < -high * 0.5f) return false
        val groupBox = group.drop(1).fold(group.first().box) { acc, l -> acc.union(l.box) }
        val xOverlap = minOf(groupBox.right, line.box.right) - maxOf(groupBox.left, line.box.left)
        if (xOverlap <= 0 && kotlin.math.abs(line.box.left - groupBox.left) > high * 2) return false
        // Linha de cima comprida: é texto corrido que quebrou. Curta: item de menu.
        val chars = upper.text.count { !it.isWhitespace() }
        return if (hasJapanese(upper.text)) chars >= 6 else chars >= 12
    }

    /** Linha que é só pontuação, números ou um sinal solto (ícones e bordas que o OCR leu como letra). */
    fun isNoise(text: String): Boolean {
        val meaningful = text.count { it.isLetter() }
        return meaningful == 0 || (meaningful == 1 && !hasJapanese(text))
    }

    /**
     * Fator inteiro de ampliação do quadro nativo antes do OCR: letras de pixel com 8 a 16 px de altura são
     * pequenas demais para os reconhecedores. Ampliar pelo vizinho mais próximo mantém as bordas nítidas.
     */
    fun upscaleFor(width: Int, height: Int, targetHeight: Int = 720, maxWidth: Int = 2048): Int {
        if (width <= 0 || height <= 0 || height >= targetHeight * 3 / 4) return 1
        val wanted = (targetHeight + height - 1) / height
        return wanted.coerceAtMost((maxWidth / width).coerceAtLeast(1)).coerceAtLeast(1)
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
