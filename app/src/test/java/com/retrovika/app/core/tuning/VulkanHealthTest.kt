package com.retrovika.app.core.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanHealthTest {

    private class MemoryStore : VulkanHealth.Store {
        val ints = mutableMapOf<String, Int>()
        val strings = mutableMapOf<String, String>()
        override fun int(key: String) = ints[key] ?: 0
        override fun string(key: String) = strings[key]
        override fun put(key: String, value: Int, sync: Boolean) { ints[key] = value }
        override fun put(key: String, value: String) { strings[key] = value }
        override fun remove(key: String) { ints.remove(key); strings.remove(key) }
        override fun clear() { ints.clear(); strings.clear() }
    }

    private val device = "Acme|X1|soc|8"
    private val core = "pcsx2"

    @Test
    fun `um aparelho sem historico tenta Vulkan`() {
        assertTrue(VulkanHealth(MemoryStore()).shouldTry(core, device))
    }

    @Test
    fun `o primeiro quadro zera as tentativas`() {
        val health = VulkanHealth(MemoryStore())
        repeat(5) { assertTrue(health.shouldTry(core, device)); health.attemptStarted(core); health.attemptSucceeded(core) }
    }

    @Test
    fun `duas aberturas sem chegar ao primeiro quadro desligam o Vulkan`() {
        val health = VulkanHealth(MemoryStore())
        assertTrue(health.shouldTry(core, device)); health.attemptStarted(core) // travou
        assertTrue(health.shouldTry(core, device)); health.attemptStarted(core) // travou de novo
        assertFalse(health.shouldTry(core, device))
        // Fica desligado nas aberturas seguintes, sem precisar de nova tentativa.
        assertFalse(health.shouldTry(core, device))
    }

    @Test
    fun `sair antes do primeiro quadro nao conta`() {
        val health = VulkanHealth(MemoryStore())
        repeat(6) { health.attemptStarted(core); health.attemptAborted(core) }
        assertTrue(health.shouldTry(core, device))
    }

    @Test
    fun `a falha do contexto desliga na hora, so para aquele nucleo e aparelho`() {
        val health = VulkanHealth(MemoryStore())
        health.attemptFailed(core, device)
        assertFalse(health.shouldTry(core, device))
        assertTrue(health.shouldTry("play", device))
        assertTrue(health.shouldTry(core, "Outro|Y|soc|4"))
    }

    @Test
    fun `reset volta tudo`() {
        val health = VulkanHealth(MemoryStore())
        health.attemptFailed(core, device)
        health.reset()
        assertTrue(health.shouldTry(core, device))
    }

    @Test
    fun `o teste da ponte roda uma vez por versao do sistema`() {
        val health = VulkanHealth(MemoryStore())
        var runs = 0
        repeat(3) { assertTrue(health.bridgeWorks("build-1") { runs++; true }) }
        assertEquals(1, runs)
        assertTrue(health.bridgeWorks("build-2") { runs++; true })
        assertEquals(2, runs)
    }

    @Test
    fun `ponte que falha ou derruba o app nao e testada de novo`() {
        val store = MemoryStore()
        assertFalse(VulkanHealth(store).bridgeWorks("b") { false })
        var runs = 0
        assertFalse(VulkanHealth(store).bridgeWorks("b") { runs++; true })
        assertEquals(0, runs)
        // Processo morto duas vezes no meio do teste: ficou "retry".
        val crashed = MemoryStore().also { it.put("bridge_b", "retry") }
        assertFalse(VulkanHealth(crashed).bridgeWorks("b") { runs++; true })
        assertEquals(0, runs)
    }

    @Test
    fun `uma queda no meio do teste da ponte ganha mais uma chance`() {
        // "running" sozinho pode ser o usuário fechando o app: testa de novo.
        val store = MemoryStore().also { it.put("bridge_b", "running") }
        var runs = 0
        assertTrue(VulkanHealth(store).bridgeWorks("b") { runs++; true })
        assertEquals(1, runs)
        assertTrue(VulkanHealth(store).bridgeWorks("b") { runs++; true })
        assertEquals(1, runs)
    }

    @Test
    fun `excecao no teste da ponte conta como falha e reset testa de novo`() {
        val health = VulkanHealth(MemoryStore())
        assertFalse(health.bridgeWorks("b") { error("driver") })
        health.reset()
        assertTrue(health.bridgeWorks("b") { true })
    }

    @Test
    fun `duas quedas seguidas no meio do jogo desligam o Vulkan`() {
        val health = VulkanHealth(MemoryStore())
        assertFalse(health.sessionCrashed(core, device))
        assertTrue(health.shouldTry(core, device))
        assertTrue(health.sessionCrashed(core, device))
        assertFalse(health.shouldTry(core, device))
        assertTrue(health.isOff(core, device))
    }

    @Test
    fun `quedas raras entre sessoes boas nao desligam`() {
        val health = VulkanHealth(MemoryStore())
        repeat(5) {
            assertFalse(health.sessionCrashed(core, device))
            health.sessionEnded(core)
            health.sessionEnded(core)
        }
        assertTrue(health.shouldTry(core, device))
    }

    @Test
    fun `quedas em mais da metade das sessoes desligam`() {
        val health = VulkanHealth(MemoryStore())
        assertFalse(health.sessionCrashed(core, device))
        health.sessionEnded(core)
        assertFalse(health.sessionCrashed(core, device))
        health.sessionEnded(core)
        assertTrue(health.sessionCrashed(core, device))
    }

    @Test
    fun `uma versao nova do nucleo tenta o Vulkan de novo`() {
        val health = VulkanHealth(MemoryStore())
        health.attemptFailed(core, "$device|v1")
        assertFalse(health.shouldTry(core, "$device|v1"))
        assertTrue(health.shouldTry(core, "$device|v2"))
    }

    @Test
    fun `tentar de novo esquece as falhas so daquele nucleo`() {
        val health = VulkanHealth(MemoryStore())
        health.attemptFailed(core, device)
        health.attemptFailed("ppsspp", device)
        health.forget(core)
        assertTrue(health.shouldTry(core, device))
        assertFalse(health.isOff(core, device))
        assertTrue(health.isOff("ppsspp", device))
    }
}
