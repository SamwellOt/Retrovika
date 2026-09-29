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

    /*
     * Os caminhos vão como bytes UTF-8 padrão: o GetStringUTFChars do JNI daria "UTF-8 modificado", que
     * codifica caracteres fora do BMP (emoji, alguns ideogramas) de outro jeito, e o fopen abriria outro nome.
     */

    /**
     * Nomes das entradas na ordem do arquivo (pastas terminam em "/"); null se não abrir. Um 7z sem
     * nomes ("7z a -si") devolve nomes vazios.
     */
    fun list(path: String): Array<String>? = list(path.toByteArray(Charsets.UTF_8))

    /** Extrai as entradas cujo destino (mesmo índice de [list]) não é null. Devolve um dos códigos acima. */
    fun extract(path: String, targets: Array<String?>): Int =
        extract(path.toByteArray(Charsets.UTF_8), Array(targets.size) { targets[it]?.toByteArray(Charsets.UTF_8) })

    @JvmStatic private external fun list(path: ByteArray): Array<String>?

    @JvmStatic private external fun extract(path: ByteArray, targets: Array<ByteArray?>): Int
}
