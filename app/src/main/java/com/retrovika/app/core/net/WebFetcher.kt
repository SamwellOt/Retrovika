package com.retrovika.app.core.net

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import java.security.SecureRandom

/**
 * Pedidos feitos de dentro de um WebView invisível, para sites cuja CDN pede uma verificação em
 * JavaScript ("Establishing a secure connection…", a BunnyCDN do Backloggd). O WebView abre o site,
 * passa pela verificação como um navegador e, dali em diante, cada pedido sai da própria página com
 * `fetch()`. Não basta copiar o cookie para o OkHttp: a CDN o prende à conexão do navegador (conferido
 * no Actions com o Chromium: o cookie reaproveitado fora dele volta a receber 403).
 *
 * O WebView fica aberto enquanto há pedidos e é destruído depois de [IDLE_MS] sem uso.
 *
 * [minGapMs] espaça os pedidos: alguns sites (RomsFun, atrás do Cloudflare) respondem a dezenas de
 * `fetch` seguidos com 429 e uma nova verificação. Quando a verificação volta no meio da sessão, o site é
 * aberto de novo e o pedido, refeito uma vez.
 */
class WebFetcher(private val context: Context, private val minGapMs: Long = 0) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val lock = Mutex()
    private var view: WebView? = null
    private var origin: String? = null
    private var idle: Job? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()
    private val random = SecureRandom()
    /** Sobe a cada [release]: diz se o WebView em que um pedido saiu ainda é o atual. */
    @Volatile private var generation = 0
    private val gap = Mutex()
    private var lastRequest = 0L

    /** Resposta de [request]: o status HTTP (0 quando o `fetch` falhou) e o corpo. */
    data class Response(val status: Int, val body: String)

    /**
     * User-Agent do WebView. A liberação da verificação (`cf_clearance`) fica presa a ele: um download
     * feito pelo OkHttp com o cookie do WebView precisa mandar o mesmo.
     */
    val userAgent: String by lazy { WebSettings.getDefaultUserAgent(context) }

    /** Cookies do WebView para [url] (inclusive os da verificação), no formato do cabeçalho Cookie. */
    fun cookies(url: String): String? = CookieManager.getInstance().getCookie(url)

    /**
     * Recebe as respostas do `fetch()` da página (chamado numa thread do WebView). O [token] é aleatório
     * por pedido: um script da própria página que chame `RetrovikaBridge.done` não acerta um pedido em
     * andamento (com ids sequenciais, bastava chutar o próximo número).
     */
    private inner class Bridge {
        @JavascriptInterface
        fun done(token: String, status: Int, body: String) {
            pending.remove(token)?.complete(status to body)
        }
    }

    /**
     * Abre o WebView em [origin] e passa pela verificação antes do primeiro pedido. Fica aberto pelo prazo
     * normal ([IDLE_MS]) esperando os pedidos.
     */
    suspend fun warm(origin: String) {
        try {
            lock.withLock { withContext(Dispatchers.Main) { ready(origin) } }
        } finally {
            main.launch { scheduleRelease() }
        }
    }

    /** GET de [url] de dentro de uma página de [origin] (ex.: `https://backloggd.com/`). */
    suspend fun get(origin: String, url: String): String {
        val (status, body) = request(origin, url)
        if (status !in 200..299) throw HttpStatusException(if (status < 0) 0 else status, url)
        return body
    }

    /**
     * Pedido de dentro de uma página de [origin], devolvendo o status como veio (sem lançar em 4xx/5xx).
     * [referrer] precisa ser da mesma origem (regra do `fetch`); há sites que descobrem pelo Referer
     * de qual página veio o pedido (o AJAX de download do RomsFun).
     */
    suspend fun request(
        origin: String,
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
        referrer: String? = null,
    ): Response {
        repeat(2) { attempt ->
            // Só a largada é espaçada: segurar a trava durante o fetch enfileiraria todos os pedidos
            // (o Backloggd pede a página do jogo e as resenhas ao mesmo tempo).
            if (minGapMs > 0) gap.withLock {
                val wait = lastRequest + minGapMs - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastRequest = System.currentTimeMillis()
            }
            val (res, gen) = runFetch(origin, url, method, body, contentType, referrer)
            if (res.status == RELEASED) {
                // Outro pedido viu a verificação (ou trocou de site) e fechou a página com este no meio: não
                // é falha deste, refaz uma vez na página nova.
                if (attempt == 0) return@repeat
                return Response(-1, "")
            }
            if (!(res.status in CHALLENGE_STATUS && isChallengePage(res.body))) return res
            // A verificação expirou ou voltou por excesso de pedidos: abre o site de novo. Só se a página
            // ainda é a deste pedido: vários pedidos paralelos veem o mesmo desafio, e o segundo derrubaria a
            // página que o primeiro acabou de reabrir.
            withContext(Dispatchers.Main) { lock.withLock { if (view != null && generation == gen) release() } }
            if (attempt == 0) delay(CHALLENGE_RETRY_MS)
        }
        throw HttpStatusException(403, url)
    }

    /**
     * Abre [url] para passar pela verificação daquele domínio (ex.: o servidor de arquivos de um site),
     * deixando a liberação no [CookieManager] para um download feito fora do WebView.
     */
    suspend fun solve(url: String) {
        // WebView próprio, descartado no fim: trocar a página do compartilhado derrubaria os pedidos que
        // outros downloads e a busca ainda esperam dele. A liberação fica no CookieManager, que é global.
        withContext(Dispatchers.Main) {
            val web = open(url)
            web.stopLoading()
            web.destroy()
        }
    }

    /** O fetch e a geração do WebView em que ele saiu. */
    private suspend fun runFetch(origin: String, url: String, method: String, body: String?, contentType: String?, referrer: String?): Pair<Response, Int> {
        val id = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val quoted = JsonPrimitive(id).toString()
        var gen = 0
        val answer = CompletableDeferred<Pair<Int, String>>()
        val options = buildList {
            add("credentials: 'include'")
            if (method != "GET") add("method: ${JsonPrimitive(method)}")
            if (body != null) add("body: ${JsonPrimitive(body)}")
            if (contentType != null) add("headers: {'Content-Type': ${JsonPrimitive(contentType)}}")
            if (referrer != null) { add("referrer: ${JsonPrimitive(referrer)}"); add("referrerPolicy: 'unsafe-url'") }
        }.joinToString(", ")
        val script = "fetch(${JsonPrimitive(url)}, {$options})" +
            ".then(r => r.text().then(t => RetrovikaBridge.done($quoted, r.status, t)))" +
            ".catch(e => RetrovikaBridge.done($quoted, -1, String(e)))"
        val result = try {
            // Registrado e disparado sem soltar a trava: um release() no meio destruiria a página antes do
            // fetch e o pedido só acabaria no prazo, sem resposta.
            lock.withLock {
                withContext(Dispatchers.Main) {
                    val page = ready(origin)
                    gen = generation
                    pending[id] = answer
                    page.evaluateJavascript(script, null)
                }
            }
            // withTimeoutOrNull, não withTimeout: o TimeoutCancellationException passaria por cancelamento
            // e a parte da página que pediu ficaria carregando para sempre, sem mostrar a falha.
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) { answer.await() }
        } finally {
            pending.remove(id)
            // Mesmo com erro ou cancelamento, o WebView volta a ter prazo para ser destruído.
            main.launch { scheduleRelease() }
        }
        val (status, text) = result ?: throw java.net.SocketTimeoutException(url)
        return Response(status, text) to gen
    }

    /** O WebView na página de [origin], já depois da verificação. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ready(origin: String): WebView {
        idle?.cancel()
        view?.takeIf { this.origin == origin }?.let { return it }
        release()
        val web = open(origin)
        view = web
        this.origin = origin
        return web
    }

    /** Um WebView novo em [url], já depois da verificação; destruído aqui se ela não passar. */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun open(url: String): WebView {
        val web = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "RetrovikaBridge")
            loadUrl(url)
        }
        // A verificação roda e recarrega a página sozinha; pronto quando o título deixa de ser o dela. Não
        // precisa esperar a página inteira (imagens, anúncios): com o documento lido, o fetch() já funciona.
        val passed = try {
            withTimeoutOrNull(CHALLENGE_TIMEOUT_MS) {
                while (true) {
                    delay(POLL_MS)
                    val title = web.title.orEmpty()
                    if (title.isNotBlank() && !isChallengeTitle(title) && web.progress == 100) break
                    if (documentReady(web)) break
                }
            }
        } catch (t: Throwable) {
            // Cancelado no meio da verificação: um WebView ainda na página de desafio não pode ser reaproveitado.
            web.destroy()
            throw t
        }
        if (passed == null) {
            web.destroy()
            throw HttpStatusException(403, url)
        }
        return web
    }

    /**
     * O documento da página já foi lido (readyState "interactive" ou "complete"), tem título e não é a
     * verificação. O título vem do próprio documento: o do WebView pode ser a URL antes de a página chegar.
     */
    private suspend fun documentReady(web: WebView): Boolean {
        val raw = CompletableDeferred<String?>()
        web.evaluateJavascript("JSON.stringify([document.readyState, document.title])") { raw.complete(it) }
        val state = runCatching {
            val inner = Http.json.parseToJsonElement(raw.await() ?: return false).jsonPrimitive.content
            Http.json.parseToJsonElement(inner).jsonArray.map { it.jsonPrimitive.content }
        }.getOrNull() ?: return false
        val (ready, title) = state.getOrNull(0) to state.getOrNull(1).orEmpty()
        return ready != "loading" && title.isNotBlank() && !isChallengeTitle(title)
    }

    private fun scheduleRelease() {
        idle?.cancel()
        idle = main.launch { delay(IDLE_MS); lock.withLock { release() } }
    }

    private fun release() {
        view?.let { it.stopLoading(); it.destroy() }
        view = null
        origin = null
        generation++
        // Os pedidos em andamento acabam como RELEASED, e request() os refaz uma vez.
        pending.values.forEach { it.complete(RELEASED to "") }
        pending.clear()
    }

    companion object {
        private const val CHALLENGE_TIMEOUT_MS = 20_000L
        private const val REQUEST_TIMEOUT_MS = 20_000L
        private const val IDLE_MS = 3 * 60_000L
        private const val POLL_MS = 250L
        private const val CHALLENGE_RETRY_MS = 2_000L
        /** Status interno de um pedido cuja página foi fechada por [release] antes da resposta. */
        private const val RELEASED = -2
        /** Status com que a página de verificação volta no meio da sessão. */
        private val CHALLENGE_STATUS = setOf(403, 429, 503)

        /** A página de verificação da BunnyCDN (e de outras CDNs com desafio em JavaScript). */
        fun isChallengeTitle(title: String): Boolean =
            title.contains("Establishing a secure connection", true) || title.contains("Just a moment", true)

        fun isChallengePage(html: String): Boolean = isChallengeTitle(html.take(600))

        /** Resposta do OkHttp que é a verificação da CDN, não um erro de verdade. */
        fun isChallenge(error: Throwable): Boolean = error is HttpStatusException && (error.code == 403 || error.code == 503)
    }
}
