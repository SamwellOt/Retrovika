package com.retrovika.app.core.storage

object FileNames {
    private val unsafeChars = Regex("""[\u0000-\u001f:*?"<>|]""")

    /**
     * Nome seguro para gravar dentro de uma pasta. O nome vem de sites e links de terceiros:
     * sem isso, "../../x" ou um nome vazio gravariam fora da pasta (ou sobre a própria pasta).
     */
    fun safe(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(unsafeChars, "_")
            .trim().trimStart('.')
            .ifBlank { "jogo" }

    /** Arquivo ou pasta oculta, ou o __MACOSX (e seus "._x") que o Mac põe dentro dos compactados. */
    fun isJunk(name: String): Boolean = name.startsWith(".") || name.equals("__MACOSX", ignoreCase = true)
}
