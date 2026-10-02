package com.retrovika.app.core.storage

import java.io.File
import java.io.InputStream

object FileNames {
    private val unsafeChars = Regex("""[\u0000-\u001f:*?"<>|]""")

    /**
     * Nome seguro para gravar dentro de uma pasta. O nome vem de sites e links de terceiros:
     * sem isso, "../../x" ou um nome vazio gravariam fora da pasta (ou sobre a própria pasta).
     */
    fun safe(name: String): String =
        capBytes(
            name.substringAfterLast('/').substringAfterLast('\\')
                .replace(unsafeChars, "_")
                .trim().trimStart('.')
                .ifBlank { "jogo" },
        ).ifBlank { "jogo" }

    /**
     * Limite do nome em bytes UTF-8. O sistema de arquivos aceita 255 por nome, e o nome ainda ganha " (2)",
     * ".part" e afins: um título longo em japonês (3 bytes por caractere) passaria disso e a gravação falharia.
     */
    private const val MAX_NAME_BYTES = 200

    /** Corta [name] em [max] bytes UTF-8 sem partir um caractere, mantendo a extensão (se curta). */
    internal fun capBytes(name: String, max: Int = MAX_NAME_BYTES): String {
        if (utf8Length(name) <= max) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= 16) name.substring(dot) else ""
        val base = if (ext.isEmpty()) name else name.substring(0, dot)
        val budget = (max - utf8Length(ext)).coerceAtLeast(1)
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < base.length) {
            val cp = base.codePointAt(i)
            val len = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (used + len > budget) break
            out.appendCodePoint(cp)
            used += len
            i += Character.charCount(cp)
        }
        return out.toString().trimEnd() + ext
    }

    private fun utf8Length(s: String): Int = s.toByteArray(Charsets.UTF_8).size

    /** Arquivo ou pasta oculta, ou o __MACOSX (e seus "._x") que o Mac põe dentro dos compactados. */
    fun isJunk(name: String): Boolean = name.startsWith(".") || name.equals("__MACOSX", ignoreCase = true)

    /**
     * [a] e [b] têm exatamente o mesmo conteúdo. Serve para distinguir "o mesmo arquivo baixado de novo"
     * (pode substituir) de "outro jogo que só tem o mesmo nome" (não pode ser apagado).
     */
    fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        val bx = ByteArray(64 * 1024)
        val by = ByteArray(64 * 1024)
        a.inputStream().use { x ->
            b.inputStream().use { y ->
                while (true) {
                    val nx = x.fill(bx)
                    val ny = y.fill(by)
                    if (nx != ny) return false
                    if (nx <= 0) return true
                    for (i in 0 until nx) if (bx[i] != by[i]) return false
                }
            }
        }
    }

    /** Lê até encher [buf] (ou chegar ao fim); devolve quantos bytes leu. */
    private fun InputStream.fill(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
