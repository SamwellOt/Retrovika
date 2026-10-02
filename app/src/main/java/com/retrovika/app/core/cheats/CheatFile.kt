package com.retrovika.app.core.cheats

import kotlinx.serialization.Serializable

/** Um código de trapaça: descrição, código no formato do núcleo e se está ligado. */
@Serializable
data class Cheat(val description: String, val code: String, val enabled: Boolean = false, val custom: Boolean = false)

/**
 * Arquivo .cht do RetroArch (o formato da libretro-database):
 *
 * ```
 * cheats = 2
 * cheat0_desc = "Infinite Lives"
 * cheat0_code = "7E0DBE63"
 * cheat0_enable = false
 * ```
 */
object CheatFile {
    private val line = Regex("""^\s*cheat(\d+)_(desc|code|enable)\s*=\s*"?(.*?)"?\s*$""")

    /**
     * [unnamed] dá o nome de uma entrada sem descrição pelo número dela (1, 2…): o texto vem dos recursos
     * do app, no idioma do usuário; o padrão serve aos testes.
     */
    fun parse(text: String, unnamed: (Int) -> String = { "Cheat $it" }): List<Cheat> {
        val desc = mutableMapOf<Int, String>()
        val code = mutableMapOf<Int, String>()
        val enabled = mutableMapOf<Int, Boolean>()
        text.lineSequence().forEach { raw ->
            val m = line.find(raw) ?: return@forEach
            val index = m.groupValues[1].toIntOrNull() ?: return@forEach
            val value = m.groupValues[3]
            when (m.groupValues[2]) {
                "desc" -> desc[index] = value
                "code" -> code[index] = value
                "enable" -> enabled[index] = value.equals("true", ignoreCase = true)
            }
        }
        // Entradas sem código (títulos de seção em alguns arquivos) não servem ao núcleo.
        return code.keys.sorted().mapNotNull { i ->
            val c = code[i]?.trim().orEmpty()
            if (c.isEmpty()) null else Cheat(desc[i]?.takeIf { it.isNotBlank() } ?: unnamed(i + 1), c, enabled[i] ?: false)
        }
    }

    /**
     * Arquivos da pasta que parecem ser do jogo [name], do mais para o menos provável: o nome exato
     * primeiro, depois o mesmo título limpo, depois os que contêm todas as palavras do título.
     */
    fun match(name: String, files: List<String>, cleanTitle: (String) -> String): List<String> {
        val base = files.map { it to it.removeSuffix(".cht") }
        val exact = base.filter { it.second.equals(name, ignoreCase = true) }.map { it.first }
        val title = key(cleanTitle(name))
        val sameTitle = base.filter { key(cleanTitle(it.second)) == title }.map { it.first }
        val words = title.split(' ').filter { it.length > 1 }
        val containing = if (words.isEmpty()) emptyList() else base.filter { (_, n) ->
            val k = key(cleanTitle(n))
            words.all { it in k }
        }.map { it.first }
        return (exact + sameTitle + containing).distinct()
    }

    /** Busca livre na lista de arquivos pelo que o usuário digitou (todas as palavras, sem caixa). */
    fun search(query: String, files: List<String>, limit: Int = 60): List<String> {
        val words = key(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return files.filter { f -> key(f).let { k -> words.all { it in k } } }.take(limit)
    }

    private fun key(s: String) = s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("")
        .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}
