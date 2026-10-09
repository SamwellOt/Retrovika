package com.retrovika.app.core.textmem

import java.nio.charset.Charset

/**
 * Descobre onde o jogo guarda o texto sem o jogador dizer: engenharia reversa dinâmica da RAM. Cada varredura procura
 * trechos que parecem texto (ASCII, UTF-16 e Shift-JIS) e guarda um resumo deles; o que **muda no mesmo lugar** entre
 * duas varreduras é um buffer que o jogo reescreve a cada fala (a caixa de diálogo, o nome do lugar). Dados que só
 * ficam parados na memória (a ROM copiada, tabelas) nunca mudam e não viram fonte. A primeira varredura só registra
 * o que já existe; o que estava na tela antes dela entra assim que o texto trocar.
 */
class TextWatcher(private val core: String?, private val wordSwap: Boolean) {

    private var previous: HashMap<Long, Int>? = null

    /**
     * Varre [ram] e devolve as fontes novas: os trechos de texto cujo conteúdo mudou desde a varredura anterior, fora das
     * janelas das [existing] (o que o jogador ou uma varredura anterior já cobre).
     */
    fun scan(ram: ByteArray, existing: List<TextHook>): List<TextHook> {
        val view = if (wordSwap) TextDecoder.slice(ram, 0, ram.size and 3.inv(), true) ?: return emptyList() else ram
        val current = HashMap<Long, Int>()
        val found = mutableListOf<Run>()
        scanAscii(view, found)
        scanUtf16(view, found)
        scanShiftJis(view, found)
        val before = previous
        previous = current
        val out = mutableListOf<TextHook>()
        for (run in found) {
            val key = (run.address.toLong() shl 3) or run.encoding.ordinal.toLong()
            current[key] = run.hash
            if (before == null || out.size >= MAX_PER_SCAN) continue
            val old = before[key] ?: continue
            if (old == run.hash) continue
            if (existing.any { covers(it, run.address) } || out.any { covers(it, run.address) }) continue
            val length = minOf(WINDOW, view.size - run.address)
            if (length <= 0) continue
            val preview = run.preview.take(24)
            out += TextHook("$AUTO_PREFIX$preview", run.address, length, run.encoding, wordSwap, null, core = core)
        }
        return out
    }

    private fun covers(hook: TextHook, address: Int) =
        hook.wordSwap == wordSwap && (hook.core == null || hook.core == core) && address >= hook.address && address < hook.address + hook.length

    private class Run(val address: Int, val encoding: TextEncoding, val hash: Int, val preview: String)

    private fun hashOf(bytes: ByteArray, from: Int, to: Int): Int {
        var h = 0x811C9DC5.toInt()
        for (i in from until to) h = (h xor (bytes[i].toInt() and 0xFF)) * 0x01000193
        return h
    }

    private fun printable(b: Int) = b in 0x20..0x7E || b == 0x0A

    private fun scanAscii(v: ByteArray, out: MutableList<Run>) {
        var i = 0
        val n = v.size
        while (i < n) {
            if (!printable(v[i].toInt() and 0xFF)) { i++; continue }
            var j = i
            while (j < n && printable(v[j].toInt() and 0xFF)) j++
            if (j - i >= MIN_ASCII && looksLikeSentence(v, i, j)) {
                out += Run(i, TextEncoding.ASCII, hashOf(v, i, j), String(v, i, j - i, Charsets.ISO_8859_1))
            }
            i = j + 1
        }
    }

    /** Frases de verdade: bastante letra, pelo menos uma palavra separada de outra e nenhuma sequência de símbolos. */
    private fun looksLikeSentence(v: ByteArray, from: Int, to: Int): Boolean {
        var letters = 0
        var spaces = 0
        var words = 0
        var run = 0
        for (k in from until to) {
            val c = v[k].toInt() and 0xFF
            val letter = c in 0x41..0x5A || c in 0x61..0x7A
            if (letter) { letters++; run++ } else { if (run >= 2) words++; run = 0 }
            if (c == 0x20 || c == 0x0A) spaces++
        }
        if (run >= 2) words++
        val total = to - from
        return spaces >= 1 && words >= 2 && letters * 100 >= total * 60
    }

