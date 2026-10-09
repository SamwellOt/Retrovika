package com.retrovika.app.core.textmem

import java.nio.charset.Charset

/**
 * Um texto achado num buffer: onde começa ([offset], em bytes lógicos desde o começo do buffer), quantos bytes o texto
 * ocupa ([byteLength]) e quantos o jogo deixa para ele ([capacity]: o texto, o terminador e os terminadores seguintes que
 * sobram até o próximo conteúdo). [lineWidth] é o tamanho da maior linha quando o texto original tem quebras de linha.
 */
data class TextSegment(
    val offset: Int,
    val byteLength: Int,
    val capacity: Int,
    val terminatorBytes: Int,
    val text: String,
    val lineWidth: Int? = null,
)

/**
 * Lê o texto de um [TextHook] numa cópia da RAM. A RAM é a de [com.retrovika.app.core.cheats.RamSearch]: bytes na ordem
 * em que o núcleo os entrega; [TextHook.wordSwap] (N64) manda ler o byte lógico N em N xor 3.
 *
 * O resultado são os trechos do buffer (os textos de um jogo ficam separados por um byte nulo ou pelo terminador da
 * tabela), sem os vazios e sem códigos de controle; quebras de linha do jogo viram espaço.
 */
object TextDecoder {

    private val shiftJis: Charset = Charset.forName("Shift_JIS")

    fun read(ram: ByteArray, hook: TextHook): List<String> {
        val bytes = slice(ram, hook.address, hook.length, hook.wordSwap) ?: return emptyList()
        return decode(bytes, hook.encoding, hook.table)
    }

    /** Os bytes lógicos do trecho, ou nulo se ele sai da RAM. */
    fun slice(ram: ByteArray, address: Int, length: Int, wordSwap: Boolean): ByteArray? {
        if (address < 0 || length <= 0) return null
        val end = address.toLong() + length
        if (end > ram.size) return null
        if (!wordSwap) return ram.copyOfRange(address, address + length)
        return ByteArray(length) { i ->
            val physical = (address + i) xor 3
            if (physical < ram.size) ram[physical] else 0
        }
    }

    /** Os bytes lógicos da janela do [hook] lidos de [access] (com a inversão por palavra do N64, se ele pedir), ou nulo. */
    fun window(access: MemoryAccess, hook: TextHook): ByteArray? {
        if (!hook.wordSwap) return access.read(hook.address, hook.length)
        // O byte lógico N está em N xor 3: lê as palavras inteiras que cobrem a janela e reordena.
        val start = hook.address and 3.inv()
        val end = (hook.address + hook.length + 3) and 3.inv()
        val raw = access.read(start, end - start) ?: return null
        return ByteArray(hook.length) { i -> raw[((hook.address + i) xor 3) - start] }
    }

    fun segments(bytes: ByteArray, hook: TextHook): List<TextSegment> =
        if (hook.gridWidth > 0) gridSegments(bytes, hook) else segments(bytes, hook.encoding, hook.table)

    /**
     * Telas de texto: cada linha da grade é um segmento. Uma linha só vale se quase tudo nela é letra, número, espaço ou
     * pontuação conhecida (gráficos e memória qualquer não passam) e se tem palavras: assim a tradução nunca escreve numa
     * memória que só parece texto.
     */
    private fun gridSegments(bytes: ByteArray, hook: TextHook): List<TextSegment> {
        val width = hook.gridWidth
        val out = mutableListOf<TextSegment>()
        for (row in 0 until bytes.size / width) {
            val raw = bytes.copyOfRange(row * width, (row + 1) * width)
            val text = when (hook.encoding) {
                TextEncoding.TABLE -> decodeTable(raw, requireNotNull(hook.table))
                TextEncoding.ASCII -> String(raw.map { if (it.toInt() in 0x20..0x7E) it else 0x20 }.toByteArray(), Charsets.ISO_8859_1)
                else -> continue
            }
            val trimmed = clean(text)
            val decodable = when (hook.encoding) {
                TextEncoding.TABLE -> raw.count { isDecodable(it, requireNotNull(hook.table)) }
                else -> raw.count { it.toInt() in 0x20..0x7E }
            }
            if (decodable * 10 < width * 9 || trimmed.count(Char::isLetter) < 3 || !trimmed.contains(' ') && trimmed.length < 4) continue
            out += TextSegment(offset = row * width, byteLength = width, capacity = width, terminatorBytes = 0, text = trimmed)
        }
        return out
    }

