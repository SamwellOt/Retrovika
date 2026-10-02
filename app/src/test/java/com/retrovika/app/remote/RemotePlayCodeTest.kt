package com.retrovika.app.remote

import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePlayCodeTest {

    private val play = RemotePlay(ContextWrapper(null))
    private val code = RemotePlay::class.java.getDeclaredField("code").apply { isAccessible = true }.get(play) as String

    private fun ws(c: String) = HttpRequest.parse(
        "GET /ws?role=pad&c=$c&id=abc HTTP/1.1\r\nUpgrade: websocket\r\nSec-WebSocket-Key: x\r\n\r\n",
    )

    @Test
    fun wrongCodesNeverLockOutTheRightOne() {
        val wrong = if (code == "000000") "111111" else "000000"
        // Erros até chegar ao limite de um endereço (20) e ao de todos juntos (60): o próximo erro já seria freado.
        repeat(20) { assertEquals(403, play.authorize(ws(wrong), "192.168.0.50")!!.status) }
        for (i in 1..40) assertEquals(403, play.authorize(ws(wrong), "192.168.0.${100 + i % 10}")!!.status)
        // O código certo passa na hora, inclusive do endereço que mais errou.
        assertNull(play.authorize(ws(code), "192.168.0.50"))
        assertNull(play.authorize(ws(code), "192.168.0.7"))
    }

    @Test
    fun wrongCodesAboveTheLimitAreSlowed() {
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(20) { assertEquals(403, play.authorize(ws(wrong), "192.168.0.50")!!.status) }
        val started = System.currentTimeMillis()
        assertEquals(429, play.authorize(ws(wrong), "192.168.0.50")!!.status)
        assertTrue(System.currentTimeMillis() - started >= 4_000)
        // Outro endereço, abaixo do limite dele e do geral, responde na hora.
        val other = System.currentTimeMillis()
        assertEquals(403, play.authorize(ws(wrong), "192.168.0.51")!!.status)
        assertTrue(System.currentTimeMillis() - other < 1_000)
    }
}
