package com.retrovika.app.core.translate

import kotlin.math.max
import kotlin.math.min

/** Retângulo em pixels, sem Android: as regras de texto rodam nos testes da JVM. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

    /** Área em comum dividida pela área da menor das duas (1 quando uma está dentro da outra). */
    fun overlapOfSmaller(other: Box): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0 || h <= 0) return 0f
        val smaller = min(width.toLong() * height, other.width.toLong() * other.height).coerceAtLeast(1)
        return (w.toLong() * h).toFloat() / smaller
    }

    fun scaled(sx: Float, sy: Float, dx: Int = 0, dy: Int = 0) =
        Box(dx + (left * sx).toInt(), dy + (top * sy).toInt(), dx + (right * sx).toInt(), dy + (bottom * sy).toInt())
}

/** Uma linha lida pelo OCR, em pixels da imagem que ele recebeu. */
data class OcrLine(val box: Box, val text: String, val confidence: Float, val vertical: Boolean = false)

/** Um trecho de texto (balão, item de menu) formado por uma ou mais linhas. */
data class OcrBlock(val box: Box, val text: String, val lines: List<OcrLine>)

/** Um trecho achado na tela, onde ele está (em pixels da view do jogo) e a tradução. */
data class TranslatedBlock(val box: Box, val original: String, val translated: String)
