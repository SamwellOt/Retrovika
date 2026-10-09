package com.retrovika.app.core.textmem

/**
 * A memória do jogo vista pelo tradutor de textos: lê um trecho e grava outro. Os endereços são as posições na memória
 * do jogo (a RAM do sistema e, depois dela, as outras regiões que o núcleo descreve).
 */
interface MemoryAccess {
    /** [length] bytes a partir de [offset], ou nulo se o trecho não está todo na memória. */
    fun read(offset: Int, length: Int): ByteArray?

    /**
     * Grava [data] a partir de [offset] depois do próximo quadro; [wordSwap] (N64): o byte lógico N vai para N xor 3.
     * Devolve se a gravação foi aceita (a fila do jogo, cheia, a recusa).
     */
    fun write(offset: Int, data: ByteArray, wordSwap: Boolean): Boolean
}
