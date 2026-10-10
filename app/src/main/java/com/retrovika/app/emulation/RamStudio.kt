package com.retrovika.app.emulation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retrovika.app.core.cheats.RamCheat
import com.retrovika.app.core.cheats.RamSearch
import com.retrovika.app.core.net.Http
import kotlinx.serialization.builtins.ListSerializer
import com.retrovika.app.core.textmem.TextHook
import com.retrovika.app.core.textmem.TextSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Estado da "busca na memória" do menu de pausa: a cópia da RAM, os filtros e o que sobrou. A RAM vem de [reader]
 * (a atividade a lê do núcleo, com o menu aberto e a emulação parada); a busca em si é [RamSearch]. Trocar a largura
 * ou a ordem dos bytes recomeça, porque os candidatos de uma largura não valem para a outra.
 *
 * A RAM de um PS2 tem 32 MB e a do Play! 64 MB, e a busca precisa de duas cópias: por isso as leituras vão para dois
 * arrays que se revezam (nada é alocado a cada filtro), a conta de memória é feita antes de começar e um
 * [OutOfMemoryError] vira o aviso [Problem.TOO_BIG] em vez de derrubar o jogo.
 */
class RamStudio(private val scope: CoroutineScope) {

    enum class Problem { NO_RAM, READ_FAILED, TOO_BIG }

    /** Copia a RAM do jogo para o array e diz quantos bytes copiou (0 se o núcleo não a expõe). */
    var reader: ((ByteArray) -> Int)? = null
    /** Tamanho da RAM exposta (0 = o núcleo não a expõe). */
    var sizeProvider: (() -> Int)? = null
    /** Memória de heap ainda livre; trocável nos testes. */
    var freeHeap: () -> Long = {
        val rt = Runtime.getRuntime()
        rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
    }
    /** O console guarda a RAM em palavras invertidas (N64): a atividade liga ao abrir um jogo desses. */
    var wordSwapSystem = false

    var width by mutableIntStateOf(1)
        private set
    var bigEndian by mutableStateOf(false)
        private set
    var wordSwap by mutableStateOf(false)
        private set
    var started by mutableStateOf(false)
        private set
    var count by mutableIntStateOf(0)
        private set
    var hits by mutableStateOf<List<RamSearch.Hit>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var problem by mutableStateOf<Problem?>(null)
        private set
    var ramSize by mutableIntStateOf(0)
        private set

    /** O núcleo que roda o jogo (a atividade o diz): o que se cria aqui fica marcado com ele. */
    var coreId: String? = null

    /** As fontes de texto que valem para o núcleo de agora (as de outro núcleo ficam guardadas, mas não são usadas). */
    val activeTextHooks: List<TextHook> get() = textHooks.filter { it.core == null || it.core == coreId }

    /** Onde o texto deste jogo está na memória; a tradução os lê no lugar do OCR. A atividade os guarda por jogo. */
    var textHooks by mutableStateOf<List<TextHook>>(emptyList())
        private set
    var onTextHooksChanged: ((List<TextHook>) -> Unit)? = null
    /** O que a última busca de texto achou, e se ela já rodou (para "nada achado"). */
    var textMatches by mutableStateOf<List<TextSearch.Match>>(emptyList())
        private set
    var textSearched by mutableStateOf(false)
        private set
    /** Colunas das linhas da tela de texto (C64, MSX, DOS): 0 é um diálogo comum com terminador. Vale para a próxima fonte criada. */
    var gridWidth by mutableIntStateOf(0)
        private set

    fun changeGridWidth(columns: Int) { gridWidth = columns }

    /** Monta o gancho de um achado, com a janela de linhas ao redor dele quando o jogador escolheu uma tela de texto. */
    fun hookFor(match: TextSearch.Match, name: String): TextHook {
        val grid = gridWidth
        val size = ramSize.takeIf { it > 0 } ?: sizeProvider?.invoke() ?: 0
        if (grid <= 0) {
            return TextHook(name, match.address, match.length, match.encoding, wordSwap, match.table, core = coreId)
        }
        // As linhas se alinham ao achado (que o jogador buscou no começo de uma linha): até 24 linhas acima e 25 abaixo.
        val above = minOf(24, match.address / grid)
        val start = match.address - above * grid
        val rows = minOf((above + 25), (size - start) / grid, TextHook.MAX_LENGTH / grid)
        // Achado colado no fim da memória, sem uma linha inteira de espaço: vira um gancho de diálogo comum.
        if (rows < 1) return TextHook(name, match.address, match.length, match.encoding, wordSwap, match.table, core = coreId)
        return TextHook(name, start, rows * grid, match.encoding, wordSwap, match.table, gridWidth = grid, core = coreId)
    }

    private var search: RamSearch? = null
    /** A cópia que o último filtro largou, pronta para a próxima leitura. */
    private var spare: ByteArray? = null

    /** Lê o tamanho da RAM (ao abrir a tela) para saber se o núcleo serve. */
    fun refreshSupport() {
        ramSize = sizeProvider?.invoke() ?: 0
        problem = if (ramSize == 0) Problem.NO_RAM else null
        // Primeira vez no N64: a ordem das palavras vem ligada, e o jogador pode desligar.
        if (!started && wordSwapSystem && !wordSwapTouched) wordSwap = true
    }

    private var wordSwapTouched = false

    fun changeWidth(value: Int) {
        if (value == width || value !in RamCheat.WIDTHS) return
        width = value
        reset()
    }

