package com.retrovika.app.core.cores

import com.retrovika.app.core.tuning.DeviceTier
import com.retrovika.app.core.tuning.Tuning
import kotlinx.serialization.Serializable

/**
 * Velocidade de um núcleo neste aparelho: 1,0 = a velocidade nativa do jogo; nulo = não rodou (tentou e falhou).
 * Um núcleo que o teste nem chegou a tentar (veio depois do primeiro com folga) não tem [CoreSpeed]: fica de fora da lista.
 */
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

    /** Núcleos de [ordered] que o teste não tentou: vieram depois do primeiro com folga, que já decidia a escolha. */
    fun untested(ordered: List<String>): List<String> = CoreBenchmark.untested(ordered, results)
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

    /**
     * O próximo núcleo a testar: o primeiro de [ordered] sem resultado, desde que nenhum antes dele tenha folga.
     * [choose] fica com o primeiro núcleo da ordem que passa de [HEADROOM], então quem vem depois dele não muda
     * a escolha: testá-lo seria baixar e rodar um núcleo à toa. Nulo quando não falta nada que importe.
     * Um núcleo que falhou (velocidade nula) não conta como vencedor, e o teste segue para o seguinte.
     */
    fun next(ordered: List<String>, results: List<CoreSpeed>): String? {
        val speeds = results.associate { it.coreId to it.speed }
        for (id in ordered) {
            if (id !in speeds) return id
            if ((speeds[id] ?: 0f) >= HEADROOM) return null
        }
        return null
    }

    /** Núcleos de [ordered] sem resultado depois do primeiro com folga: não testados, o que é diferente de "não rodou". */
    fun untested(ordered: List<String>, results: List<CoreSpeed>): List<String> {
        val speeds = results.associate { it.coreId to it.speed }
        val winner = ordered.indexOfFirst { (speeds[it] ?: 0f) >= HEADROOM }
        if (winner < 0) return emptyList()
        return ordered.drop(winner + 1).filter { it !in speeds }
    }

    /** Tempo máximo de medição de um núcleo (ou nível), depois do aquecimento. */
    const val MEASURE_MAX_MS = 4_000L

    /**
     * Tempo mínimo de medição antes de aceitar uma parada antecipada: abaixo disso uma rajada (o núcleo
     * compilando, o coletor de lixo) ainda pesa demais na média.
     */
    const val MEASURE_MIN_MS = 1_500L

    /**
     * Acima disto o resultado não tem dúvida: 2× o [Tuning.UP_PROBE], o maior limite que quem chama usa. Medindo só
     * 1,5 s a velocidade pode oscilar até uns 40-50% sem que o núcleo seja outro, e ainda assim ficaria acima dele
     * (e do [HEADROOM]), então passar ou subir de nível não muda com mais tempo.
     */
    const val DECIDED_FAST = 2 * Tuning.UP_PROBE

    /**
     * Abaixo disto o núcleo está longe da folga: metade do [HEADROOM] (0,65×). Para chegar a 1,3× com mais tempo
     * a velocidade teria de dobrar. Fica mais conservador que o lado rápido porque, sem nenhum núcleo com folga,
     * a escolha compara as velocidades lentas entre si.
     */
    const val DECIDED_SLOW = 0.5f * HEADROOM

    /**
     * Se a medição já pode parar: [millis] (só o tempo com o app na frente) passou do mínimo e a [speed] corrente está
     * longe de todos os limites de decisão ([HEADROOM] e [Tuning.UP_PROBE]). Nulo (ainda sem quadros) nunca decide.
     */
    fun isDecided(speed: Float?, millis: Long): Boolean {
        if (speed == null || millis < MEASURE_MIN_MS) return false
        return speed >= DECIDED_FAST || speed <= DECIDED_SLOW
    }

    /**
     * Console leve (8/16 bits e portáteis simples): em aparelho que não é da classe básica todo núcleo dele roda
     * muitas vezes acima do tempo real, então o teste custa segundos sem mudar a escolha.
     */
    fun skipForLightweight(lightweight: Boolean, tier: DeviceTier): Boolean = lightweight && tier >= DeviceTier.MID

    /**
     * A velocidade já medida que vale para a medição pedida, ou nulo: só quando o núcleo rodou com as mesmas
     * opções ([measuredOptions] igual a [wanted]) e deu velocidade. Evita medir duas vezes a mesma coisa.
     */
    fun reusable(measuredOptions: Map<String, String>?, measuredSpeed: Float?, wanted: Map<String, String>): Float? =
        measuredSpeed?.takeIf { measuredOptions != null && measuredOptions == wanted }
}
