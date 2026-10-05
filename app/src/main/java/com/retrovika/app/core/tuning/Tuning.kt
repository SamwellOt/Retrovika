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

/**
 * Uma opção de frameskip automático de um núcleo: [on] são os valores que pulam quadros sozinhos (por áudio ou pelo
 * relógio) e [off] o valor que não pula nenhum. As chaves e valores estão em `core-options/<coreId>.json`.
 */
data class AutoFrameskip(val coreId: String, val key: String, val on: Set<String>, val off: String)

object Tuning {
    /**
     * Frameskip automático por núcleo. Na medição de velocidade ele infla o resultado: o núcleo pula quadros para
     * "alcançar" o tempo real e a conta de quadros emulados por segundo sobe sem o jogo ter ficado mais rápido. O que
     * decide pelo buffer de áudio já é anulado no nativo durante o teste (sem som, o frontend o informa como inativo),
     * mas fica aqui também por garantia; o do PPSSPP usa o relógio e só esta lista o segura. Frameskip fixo (o nível
     * de um preset, como `ppsspp_frameskip = 1`) não entra: ele vale no jogo de verdade, então a medição o inclui.
     */
    val AUTO_FRAMESKIP: List<AutoFrameskip> = listOf(
        AutoFrameskip("genesis_plus_gx", "genesis_plus_gx_frameskip", setOf("auto", "manual"), "disabled"),
        AutoFrameskip("picodrive", "picodrive_frameskip", setOf("auto", "manual"), "disabled"),
        AutoFrameskip("snes9x2010", "snes9x_2010_frameskip", setOf("auto", "manual"), "disabled"),
        AutoFrameskip("mgba", "mgba_frameskip", setOf("auto", "auto_threshold"), "disabled"),
        AutoFrameskip("gpsp", "gpsp_frameskip", setOf("auto", "auto_threshold"), "disabled"),
        AutoFrameskip("pcsx_rearmed", "pcsx_rearmed_frameskip_type", setOf("auto", "auto_threshold"), "disabled"),
        AutoFrameskip("ppsspp", "ppsspp_auto_frameskip", setOf("enabled"), "disabled"),
    )

    /** [options] de [coreId] para medir: o frameskip automático (do preset, do aparelho ou do usuário) desligado. */
    fun withoutAutoFrameskip(coreId: String, options: Map<String, String>): Map<String, String> {
        val off = AUTO_FRAMESKIP.filter { it.coreId == coreId && options[it.key] in it.on }
        return if (off.isEmpty()) options else options + off.associate { it.key to it.off }
    }

    /** Algum frameskip automático de [coreId] está ligado nestas [options] (já com o preset, o aparelho e o usuário). */
    fun autoFrameskipOn(coreId: String, options: Map<String, String>): Boolean =
        AUTO_FRAMESKIP.any { it.coreId == coreId && options[it.key] in it.on }

    /** Pouca folga não deixa tentar o degrau acima: ele custa mais que o atual. */
    const val UP_PROBE = 1.6f

    /** Degraus de qualidade que o núcleo tem, do mais leve ao mais pesado. */
    fun ladder(core: CoreInfo): List<Preset> = Preset.entries.filter { it in core.presets }

    /** Degraus que o modo Auto pode escolher: a escada inteira, ou até [CoreInfo.autoMax]. */
    private fun autoLadder(core: CoreInfo): List<Preset> = ladder(core).filter { core.autoMax == null || it <= core.autoMax }

    /** O teste de velocidade escolhe o nível deste núcleo (fora dele só o chute, ver [CoreInfo.autoMax]). */
    fun measurable(core: CoreInfo): Boolean = core.autoMax == null && core.presets.size >= 2

    /** [preset] guardado pelo Auto (teste, vigia, queda) sem passar de [CoreInfo.autoMax]: resultados de antes do teto. */
    private fun capped(core: CoreInfo, preset: Preset): Preset = core.autoMax?.let { minOf(preset, it) } ?: preset

