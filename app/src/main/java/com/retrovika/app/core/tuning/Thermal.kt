package com.retrovika.app.core.tuning

import android.os.PowerManager

/**
 * O que o estado térmico do aparelho ([PowerManager.getCurrentThermalStatus], Android 10+) quer dizer para o jogo.
 * Os status são os do próprio PowerManager (NONE 0, LIGHT 1, MODERATE 2, SEVERE 3…); as constantes são inteiros em
 * tempo de compilação, então isto também roda em testes de JVM.
 */
object Thermal {
    /** Acima deste status o sistema já reduz a frequência: uma lentidão agora é do calor, não do jogo. */
    const val THROTTLING = PowerManager.THERMAL_STATUS_MODERATE
    /** Acima deste o jogador deve ser avisado: o sistema está limitando o aparelho de verdade. */
    const val WARN = PowerManager.THERMAL_STATUS_SEVERE

    fun throttling(status: Int) = status >= THROTTLING

    fun shouldWarn(status: Int) = status >= WARN

    /**
     * A lentidão que o [SpeedWatch] acabou de confirmar vem do calor? Sim quando o aparelho está limitado agora ou
     * esteve em qualquer momento dos últimos [lookbackMs] ([lastThrottledAt] é quando o status passou por MODERATE ou mais,
     * ou saiu dele; nulo = nunca). O recuo cobre a janela inteira do vigia: o calor pode passar logo depois de a
     * frequência ter caído, e a lentidão dessa janela continua sendo dele.
     */
    fun explainsSlowdown(status: Int, lastThrottledAt: Long?, now: Long, lookbackMs: Long): Boolean =
        throttling(status) || (lastThrottledAt != null && now - lastThrottledAt in 0..lookbackMs)
}
