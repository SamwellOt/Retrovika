package com.retrovika.app.core.systems

/**
 * A ordem em que as camadas de opções de núcleo se sobrepõem na abertura do jogo, da mais fraca à mais forte:
 * padrões do núcleo, predefinição de qualidade, opções que dependem do aparelho, escolhas do usuário para o núcleo,
 * escolhas só deste jogo e, por último, as fixas do app (que o usuário não muda).
 */
object CoreOptionLayers {

    fun merge(
        defaults: Map<String, String>,
        preset: Map<String, String>,
        device: Map<String, String>,
        user: Map<String, String>,
        game: Map<String, String>,
        fixed: Map<String, String>,
    ): Map<String, String> = defaults + preset + device + user + game + fixed

    /**
     * O que cada opção de [gameOverrides] vale sem a camada do jogo (o valor a devolver ao núcleo quando o usuário
     * limpa as escolhas do jogo); nulo quando nenhuma outra camada fala dela e vale o padrão do próprio núcleo.
     */
    fun withoutGame(gameOverrides: Map<String, String>, otherLayers: Map<String, String>): Map<String, String?> =
        gameOverrides.keys.associateWith { otherLayers[it] }
}
