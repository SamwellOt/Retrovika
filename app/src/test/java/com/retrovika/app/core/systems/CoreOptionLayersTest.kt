package com.retrovika.app.core.systems

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoreOptionLayersTest {

    @Test
    fun `cada camada vence a anterior e as fixas vencem todas`() {
        val merged = CoreOptionLayers.merge(
            defaults = mapOf("res" to "1", "fs" to "0", "a" to "d"),
            preset = mapOf("res" to "2"),
            device = mapOf("res" to "3", "fs" to "1"),
            user = mapOf("res" to "4"),
            game = mapOf("res" to "5", "extra" to "g"),
            fixed = mapOf("card" to "shared", "a" to "fixo"),
        )
        assertEquals("5", merged["res"])      // o jogo vence o usuário
        assertEquals("1", merged["fs"])       // o aparelho vence o padrão
        assertEquals("g", merged["extra"])
        assertEquals("fixo", merged["a"])     // fixa vence tudo
        assertEquals("shared", merged["card"])
    }

    @Test
    fun `o jogo nao muda o que outros jogos veem`() {
        val user = mapOf("res" to "4")
        val a = CoreOptionLayers.merge(emptyMap(), emptyMap(), emptyMap(), user, mapOf("res" to "9"), emptyMap())
        val b = CoreOptionLayers.merge(emptyMap(), emptyMap(), emptyMap(), user, emptyMap(), emptyMap())
        assertEquals("9", a["res"])
        assertEquals("4", b["res"])
    }

    @Test
    fun `sem a camada do jogo volta o valor das outras ou nada`() {
        val restored = CoreOptionLayers.withoutGame(
            gameOverrides = mapOf("res" to "9", "filter" to "x"),
            otherLayers = mapOf("res" to "4"),
        )
        assertEquals("4", restored["res"])
        assertNull(restored["filter"])         // ninguém fala dela: vale o padrão do núcleo
        assertEquals(setOf("res", "filter"), restored.keys)
    }
}
