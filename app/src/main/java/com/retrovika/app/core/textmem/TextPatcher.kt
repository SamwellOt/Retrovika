package com.retrovika.app.core.textmem

import java.text.Normalizer

/**
 * Traduz os textos do jogo e os escreve de volta na memória dele, no lugar do original: o jogo passa a mostrar o texto
 * traduzido na caixa de diálogo. Um passo ([step]) lê os buffers dos ganchos, separa os textos que ainda não são nossos,
 * pede a tradução deles e grava cada um no lugar do original, dentro do espaço que o jogo deixou ([TextSegment.capacity]).
 *
 * No modo automático o passo roda a cada meio segundo: um texto só é traduzido depois de aparecer igual em duas leituras
 * seguidas (um diálogo sendo montado não é traduzido pela metade), e um texto já traduzido nesta sessão volta a ser
 * escrito na hora, sem rede, se o jogo o recarregar. [alreadyTranslated] diz se um texto já está no idioma de quem lê
 * (a memória guarda a tradução depois de carregar um save feito com ela ligada): esse não é traduzido de novo.
 */
class TextPatcher(private val access: MemoryAccess, private val alreadyTranslated: (String) -> Boolean = { false }) {

    data class Report(
        /** Quantos textos foram escritos no jogo neste passo. */
        val written: Int = 0,
        /** Quantos deles tiveram de ser encurtados para caber. */
        val truncated: Int = 0,
        /** Algum buffer não pôde ser lido (o núcleo não expõe a memória, ou o gancho saiu dela). */
        val unreadable: Boolean = false,
        /** Quantos textos novos esperam a próxima leitura (modo automático) ou não puderam ser traduzidos. */
        val waiting: Int = 0,
    )

    private val translations = HashMap<String, String>()
    private val ours = LinkedHashSet<String>()
    private var lastCandidates: Set<String> = emptySet()

    /** Pedido de [reset]: quem o usa é outra thread, e as tabelas só podem ser mexidas pelo passo em andamento. */
    @Volatile private var resetRequested = false

    /** Esquece as traduções guardadas (os ganchos mudaram); vale no começo do próximo passo. */
    fun reset() {
        resetRequested = true
    }

    /**
     * [force]: traduz tudo o que há agora (o botão); sem ele, só o que ficou igual desde o passo anterior (automático).
     * [translate] recebe os textos e devolve a tradução de cada um (os que não precisam de tradução ficam de fora).
     */
    suspend fun step(hooks: List<TextHook>, force: Boolean, translate: suspend (List<String>) -> Map<String, String>): Report {
        if (resetRequested) {
            resetRequested = false
            // O que escrevemos continua na memória do jogo e não vira "original" porque as fontes mudaram: `ours` fica.
            translations.clear()
            lastCandidates = emptySet()
        }
        var scan = scan(hooks)
        if (scan.unreadable && scan.found.isEmpty()) return Report(unreadable = true)

        var written = 0
        var truncated = 0
        fun apply(found: List<Found>): Pair<Int, Int> {
            var w = 0
            var t = 0
            for (f in found) {
                val translated = translations[f.segment.text] ?: continue
                val result = write(f.hook, f.segment, translated) ?: continue
                w++
                if (result) t++
            }
            return w to t
        }

        // O que já foi traduzido volta ao jogo sem esperar a rede.
        apply(scan.found.filter { differs(it.segment.text) }).let { written += it.first; truncated += it.second }

        val need = scan.found.map { it.segment.text }.filter { it !in translations && it !in ours }.distinct()
        // Poucos textos por passo: um buffer cheio de lixo plausível não vira dezenas de pedidos de uma vez ao tradutor.
        val candidates = (if (force) need else need.filter { it in lastCandidates }).take(MAX_PER_STEP)
        lastCandidates = need.toSet()
        if (candidates.isEmpty()) return Report(written, truncated, scan.unreadable, waiting = need.size)

        val result = translate(candidates)
        if (translations.size > MAX_CACHE) translations.clear()
        // Tradução vazia não é tradução: gravá-la apagaria o diálogo.
        translations.putAll(result.filterValues { it.isNotBlank() })
        // Os textos que o tradutor deixou de fora (já no idioma de quem lê) não voltam a ser pedidos.
        candidates.filter { result[it].isNullOrBlank() }.forEach { translations[it] = it }

        // O jogo andou durante a tradução: lê de novo e só grava o que ainda é o mesmo texto.
        scan = scan(hooks)
        apply(scan.found.filter { it.segment.text in candidates && differs(it.segment.text) })
            .let { written += it.first; truncated += it.second }
        return Report(written, truncated, scan.unreadable, waiting = 0)
    }

