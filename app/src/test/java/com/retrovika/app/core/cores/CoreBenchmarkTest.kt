package com.retrovika.app.core.cores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.retrovika.app.core.tuning.DeviceTier
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

    // region parar no primeiro com folga

    /** Roda o teste como o GameActivity: pede o próximo e "mede" com [speedOf]. */
    private fun run(order: List<String>, start: List<CoreSpeed> = emptyList(), speedOf: (String) -> Float?): List<CoreSpeed> {
        val results = start.toMutableList()
        while (true) {
            val id = CoreBenchmark.next(order, results) ?: break
            results += CoreSpeed(id, speedOf(id))
        }
        return results
    }

    @Test
    fun `o primeiro com folga encerra o teste e os de tras ficam sem testar`() {
        val results = run(ordered) { if (it == "accurate") 0.9f else 2f }
        assertEquals(listOf("accurate", "balanced"), results.map { it.coreId })
        assertEquals("balanced", CoreBenchmark.choose(ordered, results))
        assertEquals(listOf("fast"), CoreBenchmark.untested(ordered, results))
    }

    @Test
    fun `parar cedo escolhe o mesmo que testar todos`() {
        val speeds = listOf(
            listOf(2f, 3f, 5f), listOf(0.8f, 1.4f, 9f), listOf(0.5f, 0.6f, 0.7f), listOf(null, null, 1.5f), listOf(1.29f, 1.3f, 8f),
            listOf(null, 0.4f, null), listOf(null, null, null),
        )
        speeds.forEach { s ->
            val all = ordered.mapIndexed { i, id -> CoreSpeed(id, s[i]) }
            val early = run(ordered) { id -> all.first { it.coreId == id }.speed }
            assertEquals(s.toString(), CoreBenchmark.choose(ordered, all), CoreBenchmark.choose(ordered, early))
        }
    }

    @Test
    fun `nucleo que falhou nao encerra o teste, e sem vencedor todos rodam`() {
        assertEquals(ordered, run(ordered) { null }.map { it.coreId })
        assertEquals(ordered, run(ordered) { 0.5f }.map { it.coreId })
        // Falhou o primeiro, o segundo passa: o terceiro nao e testado.
        assertEquals(listOf("accurate", "balanced"), run(ordered) { if (it == "accurate") null else 1.5f }.map { it.coreId })
    }

    @Test
    fun `retomando depois de uma queda nao repete nem passa do vencedor`() {
        // O teste anterior derrubou o app no "accurate" (resultado nulo) e mediu o "balanced" com folga.
        val resumed = listOf(CoreSpeed("accurate", null), CoreSpeed("balanced", 1.4f))
        assertNull(CoreBenchmark.next(ordered, resumed))
        assertEquals(listOf("fast"), CoreBenchmark.untested(ordered, resumed))
        // Derrubou o app no ultimo: nao ha mais o que testar.
        assertNull(CoreBenchmark.next(ordered, ordered.map { CoreSpeed(it, null) }))
    }

    @Test
    fun `nao testado so existe depois do vencedor`() {
        assertTrue(CoreBenchmark.untested(ordered, emptyList()).isEmpty())
        assertTrue(CoreBenchmark.untested(ordered, listOf(CoreSpeed("accurate", 0.5f))).isEmpty())
        // Falhou nao e "nao testado".
        assertTrue(CoreBenchmark.untested(ordered, listOf(CoreSpeed("accurate", 2f), CoreSpeed("balanced", 3f), CoreSpeed("fast", null))).isEmpty())
        assertEquals(listOf("balanced", "fast"), CoreBenchmark.untested(ordered, listOf(CoreSpeed("accurate", 2f))))
        assertEquals(listOf("balanced", "fast"), SystemBenchmark(listOf(CoreSpeed("accurate", 2f)), "accurate", "d", 0).untested(ordered))
    }

    // endregion

    // region medicao adaptativa

    @Test
    fun `so decide cedo depois do tempo minimo`() {
        assertFalse(CoreBenchmark.isDecided(20f, CoreBenchmark.MEASURE_MIN_MS - 1))
        assertTrue(CoreBenchmark.isDecided(20f, CoreBenchmark.MEASURE_MIN_MS))
        assertFalse(CoreBenchmark.isDecided(null, CoreBenchmark.MEASURE_MAX_MS))
    }

    @Test
    fun `longe dos limites decide, perto deles segue medindo`() {
        val t = CoreBenchmark.MEASURE_MIN_MS
        assertTrue(CoreBenchmark.isDecided(CoreBenchmark.DECIDED_FAST, t))
        assertTrue(CoreBenchmark.isDecided(CoreBenchmark.DECIDED_SLOW, t))
        assertTrue(CoreBenchmark.isDecided(0f, t))
        // Em torno do HEADROOM (1,3) e do UP_PROBE (1,6) e das faixas entre eles e os cortes: nada de parar.
        listOf(0.8f, 1.0f, 1.2f, 1.3f, 1.45f, 1.6f, 2.0f, 3.0f, 3.19f).forEach { assertFalse("$it", CoreBenchmark.isDecided(it, t)) }
    }

    @Test
    fun `os cortes ficam fora de todos os limites de decisao`() {
        // O corte rapido tem de passar o maior limite com folga, e o lento tem de ficar abaixo do menor.
        assertTrue(CoreBenchmark.DECIDED_FAST >= 2 * com.retrovika.app.core.tuning.Tuning.UP_PROBE)
        assertTrue(CoreBenchmark.DECIDED_SLOW <= 0.5f * CoreBenchmark.HEADROOM)
        assertTrue(CoreBenchmark.MEASURE_MIN_MS < CoreBenchmark.MEASURE_MAX_MS)
    }

    // endregion

    // region console leve e medida reaproveitada

    @Test
    fun `console leve pula o teste a partir da classe media`() {
        assertFalse(CoreBenchmark.skipForLightweight(true, DeviceTier.ENTRY))
        DeviceTier.entries.filter { it != DeviceTier.ENTRY }.forEach { assertTrue("$it", CoreBenchmark.skipForLightweight(true, it)) }
        DeviceTier.entries.forEach { assertFalse("$it", CoreBenchmark.skipForLightweight(false, it)) }
    }

    @Test
    fun `reaproveita a velocidade so com as mesmas opcoes e uma medida valida`() {
        val a = mapOf("x" to "1", "y" to "2")
        assertEquals(1.7f, CoreBenchmark.reusable(a, 1.7f, mapOf("y" to "2", "x" to "1"))!!, 0f)
        assertNull(CoreBenchmark.reusable(a, 1.7f, a + ("z" to "3")))
        assertNull(CoreBenchmark.reusable(a, 1.7f, mapOf("x" to "1", "y" to "9")))
        assertNull(CoreBenchmark.reusable(a, null, a))
        assertNull(CoreBenchmark.reusable(null, 2f, a))
        // Sem opcoes nos dois lados tambem e a mesma medida.
        assertEquals(2f, CoreBenchmark.reusable(emptyMap(), 2f, emptyMap())!!, 0f)
    }

    // endregion
}