    /** Chute pela classe do aparelho: nulo se o núcleo não tem predefinições. */
    fun estimate(tier: DeviceTier, core: CoreInfo): Preset? {
        val ladder = autoLadder(core).ifEmpty { return null }
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
        game?.takeIf { !it.skipped && it.appliesTo(profile, core) }?.let {
            return EffectivePreset(capped(core, it.presetOrNull!!), TuneSource.GAME, it.speed, it.slowdown, it.crashed)
        }
        userChoice?.takeIf { it in core.presets }?.let { return EffectivePreset(it, TuneSource.USER) }
        // Núcleo com teto não é medido: um resultado guardado é de antes do teto (a 0.6.5 media o Dolphin pela abertura do
        // jogo e subia para 3x) e o chute fica no lugar dele.
        console?.takeIf { !it.skipped && it.appliesTo(profile, core) && measurable(core) }?.let {
            return EffectivePreset(it.presetOrNull!!, TuneSource.MEASURED, it.speed)
        }
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
 *
 * Dois sinais de lentidão. O primeiro é a velocidade: quadros rodados (`retro_run`) por segundo contra os do
 * jogo. O segundo existe por causa do frameskip automático: o núcleo pula o desenho para "alcançar" o tempo real,
 * então os `retro_run` seguem em 100% enquanto o jogador vê só uma parte dos quadros. Ele compara os quadros
 * entregues com os rodados e só vale com o frameskip automático ligado (`watchSkips`). Pular quadros não é lento
 * por si: frameskip fixo de um preset, jogo de 30 fps que repete quadros e menu parado pulam sempre ou em trechos.
 * Por isso o que conta é o pulo bem acima do habitual da sessão ([SKIP_MARGIN] acima da linha de base), e a linha de
 * base é um percentil baixo dos pulos já vistos ([BASELINE_PERCENTILE]): o jogo que pula 50% o tempo todo tem base
 * de 50% e nunca avisa; o que pulava 0% e passa a pular 50% avisa. Limite assumido: um jogo que roda a maior parte
 * da sessão sem pular e depois passa mais de 10 s numa cena de 30 fps repetida parece lento (só com frameskip
 * automático ligado, e o efeito é baixar um nível de qualidade deste jogo com aviso).
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
    private val skips = ArrayDeque<Double>()
    private var fired = false

    /** Recomeça: jogo novo, estado carregado ou núcleo trocado. */
    fun reset() { activeMs = 0; window.clear(); skips.clear(); fired = false }

    /** A linha de base do pulo de quadros (0 a 1) ou nulo enquanto há poucas amostras; aberto para os testes. */
    fun skipBaseline(): Double? {
        if (skips.size < MIN_BASELINE_SAMPLES) return null
        val sorted = skips.sorted()
        return sorted[((sorted.size - 1) * BASELINE_PERCENTILE).toInt()]
    }

    /**
     * Verdadeiro uma vez, quando a lentidão se confirma. [frames] são os `retro_run` do intervalo e [delivered] os
     * quadros novos que o núcleo entregou nele; [watchSkips] liga o segundo sinal (frameskip automático ativo).
     */
    fun sample(frames: Long, millis: Long, contentFps: Double, delivered: Long = frames, watchSkips: Boolean = false): Boolean {
        val speed = CoreBenchmark.speed(frames, millis, contentFps) ?: return false
        activeMs += millis
        var slow = speed < threshold
        if (frames >= MIN_FRAMES) {
            val skip = 1.0 - delivered.coerceIn(0, frames).toDouble() / frames
            // Quase tudo repetido: tela parada ou carregando, o que não diz nada do jogo. Fora da conta e da base.
            if (skip <= MAX_SKIP) {
                if (watchSkips) skipBaseline()?.let { if (skip > it + SKIP_MARGIN) slow = true }
                skips.addLast(skip)
                if (skips.size > BASELINE_SAMPLES) skips.removeFirst()
            }
        }
        if (activeMs <= graceMs || fired) return false
        window.addLast(Sample(millis, slow))
        var span = window.sumOf { it.millis }
        while (window.size > 1 && span - window.first().millis >= windowMs) span -= window.removeFirst().millis
        if (span < windowMs) return false
        val slowTime = window.filter { it.slow }.sumOf { it.millis }
        if (slowTime < span * slowShare) return false
        fired = true
        return true
    }

    companion object {
        /**
         * Quanto o pulo precisa passar da base. Um intervalo de 1 s a 60 fps erra uns 3 quadros (5%) só pela medida
         * (o atraso do relógio e o quadro na fronteira), e a base é um percentil, não a média, então trechos
         * normais de pulo maior também aparecem. 20 pontos são ~4 vezes o ruído e da mesma ordem dos 15% do limite de
         * velocidade, um pouco mais exigente porque a repetição de quadros varia de cena para cena.
         */
        const val SKIP_MARGIN = 0.20
        /** Percentil da base: 0,2 = o pulo que só 20% das amostras ficam abaixo. */
        const val BASELINE_PERCENTILE = 0.2
        /** Amostras (~1 s cada) lembradas para a base: cerca de 5 minutos. */
        const val BASELINE_SAMPLES = 300
        /** Sem base não há comparação: a carência de 15 s já junta essas amostras. */
        const val MIN_BASELINE_SAMPLES = 10
        /** Menos quadros que isso no intervalo e a fração de pulos é só ruído. */
        const val MIN_FRAMES = 20L
        /** Pulo acima disto é tela parada, não frameskip. */
        const val MAX_SKIP = 0.9
    }
}
