package com.retrovika.app.emulation.input

import com.retrovika.app.core.net.Http
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadProfileTest {
    @Test
    fun `partes de volta ao padrao saem do mapa`() {
        val moved = PadProfile().withElement(portrait = true, PadElement.FACE, PadElementConfig(dx = 0.1f, scale = 1.3f))
        assertEquals(PadElementConfig(dx = 0.1f, scale = 1.3f), moved.element(true, PadElement.FACE))
        assertEquals(PadElementConfig(), moved.element(false, PadElement.FACE))
        val back = moved.withElement(portrait = true, PadElement.FACE, PadElementConfig())
        assertTrue(back.portrait.isEmpty())
        assertTrue(back.isDefault)
    }

    @Test
    fun `retrato e paisagem tem layouts separados`() {
        val p = PadProfile()
            .withElement(true, PadElement.LEFT, PadElementConfig(hidden = true))
            .withElement(false, PadElement.CENTER, PadElementConfig(dy = -0.2f))
        assertEquals(setOf(PadElement.LEFT), p.portrait.keys)
        assertEquals(setOf(PadElement.CENTER), p.landscape.keys)
        assertTrue(p.resetLayout(true).portrait.isEmpty())
        assertEquals(p.landscape, p.resetLayout(true).landscape)
    }

    @Test
    fun `retrato dividido e sobreposto tem layouts separados`() {
        val split = PadProfile().withElement(true, PadElement.FACE, PadElementConfig(dy = -0.3f))
        val overlay = split.copy(portraitMode = PortraitMode.OVERLAY)
        assertEquals(PadElementConfig(), overlay.element(true, PadElement.FACE))
        val moved = overlay.withElement(true, PadElement.LEFT, PadElementConfig(dx = 0.1f))
        assertEquals(setOf(PadElement.LEFT), moved.portraitOverlay.keys)
        assertEquals(setOf(PadElement.FACE), moved.portrait.keys)
        assertEquals(split.portrait, moved.resetLayout(true).portrait)
    }

    @Test
    fun `parte arrastada para nas bordas da area`() {
        // Parte de 100x50 no canto inferior esquerdo (x 20..120, y 150..200) de uma área 400x200.
        val l = OffsetLimits.of(20f, 150f, 100f, 50f, scale = 1f, areaWidth = 400f, areaHeight = 200f)!!
        assertEquals(-20f / 400f, l.clampX(-0.9f), 1e-6f)
        assertEquals(280f / 400f, l.clampX(0.9f), 1e-6f)
        assertEquals(-150f / 200f, l.clampY(-0.9f), 1e-6f)
        assertEquals(0f, l.clampY(0.3f), 1e-6f)
        // Com o dobro do tamanho (cresce do centro), sobra menos espaço: a borda esquerda chega em 70 - 100 = -30.
        val big = OffsetLimits.of(20f, 150f, 100f, 50f, scale = 2f, areaWidth = 400f, areaHeight = 200f)!!
        assertEquals(30f / 400f, big.clampX(-0.9f), 1e-6f)
        assertEquals(230f / 400f, big.clampX(0.9f), 1e-6f)
    }

    @Test
    fun `parte maior que a area fica centralizada`() {
        val l = OffsetLimits.of(0f, 0f, 300f, 50f, scale = 2f, areaWidth = 400f, areaHeight = 200f)!!
        assertEquals(50f / 400f, l.clampX(-0.5f), 1e-6f)
        assertEquals(50f / 400f, l.clampX(0.5f), 1e-6f)
    }

    @Test
    fun `sem controle o jogo ocupa a tela inteira`() {
        val default = PadProfile()
        assertFalse(default.fullScreenVideo(portrait = true, padShown = true))
        assertTrue(default.fullScreenVideo(portrait = false, padShown = true))
        assertTrue(default.fullScreenVideo(portrait = true, padShown = false))
        assertTrue(default.copy(visible = false).fullScreenVideo(portrait = true, padShown = true))
        assertTrue(default.copy(portraitMode = PortraitMode.OVERLAY).fullScreenVideo(portrait = true, padShown = true))
    }

    @Test
    fun `perfil sobrevive a ida e volta em json`() {
        val p = PadProfile(visible = false, opacity = 0.4f, scale = 1.2f, portraitMode = PortraitMode.OVERLAY, showHud = false)
            .withElement(true, PadElement.RIGHT_SHOULDERS, PadElementConfig(dx = -0.05f, dy = 0.3f, scale = 0.8f, hidden = true))
        val json = Http.json.encodeToString(PadProfile.serializer(), p)
        assertEquals(p, Http.json.decodeFromString(PadProfile.serializer(), json))
    }

    @Test
    fun `layout lista so as partes que o console tem`() {
        assertEquals(listOf(PadElement.LEFT, PadElement.FACE, PadElement.CENTER), PadLayouts.NES.elements())
        assertEquals(
            listOf(PadElement.LEFT_SHOULDERS, PadElement.RIGHT_SHOULDERS, PadElement.LEFT, PadElement.FACE, PadElement.CENTER),
            PadLayouts.NDS.elements(),
        )
        assertEquals(listOf(PadElement.LEFT, PadElement.FACE), PadLayouts.VECTREX.elements())
    }
}