    private fun isDecodable(raw: Byte, t: LinearTable): Boolean {
        val b = raw.toInt() and 0xFF
        return (t.upper != null && b in t.upper..t.upper + 25) || (t.lower != null && b in t.lower..t.lower + 25) ||
            (t.digit != null && b in t.digit..t.digit + 9) || (t.space != null && b == t.space) || t.extra.containsValue(b) ||
            (t.asciiLow && b in 0x20..0x3F)
    }

    fun segments(bytes: ByteArray, encoding: TextEncoding, table: LinearTable? = null): List<TextSegment> {
        val unit = if (encoding == TextEncoding.UTF16LE || encoding == TextEncoding.UTF16BE) 2 else 1
        val separator = if (encoding == TextEncoding.TABLE) requireNotNull(table).terminator else 0
        val out = mutableListOf<TextSegment>()
        var i = 0
        while (i + unit <= bytes.size) {
            if (isSeparator(bytes, i, unit, separator)) { i += unit; continue }
            var j = i
            while (j + unit <= bytes.size && !isSeparator(bytes, j, unit, separator)) j += unit
            // Os terminadores que seguem o texto também são espaço do jogo para ele, mas só até o dobro do texto mais um
            // pouco: um número zero logo depois de uma string (campo de uma estrutura) não é espaço para a tradução crescer.
            var end = j
            val room = (j - i) * 2 + 8 * unit
            while (end + unit <= bytes.size && end - i < room && isSeparator(bytes, end, unit, separator)) end += unit
            val raw = bytes.copyOfRange(i, j)
            val text = decode(raw, encoding, table).joinToString(" ")
            // Um texto que vai até o fim da janela sem terminador pode continuar além dela: meio texto não se traduz nem se grava.
            val cutByWindow = j > bytes.size - unit
            if (!cutByWindow && plausible(raw, text, encoding, table)) {
                out += TextSegment(
                    offset = i, byteLength = j - i, capacity = end - i, terminatorBytes = unit,
                    text = text, lineWidth = lineWidth(raw, encoding, table),
                )
            }
            i = end
        }
        return out
    }

    /**
     * Texto de verdade, e não um trecho de dados qualquer que por acaso tem uma letra: quase todos os bytes precisam ser
     * caracteres legíveis (85%; 80% numa tabela, onde há códigos de controle no meio do diálogo) e o texto precisa ter
     * pelo menos duas letras. A janela de um gancho costuma ir além do diálogo, e o que há depois dele não pode ser
     * traduzido nem sobrescrito.
     */
    private fun plausible(raw: ByteArray, text: String, encoding: TextEncoding, table: LinearTable?): Boolean {
        if (text.count(Char::isLetter) < 2 || raw.isEmpty()) return false
        val readable = when (encoding) {
            TextEncoding.ASCII -> raw.count { val b = it.toInt() and 0xFF; b in 0x20..0x7E || b == 0x0A || b == 0x0D || b == 0x09 }
            TextEncoding.SHIFT_JIS -> {
                val decoded = String(raw, shiftJis)
                return decoded.isNotEmpty() && decoded.count { it != '\uFFFD' && (!it.isISOControl() || it == '\n' || it == '\r') } * 100 >= decoded.length * 85
            }
            TextEncoding.UTF16LE, TextEncoding.UTF16BE -> {
                val units = raw.size / 2
                if (units == 0) return false
                var ok = 0
                for (u in 0 until units) {
                    val a = raw[2 * u].toInt() and 0xFF
                    val b = raw[2 * u + 1].toInt() and 0xFF
                    val unit = if (encoding == TextEncoding.UTF16BE) (a shl 8) or b else (b shl 8) or a
                    if (unit >= 0x20 && !Character.isISOControl(unit) && unit !in 0xD800..0xDFFF) ok++
                    else if (unit == 0x0A || unit == 0x0D) ok++
                }
                return ok * 100 >= units * 85
            }
            TextEncoding.TABLE -> {
                val t = requireNotNull(table)
                return raw.count { isDecodable(it, t) } * 100 >= raw.size * 80
            }
        }
        return readable * 100 >= raw.size * 85
    }

