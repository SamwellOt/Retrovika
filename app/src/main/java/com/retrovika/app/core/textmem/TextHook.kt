package com.retrovika.app.core.textmem

import kotlinx.serialization.Serializable

/** Como os bytes de um texto do jogo viram letras. */
@Serializable
enum class TextEncoding { ASCII, UTF16LE, UTF16BE, SHIFT_JIS, TABLE }

/**
 * Tabela de caracteres de jogos que não usam ASCII mas guardam as letras em sequência (A-Z, a-z e 0-9 em blocos
 * seguidos, como na tabela dos Pokémon da 3ª geração): [upper] é o byte do "A", [lower] o do "a", [digit] o do "0",
 * [space] o do espaço e [terminator] o que fecha o texto. Uma tabela pode ter só um dos dois blocos de letras (a tela de
 * texto do C64 só tem maiúsculas): o outro é dobrado para o que existe.
 */
@Serializable
data class LinearTable(
    val upper: Int? = null,
    val lower: Int? = null,
    val digit: Int? = null,
    val space: Int? = null,
    val terminator: Int = 0xFF,
    /** Outros caracteres aprendidos no achado (pontuação: "!", "?", "."), do caractere (texto de 1 letra) ao byte. */
    val extra: Map<String, Int> = emptyMap(),
    /**
     * Os bytes 0x20 a 0x3F são o ASCII (espaço, pontuação e dígitos), como nos códigos de tela do C64, onde só as letras
     * ficam em outro lugar (A=1). Dispensa aprender cada símbolo.
     */
    val asciiLow: Boolean = false,
) {
    init {
        require(upper != null || lower != null) { "A tabela precisa de pelo menos um bloco de letras" }
    }
}

/**
 * Onde o texto do jogo está na memória: [length] bytes a partir de [address] (o endereço de quem vê o jogo; no N64,
 * com [wordSwap], o do RDRAM em palavras invertidas), lidos conforme [encoding]. Um "gancho" por caixa de diálogo,
 * menu ou nome; o Retrovika lê todos quando o jogador pede a tradução.
 */
@Serializable
data class TextHook(
    val name: String,
    val address: Int,
    val length: Int,
    val encoding: TextEncoding,
    val wordSwap: Boolean = false,
    val table: LinearTable? = null,
    /**
     * Telas de texto (C64, MSX, DOS): a janela é uma grade de linhas de [gridWidth] bytes, sem terminador; cada linha
     * que parece texto é traduzida e reescrita no lugar, completada com espaços. 0 = texto comum com terminador.
     */
    val gridWidth: Int = 0,
    /**
     * O núcleo em que o gancho foi criado: os endereços são posições na memória que aquele núcleo expõe, e outro núcleo
     * a monta diferente. Nulo (ganchos de versões antigas ou compartilhados sem núcleo) vale para qualquer um.
     */
    val core: String? = null,
) {
    init {
        require(address >= 0 && length in 1..MAX_LENGTH) { "Gancho fora dos limites" }
        require(encoding != TextEncoding.TABLE || table != null) { "A tabela é obrigatória" }
        require(gridWidth == 0 || gridWidth in 8..MAX_LENGTH) { "Largura de linha inválida" }
    }

    companion object {
        const val MAX_LENGTH = 4096
    }
}