    fun changeEndian(big: Boolean) {
        if (big == bigEndian) return
        bigEndian = big
        reset()
    }

    fun changeWordSwap(on: Boolean) {
        wordSwapTouched = true
        if (on == wordSwap) return
        wordSwap = on
        reset()
    }

    fun loadTextHooks(hooks: List<TextHook>) { textHooks = hooks }

    /** Várias fontes de uma vez (as que o app achou sozinho): uma só notificação; devolve quantas eram novas. */
    fun addTextHooks(hooks: List<TextHook>): Int {
        val added = addNew(hooks)
        if (added > 0) onTextHooksChanged?.invoke(textHooks)
        return added
    }

    fun addTextHook(hook: TextHook) {
        if (addNew(listOf(hook)) > 0) onTextHooksChanged?.invoke(textHooks)
    }

    /** Junta várias fontes de uma vez (uma só gravação); devolve quantas eram novas. */
    private fun addNew(incoming: List<TextHook>): Int {
        val fresh = mutableListOf<TextHook>()
        for (hook in incoming) if ((textHooks + fresh).none { it.sameSpot(hook) }) fresh += hook
        if (fresh.isEmpty()) return 0
        textHooks = textHooks + fresh
        return fresh.size
    }

    private fun TextHook.sameSpot(o: TextHook) =
        address == o.address && encoding == o.encoding && wordSwap == o.wordSwap && gridWidth == o.gridWidth && core == o.core

    /** As fontes de texto deste jogo como JSON, para o jogador mandar a outro (que as cola em "Colar fontes"). */
    fun exportHooks(): String = Http.json.encodeToString(ListSerializer(TextHook.serializer()), textHooks)

    /**
     * Junta as fontes de [json] às do jogo. Devolve quantas entraram (as repetidas não contam) ou -1 se o texto não é uma
     * lista de fontes válida. Nada entra se alguma delas for inválida.
     */
    fun importHooks(json: String): Int {
        val incoming = try {
            Http.json.decodeFromString(ListSerializer(TextHook.serializer()), json.trim())
        } catch (e: Exception) {
            return -1
        }
        if (incoming.isEmpty()) return -1
        val added = addNew(incoming)
        if (added > 0) onTextHooksChanged?.invoke(textHooks)
        return added
    }

    fun removeTextHook(hook: TextHook) {
        textHooks = textHooks - hook
        onTextHooksChanged?.invoke(textHooks)
    }

    /** Procura [text] (o que o jogador lê na tela) na RAM e guarda os lugares achados. */
    fun findText(text: String) = work { size ->
        val buffer = ByteArray(size)
        if (!read(buffer, size)) return@work
        textMatches = TextSearch.find(buffer, text, wordSwap = wordSwap, gridWidth = gridWidth)
        textSearched = true
    }

    fun clearTextMatches() {
        textMatches = emptyList()
        textSearched = false
    }

    fun reset() {
        search = null
        spare = null
        started = false
        count = 0
        hits = emptyList()
    }

    /** Tira a cópia da RAM de agora e começa uma busca nova. */
    fun start() = work { size ->
        val snapshot = ByteArray(size)
        if (!read(snapshot, size)) return@work
        val s = RamSearch(width, bigEndian, wordSwap)
        s.start(snapshot)
        search = s
        spare = null
        started = true
        count = s.count
        hits = emptyList()
    }

    fun filterEqual(value: Long) = filtering { s, buffer -> s.filterEqual(buffer, value) }

    fun filterDelta(delta: Long) = filtering { s, buffer -> s.filterDelta(buffer, delta) }

    fun filter(kind: RamSearch.Filter) = filtering { s, buffer -> s.filter(buffer, kind) }

    private fun filtering(block: (RamSearch, ByteArray) -> Int) = work { size ->
        val s = search ?: return@work
        val buffer = spare?.takeIf { it.size == size } ?: ByteArray(size)
        spare = null
        if (!read(buffer, size)) return@work
        // O tamanho da RAM não muda no meio do jogo; se mudou (outro núcleo, jogo reiniciado), recomeça.
        count = try {
            block(s, buffer)
        } catch (e: IllegalArgumentException) {
            reset()
            return@work
        }
        spare = s.takeRetired()
        hits = if (count <= MAX_LISTED) s.hits(MAX_LISTED) else emptyList()
    }

    private fun read(into: ByteArray, size: Int): Boolean {
        val copied = reader?.invoke(into) ?: 0
        if (copied == size) return true
        problem = if ((sizeProvider?.invoke() ?: 0) == 0) Problem.NO_RAM else Problem.READ_FAILED
        return false
    }

    private fun work(block: suspend (size: Int) -> Unit) {
        if (busy) return
        busy = true
        problem = null
        scope.launch {
            try {
                val size = sizeProvider?.invoke() ?: 0
                when {
                    size <= 0 -> problem = Problem.NO_RAM
                    // Só a primeira leitura aloca; depois os dois arrays se revezam (já contados aqui).
                    !started && RamSearch.bytesNeeded(size, width) > freeHeap() * 9 / 10 -> problem = Problem.TOO_BIG
                    else -> withContext(Dispatchers.Default) { block(size) }
                }
            } catch (e: OutOfMemoryError) {
                reset()
                problem = Problem.TOO_BIG
            } finally {
                busy = false
            }
        }
    }

    companion object {
        /** Acima disto a lista não é mostrada: o usuário filtra mais. */
        const val MAX_LISTED = 40
    }
}
