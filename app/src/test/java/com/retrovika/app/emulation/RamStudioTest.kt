package com.retrovika.app.emulation

import com.retrovika.app.core.cheats.RamSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RamStudioTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun studio(size: Int, ram: () -> ByteArray = { ByteArray(size) }, seen: MutableSet<Int> = mutableSetOf()) = RamStudio(scope).also { s ->
        s.sizeProvider = { size }
        s.reader = { buffer ->
            seen += System.identityHashCode(buffer)
            val data = ram()
            data.copyInto(buffer)
            data.size
        }
        s.freeHeap = { Long.MAX_VALUE / 2 }
    }

    private fun RamStudio.settle() = runBlocking {
        val deadline = System.currentTimeMillis() + 5000
        while (busy && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertFalse("A operação não terminou", busy)
    }

    @Test
    fun `sem ram exposta avisa e nao comeca`() {
        val s = studio(0)
        s.refreshSupport()
        assertEquals(RamStudio.Problem.NO_RAM, s.problem)
        s.start(); s.settle()
        assertFalse(s.started)
    }

    @Test
    fun `comeca e filtra`() {
        var ram = ByteArray(64)
        val s = studio(64, { ram })
        s.refreshSupport()
        s.start(); s.settle()
        assertTrue(s.started)
        assertEquals(64, s.count)
        ram = ByteArray(64).also { it[10] = 5 }
        s.filter(RamSearch.Filter.CHANGED); s.settle()
        assertEquals(1, s.count)
        assertEquals(listOf(RamSearch.Hit(10, 5)), s.hits)
    }

    @Test
    fun `as leituras se revezam em dois arrays`() {
        val seen = mutableSetOf<Int>()
        val s = studio(256, seen = seen)
        s.refreshSupport()
        s.start(); s.settle()
        repeat(6) { s.filter(RamSearch.Filter.UNCHANGED); s.settle() }
        assertEquals(2, seen.size)
    }

    @Test
    fun `sem memoria suficiente avisa em vez de alocar`() {
        val s = studio(1 shl 20)
        s.freeHeap = { 1024 }
        s.refreshSupport()
        s.start(); s.settle()
        assertEquals(RamStudio.Problem.TOO_BIG, s.problem)
        assertFalse(s.started)
    }

    @Test
    fun `falta de memoria no meio vira aviso e recomeca`() {
        val s = studio(64)
        s.reader = { throw OutOfMemoryError("teste") }
        s.refreshSupport()
        s.start(); s.settle()
        assertEquals(RamStudio.Problem.TOO_BIG, s.problem)
        assertFalse(s.started)
        assertFalse(s.busy)
    }

    @Test
    fun `leitura que copia menos que a ram e falha de leitura`() {
        val s = studio(64)
        s.reader = { 10 }
        s.refreshSupport()
        s.start(); s.settle()
        assertEquals(RamStudio.Problem.READ_FAILED, s.problem)
    }

    @Test
    fun `n64 comeca com as palavras invertidas ligadas e o jogador pode desligar`() {
        val s = studio(64)
        s.wordSwapSystem = true
        s.refreshSupport()
        assertTrue(s.wordSwap)
        s.changeWordSwap(false)
        s.refreshSupport()
        assertFalse(s.wordSwap)
    }

    @Test
    fun `trocar a largura recomeca`() {
        val s = studio(64)
        s.refreshSupport()
        s.start(); s.settle()
        assertTrue(s.started)
        s.changeWidth(2)
        assertFalse(s.started)
        assertNull(s.problem)
    }

    @Test
    fun `fontes de texto vao e voltam por json`() {
        val a = studio(64)
        a.addTextHook(com.retrovika.app.core.textmem.TextHook("dialogo", 0x40, 128, com.retrovika.app.core.textmem.TextEncoding.ASCII))
        a.addTextHook(com.retrovika.app.core.textmem.TextHook(
            "tela", 0x400, 1000, com.retrovika.app.core.textmem.TextEncoding.TABLE, table = com.retrovika.app.core.textmem.LinearTable(upper = 1, space = 32, asciiLow = true), gridWidth = 40,
        ))
        val json = a.exportHooks()
        val b = studio(64)
        assertEquals(2, b.importHooks(json))
        assertEquals(a.textHooks, b.textHooks)
        assertEquals(0, b.importHooks(json))          // repetidas não entram
    }

    @Test
    fun `json invalido nao entra nem parcialmente`() {
        val s = studio(64)
        assertEquals(-1, s.importHooks("isto nao e json"))
        assertEquals(-1, s.importHooks("[]"))
        // um gancho com tamanho zero é recusado inteiro
        assertEquals(-1, s.importHooks("""[{"name":"ok","address":0,"length":16,"encoding":"ASCII"},{"name":"ruim","address":0,"length":0,"encoding":"ASCII"}]"""))
        assertTrue(s.textHooks.isEmpty())
    }

    @Test
    fun `tela de texto no fim da memoria vira gancho comum em vez de quebrar`() {
        val s = studio(30)       // memória menor que uma linha de 40 colunas
        s.refreshSupport()
        s.changeGridWidth(40)
        val table = com.retrovika.app.core.textmem.LinearTable(upper = 1, space = 32)
        val match = com.retrovika.app.core.textmem.TextSearch.Match(10, com.retrovika.app.core.textmem.TextEncoding.TABLE, table, "READY", 20)
        val hook = s.hookFor(match, "fim")
        assertEquals(0, hook.gridWidth)
        assertEquals(10, hook.address)
    }

    @Test
    fun `tela de texto monta a janela de linhas ao redor do achado`() {
        val s = studio(4096)
        s.refreshSupport()
        s.changeGridWidth(40)
        val table = com.retrovika.app.core.textmem.LinearTable(upper = 1, space = 32)
        val match = com.retrovika.app.core.textmem.TextSearch.Match(0x4A0, com.retrovika.app.core.textmem.TextEncoding.TABLE, table, "READY", 256)
        val hook = s.hookFor(match, "tela")
        assertEquals(40, hook.gridWidth)
        // As linhas se alinham ao achado e a janela cobre até 24 linhas acima e 25 abaixo
        assertEquals(0, (hook.address - 0x4A0) % 40)
        assertTrue(hook.address <= 0x4A0 && hook.address + hook.length >= 0x4A0 + 40)
        assertTrue(hook.length % 40 == 0 && hook.length <= com.retrovika.app.core.textmem.TextHook.MAX_LENGTH)
    }

    @Test
    fun `fontes de outro nucleo ficam guardadas mas nao valem`() {
        val s = studio(64)
        s.coreId = "mgba"
        s.addTextHook(com.retrovika.app.core.textmem.TextHook("a", 0x10, 32, com.retrovika.app.core.textmem.TextEncoding.ASCII, core = "gpsp"))
        s.addTextHook(com.retrovika.app.core.textmem.TextHook("b", 0x80, 32, com.retrovika.app.core.textmem.TextEncoding.ASCII, core = "mgba"))
        s.addTextHook(com.retrovika.app.core.textmem.TextHook("c", 0x90, 32, com.retrovika.app.core.textmem.TextEncoding.ASCII))
        assertEquals(3, s.textHooks.size)
        assertEquals(listOf("b", "c"), s.activeTextHooks.map { it.name })
    }

    @Test
    fun `gancho novo leva o nucleo de agora`() {
        val s = studio(256)
        s.refreshSupport()
        s.coreId = "mgba"
        val match = com.retrovika.app.core.textmem.TextSearch.Match(0x40, com.retrovika.app.core.textmem.TextEncoding.ASCII, null, "oi", 64)
        assertEquals("mgba", s.hookFor(match, "x").core)
    }

    @Test
    fun `importar varias fontes grava uma vez so com todas`() {
        val s = studio(64)
        var notified = 0
        var last = emptyList<com.retrovika.app.core.textmem.TextHook>()
        s.onTextHooksChanged = { notified++; last = it }
        val json = """[{"name":"a","address":16,"length":32,"encoding":"ASCII"},{"name":"b","address":64,"length":32,"encoding":"ASCII"}]"""
        assertEquals(2, s.importHooks(json))
        assertEquals(1, notified)
        assertEquals(2, last.size)
    }

    @Test
    fun `fonte de tela de texto no mesmo endereco de uma de dialogo nao e descartada como repetida`() {
        val s = studio(64)
        val table = com.retrovika.app.core.textmem.LinearTable(upper = 1, space = 32)
        s.addTextHook(com.retrovika.app.core.textmem.TextHook("d", 0x40, 128, com.retrovika.app.core.textmem.TextEncoding.TABLE, table = table))
        s.addTextHook(com.retrovika.app.core.textmem.TextHook("g", 0x40, 400, com.retrovika.app.core.textmem.TextEncoding.TABLE, table = table, gridWidth = 40))
        assertEquals(2, s.textHooks.size)
        // a mesma de novo continua sendo repetida
        s.addTextHook(com.retrovika.app.core.textmem.TextHook("g2", 0x40, 400, com.retrovika.app.core.textmem.TextEncoding.TABLE, table = table, gridWidth = 40))
        assertEquals(2, s.textHooks.size)
    }

    @Test
    fun `gancho de tela de texto funciona mesmo antes de a tela ler o tamanho da memoria`() {
        val s = studio(4096)             // refreshSupport ainda não rodou: ramSize == 0 no estado
        s.changeGridWidth(40)
        val table = com.retrovika.app.core.textmem.LinearTable(upper = 1, space = 32)
        val match = com.retrovika.app.core.textmem.TextSearch.Match(0x4A0, com.retrovika.app.core.textmem.TextEncoding.TABLE, table, "READY", 256)
        assertEquals(40, s.hookFor(match, "tela").gridWidth)
    }
}
