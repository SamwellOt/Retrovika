package com.retrovika.app.core.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionGuardTest {

    private class MemoryStore : SessionGuard.Store {
        var value: String? = null
        override fun read() = value
        override fun write(value: String?) { this.value = value }
    }

    private val session = PlaySession("ps2", "pcsx2", 42, vulkan = true, vulkanKey = "Acme|X1|soc|8|v1", preset = "QUALITY", pid = 100)

    @Test
    fun `sem registro nao ha queda`() {
        assertNull(SessionGuard(MemoryStore()).takeDead(200) { error("não consulta") })
    }

    @Test
    fun `sessao fechada normalmente nao conta`() {
        val guard = SessionGuard(MemoryStore())
        guard.open(session)
        guard.close()
        assertNull(guard.takeDead(200) { null })
    }

    @Test
    fun `processo morto com o jogo na frente volta com o motivo`() {
        val store = MemoryStore()
        SessionGuard(store).open(session)
        var asked = -1
        val dead = SessionGuard(store).takeDead(200) { pid -> asked = pid; 5 }
        assertEquals(100, asked)
        assertEquals(session to ExitKind.CRASH, dead)
        // Consumido: a abertura seguinte não conta de novo.
        assertNull(SessionGuard(store).takeDead(300) { 5 })
    }

    @Test
    fun `registro do proprio processo e descartado`() {
        val store = MemoryStore()
        SessionGuard(store).open(session)
        assertNull(SessionGuard(store).takeDead(100) { 5 })
        assertNull(store.value)
    }

    @Test
    fun `registro ilegivel e descartado`() {
        val store = MemoryStore().also { it.value = "{quebrado" }
        assertNull(SessionGuard(store).takeDead(200) { 5 })
        assertNull(store.value)
    }

    @Test
    fun `motivos do sistema`() {
        assertEquals(ExitKind.CRASH, SessionGuard.classify(null))
        assertEquals(ExitKind.CRASH, SessionGuard.classify(0))
        assertEquals(ExitKind.CRASH, SessionGuard.classify(5)) // nativa
        assertEquals(ExitKind.CRASH, SessionGuard.classify(6)) // ANR
        assertEquals(ExitKind.MEMORY, SessionGuard.classify(3))
        assertEquals(ExitKind.MEMORY, SessionGuard.classify(9))
        assertEquals(ExitKind.OTHER, SessionGuard.classify(4)) // exceção do app
        assertEquals(ExitKind.OTHER, SessionGuard.classify(10)) // o usuário pediu
        assertEquals(ExitKind.OTHER, SessionGuard.classify(16)) // app atualizado
    }
}
