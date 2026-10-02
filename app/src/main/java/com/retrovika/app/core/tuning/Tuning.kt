package com.retrovika.app.core.tuning

import com.retrovika.app.core.cores.CoreBenchmark
import com.retrovika.app.core.systems.CoreInfo
import com.retrovika.app.core.systems.Preset
import kotlinx.serialization.Serializable

/**
 * Qualidade que um núcleo roda neste aparelho, medida ou estimada. Vale para um console com [coreId]
 * ou, guardada por jogo, para um jogo só (uma cena pesada de um jogo não pesa nos outros).
 */
@Serializable
data class TuneResult(
    val coreId: String,
    val preset: String,
    /** Velocidade medida (1,0 = a do jogo); nulo quando o teste não rodou. */
    val speed: Float? = null,
    /** [DeviceProfile.signature] de quando foi medido. */
    val device: String,
    val at: Long,
    /** O usuário pulou o teste: vale a estimativa pela classe do aparelho, e ele não roda sozinho de novo. */
    val skipped: Boolean = false,
    /** Gravado pelo vigia de velocidade durante o jogo, não pelo teste. */
    val slowdown: Boolean = false,
    /** Gravado porque o jogo derrubou o app neste nível (ver SessionGuard). */
    val crashed: Boolean = false,
) {
    val presetOrNull: Preset? get() = runCatching { Preset.valueOf(preset) }.getOrNull()

    /** Só vale para o mesmo aparelho e o mesmo núcleo. */
    fun appliesTo(profile: DeviceProfile, core: CoreInfo): Boolean = coreId == core.id && device == profile.signature && presetOrNull in core.presets
}

enum class TuneSource { USER, GAME, MEASURED, ESTIMATED }

/** O que roda de fato e por quê (a tela mostra a origem). */
data class EffectivePreset(val preset: Preset, val source: TuneSource, val speed: Float? = null, val slowdown: Boolean = false, val crashed: Boolean = false)

object Tuning {
    /** Pouca folga não deixa tentar o degrau acima: ele custa mais que o atual. */
    const val UP_PROBE = 1.6f

    /** Degraus de qualidade que o núcleo tem, do mais leve ao mais pesado. */
    fun ladder(core: CoreInfo): List<Preset> = Preset.entries.filter { it in core.presets }

    /** Chute pela classe do aparelho: nulo se o núcleo não tem predefinições. */
    fun estimate(tier: DeviceTier, core: CoreInfo): Preset? {
        val ladder = ladder(core).ifEmpty { return null }
        val want = when (tier) {
            DeviceTier.ENTRY -> Preset.PERFORMANCE
            DeviceTier.MID, DeviceTier.HIGH -> Preset.BALANCED
            DeviceTier.TOP -> Preset.QUALITY
        }
        // O degrau pedido pode não existir (núcleo só com dois): o mais próximo, sem passar para cima.
        return ladder.lastOrNull { it <= want } ?: ladder.first()
    }

    /**
     * A predefinição que vale para [core]: o ajuste do jogo, depois a escolha do usuário para o console,
     * depois o medido no console e por último o chute pela classe do aparelho. Nulo se o núcleo não tem
     * predefinições.
     */
    fun effective(
        core: CoreInfo, profile: DeviceProfile, userChoice: Preset?, game: TuneResult?, console: TuneResult?,
    ): EffectivePreset? {
        if (core.presets.isEmpty()) return null
        game?.takeIf { !it.skipped && it.appliesTo(profile, core) }?.let { return EffectivePreset(it.presetOrNull!!, TuneSource.GAME, it.speed, it.slowdown, it.crashed) }
        userChoice?.takeIf { it in core.presets }?.let { return EffectivePreset(it, TuneSource.USER) }
        console?.takeIf { !it.skipped && it.appliesTo(profile, core) }?.let { return EffectivePreset(it.presetOrNull!!, TuneSource.MEASURED, it.speed) }
        return EffectivePreset(estimate(profile.tier, core)!!, TuneSource.ESTIMATED)
    }

    /** Um degrau abaixo de [from], ou nulo se já é o mais leve. */
    fun lower(core: CoreInfo, from: Preset): Preset? = ladder(core).lastOrNull { it < from }

    /**
     * Procura a maior qualidade que roda com folga ([CoreBenchmark.HEADROOM]), medindo a partir de [start]:
     * sem folga, desce até achar; com folga sobrando ([UP_PROBE]), sobe enquanto o degrau de cima também
     * passa. Cada degrau é medido uma vez. [measure] devolve nulo quando o núcleo não rodou naquele degrau.
     * Volta o degrau escolhido e a velocidade medida nele (nulo se nenhum rodou).
     */
    suspend fun search(ladder: List<Preset>, start: Preset, measure: suspend (Preset) -> Float?): Pair<Preset, Float?> {
        require(ladder.isNotEmpty())
        var i = ladder.indexOf(start).coerceAtLeast(0)
        var speed = measure(ladder[i])
        if ((speed ?: 0f) < CoreBenchmark.HEADROOM) {
            // Desce até um degrau com folga; se nenhum tem, fica no mais rápido que rodou.
            var best: Pair<Int, Float>? = speed?.let { i to it }
            var j = i
            while (j > 0) {
                j--
                val s = measure(ladder[j])
                if (s == null) continue
                if (best == null || s > best.second) best = j to s
                if (s >= CoreBenchmark.HEADROOM) { best = j to s; break }
            }
            return best?.let { ladder[it.first] to it.second } ?: (ladder[i] to null)
        }
        while (i + 1 < ladder.size && (speed ?: 0f) >= UP_PROBE) {
            val up = measure(ladder[i + 1])
            if (up == null || up < CoreBenchmark.HEADROOM) break
            i++
            speed = up
        }
        return ladder[i] to speed
    }
}

/**
 * Vigia a velocidade durante o jogo: avisa uma vez quando, numa janela de [windowMs], a maior parte do
 * tempo ([slowShare]) passou abaixo de [threshold], depois de [graceMs] de jogo (o começo carrega shaders
 * e engasga em qualquer aparelho). Conta o tempo lento, não a média: uma tela de carregamento ou uma
 * troca de cena que trava por alguns segundos não é um jogo lento. Quem chama alimenta só intervalos em
 * que o jogo rodou de verdade (sem menu, avanço rápido ou segundo plano).
 */
class SpeedWatch(
    private val threshold: Float = 0.85f,
    private val windowMs: Long = 10_000,
    private val graceMs: Long = 15_000,
    private val slowShare: Float = 0.8f,
) {
    private class Sample(val millis: Long, val slow: Boolean)

    private var activeMs = 0L
    private val window = ArrayDeque<Sample>()
    private var fired = false

    /** Recomeça: jogo novo, estado carregado ou núcleo trocado. */
    fun reset() { activeMs = 0; window.clear(); fired = false }

    /** Verdadeiro uma vez, quando a lentidão se confirma. */
    fun sample(frames: Long, millis: Long, contentFps: Double): Boolean {
        val speed = CoreBenchmark.speed(frames, millis, contentFps) ?: return false
        activeMs += millis
        if (activeMs <= graceMs || fired) return false
        window.addLast(Sample(millis, speed < threshold))
        var span = window.sumOf { it.millis }
        while (window.size > 1 && span - window.first().millis >= windowMs) span -= window.removeFirst().millis
        if (span < windowMs) return false
        val slow = window.filter { it.slow }.sumOf { it.millis }
        if (slow < span * slowShare) return false
        fired = true
        return true
    }
}