    /** Há tradução guardada para [text] e ela não é o próprio texto (o tradutor devolveu algo diferente). */
    private fun differs(text: String): Boolean = translations[text]?.let { it != text } == true

    private data class Found(val hook: TextHook, val segment: TextSegment)
    private data class Scan(val found: List<Found>, val unreadable: Boolean)

    private fun scan(hooks: List<TextHook>): Scan {
        val found = mutableListOf<Found>()
        var unreadable = false
        for (hook in hooks) {
            val window = TextDecoder.window(access, hook)
            if (window == null) { unreadable = true; continue }
            // Letras de largura total (que nós gravamos) voltam normalizadas, para o filtro de "já traduzido" as reconhecer.
            TextDecoder.segments(window, hook)
                .filter { it.text !in ours && !alreadyTranslated(Normalizer.normalize(it.text, Normalizer.Form.NFKC)) }
                .forEach { found += Found(hook, it) }
        }
        return Scan(found, unreadable)
    }

    /** Escreve [translated] no lugar do texto de [segment]; devolve se foi encurtado, ou nulo se não coube nem cortado. */
    private fun write(hook: TextHook, segment: TextSegment, translated: String): Boolean? {
        if (hook.gridWidth > 0) return writeRow(hook, segment, translated)
        val terminator = TextEncoder.terminator(hook)
        // Shift-JIS sem nenhuma letra ou dígito meia-largura no original: a fonte só tem os caracteres de largura total.
        val fullWidth = hook.encoding == TextEncoding.SHIFT_JIS &&
            segment.text.none { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }
        val encoded = TextEncoder.encode(translated, hook, segment.capacity - terminator.size, segment.lineWidth, fullWidth)
            ?.takeIf { it.bytes.isNotEmpty() } ?: return null
        // O texto, o terminador e mais terminadores até cobrir o que o original ocupava (apaga o que sobraria dele).
        // (nunca além da capacidade: sem terminador depois do original, o espaço acaba onde o texto acaba)
        val total = maxOf(encoded.bytes.size + terminator.size, minOf(segment.byteLength + terminator.size, segment.capacity))
        val out = ByteArray(total)
        encoded.bytes.copyInto(out)
        var i = encoded.bytes.size
        while (i < total) {
            terminator.copyInto(out, i, 0, minOf(terminator.size, total - i))
            i += terminator.size
        }
        // Fila cheia: nada foi gravado, e o texto continua pendente (volta no próximo passo).
        if (!access.write(hook.address + segment.offset, out, hook.wordSwap)) return null
        // O que o jogo vai ler de volta: assim o próximo passo reconhece este texto como nosso.
        remember(TextDecoder.decode(encoded.bytes, hook.encoding, hook.table).joinToString(" "))
        return encoded.truncated
    }

    /** Guarda um texto nosso; passando do limite, os mais antigos saem (o idioma do texto ainda o denuncia). */
    private fun remember(text: String) {
        ours += text
        if (ours.size > MAX_OURS) {
            val drop = ours.iterator()
            repeat(MAX_OURS / 4) { if (drop.hasNext()) { drop.next(); drop.remove() } }
        }
    }

    /** Uma linha de tela de texto: a tradução completada com espaços até a largura da linha (sem terminador). */
    private fun writeRow(hook: TextHook, segment: TextSegment, translated: String): Boolean? {
        val encoded = TextEncoder.encode(translated, hook, segment.capacity)?.takeIf { it.bytes.isNotEmpty() } ?: return null
        val fill = TextEncoder.fill(hook)
        val out = ByteArray(segment.byteLength)
        encoded.bytes.copyInto(out)
        var i = encoded.bytes.size
        while (i < out.size) { fill.copyInto(out, i, 0, minOf(fill.size, out.size - i)); i += fill.size }
        if (!access.write(hook.address + segment.offset, out, hook.wordSwap)) return null
        remember(TextDecoder.segments(out, hook.copy(address = 0)).firstOrNull()?.text ?: TextDecoder.clean(translated))
        return encoded.truncated
    }

    private companion object {
        const val MAX_CACHE = 500
        const val MAX_PER_STEP = 8
        const val MAX_OURS = 2000
    }
}
