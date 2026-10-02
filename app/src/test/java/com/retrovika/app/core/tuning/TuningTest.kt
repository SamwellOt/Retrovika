package com.retrovika.app.core.tuning

import com.retrovika.app.core.systems.CoreInfo
import com.retrovika.app.core.systems.Preset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `amostra invalida e ignorada`() {
        val watch = SpeedWatch()
        assertFalse(watch.sample(10, 0, 60.0))
        assertFalse(watch.sample(-1, 1_000, 60.0))
        assertFalse(watch.sample(10, 1_000, 0.0))
    }

    // endregion
}
