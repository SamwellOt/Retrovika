package com.retrovika.app.emulation

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retrovika.app.core.cheats.Cheat
import com.retrovika.app.core.cheats.CheatRepository
import com.retrovika.app.core.cheats.GameCheats
import com.retrovika.app.core.cheats.RamCheat
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Trapaças do jogo aberto. O menu mexe aqui; o [GameActivity] aplica no núcleo quando a emulação volta a
 * rodar ([dirty]), porque o núcleo só é tocado na thread de emulação, e ela está parada com o menu aberto.
 */
class CheatSession(
    private val repo: CheatRepository,
    private val game: Game,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    /** Busca de valores na RAM do jogo (a atividade liga o leitor da memória quando a vista existe). */
    val ram = RamStudio(scope)

    var state by mutableStateOf(GameCheats())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Outros arquivos que parecem ser deste jogo (a primeira sugestão é carregada sozinha). */
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    /** Há mudança ainda não aplicada ao núcleo. */
    var dirty = false
    private var autoTried = false
    private val saveLock = Mutex()

    val supported: Boolean get() = repo.supports(game.systemId)

    suspend fun restore() {
        // Arquivos gravados por versões antigas podem ter o mesmo código repetido.
        state = repo.load(game).let { it.copy(cheats = it.cheats.distinctBy(::identity)) }
    }

    /** Na primeira vez que a aba abre, procura o arquivo do jogo e carrega a melhor sugestão. */
    fun ensureLoaded() {
        if (autoTried || loading || !supported) return
        autoTried = true
        if (state.file != null) {
            // Arquivo já escolhido: só as sugestões, para o "trocar" ter algo a mostrar.
            scope.launch { runCatching { suggestions = repo.suggestions(game) } }
            return
        }
        launchLoading {
            val found = repo.suggestions(game)
            suggestions = found
            found.firstOrNull()?.let { load(it) }
        }
    }

    fun retry() {
        autoTried = false
        error = null
        ensureLoaded()
    }

    /** Troca o arquivo: os códigos próprios ficam, e os que existem nos dois continuam ligados. */
    fun choose(file: String) {
        launchLoading { load(file) }
    }

    private suspend fun load(file: String) {
        val loaded = repo.read(game.systemId, file)
        val wasOn = state.enabled.map { it.code }.toSet()
        val custom = state.cheats.filter { it.custom }
        // Um .cht pode repetir a mesma trapaça, e um código próprio pode ser igual a um do arquivo:
        // fica uma só (a lista não aceita dois itens iguais, e o núcleo receberia o código duas vezes).
        val merged = (loaded.map { it.copy(enabled = it.code in wasOn) } + custom).distinctBy(::identity)
        update(GameCheats(file, merged))
    }

    /** O que torna duas trapaças a mesma: código e descrição (próprias e do arquivo contam separado). */
    private fun identity(c: Cheat) = Triple(c.custom, c.code.trim(), c.description)

    fun toggle(cheat: Cheat) = update(state.copy(cheats = state.cheats.map { if (it === cheat) it.copy(enabled = !it.enabled) else it }))

    fun disableAll() = update(state.copy(cheats = state.cheats.map { it.copy(enabled = false) }))

    fun addCustom(description: String, code: String) {
        val c = Cheat(description.ifBlank { code }, code.trim(), enabled = true, custom = true)
        // A mesma trapaça já está na lista (toque duplo em "Adicionar", ou igual a uma do arquivo): só liga a que existe.
        state.cheats.firstOrNull { it.code.trim() == c.code && it.description == c.description }?.let { existing ->
            if (!existing.enabled) toggle(existing)
            return
        }
        update(state.copy(cheats = state.cheats + c))
    }

    /** Trava [cheat] (da busca na memória): entra na lista como código próprio e já ligado. */
    fun addRam(cheat: RamCheat, description: String) {
        addCustom(description.ifBlank { "0x${cheat.address.toString(16).uppercase()} = ${cheat.value}" }, cheat.code())
    }

    fun remove(cheat: Cheat) = update(state.copy(cheats = state.cheats.filterNot { it === cheat }))

    suspend fun search(query: String): List<String> = repo.search(game.systemId, query)

    private fun update(new: GameCheats) {
        val changedCodes = new.enabled != state.enabled
        state = new
        if (changedCodes) dirty = true
        // Sempre o estado mais recente, uma gravação por vez: duas mudanças seguidas não se atropelam.
        scope.launch { saveLock.withLock { runCatching { repo.save(game, state) } } }
    }

    private fun launchLoading(block: suspend () -> Unit) {
        loading = true
        error = null
        scope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                error = t.userMessage(context)
            } finally {
                loading = false
            }
        }
    }
}
