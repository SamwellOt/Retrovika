package com.retrovika.app.core.cheats

/**
 * Trapaça de memória: grava [value] (1, 2 ou 4 bytes) no endereço [address] da RAM do jogo depois de cada quadro.
 * Não depende do formato de código de nenhum núcleo; só precisa que o núcleo exponha a RAM do sistema.
 *
 * No arquivo de trapaças do jogo ela é um código de texto: `ram:<endereço>:<valor>:<largura>[:be]`, em hexadecimal
 * (sem `0x`), por exemplo `ram:1f2a:63:2`. `be` marca a ordem dos bytes big-endian; `sw`, as palavras invertidas do N64
 * (o RDRAM fica em palavras de 4 bytes na ordem do processador: o byte lógico N está em N xor 3; implica big-endian).
 * `c-<núcleo>` diz em que núcleo o endereço vale.
 */
data class RamCheat(
    val address: Int,
    val value: Long,
    val width: Int,
    val bigEndian: Boolean = false,
    val wordSwap: Boolean = false,
    /** O núcleo em que o endereço foi achado (a memória é montada diferente em cada um); nulo vale para qualquer. */
    val core: String? = null,
) {

    fun code(): String = buildString {
        append(PREFIX).append(address.toUInt().toString(16)).append(':').append(value.toString(16)).append(':').append(width)
        if (bigEndian) append(":be")
        if (wordSwap) append(":sw")
        if (core != null) append(":c-").append(core)
    }

    /** O cheat vale para o núcleo [current]? */
    fun appliesTo(current: String): Boolean = core == null || core == current

    /** O que o LibretroDroid espera na largura: 0x100 para big-endian e 0x200 para as palavras invertidas. */
    fun nativeWidth(): Int = width or (if (bigEndian) 0x100 else 0) or (if (wordSwap) 0x200 else 0)

    companion object {
        const val PREFIX = "ram:"
        val WIDTHS = intArrayOf(1, 2, 4)

        fun isRam(code: String): Boolean = code.trimStart().startsWith(PREFIX, ignoreCase = true)

        /** Nulo se o código não é de memória ou está malformado (largura estranha, valor que não cabe, endereço negativo). */
        fun parse(code: String): RamCheat? {
            val text = code.trim()
            if (!isRam(text)) return null
            val parts = text.substring(PREFIX.length).split(':')
            if (parts.size !in 3..6) return null
            val address = parts[0].toLongOrNull(16)?.takeIf { it in 0..0xFFFFFFFFL } ?: return null
            val width = parts[2].toIntOrNull()?.takeIf { it in WIDTHS } ?: return null
            val value = parts[1].toLongOrNull(16)?.takeIf { it in 0..maxValue(width) } ?: return null
            var bigEndian = false
            var wordSwap = false
            var core: String? = null
            for (flag in parts.drop(3)) {
                val lower = flag.lowercase()
                when {
                    lower == "be" -> if (bigEndian) return null else bigEndian = true
                    lower == "sw" -> if (wordSwap) return null else wordSwap = true
                    lower.startsWith("c-") && core == null && lower.length > 2 && lower.drop(2).all { it.isLetterOrDigit() || it == '_' } -> core = lower.drop(2)
                    else -> return null
                }
            }
            // Endereços de 32 bits que não cabem em Int (RAM tem no máximo 64 MB) não servem.
            if (address > Int.MAX_VALUE) return null
            return RamCheat(address.toInt(), value, width, bigEndian, wordSwap, core)
        }

        fun maxValue(width: Int): Long = if (width >= 4) 0xFFFFFFFFL else (1L shl (8 * width)) - 1
    }
}
