package com.retrovika.app.core.textmem

import java.text.Normalizer

/**
 * Volta um texto traduzido para os bytes que o jogo entende: o inverso do [TextDecoder]. As fontes dos jogos raramente
 * têm acentos, então o texto passa por uma transliteração (ã vira a, é vira e) onde a codificação não os guarda; o que
 * ainda não cabe vira "?" (ou some, nas tabelas). O resultado precisa caber no espaço que o jogo deixou: se não cabe, o
 * texto é enxugado e, por último, cortado numa palavra.
 */
object TextEncoder {

    data class Encoded(val bytes: ByteArray, val truncated: Boolean) {
        override fun equals(other: Any?) = other is Encoded && truncated == other.truncated && bytes.contentEquals(other.bytes)
        override fun hashCode() = 31 * bytes.contentHashCode() + truncated.hashCode()
    }

    /**
     * [text] nos bytes de [hook], em no máximo [maxBytes] (sem o terminador). [lineWidth]: o jogo já quebra o texto em
     * linhas dessa largura, e a tradução é quebrada igual (com o byte 0x0A); nulo = uma linha só.
     */
    fun encode(text: String, hook: TextHook, maxBytes: Int, lineWidth: Int? = null): Encoded? {
        if (maxBytes <= 0) return null
        val canBreak = hook.encoding != TextEncoding.TABLE && lineWidth != null && lineWidth > 0
        var candidate = text.split(' ', '\n', '\r', '\t').filter { it.isNotEmpty() }.joinToString(" ")
        var truncated = false
        fun render(t: String): ByteArray? = bytesOf(if (canBreak) wrap(t, lineWidth!!) else t, hook)
        var bytes = render(candidate) ?: return null
        if (bytes.size > maxBytes) {
            // Corta em palavras inteiras, com reticências quando couberem.
            val words = candidate.split(' ')
            var kept = words.size
            while (kept > 1 && (render(words.take(kept).joinToString(" ") + ELLIPSIS)?.size ?: Int.MAX_VALUE) > maxBytes) kept--
            candidate = words.take(kept).joinToString(" ") + ELLIPSIS
            bytes = render(candidate) ?: return null
            if (bytes.size > maxBytes) {
                // Nem a primeira palavra cabe: corta letras.
                var letters = candidate.length
                while (letters > 1 && (render(candidate.take(letters))?.size ?: Int.MAX_VALUE) > maxBytes) letters--
                bytes = render(candidate.take(letters)) ?: return null
                if (bytes.size > maxBytes) return null
            }
            truncated = true
        }
        return Encoded(bytes, truncated)
    }

    /** O terminador do texto no jogo: 1 byte (ou 2 no UTF-16). */
    fun terminator(hook: TextHook): ByteArray = when (hook.encoding) {
        TextEncoding.UTF16LE, TextEncoding.UTF16BE -> byteArrayOf(0, 0)
        TextEncoding.TABLE -> byteArrayOf(requireNotNull(hook.table).terminator.toByte())
        else -> byteArrayOf(0)
    }

    private const val ELLIPSIS = "..."

    private fun bytesOf(text: String, hook: TextHook): ByteArray? = when (hook.encoding) {
        TextEncoding.ASCII -> ascii(text)
        TextEncoding.SHIFT_JIS -> ascii(text)   // ASCII é um byte só em Shift-JIS
        TextEncoding.UTF16LE -> text.toByteArray(Charsets.UTF_16LE)
        TextEncoding.UTF16BE -> text.toByteArray(Charsets.UTF_16BE)
        TextEncoding.TABLE -> table(text, requireNotNull(hook.table))
    }

    private fun ascii(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in plain(text)) {
            when {
                c == '\n' -> out.write(0x0A)
                c.code in 0x20..0x7E -> out.write(c.code)
                else -> out.write('?'.code)
            }
        }
        return out.toByteArray()
    }

    private fun table(text: String, t: LinearTable): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (c in plain(text)) {
            val byte: Int? = when (c) {
                // Sem o bloco de minúsculas (ou de maiúsculas) a letra é dobrada para o que a tabela tem.
                in 'A'..'Z' -> t.upper?.let { it + (c - 'A') } ?: t.lower?.let { it + (c - 'A') }
                in 'a'..'z' -> t.lower?.let { it + (c - 'a') } ?: t.upper?.let { it + (c - 'a') }
                in '0'..'9' -> t.digit?.let { it + (c - '0') } ?: if (t.asciiLow) c.code else null
                ' ' -> t.space ?: if (t.asciiLow) 0x20 else null
                else -> t.extra[c.toString()] ?: if (t.asciiLow && c.code in 0x20..0x3F) c.code else null
            }
            // Sem byte para o caractere: o espaço, se há; senão some.
            (byte ?: t.space)?.let { out.write(it) }
        }
        return out.toByteArray()
    }

    /** O byte que completa uma linha de uma tela de texto (o espaço do jogo). */
    fun fill(hook: TextHook): ByteArray = when (hook.encoding) {
        TextEncoding.TABLE -> byteArrayOf((requireNotNull(hook.table).space ?: hook.table.terminator).toByte())
        TextEncoding.UTF16LE -> byteArrayOf(0x20, 0)
        TextEncoding.UTF16BE -> byteArrayOf(0, 0x20)
        else -> byteArrayOf(0x20)
    }

    /** Sem acentos e com as aspas e traços "bonitos" trocados pelos simples. */
    private fun plain(text: String): String {
        val mapped = text.replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
            .replace('–', '-').replace('—', '-').replace("…", "...").replace('«', '"').replace('»', '"')
        return Normalizer.normalize(mapped, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    /** Quebra [text] em linhas de até [width] letras, só entre palavras (palavra maior que a linha fica sozinha). */
    fun wrap(text: String, width: Int): String {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in text.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) { lines += line.toString(); line = StringBuilder() }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines.joinToString("\n")
    }
}
