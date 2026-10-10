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

    /** Último hash de cada trecho já visto, por endereço e codificação. Não se apaga quando o trecho some: é isso que pega o buffer limpo e refeito. */
    private val seen = HashMap<Long, Int>()
    private var blockHashes: IntArray? = null
    private var viewSize = -1
    private var primed = false

    /**
     * Varre [ram] e devolve as fontes novas: os trechos de texto cujo conteúdo mudou desde a varredura anterior, fora das
     * janelas das [existing] (o que o jogador ou uma varredura anterior já cobre).
     */
    fun scan(ram: ByteArray, existing: List<TextHook>): List<TextHook> {
        val view = if (wordSwap) TextDecoder.slice(ram, 0, ram.size and 3.inv(), true) ?: return emptyList() else ram
        // Outro tamanho de RAM é outra memória: recomeça como primeira varredura.
        if (view.size != viewSize) {
            viewSize = view.size
            seen.clear()
            blockHashes = null
            primed = false
        }
        val first = !primed
        primed = true
        val changed = changedBlocks(view)
        val out = mutableListOf<TextHook>()
        val found = mutableListOf<Run>()
        if (first) {
            scanAll(view, 0, view.size, found)
            for (run in found) seen[keyOf(run)] = run.hash
            return out
        }
        // Só o que mudou pode ter virado texto novo: varre as faixas em volta dos blocos alterados.
        for ((from, to) in textRanges(view, changed)) scanAll(view, from, to, found)
        for (run in found) {
            val old = seen.put(keyOf(run), run.hash) ?: continue
            if (old == run.hash || out.size >= MAX_PER_SCAN) continue
            if (existing.any { covers(it, run.address) } || out.any { covers(it, run.address) }) continue
            val length = minOf(WINDOW, view.size - run.address)
            if (length <= 0) continue
            val preview = run.preview.take(24)
            out += TextHook("$AUTO_PREFIX$preview", run.address, length, run.encoding, wordSwap, null, core = core)
        }
        tableHooks(view, changed, existing, out)
        // Esquecendo tudo, a próxima varredura é completa (só registra): as regiões paradas também voltam ao mapa.
        if (seen.size > MAX_SEEN) { seen.clear(); primed = false }
        return out
    }

    private fun keyOf(run: Run) = (run.address.toLong() shl 3) or run.encoding.ordinal.toLong()

    /**
     * Faixas [from, to) da RAM para varrer depois de uma mudança: cada bloco alterado se estende até o `00 00` mais
     * próximo dos dois lados (texto nunca contém `00 00`), blocos com até dois blocos inalterados no meio são uma faixa só
     * e faixas que se sobrepõem depois de estendidas viram uma.
     */
    private fun textRanges(v: ByteArray, changed: List<Int>): List<Pair<Int, Int>> {
        if (changed.isEmpty()) return emptyList()
        val spans = mutableListOf<Pair<Int, Int>>()
        var start = changed[0]
        var end = changed[0]
        for (b in changed.drop(1)) {
            if (b - end <= 3) end = b else { spans += start to end; start = b; end = b }
        }
        spans += start to end
        val ranges = spans.map { (s, e) ->
            val from = extendBack(v, s * BLOCK) and 1.inv()
            from to extendForward(v, (e + 1) * BLOCK)
        }.sortedBy { it.first }
        val merged = mutableListOf<Pair<Int, Int>>()
        for ((from, to) in ranges) {
            val last = merged.lastOrNull()
            if (last != null && from <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, to)
            else merged += from to to
        }
        return merged
    }

    /** Começo do texto que termina antes de [at]: logo depois do `00 00` mais próximo, até [MAX_EXTEND] bytes para trás. */
    private fun extendBack(v: ByteArray, at: Int): Int {
        val limit = maxOf(0, at - MAX_EXTEND)
        var k = at - 2
        while (k >= limit) {
            if (v[k].toInt() == 0 && v[k + 1].toInt() == 0) return k + 2
            k--
        }
        return limit
    }

    /** Fim do texto que começa em [at] ou depois: o primeiro `00 00`, até [MAX_EXTEND] bytes para frente. */
    private fun extendForward(v: ByteArray, at: Int): Int {
        val limit = minOf(v.size, at + MAX_EXTEND)
        var k = at
        while (k + 1 < limit) {
            if (v[k].toInt() == 0 && v[k + 1].toInt() == 0) return k
            k++
        }
        return limit
    }

    private fun scanAll(v: ByteArray, from: Int, to: Int, out: MutableList<Run>) {
        scanAscii(v, from, to, out)
        scanUtf16(v, from, to, out)
        scanShiftJis(v, from, to, out)
    }

    /** Os blocos de [BLOCK] bytes que mudaram desde a varredura anterior (vazio na primeira). */
    private fun changedBlocks(v: ByteArray): List<Int> {
        val count = v.size / BLOCK
        val old = blockHashes?.takeIf { it.size == count }
        val now = IntArray(count)
        val changed = ArrayList<Int>()
        for (b in 0 until count) {
            val h = hashOf(v, b * BLOCK, b * BLOCK + BLOCK)
            now[b] = h
            if (old != null && old[b] != h) changed += b
        }
        blockHashes = now
        return changed
    }

    /**
     * Texto que não é ASCII: as regiões que mudaram entre duas varreduras são lidas como tabela linear de inglês
     * ([TableDetector]); onde formam palavras, o buffer vira uma fonte do tipo tabela.
     */
    private fun tableHooks(v: ByteArray, changed: List<Int>, existing: List<TextHook>, out: MutableList<TextHook>) {
        if (changed.isEmpty() || changed.size > MAX_CHANGED_BLOCKS) return
        // Junta blocos vizinhos (um buraco de um bloco) numa região só.
        val regions = ArrayList<IntRange>()
        var start = changed[0]
        var end = changed[0]
        for (b in changed.drop(1)) {
            if (b - end <= 2) end = b else { regions += start..end; start = b; end = b }
        }
        regions += start..end
        var tried = 0
        for (r in regions) {
            if (tried >= MAX_REGIONS || out.size >= MAX_PER_SCAN) break
            val from = r.first * BLOCK
            val to = (r.last + 1) * BLOCK
            if (to - from > MAX_REGION_BYTES) continue
            if (existing.any { covers(it, from) } || out.any { covers(it, from) }) continue
            tried++
            val windowStart = maxOf(0, from - BLOCK)
            val windowEnd = minOf(v.size, to + BLOCK)
            val found = TableDetector.detect(v.copyOfRange(windowStart, windowEnd)) ?: continue
            val table = found.table
            // A janela começa um bloco antes da região que mudou: o texto pode começar um pouco antes do primeiro byte novo.
            val at = windowStart
            val length = minOf(WINDOW, v.size - at)
            if (length <= 0) continue
            val preview = TextDecoder.decode(v.copyOfRange(at, at + length), TextEncoding.TABLE, table).firstOrNull().orEmpty().take(24)
            out += TextHook("$AUTO_PREFIX$preview", at, length, TextEncoding.TABLE, wordSwap, table, core = core)
        }
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

    private fun scanAscii(v: ByteArray, from: Int, to: Int, out: MutableList<Run>) {
        var i = from
        while (i < to) {
            if (!printable(v[i].toInt() and 0xFF)) { i++; continue }
            var j = i
            while (j < to && printable(v[j].toInt() and 0xFF)) j++
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

    private fun scanUtf16(v: ByteArray, from: Int, to: Int, out: MutableList<Run>) {
        for (bigEndian in booleanArrayOf(false, true)) {
            var i = from
            val n = to and 1.inv()
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

    private fun scanShiftJis(v: ByteArray, from: Int, to: Int, out: MutableList<Run>) {
        var i = from
        val n = to - 1
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
        private const val BLOCK = 32
        private const val MAX_CHANGED_BLOCKS = 600
        private const val MAX_REGIONS = 48
        private const val MAX_REGION_BYTES = 768
        /** Quantos trechos lembrados antes de esquecer tudo (a varredura seguinte é completa e repovoa). */
        private const val MAX_SEEN = 200_000
        /** Até onde procurar `00 00` para os lados de uma faixa alterada. */
        private const val MAX_EXTEND = 4096
        private const val WINDOW = 256
        private const val MIN_ASCII = 10
        private const val MIN_UTF16 = 6
        private const val MIN_SJIS = 4
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
