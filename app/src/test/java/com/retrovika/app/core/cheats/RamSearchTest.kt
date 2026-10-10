package com.retrovika.app.core.cheats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RamSearchTest {

    private fun ram(size: Int, vararg cells: Pair<Int, Int>) = ByteArray(size).also { r ->
        cells.forEach { (address, value) -> r[address] = value.toByte() }
    }

    @Test
    fun `comeca com todos os enderecos`() {
        val search = RamSearch(width = 1)
        assertFalse(search.started)
        search.start(ram(16))
        assertTrue(search.started)
        assertEquals(16, search.count)
        assertEquals(8, RamSearch(2).also { it.start(ram(16)) }.count)
        assertEquals(4, RamSearch(4).also { it.start(ram(16)) }.count)
    }

    @Test
    fun `valor igual e depois mudou acha a vida`() {
        val search = RamSearch(1)
        search.start(ram(32, 5 to 3, 9 to 3, 20 to 7))
        // O jogador perde uma vida: só o endereço 5 vai de 3 para 2.
        assertEquals(2, search.filterEqual(ram(32, 5 to 3, 9 to 3, 20 to 7), 3))
        assertEquals(1, search.filter(ram(32, 5 to 2, 9 to 3, 20 to 7), RamSearch.Filter.CHANGED))
        assertEquals(listOf(RamSearch.Hit(5, 2)), search.hits(10))
    }

    @Test
    fun `aumentou e diminuiu`() {
        val search = RamSearch(1)
        search.start(ram(8, 0 to 10, 1 to 10, 2 to 10))
        search.filter(ram(8, 0 to 12, 1 to 8, 2 to 10), RamSearch.Filter.INCREASED)
        assertEquals(listOf(RamSearch.Hit(0, 12)), search.hits(10))

        val other = RamSearch(1)
        other.start(ram(8, 0 to 10, 1 to 10, 2 to 10))
        other.filter(ram(8, 0 to 12, 1 to 8, 2 to 10), RamSearch.Filter.DECREASED)
        assertEquals(listOf(RamSearch.Hit(1, 8)), other.hits(10))
    }

    @Test
    fun `sem mudanca fica so o que nao mudou`() {
        val search = RamSearch(1)
        search.start(ram(4, 0 to 1, 1 to 1))
        assertEquals(3, search.filter(ram(4, 0 to 1, 1 to 2), RamSearch.Filter.UNCHANGED))
    }

    @Test
    fun `valor de dois bytes little endian`() {
        // 0x0123 = 291 em little endian: 23 01.
        val bytes = ByteArray(8).also { it[2] = 0x23; it[3] = 0x01 }
        val search = RamSearch(2)
        search.start(bytes)
        assertEquals(1, search.filterEqual(bytes.copyOf(), 0x0123))
        assertEquals(listOf(RamSearch.Hit(2, 0x0123)), search.hits(5))
    }

    @Test
    fun `valor de quatro bytes big endian`() {
        val bytes = ByteArray(8).also { it[4] = 0x00; it[5] = 0x00; it[6] = 0x03; it[7] = 0xE7.toByte() } // 999
        val search = RamSearch(4, bigEndian = true)
        search.start(bytes)
        assertEquals(1, search.filterEqual(bytes.copyOf(), 999))
        assertEquals(RamSearch.Hit(4, 999), search.hits(5).single())
    }

    @Test
    fun `valor de quatro bytes acima de int e lido sem sinal`() {
        val bytes = ByteArray(4) { 0xFF.toByte() }
        val search = RamSearch(4)
        search.start(bytes)
        assertEquals(1, search.filterEqual(bytes.copyOf(), 0xFFFFFFFFL))
        assertEquals(0xFFFFFFFFL, search.hits(1).single().value)
    }

    @Test
    fun `valor que nao cabe na largura nao acha nada`() {
        val search = RamSearch(1)
        search.start(ram(4, 0 to 255))
        assertEquals(0, search.filterEqual(ram(4, 0 to 255), 256))
        assertTrue(search.hits(5).isEmpty())
    }

    @Test
    fun `byte acima de 127 nao vira negativo`() {
        val search = RamSearch(1)
        search.start(ram(2, 0 to 200))
        search.filter(ram(2, 0 to 201), RamSearch.Filter.INCREASED)
        assertEquals(RamSearch.Hit(0, 201), search.hits(1).single())
    }

    @Test
    fun `os filtros seguintes partem da copia mais recente`() {
        val search = RamSearch(1)
        search.start(ram(4, 0 to 1))
        search.filter(ram(4, 0 to 2), RamSearch.Filter.CHANGED)     // 1 -> 2
        // Agora o "antes" é 2, não 1: 2 -> 3 é aumento, e 3 -> 3 deixaria de ser mudança.
        assertEquals(1, search.filter(ram(4, 0 to 3), RamSearch.Filter.INCREASED))
        assertEquals(0, search.filter(ram(4, 0 to 3), RamSearch.Filter.CHANGED))
    }

    @Test
    fun `limite de resultados`() {
        val search = RamSearch(1)
        search.start(ram(100))
        assertEquals(10, search.hits(10).size)
        assertEquals(0, search.hits(10).first().address)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `ram de outro tamanho e recusada`() {
        val search = RamSearch(1)
        search.start(ram(8))
        search.filter(ram(16), RamSearch.Filter.CHANGED)
    }

    @Test(expected = IllegalStateException::class)
    fun `filtrar sem comecar falha`() {
        RamSearch(1).filter(ram(8), RamSearch.Filter.CHANGED)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `largura invalida`() {
        RamSearch(3)
    }

    @Test
    fun `palavras invertidas do n64 leem o byte logico em n xor 3`() {
        // Palavra 0x12345678 guardada em little-endian: 78 56 34 12. Logicamente o byte 0 é 0x12, o 3 é 0x78.
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0)
        val one = RamSearch(1, wordSwap = true).also { it.start(bytes) }
        assertEquals(1, one.filterEqual(bytes.copyOf(), 0x12))
        assertEquals(RamSearch.Hit(0, 0x12), one.hits(5).single())
        val two = RamSearch(2, wordSwap = true).also { it.start(bytes) }
        assertEquals(1, two.filterEqual(bytes.copyOf(), 0x1234))
        assertEquals(RamSearch.Hit(0, 0x1234), two.hits(5).single())
        val four = RamSearch(4, wordSwap = true).also { it.start(bytes) }
        assertEquals(1, four.filterEqual(bytes.copyOf(), 0x12345678))
    }

    @Test
    fun `palavras invertidas ignoram o resto que nao fecha uma palavra`() {
        val search = RamSearch(1, wordSwap = true)
        search.start(ByteArray(10))
        assertEquals(8, search.count)
    }

    @Test
    fun `a copia largada pelo filtro volta uma vez para ser reaproveitada`() {
        val first = ram(8, 0 to 1)
        val search = RamSearch(1)
        search.start(first)
        assertEquals(null, search.takeRetired())
        search.filter(ram(8, 0 to 2), RamSearch.Filter.CHANGED)
        assertTrue(search.takeRetired() === first)
        assertEquals(null, search.takeRetired())
    }

    @Test
    fun `memoria necessaria conta duas copias e os candidatos`() {
        val need = RamSearch.bytesNeeded(32 * 1024 * 1024, 1)
        assertTrue(need > 64L * 1024 * 1024)
        assertTrue(need < 70L * 1024 * 1024)
        // Largura maior, menos candidatos.
        assertTrue(RamSearch.bytesNeeded(1 shl 20, 4) < RamSearch.bytesNeeded(1 shl 20, 1))
    }

    @Test
    fun `mudou em um valor fixo fica so o endereco que perdeu 3`() {
        val search = RamSearch(1)
        search.start(ram(4, 0 to 10, 1 to 10))
        // Endereço 0 foi de 10 para 7 (-3); o endereço 1 foi de 10 para 9 (-1).
        assertEquals(1, search.filterDelta(ram(4, 0 to 7, 1 to 9), -3))
        assertEquals(listOf(RamSearch.Hit(0, 7)), search.hits(5))
    }

    @Test
    fun `mudou em dois bytes little endian`() {
        // 1000 = 0x03E8 em little endian: E8 03; depois 1050 = 0x041A: 1A 04.
        val before = ByteArray(4).also { it[0] = 0xE8.toByte(); it[1] = 0x03 }
        val after = ByteArray(4).also { it[0] = 0x1A; it[1] = 0x04 }
        val search = RamSearch(2)
        search.start(before)
        assertEquals(1, search.filterDelta(after, 50))
        assertEquals(RamSearch.Hit(0, 1050), search.hits(5).single())
    }

    @Test
    fun `mudanca de zero da volta no contador de um byte`() {
        // 0 -> 255 é -1 módulo 256.
        val search = RamSearch(1)
        search.start(ram(2, 0 to 0))
        assertEquals(1, search.filterDelta(ram(2, 0 to 255), -1))
        assertEquals(listOf(RamSearch.Hit(0, 255)), search.hits(5))
    }

    @Test
    fun `mudou em zero e o mesmo que nao mudou`() {
        val search = RamSearch(1)
        search.start(ram(4, 0 to 1, 1 to 1))
        assertEquals(3, search.filterDelta(ram(4, 0 to 1, 1 to 2), 0))
    }
}
