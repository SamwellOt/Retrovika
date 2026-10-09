package com.retrovika.app.core.cheats

import java.util.BitSet

/**
 * Busca de valores na RAM do jogo, no estilo dos "cheat finders": tira-se uma cópia da memória, o jogo anda, e
 * cada filtro (mudou, aumentou, é igual a 99…) descarta os endereços que não servem, até sobrar o da vida ou
 * do dinheiro. Só endereços alinhados à largura do valor (a regra nos jogos), e o valor é lido sem sinal.
 *
 * A cópia anterior é uma só (a do último filtro), e os candidatos ficam num [BitSet] indexado por posição, então
 * 32 MB de RAM custam uns 4 MB de candidatos. A cópia que o filtro deixa de usar volta por [takeRetired], para quem
 * chama reaproveitá-la na leitura seguinte (sem alocar 32 MB a cada filtro).
 *
 * [wordSwap] é o RDRAM do N64: palavras de 4 bytes na ordem do processador, então o byte lógico N está em N xor 3 e o
 * valor é lido do byte mais significativo para o menos.
 */
class RamSearch(val width: Int, val bigEndian: Boolean = false, val wordSwap: Boolean = false) {

    enum class Filter { UNCHANGED, CHANGED, INCREASED, DECREASED }

    data class Hit(val address: Int, val value: Long)

    private var previous: ByteArray? = null
    private var candidates: BitSet? = null
    private var slots = 0
    private var retired: ByteArray? = null

    init {
        require(width in RamCheat.WIDTHS) { "Largura inválida: $width" }
    }

    val started: Boolean get() = previous != null

    /** Quantos endereços ainda servem (todos, logo depois de [start]). */
    val count: Int get() = candidates?.cardinality() ?: slots

    /** Começa uma busca nova com a RAM de agora: todos os endereços alinhados são candidatos. */
    fun start(ram: ByteArray) {
        previous = ram
        candidates = null
        retired = null
        // Com as palavras invertidas, o último pedaço que não completa uma palavra não tem como ser lido.
        slots = (if (wordSwap) ram.size / 4 * 4 else ram.size) / width
    }

    /** A cópia anterior que o último filtro largou (para a próxima leitura reaproveitar), uma vez só. */
    fun takeRetired(): ByteArray? = retired.also { retired = null }

    /** Fica só com os endereços cujo valor é [value]. Devolve quantos sobraram. */
    fun filterEqual(current: ByteArray, value: Long): Int {
        if (value < 0 || value > RamCheat.maxValue(width)) return keep(current) { _, _ -> false }
        return keep(current) { now, _ -> now == value }
    }

    fun filter(current: ByteArray, kind: Filter): Int = keep(current) { now, before ->
        when (kind) {
            Filter.UNCHANGED -> now == before
            Filter.CHANGED -> now != before
            Filter.INCREASED -> now > before
            Filter.DECREASED -> now < before
        }
    }

    /** Os primeiros [limit] candidatos, com o valor que têm na RAM mais recente. */
    fun hits(limit: Int): List<Hit> {
        val ram = previous ?: return emptyList()
        val set = candidates
        val out = ArrayList<Hit>(minOf(limit, count))
        var slot = set?.nextSetBit(0) ?: 0
        while (slot in 0 until slots && out.size < limit) {
            out += Hit(slot * width, read(ram, slot * width))
            slot = if (set == null) slot + 1 else set.nextSetBit(slot + 1)
        }
        return out
    }

    private inline fun keep(current: ByteArray, test: (now: Long, before: Long) -> Boolean): Int {
        val before = previous ?: throw IllegalStateException("A busca não começou")
        require(current.size == before.size) { "A RAM mudou de tamanho" }
        val old = candidates
        val result = BitSet(slots)
        var slot = old?.nextSetBit(0) ?: 0
        while (slot in 0 until slots) {
            val address = slot * width
            if (test(read(current, address), read(before, address))) result.set(slot)
            slot = if (old == null) slot + 1 else old.nextSetBit(slot + 1)
        }
        candidates = result
        retired = before
        previous = current
        return result.cardinality()
    }

    private fun read(ram: ByteArray, address: Int): Long {
        var value = 0L
        for (i in 0 until width) {
            val logical = address + (if (bigEndian || wordSwap) i else width - 1 - i)
            val b = ram[if (wordSwap) logical xor 3 else logical].toLong() and 0xFF
            value = (value shl 8) or b
        }
        return value
    }

    companion object {
        /** Memória de heap que a busca consome numa RAM de [ramSize] bytes: a cópia anterior, a atual e os candidatos. */
        fun bytesNeeded(ramSize: Int, width: Int): Long = ramSize * 2L + ramSize / width / 8 + 4096
    }
}