    private fun scanUtf16(v: ByteArray, out: MutableList<Run>) {
        for (bigEndian in booleanArrayOf(false, true)) {
            var i = 0
            val n = v.size and 1.inv()
            while (i < n) {
                if (!unitOk(v, i, bigEndian)) { i += 2; continue }
                var j = i
                while (j < n && unitOk(v, j, bigEndian)) j += 2
                if (j - i >= MIN_UTF16 * 2) {
                    val text = String(v, i, j - i, if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE)
                    // Latim (letras e espaços) ou japonês com kana: kanji soltos é o que o ASCII vira lido na ordem errada.
                    val latin = text.count { it in 'a'..'z' || it in 'A'..'Z' } * 100 >= text.length * 60 && text.contains(' ')
                    val japanese = text.count { it.code in 0x3041..0x30FF } * 100 >= text.length * 30
                    if (latin || japanese) {
                        out += Run(i, if (bigEndian) TextEncoding.UTF16BE else TextEncoding.UTF16LE, hashOf(v, i, j), text)
                    }
                }
                i = j + 2
            }
        }
    }

    /** Uma unidade UTF-16 de texto: ASCII imprimível, kana, CJK ou pontuação japonesa. */
    private fun unitOk(v: ByteArray, at: Int, bigEndian: Boolean): Boolean {
        val a = v[at].toInt() and 0xFF
        val b = v[at + 1].toInt() and 0xFF
        val u = if (bigEndian) (a shl 8) or b else (b shl 8) or a
        return u in 0x20..0x7E || u == 0x0A || u in 0x3000..0x30FF || u in 0x4E00..0x9FFF || u in 0xFF01..0xFF9F
    }

    private fun scanShiftJis(v: ByteArray, out: MutableList<Run>) {
        var i = 0
        val n = v.size - 1
        while (i < n) {
            val lead = v[i].toInt() and 0xFF
            // Só começa em kana (hiragana 0x82 0x9F-0xF1, katakana 0x83 0x40-0x96): o resto do texto pode ter kanji.
            if (!(lead == 0x82 || lead == 0x83) || !kana(lead, v[i + 1].toInt() and 0xFF)) { i++; continue }
            var j = i
            var chars = 0
            var kanas = 0
            while (j < n) {
                val l = v[j].toInt() and 0xFF
                val t = v[j + 1].toInt() and 0xFF
                if (isLead(l) && t in 0x40..0xFC && t != 0x7F) {
                    if (kana(l, t)) kanas++
                    chars++
                    j += 2
                } else break
            }
            if (chars >= MIN_SJIS && kanas * 100 >= chars * 50) {
                val text = String(v, i, j - i, SHIFT_JIS)
                if (text.indexOf('�') < 0) out += Run(i, TextEncoding.SHIFT_JIS, hashOf(v, i, j), text)
            }
            i = maxOf(j, i + 1)
        }
    }

    private fun isLead(b: Int) = b in 0x81..0x9F || b in 0xE0..0xEF

    private fun kana(lead: Int, trail: Int) =
        (lead == 0x82 && trail in 0x9F..0xF1) || (lead == 0x83 && trail in 0x40..0x96)

    companion object {
        /** O nome das fontes achadas sozinhas: o jogador as vê (e apaga) na lista, e a varredura sabe contá-las. */
        const val AUTO_PREFIX = "Auto: "
        const val MAX_AUTO_HOOKS = 48
        private const val MAX_PER_SCAN = 6
        private const val WINDOW = 256
        private const val MIN_ASCII = 10
        private const val MIN_UTF16 = 6
        private const val MIN_SJIS = 4
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
