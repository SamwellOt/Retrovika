package com.retrovika.app.core.net

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetcherPageTest {

    /** Como o evaluateJavascript devolve o JSON.stringify do PAGE_STATE_JS: uma string JSON dentro de outra. */
    private fun evaluated(ready: String, title: String, host: String): String =
        JsonPrimitive("""["$ready","$title","$host"]""").toString()

    @Test
    fun `pagina do site depois da verificacao passa`() {
        assertTrue(WebFetcher.pageCleared(evaluated("complete", "RomsFun", "romsfun.com")))
        assertTrue(WebFetcher.pageCleared(evaluated("interactive", "RomsFun", "romsfun.com")))
    }

    @Test
    fun `pagina sem titulo passa`() {
        // A raiz do sto2.romsforever.co, liberada, é texto puro ("Sorry, your request is invalid"): sem <title>.
        assertTrue(WebFetcher.pageCleared(evaluated("complete", "", "sto2.romsforever.co")))
    }

    @Test
    fun `verificacao, carregamento e about blank nao passam`() {
        assertFalse(WebFetcher.pageCleared(evaluated("complete", "Just a moment...", "sto2.romsforever.co")))
        assertFalse(WebFetcher.pageCleared(evaluated("complete", "Establishing a secure connection", "backloggd.com")))
        assertFalse(WebFetcher.pageCleared(evaluated("loading", "RomsFun", "romsfun.com")))
        // O about:blank do começo: documento pronto, sem título e sem host.
        assertFalse(WebFetcher.pageCleared(evaluated("complete", "", "")))
        assertFalse(WebFetcher.pageCleared(null))
        assertFalse(WebFetcher.pageCleared("null"))
    }
}
