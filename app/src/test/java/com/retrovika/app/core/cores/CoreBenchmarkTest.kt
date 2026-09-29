package com.retrovika.app.core.cores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreBenchmarkTest {

    private val ordered = listOf("accurate", "balanced", "fast")

    @Test
    fun `fica com o mais fiel que roda com folga`() {
        val results = listOf(CoreSpeed("accurate", 1.1f), CoreSpeed("balanced", 2.5f), CoreSpeed("fast", 6f))
        assertEquals("balanced", CoreBenchmark.choose(ordered, results))
        assertEquals("accurate", CoreBenchmark.choose(ordered, results.map { it.copy(speed = 4f) }))
    }

    @Test
    fun `sem folga em nenhum, o mais rapido`() {
        val results = listOf(CoreSpeed("accurate", 0.5f), CoreSpeed("balanced", null), CoreSpeed("fast", 0.9f))
        assertEquals("fast", CoreBenchmark.choose(ordered, results))
    }

    @Test
    fun `nenhum rodou, fica o padrao`() {
        assertEquals("accurate", CoreBenchmark.choose(ordered, ordered.map { CoreSpeed(it, null) }))
    }

    @Test
    fun `mede a velocidade pela taxa nativa`() {
        assertEquals(2f, CoreBenchmark.speed(frames = 480, millis = 4000, contentFps = 60.0)!!, 0.001f)
        assertEquals(1f, CoreBenchmark.speed(frames = 200, millis = 4000, contentFps = 50.0)!!, 0.001f)
        assertNull(CoreBenchmark.speed(10, 0, 60.0))
    }
}
