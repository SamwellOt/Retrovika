package com.retrovika.app.emulation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class SaveStatesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun states(): SaveStates {
        val dir = tmp.newFolder("states")
        return SaveStates({ dir }, { tmp.newFolder("saves") }, "Jogo")
    }

    private fun fakeState(size: Int, seed: Int): ByteArray {
        val b = ByteArray(size)
        Random(seed).nextBytes(b, 0, size / 8)
        return b
    }

    @Test
    fun `leitura logo depois de gravar em segundo plano ve o estado novo`() {
        val s = states()
        val old = fakeState(2_000_000, 1)
        s.write(SaveStates.AUTO_SLOT, old, null)
        repeat(20) { i ->
            val fresh = fakeState(2_000_000, 100 + i)
            s.writeAsync(scope, SaveStates.AUTO_SLOT, fresh, null)
            // Sem esperar: é o que o próximo jogo faz ao abrir logo depois de sair.
            assertArrayEquals("rodada $i", fresh, s.read(SaveStates.AUTO_SLOT))
        }
    }

    @Test
    fun `miniatura atrasada so entra se o estado ainda for o mesmo`() {
        val s = states()
        s.write(SaveStates.AUTO_SLOT, fakeState(100_000, 7), null)
        val first = s.generation(SaveStates.AUTO_SLOT)
        // Chegou antes de outro estado: vale.
        assertTrue(s.attachIfCurrent(SaveStates.AUTO_SLOT, first) { it.writeText("a") })
        s.write(SaveStates.AUTO_SLOT, fakeState(100_000, 8), null)
        // Gravar sem miniatura apaga a antiga, e a captura do estado anterior não volta por cima do novo.
        assertFalse(s.attachIfCurrent(SaveStates.AUTO_SLOT, first) { it.writeText("velha") })
        assertTrue(s.slots()[SaveStates.AUTO_SLOT].thumbnail == null)
        assertTrue(s.attachIfCurrent(SaveStates.AUTO_SLOT, s.generation(SaveStates.AUTO_SLOT)) { it.writeText("b") })
        assertEquals("b", s.slots()[SaveStates.AUTO_SLOT].thumbnail?.readText())
    }

    @Test
    fun `gravacoes seguidas do mesmo slot terminam na ordem`() {
        val s = states()
        val last = fakeState(1_000_000, 50)
        for (i in 0 until 5) s.writeAsync(scope, SaveStates.AUTO_SLOT, fakeState(1_000_000, i), null)
        s.writeAsync(scope, SaveStates.AUTO_SLOT, last, null)
        SaveStates.awaitAll()
        assertArrayEquals(last, s.read(SaveStates.AUTO_SLOT))
    }

    @Test
    fun `nao deixa temporario nem estado vazio`() {
        val s = states()
        s.write(SaveStates.AUTO_SLOT, fakeState(500_000, 3), null)
        val before = s.read(SaveStates.AUTO_SLOT)
        // Estado vazio (o núcleo falhou ao serializar) nunca troca um bom, nem pelo caminho assíncrono.
        s.writeAsync(scope, SaveStates.AUTO_SLOT, ByteArray(0), null)
        SaveStates.awaitAll()
        assertArrayEquals(before, s.read(SaveStates.AUTO_SLOT))
        assertFalse(tmp.root.resolve("states").listFiles().orEmpty().any { it.name.endsWith(".tmp") })
        assertTrue(s.slots().first().exists)
    }

    @Test
    fun `tamanho do estado sem descompactar`() {
        val s = states()
        assertEquals(0L, s.size(SaveStates.AUTO_SLOT))
        s.write(SaveStates.AUTO_SLOT, fakeState(1_234_567, 9), null)
        assertEquals(1_234_567L, s.size(SaveStates.AUTO_SLOT))
    }
}
