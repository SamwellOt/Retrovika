package com.retrovika.app.core.textmem

import java.nio.charset.Charset

/**
 * Acha na RAM um texto que o jogador vê na tela, para virar um [TextHook]. Tenta, nesta ordem, ASCII, UTF-16 (LE e BE),
 * Shift-JIS e uma tabela linear (letras em sequência, mas com outro byte inicial: a busca relativa dos tradutores de
 * ROM, que compara as diferenças entre letras seguidas e não os valores).
 */
object TextSearch {

    data class Match(val address: Int, val encoding: TextEncoding, val table: LinearTable?, val preview: String, val length: Int)

    private val shiftJis: Charset = Charset.forName("Shift_JIS")
    private const val MIN_CHARS = 3
    private const val MAX_MATCHES = 12
    /** Quanto do buffer o gancho cobre a partir do achado: diálogos têm algumas linhas. */
    private const val HOOK_LENGTH = 256

    /**
     * Os lugares onde [text] aparece. [wordSwap] procura na RAM do N64 em ordem lógica (endereços de quem joga).
     * Textos curtos demais (menos de 3 caracteres) acham quase qualquer coisa e voltam vazios.
     */
    fun find(ram: ByteArray, text: String, wordSwap: Boolean = false, gridWidth: Int = 0): List<Match> {
        val needle = text.trim()
        if (needle.length < MIN_CHARS) return emptyList()
        val view = if (wordSwap) TextDecoder.slice(ram, 0, ram.size and 3.inv(), true) ?: return emptyList() else ram
        val out = mutableListOf<Match>()

        fun add(address: Int, encoding: TextEncoding, table: LinearTable?) {
            if (out.size >= MAX_MATCHES || out.any { it.address == address }) return
            val length = minOf(HOOK_LENGTH, view.size - address)
            val preview = TextDecoder.decode(view.copyOfRange(address, address + length), encoding, table).firstOrNull().orEmpty()
            out += Match(address, encoding, table, preview.take(80), length)
        }

        if (needle.all { it.code < 0x80 }) {
            indexesOf(view, needle.toByteArray(Charsets.ISO_8859_1)).forEach { add(it, TextEncoding.ASCII, null) }
        }
        val le = ByteArray(needle.length * 2) { i -> if (i % 2 == 0) needle[i / 2].code.toByte() else (needle[i / 2].code shr 8).toByte() }
        indexesOf(view, le).forEach { add(it, TextEncoding.UTF16LE, null) }
        val be = ByteArray(needle.length * 2) { i -> if (i % 2 == 1) needle[i / 2].code.toByte() else (needle[i / 2].code shr 8).toByte() }
        indexesOf(view, be).forEach { add(it, TextEncoding.UTF16BE, null) }
        if (needle.any { it.code >= 0x80 }) {
            indexesOf(view, needle.toByteArray(shiftJis)).forEach { add(it, TextEncoding.SHIFT_JIS, null) }
        }
        linearMatches(view, needle).forEach { (address, table) ->
            add(address, TextEncoding.TABLE, completeTable(table, view, address, gridWidth))
        }
        return out
    }

    /**
     * Completa o que a busca não pôde aprender (o texto buscado pode não ter espaço, dígito nem pontuação): o espaço é o
     * byte mais comum na linha ([gridWidth], numa tela de texto) ou no buffer, se domina; e quando o espaço é 0x20 e o
     * alfabeto começa em 1, é um código de tela do C64: 0x20 a 0x3F são ASCII.
     */
    internal fun completeTable(table: LinearTable, data: ByteArray, at: Int, gridWidth: Int): LinearTable {
        var result = table
        if (result.space == null) {
            val size = (if (gridWidth > 0) gridWidth else HOOK_LENGTH).coerceAtMost(data.size - at)
            val counts = IntArray(256)
            for (i in 0 until size) counts[data[at + i].toInt() and 0xFF]++
            val best = counts.indices.maxByOrNull { counts[it] } ?: 0
            val inLetters = (table.upper != null && best in table.upper..table.upper + 25) || (table.lower != null && best in table.lower..table.lower + 25)
            if (size > 0 && counts[best] * 100 >= size * 40 && !inLetters && best != table.terminator) result = result.copy(space = best)
        }
        if (result.space == 0x20 && (result.upper == 1 || result.lower == 1)) result = result.copy(asciiLow = true)
        return result
    }

