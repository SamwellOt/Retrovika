package com.retrovika.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O regex do Android é o ICU, mais rígido que o do Java do PC: "}" ou "{" soltos (fora de um quantificador
 * como {2,3}) passam nos testes da JVM e dão PatternSyntaxException no aparelho. Numa regex de um
 * `companion`/`object` isso derruba o app já na abertura (foi o que aconteceu na 0.5.0, em WikiClient).
 * Este teste lê o código-fonte e exige as chaves literais escapadas.
 */
class RegexSyntaxTest {

    private val raw = Regex("(?:Regex|toRegex|Pattern\\.compile)\\(\\s*\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)
    private val plain = Regex("(?:Regex|toRegex|Pattern\\.compile)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    // \p{L}, \P{M}, \x{41}: chaves que fazem parte do escape
    private val braceEscape = Regex("\\\\[pPxN]\\{[^}]*\\}")
    private val escaped = Regex("\\\\.")
    private val quantifier = Regex("\\{\\d+(,\\d*)?\\}")
    private val charClass = Regex("\\[\\^?\\]?[^\\]]*\\]")

    /** Chaves que sobram depois de tirar escapes (\p{…} inclusive), quantificadores e classes de caracteres. */
    private fun looseBraces(pattern: String): Boolean =
        pattern.replace(braceEscape, "").replace(escaped, "").replace(quantifier, "").replace(charClass, "").any { it == '{' || it == '}' }

    @Test
    fun `chaves literais escapadas nas regex do app`() {
        val root = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        assertTrue("código-fonte não encontrado", root.isDirectory)
        val bad = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            raw.findAll(text).forEach { m -> if (looseBraces(m.groupValues[1])) bad += "${file.name}: ${m.groupValues[1]}" }
            // Em string comum "\\{" vira \{ na regex: desfaz o escape do Kotlin antes de conferir.
            plain.findAll(text).forEach { m ->
                val p = m.groupValues[1].replace("\\\\", "\\")
                // Interpolação do Kotlin ("${x}") não faz parte da regex.
                if ("\${" !in p && looseBraces(p)) bad += "${file.name}: $p"
            }
        }
        assertTrue("Regex com { ou } sem escape (quebra no Android):\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