    private fun isSeparator(bytes: ByteArray, at: Int, unit: Int, separator: Int): Boolean =
        if (unit == 1) (bytes[at].toInt() and 0xFF) == separator else bytes[at].toInt() == 0 && bytes[at + 1].toInt() == 0

    /** O tamanho da maior linha de um texto que o jogo já quebra em linhas (byte 0x0A); nulo se ele tem uma só. */
    private fun lineWidth(raw: ByteArray, encoding: TextEncoding, table: LinearTable?): Int? {
        if (encoding == TextEncoding.TABLE) return null
        val text = when (encoding) {
            TextEncoding.ASCII -> String(raw, Charsets.ISO_8859_1)
            TextEncoding.SHIFT_JIS -> String(raw, shiftJis)
            TextEncoding.UTF16LE -> String(raw, Charsets.UTF_16LE)
            TextEncoding.UTF16BE -> String(raw, Charsets.UTF_16BE)
            TextEncoding.TABLE -> return null
        }
        val lines = text.split('\n').map { it.trim('\r') }.filter { it.isNotBlank() }
        return if (lines.size > 1) lines.maxOf { it.length } else null
    }

    fun decode(bytes: ByteArray, encoding: TextEncoding, table: LinearTable? = null): List<String> {
        val segments = when (encoding) {
            TextEncoding.ASCII -> splitBytes(bytes, 0).map { String(it, Charsets.ISO_8859_1) }
            TextEncoding.SHIFT_JIS -> splitBytes(bytes, 0).map { String(it, shiftJis) }
            TextEncoding.UTF16LE -> splitUtf16(bytes, bigEndian = false)
            TextEncoding.UTF16BE -> splitUtf16(bytes, bigEndian = true)
            TextEncoding.TABLE -> {
                val t = requireNotNull(table) { "A tabela é obrigatória" }
                splitBytes(bytes, t.terminator).map { decodeTable(it, t) }
            }
        }
        return segments.map(::clean).filter { it.any(Char::isLetterOrDigit) }
    }

    private fun splitBytes(bytes: ByteArray, separator: Int): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var start = 0
        for (i in bytes.indices) {
            if ((bytes[i].toInt() and 0xFF) == separator) {
                if (i > start) out += bytes.copyOfRange(start, i)
                start = i + 1
            }
        }
        if (start < bytes.size) out += bytes.copyOfRange(start, bytes.size)
        return out
    }

    private fun splitUtf16(bytes: ByteArray, bigEndian: Boolean): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i + 1 < bytes.size) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            val unit = if (bigEndian) (a shl 8) or b else (b shl 8) or a
            if (unit == 0) {
                if (current.isNotEmpty()) out += current.toString()
                current.setLength(0)
            } else current.append(unit.toChar())
            i += 2
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    internal fun decodeTable(bytes: ByteArray, t: LinearTable): String = buildString {
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            when {
                t.upper != null && b in t.upper..t.upper + 25 -> append('A' + (b - t.upper))
                t.lower != null && b in t.lower..t.lower + 25 -> append('a' + (b - t.lower))
                t.digit != null && b in t.digit..t.digit + 9 -> append('0' + (b - t.digit))
                t.space != null && b == t.space -> append(' ')
                t.asciiLow && b in 0x20..0x3F -> append(b.toChar())
                else -> t.extra.entries.firstOrNull { it.value == b }?.key?.let { append(it) }
            }
        }
    }

    /** Sem códigos de controle (as quebras de linha viram espaço) e sem espaços repetidos. */
    internal fun clean(text: String): String {
        val out = StringBuilder()
        for (c in text) {
            val ch = when {
                c == '\n' || c == '\r' || c == '\t' -> ' '
                c.isISOControl() || c == '\uFFFD' -> continue
                else -> c
            }
            if (ch == ' ' && out.isNotEmpty() && out.last() == ' ') continue
            out.append(ch)
        }
        return out.toString().trim()
    }
}
