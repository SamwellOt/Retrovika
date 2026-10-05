package com.retrovika.app.core.tuning

import com.retrovika.app.core.systems.CoreInfo
import com.retrovika.app.core.systems.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningTest {

    private val full = CoreInfo("c", "C", 0, presets = Preset.entries.associateWith { emptyMap<String, String>() })
    private val two = CoreInfo("c", "C", 0, presets = mapOf(Preset.PERFORMANCE to emptyMap(), Preset.QUALITY to emptyMap()))
    private val none = CoreInfo("c", "C", 0)
    private val device = DeviceProfile("Acme", "X1", "soc", 8_000, 8, 3_000, "Adreno (TM) 750") // TOP
    private val weak = device.copy(ramMb = 2_000) // ENTRY

    private fun result(preset: Preset, core: CoreInfo = full, profile: DeviceProfile = device, skipped: Boolean = false, speed: Float? = 2f, slowdown: Boolean = false) =
        TuneResult(core.id, preset.name, speed, profile.signature, 0, skipped, slowdown)

    // region estimate / effective

    @Test
    fun `o chute segue a classe do aparelho`() {
        assertEquals(Preset.PERFORMANCE, Tuning.estimate(DeviceTier.ENTRY, full))
        assertEquals(Preset.BALANCED, Tuning.estimate(DeviceTier.MID, full))
        assertEquals(Preset.BALANCED, Tuning.estimate(DeviceTier.HIGH, full))
        assertEquals(Preset.QUALITY, Tuning.estimate(DeviceTier.TOP, full))
    }

    @Test
    fun `o chute fica num degrau que o nucleo tem`() {
        // Sem BALANCED: a classe do meio cai para o degrau abaixo, nunca para cima.
        assertEquals(Preset.PERFORMANCE, Tuning.estimate(DeviceTier.MID, two))
        assertEquals(Preset.QUALITY, Tuning.estimate(DeviceTier.TOP, two))
        assertNull(Tuning.estimate(DeviceTier.TOP, none))
    }

    @Test
    fun `sem predefinicoes nao ha nivel`() {
        assertNull(Tuning.effective(none, device, null, null, null))
    }

    @Test
    fun `a ordem de precedencia e jogo, usuario, medido, chute`() {
        val game = result(Preset.PERFORMANCE)
        val console = result(Preset.BALANCED)
        assertEquals(EffectivePreset(Preset.PERFORMANCE, TuneSource.GAME, 2f), Tuning.effective(full, device, Preset.QUALITY, game, console))
        assertEquals(EffectivePreset(Preset.QUALITY, TuneSource.USER), Tuning.effective(full, device, Preset.QUALITY, null, console))
        assertEquals(EffectivePreset(Preset.BALANCED, TuneSource.MEASURED, 2f), Tuning.effective(full, device, null, null, console))
        assertEquals(EffectivePreset(Preset.QUALITY, TuneSource.ESTIMATED), Tuning.effective(full, device, null, null, null))
    }

    @Test
    fun `medida pulada ou de outro aparelho ou de outro nucleo nao vale`() {
        assertEquals(TuneSource.ESTIMATED, Tuning.effective(full, device, null, null, result(Preset.BALANCED, skipped = true))!!.source)
        assertEquals(TuneSource.ESTIMATED, Tuning.effective(full, device, null, null, result(Preset.BALANCED, profile = device.copy(model = "Y")))!!.source)
        assertEquals(TuneSource.ESTIMATED, Tuning.effective(full, device, null, null, result(Preset.BALANCED).copy(coreId = "other"))!!.source)
        assertEquals(TuneSource.ESTIMATED, Tuning.effective(full, device, null, result(Preset.BALANCED, profile = device.copy(model = "Y")), null)!!.source)
    }

    @Test
    fun `ajuste por jogo pulado nao vale e nao esconde a escolha do usuario`() {
        val skippedGame = result(Preset.PERFORMANCE, skipped = true)
        assertEquals(EffectivePreset(Preset.QUALITY, TuneSource.USER), Tuning.effective(full, device, Preset.QUALITY, skippedGame, null))
    }

    @Test
    fun `nivel que o nucleo nao tem e escolha do usuario ignorada`() {
        assertEquals(TuneSource.ESTIMATED, Tuning.effective(two, device, Preset.BALANCED, null, null)!!.source)
        assertEquals(TuneSource.ESTIMATED, Tuning.effective(two, device, null, null, result(Preset.BALANCED, core = two))!!.source)
    }

    @Test
    fun `o ajuste gravado pela lentidao carrega a marca`() {
        assertEquals(true, Tuning.effective(full, device, null, result(Preset.BALANCED, speed = null, slowdown = true), null)!!.slowdown)
        assertFalse(Tuning.effective(full, device, null, result(Preset.BALANCED), null)!!.slowdown)
    }

    @Test
    fun `lower desce um degrau`() {
        assertEquals(Preset.BALANCED, Tuning.lower(full, Preset.QUALITY))
        assertEquals(Preset.PERFORMANCE, Tuning.lower(full, Preset.BALANCED))
        assertNull(Tuning.lower(full, Preset.PERFORMANCE))
        assertEquals(Preset.PERFORMANCE, Tuning.lower(two, Preset.QUALITY))
    }

    @Test
    fun `um resultado de outra versao do app com nivel desconhecido e ignorado`() {
        assertNull(result(Preset.BALANCED).copy(preset = "ULTRA").presetOrNull)
        assertEquals(TuneSource.ESTIMATED, Tuning.effective(full, device, null, null, result(Preset.BALANCED).copy(preset = "ULTRA"))!!.source)
    }

    // endregion

    // region search

    private val ladder = Preset.entries.toList()

    private fun search(start: Preset, speeds: Map<Preset, Float?>): Triple<Preset, Float?, List<Preset>> = runBlocking {
        val asked = mutableListOf<Preset>()
        val (p, s) = Tuning.search(ladder, start) { asked += it; speeds[it] }
        Triple(p, s, asked)
    }

    @Test
    fun `com folga larga sobe ate o topo`() {
        val (preset, speed, asked) = search(Preset.BALANCED, mapOf(Preset.BALANCED to 3f, Preset.QUALITY to 1.5f))
        assertEquals(Preset.QUALITY, preset)
        assertEquals(1.5f, speed)
        assertEquals(listOf(Preset.BALANCED, Preset.QUALITY), asked)
    }

    @Test
    fun `o degrau de cima sem folga fica de fora`() {
        val (preset, speed, _) = search(Preset.BALANCED, mapOf(Preset.BALANCED to 2f, Preset.QUALITY to 0.8f))
        assertEquals(Preset.BALANCED to 2f, preset to speed)
    }

    @Test
    fun `folga curta nem tenta o degrau de cima`() {
        val (preset, _, asked) = search(Preset.BALANCED, mapOf(Preset.BALANCED to 1.4f, Preset.QUALITY to 9f))
        assertEquals(Preset.BALANCED, preset)
        assertEquals(listOf(Preset.BALANCED), asked)
    }

    @Test
    fun `sem folga desce ate achar`() {
        val (preset, speed, asked) = search(Preset.QUALITY, mapOf(Preset.QUALITY to 0.4f, Preset.BALANCED to 0.9f, Preset.PERFORMANCE to 1.8f))
        assertEquals(Preset.PERFORMANCE to 1.8f, preset to speed)
        assertEquals(listOf(Preset.QUALITY, Preset.BALANCED, Preset.PERFORMANCE), asked)
    }

    @Test
    fun `desce so ate o primeiro com folga`() {
        val (preset, _, asked) = search(Preset.QUALITY, mapOf(Preset.QUALITY to 0.5f, Preset.BALANCED to 1.4f, Preset.PERFORMANCE to 5f))
        assertEquals(Preset.BALANCED, preset)
        assertEquals(listOf(Preset.QUALITY, Preset.BALANCED), asked)
    }

    @Test
    fun `nenhum com folga fica no mais rapido que rodou`() {
        val (preset, speed, _) = search(Preset.QUALITY, mapOf(Preset.QUALITY to 0.3f, Preset.BALANCED to 0.6f, Preset.PERFORMANCE to 0.9f))
        assertEquals(Preset.PERFORMANCE to 0.9f, preset to speed)
    }

    @Test
    fun `um nivel que nao abriu faz descer`() {
        val (preset, speed, _) = search(Preset.QUALITY, mapOf(Preset.QUALITY to null, Preset.BALANCED to 2f))
        assertEquals(Preset.BALANCED to 2f, preset to speed)
    }

    @Test
    fun `nenhum nivel abriu`() {
        val (preset, speed, asked) = search(Preset.BALANCED, emptyMap())
        assertEquals(Preset.BALANCED, preset)
        assertNull(speed)
        assertEquals(listOf(Preset.BALANCED, Preset.PERFORMANCE), asked)
    }

    @Test
    fun `subir para um nivel que nao abriu para ali`() {
        val (preset, speed, _) = search(Preset.BALANCED, mapOf(Preset.BALANCED to 3f, Preset.QUALITY to null))
        assertEquals(Preset.BALANCED to 3f, preset to speed)
    }

    @Test
    fun `chute no topo mede cada nivel uma vez`() {
        val (_, _, asked) = search(Preset.QUALITY, mapOf(Preset.QUALITY to 4f))
        assertEquals(listOf(Preset.QUALITY), asked)
    }

    // endregion

    // region SpeedWatch

    private fun SpeedWatch.feed(seconds: Int, speed: Double, fps: Double = 60.0): List<Boolean> =
        (1..seconds).map { sample((fps * speed).toLong(), 1_000, fps) }

    @Test
    fun `autoFrameskipOn so vale com valor ligado do proprio nucleo`() {
        assertTrue(Tuning.autoFrameskipOn("mgba", mapOf("mgba_frameskip" to "auto")))
        assertFalse(Tuning.autoFrameskipOn("mgba", mapOf("mgba_frameskip" to "disabled")))
        assertFalse(Tuning.autoFrameskipOn("mgba", emptyMap()))
        assertFalse(Tuning.autoFrameskipOn("snes9x", mapOf("mgba_frameskip" to "auto")))
        assertTrue(Tuning.autoFrameskipOn("ppsspp", mapOf("ppsspp_auto_frameskip" to "enabled")))
    }

    @Test
    fun `lentidao continua avisa uma vez depois da carencia e da janela`() {
        val watch = SpeedWatch()
        val fired = watch.feed(40, 0.5)
        assertEquals(1, fired.count { it })
        // Carência de 15 s + janela de 10 s: o aviso vem no 25º segundo.
        assertEquals(25, fired.indexOf(true) + 1)
    }

    @Test
    fun `velocidade cheia nunca avisa`() {
        assertFalse(SpeedWatch().feed(120, 1.0).any { it })
    }

    @Test
    fun `engasgo curto nao avisa`() {
        val watch = SpeedWatch()
        assertFalse(watch.feed(20, 1.0).any { it })
        assertFalse(watch.feed(4, 0.2).any { it })
        assertFalse(watch.feed(30, 1.0).any { it })
    }

    @Test
    fun `lentidao so no comeco, dentro da carencia, e ignorada`() {
        val watch = SpeedWatch()
        assertFalse(watch.feed(14, 0.1).any { it })
        assertFalse(watch.feed(60, 1.0).any { it })
    }

    @Test
    fun `depois de avisar nao avisa de novo ate recomecar`() {
        val watch = SpeedWatch()
        assertEquals(1, watch.feed(60, 0.5).count { it })
        watch.reset()
        assertEquals(1, watch.feed(60, 0.5).count { it })
    }

    /** [seconds] intervalos de 1 s a 60 fps a 100% de velocidade em que o núcleo entrega [shown] dos quadros rodados. */
    private fun SpeedWatch.feedShown(seconds: Int, shown: Double, watchSkips: Boolean = true): List<Boolean> =
        (1..seconds).map { sample(60, 1_000, 60.0, (60 * shown).toLong(), watchSkips) }

    @Test
    fun `pulo constante de quadros nunca avisa`() {
        // Frameskip fixo de um preset, ou jogo de 30 fps que repete quadros: sempre 50%, a base acompanha.
        assertFalse(SpeedWatch().feedShown(180, 0.5).any { it })
        assertFalse(SpeedWatch().feedShown(180, 0.0 + 1.0 / 3).any { it })
    }

    @Test
    fun `jogo normal com variacao pequena de quadros nunca avisa`() {
        val watch = SpeedWatch()
        val fired = (1..180).map { i -> watch.sample(60, 1_000, 60.0, if (i % 3 == 0) 56 else 60, true) }
        assertFalse(fired.any { it })
    }

    @Test
    fun `frameskip automatico escondendo lentidao avisa mesmo com os quadros rodados a 100 por cento`() {
        val watch = SpeedWatch()
        assertFalse(watch.feedShown(30, 1.0).any { it })
        val fired = watch.feedShown(30, 0.5)
        assertEquals(1, fired.count { it })
        // A janela de 10 s enche a 80% de tempo lento: perto do 10º segundo do pulo.
        assertTrue(fired.indexOf(true) + 1 in 8..12)
    }

    @Test
    fun `sem frameskip automatico ligado o pulo de quadros nao conta`() {
        val watch = SpeedWatch()
        assertFalse(watch.feedShown(30, 1.0, watchSkips = false).any { it })
        assertFalse(watch.feedShown(60, 0.4, watchSkips = false).any { it })
    }

    @Test
    fun `pulo repentino curto nao avisa`() {
        val watch = SpeedWatch()
        assertFalse(watch.feedShown(40, 1.0).any { it })
        assertFalse(watch.feedShown(5, 0.4).any { it })
        assertFalse(watch.feedShown(40, 1.0).any { it })
    }

    @Test
    fun `tela parada com quase tudo repetido nao conta como lenta nem entra na base`() {
        val watch = SpeedWatch()
        assertFalse(watch.feedShown(30, 1.0).any { it })
        // Menu parado por 40 s: o núcleo repete tudo.
        assertFalse(watch.feedShown(40, 0.0).any { it })
        assertEquals(0.0, watch.skipBaseline()!!, 0.001)
    }

    @Test
    fun `base do pulo vem do percentil baixo e some no reset`() {
        val watch = SpeedWatch()
        assertNull(watch.skipBaseline())
        watch.feedShown(5, 0.5)
        assertNull(watch.skipBaseline())
        watch.feedShown(30, 0.5)
        assertEquals(0.5, watch.skipBaseline()!!, 0.001)
        watch.reset()
        assertNull(watch.skipBaseline())
    }

    @Test
    fun `velocidade baixa continua avisando junto do sinal de quadros`() {
        val watch = SpeedWatch()
        // 50% de velocidade e todos os quadros rodados entregues: só o primeiro sinal vale.
        val fired = (1..40).map { watch.sample(30, 1_000, 60.0, 30, true) }
        assertEquals(1, fired.count { it })
    }

    @Test
    fun `amostra invalida e ignorada`() {
        val watch = SpeedWatch()
        assertFalse(watch.sample(10, 0, 60.0))
        assertFalse(watch.sample(-1, 1_000, 60.0))
        assertFalse(watch.sample(10, 1_000, 0.0))
    }

    // endregion
}
