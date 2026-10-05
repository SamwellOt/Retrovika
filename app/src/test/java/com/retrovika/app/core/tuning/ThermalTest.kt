package com.retrovika.app.core.tuning

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalTest {
    private val lookback = 15_000L

    @Test
    fun `os limites sao os do PowerManager`() {
        assertEquals(PowerManager.THERMAL_STATUS_MODERATE, Thermal.THROTTLING)
        assertEquals(PowerManager.THERMAL_STATUS_SEVERE, Thermal.WARN)
    }

    @Test
    fun `aviso so a partir do severo`() {
        assertFalse(Thermal.shouldWarn(PowerManager.THERMAL_STATUS_NONE))
        assertFalse(Thermal.shouldWarn(PowerManager.THERMAL_STATUS_LIGHT))
        assertFalse(Thermal.shouldWarn(PowerManager.THERMAL_STATUS_MODERATE))
        assertTrue(Thermal.shouldWarn(PowerManager.THERMAL_STATUS_SEVERE))
        assertTrue(Thermal.shouldWarn(PowerManager.THERMAL_STATUS_SHUTDOWN))
    }

    @Test
    fun `limitado agora explica a lentidao`() {
        assertTrue(Thermal.explainsSlowdown(PowerManager.THERMAL_STATUS_MODERATE, null, 100_000, lookback))
        assertTrue(Thermal.explainsSlowdown(PowerManager.THERMAL_STATUS_SEVERE, null, 100_000, lookback))
    }

    @Test
    fun `frio ou so leve nao explica`() {
        assertFalse(Thermal.explainsSlowdown(PowerManager.THERMAL_STATUS_NONE, null, 100_000, lookback))
        assertFalse(Thermal.explainsSlowdown(PowerManager.THERMAL_STATUS_LIGHT, null, 100_000, lookback))
    }

    @Test
    fun `esteve limitado dentro do recuo explica, fora dele nao`() {
        assertTrue(Thermal.explainsSlowdown(PowerManager.THERMAL_STATUS_NONE, 90_000, 100_000, lookback))
        assertTrue(Thermal.explainsSlowdown(PowerManager.THERMAL_STATUS_LIGHT, 85_000, 100_000, lookback))
        assertFalse(Thermal.explainsSlowdown(PowerManager.THERMAL_STATUS_NONE, 80_000, 100_000, lookback))
    }
}
