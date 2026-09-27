package com.retrovika.app.core.net

/**
 * Codificação de URL em Kotlin puro, com as mesmas regras do `android.net.Uri.encode`: mantém as
 * letras, os dígitos e `_-!.~'()*` e troca o resto por `%XX` em UTF-8 (espaço vira `%20`, não `+`).
 * Sem depender do Android, as fontes do catálogo rodam nos testes da JVM contra os sites reais.
 */
object Urls {
    private const val UNRESERVED = "_-!.~'()*"
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun encode(value: String, allow: String = ""): String {
        val out = StringBuilder(value.length + 8)
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xFF).toChar()
            val keep = b >= 0 && (c.isLetterOrDigit() && c.code < 128 || c in UNRESERVED || c in allow)
            if (keep) out.append(c) else out.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    /** Monta `base?chave=valor&…` codificando cada parte; a ordem dos parâmetros é mantida. */
    fun withQuery(base: String, params: List<Pair<String, String>>): String =
        if (params.isEmpty()) base
        else base + "?" + params.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
}
