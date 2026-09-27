package com.retrovika.app.core.net

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Alguns sites ficam atrás de uma CDN que, conforme o IP, responde com uma página de verificação em
 * JavaScript ("Establishing a secure connection…") em vez do conteúdo. O OkHttp não roda JavaScript;
 * um WebView invisível abre o site, passa pela verificação como um navegador e deixa o cookie no
 * [CookieManager]. Os pedidos seguintes vão com esse cookie e o User-Agent do mesmo WebView.
 */
class WebChallenge(private val context: Context) {
    private val lock = Mutex()

    /** Cabeçalhos (Cookie e User-Agent) que passam pela verificação de [url], ou nulo se ela não foi vencida a tempo. */
    suspend fun solve(url: String): Map<String, String>? = lock.withLock {
        withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance()
            val userAgent = WebSettings.getDefaultUserAgent(context)
            val view = createView(url)
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    // A verificação recarrega a página sozinha quando termina: espera o título deixar de ser o dela.
                    while (true) {
                        delay(POLL_MS)
                        val title = view.title.orEmpty()
                        val cookie = cookies.getCookie(url)
                        if (!cookie.isNullOrBlank() && title.isNotBlank() && !isChallengeTitle(title)) break
                    }
                } ?: return@withContext null
                cookies.flush()
                mapOf("Cookie" to cookies.getCookie(url).orEmpty(), "User-Agent" to userAgent)
            } finally {
                view.stopLoading()
                view.destroy()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(url: String): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = WebViewClient()
        CookieManager.getInstance().setAcceptCookie(true)
        loadUrl(url)
    }

    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val POLL_MS = 400L

        /** A página de verificação da BunnyCDN (e de outras CDNs com desafio em JavaScript). */
        fun isChallengeTitle(title: String): Boolean =
            title.contains("Establishing a secure connection", true) || title.contains("Just a moment", true)

        fun isChallenge(error: Throwable): Boolean = error is HttpStatusException && (error.code == 403 || error.code == 503)
    }
}
