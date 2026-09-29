package com.retrovika.app.core.share

import com.retrovika.app.core.library.Game
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatePackageTest {

    private fun game(id: Long, raw: String, system: String = "snes", datName: String? = null, file: String = "$raw.sfc") =
        Game(id = id, title = raw, rawName = raw, fileName = file, uri = "/x/$file", systemId = system, size = 1, datName = datName)

    private val manifest = StateManifest(
        systemId = "snes", coreId = "snes9x", title = "Chrono Trigger", rawName = "Chrono Trigger (USA)",
        datName = "Chrono Trigger (USA)", fileName = "Chrono Trigger (USA).sfc", createdAt = 1,
    )

    @Test
    fun `empacota e desempacota`() {
        val state = ByteArray(5000) { (it % 251).toByte() }
        val thumb = byteArrayOf(1, 2, 3)
        val back = StatePackage.unpack(StatePackage.pack(manifest, state, thumb))!!
        assertEquals(manifest, back.manifest)
        assertArrayEquals(state, back.state)
        assertArrayEquals(thumb, back.thumbnail)
    }

    @Test
    fun `recusa o que nao e pacote`() {
        assertNull(StatePackage.unpack(byteArrayOf(1, 2, 3, 4)))
        assertNull(StatePackage.unpack(StatePackage.pack(manifest, ByteArray(0), null)))
    }

    @Test
    fun `acha o jogo pelo dat, pelo arquivo ou pelo titulo com a mesma regiao`() {
        val renamed = game(1, "ct", datName = "Chrono Trigger (USA)")
        val japan = game(2, "Chrono Trigger (Japan)")
        val otherSystem = game(3, "Chrono Trigger (USA)", system = "nds")
        assertEquals(1L, StatePackage.match(manifest, listOf(japan, otherSystem, renamed))?.id)

        val plain = game(4, "Chrono Trigger (USA) [!]")
        assertEquals(4L, StatePackage.match(manifest, listOf(japan, plain))?.id)
        // Só outra região: o estado provavelmente não abriria.
        assertNull(StatePackage.match(manifest, listOf(japan, otherSystem)))
    }
}
