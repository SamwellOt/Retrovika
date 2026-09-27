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
