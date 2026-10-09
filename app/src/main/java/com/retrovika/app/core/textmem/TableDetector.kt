package com.retrovika.app.core.textmem

/**
 * Descobre sozinho a tabela de caracteres de um jogo em inglês, sem um texto conhecido: muitos jogos guardam as letras
 * em sequência (a, b, c... em bytes seguidos) a partir de um byte qualquer. Para cada byte inicial possível, o trecho é
 * lido como letras e o que sai é conferido com as palavras mais comuns do inglês; só a tabela certa forma palavras.
 * Dali saem o espaço (um dos bytes mais frequentes), o bloco das maiúsculas (o que mais forma palavras com a inicial
 * maiúscula), o ponto e a vírgula (pelo que vem antes de maiúscula ou de minúscula) e o terminador.
 */
object TableDetector {

    class Detected(val table: LinearTable, val words: Int)

    /** Palavras de 3 letras ou mais (as de 2 acertam demais por acaso). */
    private val dictionary: Set<String> = (
        "the and you that for are was but not have with this from they will would there their what about which when your " +
            "can said each she how him his her has had were been one all out other then them these some could into time two " +
            "more write see him now than like only come over think also back after use work first well way even new want " +
            "because any give day most our may get good know take people year just see look made find going where here " +
            "much let did own too off tell hello thank thanks please yes why who help need must should never always again " +
            "world world town city king queen hero sword magic master power home door room house road forest cave castle " +
            "village night away down live love life dead death fire water earth wind mountain person friend enemy monster " +
            "battle fight win lost story begin start end stop wait run come go gone stay left right front long little big " +
            "old young great small many few every very quite still once ever before while through under between both " +
            "something nothing everything someone anyone people name place thing man woman boy girl child father mother " +
            "brother sister cat dog bird sat mat nearby welcome traveler adventure quest item found treasure gold money " +
            "buy sell shop inn rest sleep health strength attack defense level exp experience learned lost defeated " +
            "morning evening afternoon today tomorrow yesterday goodbye sorry okay really maybe together remember believe " +
            "special strange dangerous legend journey princess dragon wizard knight ghost secret key book map letter " +
            "friend friends meet meeting thank welcome trouble problem danger safe save lead follow carry bring open close " +
            "stone river island ocean sea ship boat bridge tower temple church school market garden field farm animal " +
            "pokemon trainer gym badge ball potion medicine heal cure poison sleep wake strong weak fast slow hard easy " +
            "angry happy sad scared afraid brave kind smart funny strange beautiful powerful ancient holy dark light"
        ).split(' ').filter { it.length >= 3 }.toSet()

    /** "ood" -> "good": a inicial maiúscula tem um byte que ainda não se conhece; o resto da palavra vale como prova. */
    private val suffixes: Map<String, String> = dictionary.filter { it.length >= 4 }.associateBy { it.substring(1) }

    private const val MIN_WORDS = 3
    private const val MIN_CHARS = 12

