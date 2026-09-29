package com.retrovika.app.core.cores

import kotlinx.serialization.Serializable

/** Velocidade de um núcleo neste aparelho: 1,0 = a velocidade nativa do jogo; nulo = não rodou. */
@Serializable
data class CoreSpeed(val coreId: String, val speed: Float? = null)

/**
 * Resultado do teste automático de um console neste aparelho: a velocidade de cada núcleo e o escolhido.
 * [skipped]: o usuário pulou o teste (vale o padrão, e ele não roda de novo sozinho).
 */
@Serializable
data class SystemBenchmark(
    val results: List<CoreSpeed>,
    val chosen: String,
    val device: String,
    val at: Long,
    val skipped: Boolean = false,
) {
    fun speedOf(coreId: String): Float? = results.firstOrNull { it.coreId == coreId }?.speed
}

object CoreBenchmark {
    /**
     * Folga exigida: o jogo testado costuma estar na abertura, mais leve que o jogo em si, e cenas pesadas
     * pedem mais. Um núcleo a 130% da velocidade no teste tende a não engasgar jogando.
     */
    const val HEADROOM = 1.3f

    /** Quadros por desenho durante a medição: deixa um núcleo rápido mostrar até 8× sem o limite da tela. */
    const val FRAME_SPEED = 8

    /**
     * O núcleo ideal: na ordem do catálogo (o primeiro é o mais fiel), o primeiro que roda com folga; se
     * nenhum tem folga, o mais rápido; se nenhum rodou, o padrão.
     */
    fun choose(ordered: List<String>, results: List<CoreSpeed>): String {
        val speeds = results.associate { it.coreId to it.speed }
        ordered.firstOrNull { (speeds[it] ?: 0f) >= HEADROOM }?.let { return it }
        return ordered.filter { speeds[it] != null }.maxByOrNull { speeds[it]!! } ?: ordered.first()
    }

    /** Velocidade medida: quadros emulados por segundo divididos pela taxa nativa do jogo. */
    fun speed(frames: Long, millis: Long, contentFps: Double): Float? {
        if (millis <= 0 || contentFps <= 0 || frames < 0) return null
        return (frames * 1000.0 / millis / contentFps).toFloat()
    }
}
