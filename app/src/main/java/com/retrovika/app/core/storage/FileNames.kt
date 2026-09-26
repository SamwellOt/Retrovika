package com.retrovika.app.core.storage

object FileNames {
    /**
     * Nome seguro para gravar dentro de uma pasta. O nome vem de sites e links de terceiros:
     * sem isso, "../../x" ou um nome vazio gravariam fora da pasta (ou sobre a própria pasta).
     */
    fun safe(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[\u0000-\u001f:*?"<>|]"""), "_")
            .trim().trimStart('.')
            .ifBlank { "jogo" }
}