    /** Lê [window] como uma tabela linear de inglês; nulo se nada nele forma palavras. */
    fun detect(window: ByteArray): Detected? {
        if (window.size < MIN_CHARS) return null
        var bestLower = -1
        var base: Score? = null
        for (lower in 0..230) {
            val score = score(window, lower, null)
            if (score.chars >= MIN_CHARS && score.words.size >= MIN_WORDS && (base == null || score.chars > base.chars)) {
                bestLower = lower
                base = score
            }
        }
        val lower = bestLower
        if (base == null) return null
        // O espaço: o byte que mais fica entre duas letras (os zeros em volta do buffer não contam).
        val between = IntArray(256)
        for (i in 1 until window.size - 1) {
            if ((window[i - 1].toInt() and 0xFF) in lower..lower + 25 && (window[i + 1].toInt() and 0xFF) in lower..lower + 25) {
                between[window[i].toInt() and 0xFF]++
            }
        }
        val space = (0 until 256).filter { it !in lower..lower + 25 }.maxByOrNull { between[it] }?.takeIf { between[it] > 0 } ?: return null
        // ASCII puro já tem o seu caminho.
        if (lower == 'a'.code && space == 0x20) return null

        // Maiúsculas: o bloco que mais palavras forma com a inicial maiúscula.
        var upper: Int? = null
        var bestChars = score(window, lower, null, suffix = false).chars
        for (u in listOf(lower - 26, lower + 26, lower - 32, lower + 32) + (0..230)) {
            if (u < 0 || u > 230 || u == space || u in lower - 25..lower + 25) continue
            val s = score(window, lower, u)
            if (s.chars > bestChars) { bestChars = s.chars; upper = u }
        }

        val extra = mutableMapOf<String, Int>()
        val stops = mutableMapOf<Int, Int>()   // byte -> vezes antes de espaço + maiúscula
        val commas = mutableMapOf<Int, Int>()  // byte -> vezes antes de espaço + minúscula
        fun letter(b: Int) = b in lower..lower + 25 || (upper != null && b in upper..upper + 25)
        for (i in 1 until window.size - 2) {
            val p = window[i].toInt() and 0xFF
            if (letter(p) || p == space || !letter(window[i - 1].toInt() and 0xFF)) continue
            if ((window[i + 1].toInt() and 0xFF) != space) continue
            val next = window[i + 2].toInt() and 0xFF
            if (upper != null && next in upper..upper + 25) stops.merge(p, 1, Int::plus)
            else if (next in lower..lower + 25) commas.merge(p, 1, Int::plus)
        }
        // O ponto só se aprende com pelo menos duas ocorrências: com uma, o byte pode ser "!" ou "?", e errar a pontuação é pior que omitir.
        stops.maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.let { extra["."] = it.key }
        commas.maxByOrNull { it.value }?.takeIf { it.key != extra["."] }?.let { extra[","] = it.key }

        // O terminador: o que fecha o texto. Pontuação do meio da frase (seguida de espaço) não conta; quando o texto
        // acaba em pontuação seguida do terminador, vale o byte depois dela.
        val mid = HashSet<Int>()
        for (i in 1 until window.size - 1) {
            val p = window[i].toInt() and 0xFF
            if (!letter(p) && p != space && letter(window[i - 1].toInt() and 0xFF) && (window[i + 1].toInt() and 0xFF) == space) mid += p
        }
        val enders = mutableMapOf<Int, Double>()
        fun vote(v: Int, weight: Double) { if (!letter(v) && v != space && v !in mid) enders.merge(v, weight, Double::plus) }
        var i = 0
        while (i < window.size - 1) {
            if (letter(window[i].toInt() and 0xFF) && letter(window[maxOf(0, i - 1)].toInt() and 0xFF) && !letter(window[i + 1].toInt() and 0xFF)) {
                val x = window[i + 1].toInt() and 0xFF
                vote(x, 1.0)
                if (i + 2 < window.size) vote(window[i + 2].toInt() and 0xFF, 1.01)
            }
            i++
        }
        val terminator = enders.maxByOrNull { it.value }?.key ?: 0xFF
        val table = LinearTable(upper = upper, lower = lower, space = space, terminator = terminator, extra = extra)
        return Detected(table, base.words.size)
    }

    private class Score(val chars: Int, val words: Set<String>)

    /** Lê o trecho com as letras a partir de [lower] (e de [upper], se houver) e soma o que forma palavras do dicionário. */
    private fun score(w: ByteArray, lower: Int, upper: Int?, suffix: Boolean = true): Score {
        val found = HashSet<String>()
        var chars = 0
        val sb = StringBuilder()
        fun flush() {
            if (sb.length >= 3) {
                val word = sb.toString()
                if (word in dictionary) { found += word; chars += word.length }
                else if (suffix && upper == null) suffixes[word]?.let { found += it; chars += it.length }
            }
            sb.setLength(0)
        }
        for (raw in w) {
            val b = raw.toInt() and 0xFF
            when {
                b in lower..lower + 25 -> sb.append('a' + (b - lower))
                upper != null && b in upper..upper + 25 -> sb.append('a' + (b - upper))
                else -> flush()
            }
        }
        flush()
        return Score(chars, found)
    }
}
