package com.retrovika.app.core.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verificação que o WebView invisível não passou sozinho: o Cloudflare às vezes pede o toque na caixa
 * "Verify you are human" (ou demora mais que o prazo). A tela mostra a página ao usuário (ChallengeHost);
 * a liberação fica no CookieManager, que é global, e serve ao download feito pelo OkHttp.
 *
 * Sem nenhuma tela à vista (app em segundo plano), [ask] falha na hora: o download mostra o erro e o
 * "Tentar de novo" pergunta outra vez com o app aberto.
 */
class ChallengePrompt {
    class Request(val url: String) {
        val host: String = runCatching { URI(url).host }.getOrNull().orEmpty()
        internal val answer = CompletableDeferred<Boolean>()
    }

    private val _current = MutableStateFlow<Request?>(null)
    /** A verificação à espera do usuário, mostrada pela tela. */
    val current: StateFlow<Request?> = _current
    private val visible = AtomicInteger()
    // Uma por vez: dois downloads do mesmo servidor não abrem duas janelas; o segundo, depois do primeiro,
    // já encontra a liberação e a janela dele fecha sozinha.
    private val queue = Mutex()

    /** Uma tela capaz de mostrar a verificação está à vista; chame o retorno quando ela sair. */
    fun attach(): () -> Unit {
        visible.incrementAndGet()
        return { visible.decrementAndGet() }
    }

    /** Mostra [url] ao usuário e espera: true quando a verificação passou. */
    suspend fun ask(url: String): Boolean {
        if (visible.get() == 0) return false
        return queue.withLock {
            val request = Request(url)
            _current.value = request
            try {
                withTimeoutOrNull(TIMEOUT_MS) { request.answer.await() } ?: false
            } finally {
                _current.compareAndSet(request, null)
            }
        }
    }

    /** Resposta da tela: passou ou o usuário desistiu. */
    fun answer(request: Request, passed: Boolean) {
        request.answer.complete(passed)
        _current.compareAndSet(request, null)
    }

    private companion object {
        const val TIMEOUT_MS = 3 * 60_000L
    }
}