    /** Posições de [pattern] em [data], até o limite de achados. */
    internal fun indexesOf(data: ByteArray, pattern: ByteArray, limit: Int = MAX_MATCHES): List<Int> {
        if (pattern.isEmpty() || pattern.size > data.size) return emptyList()
        val out = mutableListOf<Int>()
        val first = pattern[0]
        var i = 0
        val last = data.size - pattern.size
        while (i <= last && out.size < limit) {
            if (data[i] == first) {
                var k = 1
                while (k < pattern.size && data[i + k] == pattern[k]) k++
                if (k == pattern.size) out += i
            }
            i++
        }
        return out
    }

    /**
     * Busca relativa: as letras minúsculas (ou maiúsculas) seguidas do texto têm entre si as mesmas diferenças que na
     * tabela do jogo, mesmo com outro byte inicial. Do achado sai a tabela: o byte do "a" (ou do "A"), e os de
     * maiúsculas, dígitos e espaço que o texto também traga.
     */
    private fun linearMatches(data: ByteArray, needle: String): List<Pair<Int, LinearTable>> {
        val runStart = longestLetterRun(needle) ?: return emptyList()
        val (from, to) = runStart
        val run = needle.substring(from, to)
        val diffs = IntArray(run.length - 1) { run[it + 1].code - run[it].code }
        val results = mutableListOf<Pair<Int, LinearTable>>()
        var i = 0
        val last = data.size - needle.length
        while (i <= last && results.size < MAX_MATCHES) {
            // O começo do achado é o do texto inteiro: a corrida de letras fica "from" bytes depois.
            val p = i + from
            var ok = true
            for (k in diffs.indices) {
                if ((data[p + k + 1].toInt() and 0xFF) - (data[p + k].toInt() and 0xFF) != diffs[k]) { ok = false; break }
            }
            if (ok) tableFor(data, i, needle)?.let { results += i to it }
            i++
        }
        return results
    }

    private fun longestLetterRun(text: String): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var start = -1
        var kind = 0   // 1 minúscula, 2 maiúscula
        fun close(end: Int) {
            if (start >= 0 && end - start >= MIN_CHARS && (best == null || end - start > best!!.second - best!!.first)) best = start to end
        }
        for (i in text.indices) {
            val k = when (text[i]) { in 'a'..'z' -> 1; in 'A'..'Z' -> 2; else -> 0 }
            if (k != kind) { close(i); start = if (k == 0) -1 else i; kind = k }
        }
        close(text.length)
        return best
    }

    /** A tabela que faz os bytes de [at] lerem exatamente [needle]; nula se as letras do achado não são coerentes. */
    private fun tableFor(data: ByteArray, at: Int, needle: String): LinearTable? {
        var lower: Int? = null
        var upper: Int? = null
        var digit: Int? = null
        var space: Int? = null
        val extra = mutableMapOf<String, Int>()
        for (k in needle.indices) {
            val b = data[at + k].toInt() and 0xFF
            val c = needle[k]
            when (c) {
                in 'a'..'z' -> { val base = b - (c - 'a'); if (lower != null && lower != base) return null; lower = base }
                in 'A'..'Z' -> { val base = b - (c - 'A'); if (upper != null && upper != base) return null; upper = base }
                in '0'..'9' -> { val base = b - (c - '0'); if (digit != null && digit != base) return null; digit = base }
                ' ' -> { if (space != null && space != b) return null; space = b }
                // Pontuação: o byte que o jogo usa para ela vem do próprio achado.
                else -> { val known = extra[c.toString()]; if (known != null && known != b) return null; extra[c.toString()] = b }
            }
        }
        // Sem maiúsculas no texto, a tabela só sabe as minúsculas (e vice-versa).
        if (lower == null && upper == null) return null
        // ASCII puro já foi achado pela busca exata.
        if ((lower == null || lower == 'a'.code) && (upper == null || upper == 'A'.code)) return null
        if (lower != null && lower < 0 || upper != null && upper < 0) return null
        // O bloco que o texto não mostrou fica de fora: a tradução o dobra para o que existe, em vez de chutar um byte.
        return LinearTable(upper = upper, lower = lower, digit = digit, space = space, extra = extra)
    }
}
