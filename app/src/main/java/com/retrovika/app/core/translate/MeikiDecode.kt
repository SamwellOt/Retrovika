package com.retrovika.app.core.translate

/**
 * A parte do MeikiOCR (github.com/rtr46/meikiocr, Apache-2.0) que não depende do ONNX: o reconhecedor
 * não lê uma sequência, ele detecta cada caractere com uma caixa e uma nota. Aqui as detecções de uma linha
 * viram texto: descarta as fracas, fica com a melhor quando duas se sobrepõem e ordena pela posição.
 */
object MeikiDecode {

    /** Um caractere detectado, em pixels da imagem; [start]/[end] são a faixa no sentido da leitura. */
    data class Candidate(val char: String, val box: Box, val conf: Float, val start: Int, val end: Int)

    const val DET_THRESHOLD = 0.5f
    const val REC_THRESHOLD = 0.1f
    /** Pontuação perde para letra sobreposta (o perfil do MeikiPop, que lê telas de jogo). */
    const val PUNCT_FACTOR = 0.2f
    private const val OVERLAP_THRESHOLD = 0.3f

    private val SWAPPED_PAIRS = mapOf(
        "儡傀" to "傀儡", "談冗" to "冗談", "汰淘" to "淘汰", "沱滂" to "滂沱",
        "攣痙" to "痙攣", "酊酩" to "酩酊", "麭麺" to "麺麭", "哭慟" to "慟哭",
    )

    /** Texto e nota média (0 a 1) da linha. */
    fun decode(candidates: List<Candidate>, punctFactor: Float = PUNCT_FACTOR): Pair<String, Float> {
        val weighted = candidates.map { if (isPunctuation(it.char)) it.copy(conf = it.conf * punctFactor) else it }
        val accepted = mutableListOf<Candidate>()
        for (c in weighted.sortedByDescending { it.conf }) {
            val len = c.end - c.start + 1e-6f
            val overlaps = accepted.any { a ->
                if (c.start >= a.end || a.start >= c.end) return@any false
                val inter = (minOf(c.end, a.end) - maxOf(c.start, a.start)).coerceAtLeast(0)
                inter / minOf(len, a.end - a.start + 1e-6f) > OVERLAP_THRESHOLD
            }
            if (!overlaps) accepted += c
        }
        accepted.sortBy { it.start }
        var text = accepted.joinToString("") { it.char }
        for ((wrong, right) in SWAPPED_PAIRS) text = text.replace(wrong, right)
        val conf = if (accepted.isEmpty()) 0f else accepted.map { it.conf }.average().toFloat()
        return text to conf
    }

    fun isPunctuation(char: String): Boolean {
        val cp = char.codePointAt(0)
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    /**
     * Converte a caixa de um caractere, dada no espaço da entrada do reconhecedor, para a imagem. Horizontal:
     * a linha foi redimensionada para [effectiveW] x [effectiveH] dentro de 960 x 32; vertical, para
     * 32 x [effectiveH] dentro de 32 x 480. Nulo para detecções no enchimento (fora do conteúdo).
     */
    fun toImage(
        code: Int, x1: Float, y1: Float, x2: Float, y2: Float, conf: Float,
        crop: Box, effectiveW: Int, effectiveH: Int, vertical: Boolean, inputW: Int, inputH: Int,
    ): Candidate? {
        if (conf < REC_THRESHOLD || code <= 0 || !Character.isValidCodePoint(code)) return null
        val char = String(Character.toChars(code))
        return if (!vertical) {
            if (x1 >= effectiveW) return null
            val cx1 = minOf(x1, effectiveW.toFloat()) / effectiveW * crop.width
            val cx2 = minOf(x2, effectiveW.toFloat()) / effectiveW * crop.width
            val cy1 = y1 / inputH * crop.height
            val cy2 = y2 / inputH * crop.height
            val box = Box(crop.left + cx1.toInt(), crop.top + cy1.toInt(), crop.left + cx2.toInt(), crop.top + cy2.toInt())
            Candidate(char, box, conf, box.left, box.right)
        } else {
            if (y1 >= effectiveH) return null
            val cx1 = x1 / inputW * crop.width
            val cx2 = x2 / inputW * crop.width
            val cy1 = minOf(y1, effectiveH.toFloat()) / effectiveH * crop.height
            val cy2 = minOf(y2, effectiveH.toFloat()) / effectiveH * crop.height
            val box = Box(crop.left + cx1.toInt(), crop.top + cy1.toInt(), crop.left + cx2.toInt(), crop.top + cy2.toInt())
            if (box.bottom <= box.top) return null
            Candidate(char, box, conf, box.top, box.bottom)
        }
    }

    /**
     * Onde cortar uma linha vertical alta demais para os 480 px do reconhecedor: trechos de 420 px (na escala
     * do modelo) com 64 px de sobreposição, o último encostado no fim. Devolve o topo de cada trecho e a
     * altura dele, em pixels da imagem.
     */
    fun verticalSegments(top: Int, bottom: Int, scale: Float): Pair<List<Float>, Float> {
        val h = bottom - top
        if (h * scale <= 480f) return listOf(top.toFloat()) to h.toFloat()
        val segment = 420f / scale
        val stride = (420f - 64f) / scale
        val starts = mutableListOf<Float>()
        var y = top.toFloat()
        while (y + segment < bottom) { starts += y; y += stride }
        val last = bottom - segment
        if (starts.isEmpty() || last > starts.last() + 1f) starts += last
        return starts to segment
    }
}
