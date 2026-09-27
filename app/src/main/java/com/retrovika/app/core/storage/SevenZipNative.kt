package com.retrovika.app.core.storage

/**
 * Extração de .7z em código nativo (`src/main/cpp/sevenzip.c`, LZMA SDK). O commons-compress aloca o
 * dicionário do LZMA no heap Java, e .7z "ultra" pedem 256 MB ou mais: em aparelhos cujo heap para em
 * 256 MB a extração falhava. Aqui o dicionário é memória nativa e o bloco sólido é decodificado aos
 * pedaços, direto nos arquivos.
 *
 * Só Copy, LZMA e LZMA2 com um codificador por bloco (o caso de quase todo .7z de ROM); o resto
 * devolve [UNSUPPORTED] e quem chama usa o commons-compress.
 */
internal object SevenZipNative {
    const val OK = 0
    const val UNSUPPORTED = 1
    const val MEMORY = 2
    const val DATA = 3
    const val WRITE = 4
    const val OPEN = 5

    /** Falso nos testes de JVM e se a biblioteca não carregar: aí fica tudo com o commons-compress. */
    val available: Boolean = runCatching { System.loadLibrary("retrovika7z") }.isSuccess

    /** Nomes das entradas na ordem do arquivo (pastas terminam em "/"); null se não abrir. */
    @JvmStatic external fun list(path: String): Array<String>?

    /** Extrai as entradas cujo destino (mesmo índice de [list]) não é null. Devolve um dos códigos acima. */
    @JvmStatic external fun extract(path: String, targets: Array<String?>): Int
}
