package com.retrovika.app.core.net

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pedidos feitos de dentro de um WebView invisível, para sites cuja CDN pede uma verificação em
 * JavaScript ("Establishing a secure connection…", a BunnyCDN do Backloggd). O WebView abre o site,
 * passa pela verificação como um navegador e, dali em diante, cada pedido sai da própria página com
 * `fetch()`. Não basta copiar o cookie para o OkHttp: a CDN o prende à conexão do navegador (conferido
 * no Actions com o Chromium: o cookie reaproveitado fora dele volta a receber 403).
 *
 * O WebView fica aberto enquanto há pedidos e é destruído depois de [IDLE_MS] sem uso.
 */
class WebFetcher(private val context: Context) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Mutex()
    private var view: WebView? = null
    private var origin: String? = null
    private var idle: Job? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String>>>()
    private val ids = AtomicInteger()

    /** Recebe as respostas do `fetch()` da página (chamado numa thread do WebView). */
    private inner class Bridge {
        @JavascriptInterface
        fun done(id: Int, status: Int, body: String) {
            pending.remove(id)?.complete(status to body)
        }
    }

    /** GET de [url] de dentro de uma página de [origin] (ex.: `https://backloggd.com/`). */
    suspend fun get(origin: String, url: String): String {
        val page = lock.withLock { withContext(Dispatchers.Main) { ready(origin) } }
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<Pair<Int, String>>()
        pending[id] = answer
        val script = "fetch(${JsonPrimitive(url)}, {credentials: 'include'})" +
            ".then(r => r.text().then(t => RetrovikaBridge.done($id, r.status, t)))" +
            ".catch(e => RetrovikaBridge.done($id, -1, String(e)))"
        val result = try {
            withContext(Dispatchers.Main) { page.evaluateJavascript(script, null) }
            // withTimeoutOrNull, não withTimeout: o TimeoutCancellationException passaria por cancelamento
            // e a parte da página que pediu ficaria carregando para sempre, sem mostrar a falha.
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { answer.await() }
        } finally {
            pending.remove(id)
            // Mesmo com erro ou cancelamento, o WebView volta a ter prazo para ser destruído.
            main.launch { scheduleRelease() }
        }
        val (status, body) = result ?: throw java.net.SocketTimeoutException(url)
        if (status == 403 && isChallengePage(body)) {
            // A verificação expirou: o próximo pedido abre o site de novo.
            // Só se ainda for o mesmo WebView: outro pedido pode já ter aberto um novo (e estar esperando
            // a verificação nele), e destruí-lo faria esse pedido falhar por tempo esgotado.
            withContext(Dispatchers.Main) { lock.withLock { if (view === page) release() } }
            throw HttpStatusException(403, url)
        }
        if (status !in 200..299) throw HttpStatusException(if (status < 0) 0 else status, url)
        return body
    }

    /** O WebView na página de [origin], já depois da verificação. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ready(origin: String): WebView {
        idle?.cancel()
        view?.takeIf { this.origin == origin }?.let { return it }
        release()
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "RetrovikaBridge")
            loadUrl(origin)
        }
        view = web
        this.origin = origin
        // A verificação roda e recarrega a página sozinha; pronto quando o título deixa de ser o dela.
        val passed = try {
            withTimeoutOrNull(CHALLENGE_TIMEOUT_MS) {
                while (true) {
                    delay(POLL_MS)
                    val title = web.title.orEmpty()
                    if (title.isNotBlank() && !isChallengeTitle(title) && web.progress == 100) break
                }
            }
        } catch (t: Throwable) {
            // Cancelado no meio da verificação: um WebView ainda na página de desafio não pode ser reaproveitado.
            release()
            throw t
        }
        if (passed == null) {
            release()
            throw HttpStatusException(403, origin)
        }
        return web
    }

    private fun scheduleRelease() {
        idle?.cancel()
        idle = main.launch { delay(IDLE_MS); lock.withLock { release() } }
    }

    private fun release() {
        view?.let { it.stopLoading(); it.destroy() }
        view = null
        origin = null
        pending.values.forEach { it.complete(-1 to "") }
        pending.clear()
    }

    companion object {
        private const val CHALLENGE_TIMEOUT_MS = 20_000L
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val IDLE_MS = 3 * 60_000L
        private const val POLL_MS = 250L

        /** A página de verificação da BunnyCDN (e de outras CDNs com desafio em JavaScript). */
        fun isChallengeTitle(title: String): Boolean =
            title.contains("Establishing a secure connection", true) || title.contains("Just a moment", true)

        fun isChallengePage(html: String): Boolean = isChallengeTitle(html.take(600))

        /** Resposta do OkHttp que é a verificação da CDN, não um erro de verdade. */
        fun isChallenge(error: Throwable): Boolean = error is HttpStatusException && (error.code == 403 || error.code == 503)
    }
}
