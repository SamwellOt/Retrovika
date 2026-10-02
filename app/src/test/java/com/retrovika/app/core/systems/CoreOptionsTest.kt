package com.retrovika.app.core.systems

import com.retrovika.app.core.tuning.DeviceProfile
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaves e valores que o Systems.kt manda aos núcleos contra o que cada binário declara (`core-options/`).
 * Um valor fora da lista volta ao padrão do núcleo sem aviso: o nível de qualidade não faria efeito nenhum.
 */
class CoreOptionsTest {

    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private fun declared(coreId: String): Map<String, List<String>>? =
        javaClass.getResourceAsStream("/core-options/$coreId.json")?.bufferedReader()?.use { Json.decodeFromString(serializer, it.readText()) }

    private fun check(core: CoreInfo, options: Map<String, String>, what: String) {
        val known = declared(core.id) ?: return
        options.forEach { (key, value) ->
            val values = known[key]
            assertTrue("${core.id}: $what usa a opção inexistente $key", values != null)
            assertTrue("${core.id}: $what manda $key=$value, o núcleo aceita ${values?.take(12)}", value in values!!)
        }
    }

    private val cores = Systems.all.flatMap { it.cores }.distinctBy { it.id }

    @Test
    fun `defaults, niveis e fixos usam chaves e valores do nucleo`() {
        cores.forEach { core ->
            check(core, core.defaults, "defaults")
            core.presets.forEach { (level, options) -> check(core, options, "preset $level") }
            check(core, core.fixed, "fixed")
        }
    }

    @Test
    fun `os nucleos de PS2 tem os tres niveis e cada um muda alguma coisa`() {
        listOf("play", "pcsx2").forEach { id ->
            val core = Systems.byId("ps2")!!.core(id)
            assertEquals(Preset.entries.toSet(), core.presets.keys)
            assertTrue("$id: níveis iguais", core.presets.values.toSet().size > 1)
        }
    }

    private fun profile(cores: Int, perf: Int, mhz: Int = 3_000, gpu: String? = "Adreno (TM) 740", ramMb: Int = 8_000, vulkan: Boolean = false) =
        DeviceProfile("Acme", "X", "soc", ramMb, cores, mhz, gpu, perf, vulkan)

    private val lrps2 = Systems.byId("ps2")!!.core("pcsx2")

    @Test
    fun `LRPS2 usa Vulkan quando o aparelho tem e software quando nao`() {
        assertEquals("Vulkan", lrps2.deviceOptions!!(profile(8, 6, vulkan = true))["pcsx2_renderer"])
        val software = lrps2.deviceOptions!!(profile(8, 6, vulkan = false))
        assertEquals("Software (SW)", software["pcsx2_renderer"])
        // Software a mais de 1x é de computador de mesa: o teto vale por cima do nível.
        assertEquals("1x (Native)", software["pcsx2_upscale_multiplier"])
        listOf(true, false).forEach { vulkan -> check(lrps2, lrps2.deviceOptions!!(profile(8, 6, vulkan = vulkan)), "deviceOptions") }
    }

    @Test
    fun `nucleos com Vulkan escolhem outro renderizador quando o aparelho nao tem`() {
        assertEquals(setOf("pcsx2", "ppsspp"), cores.filter { it.vulkan }.map { it.id }.toSet())
        // Sem Vulkan o núcleo nunca pode pedir Vulkan (o PPSSPP aborta se pedir e a ponte não existir).
        cores.filter { it.vulkan }.forEach { core ->
            val without = core.deviceOptions!!(profile(8, 6, vulkan = false))
            assertTrue("${core.id}: pede Vulkan sem ele", without.values.none { it.equals("vulkan", true) })
            check(core, without, "deviceOptions sem Vulkan")
            check(core, core.deviceOptions!!(profile(8, 6, vulkan = true)), "deviceOptions com Vulkan")
        }
        assertEquals("vulkan", Systems.byId("psp")!!.core("ppsspp").deviceOptions!!(profile(8, 6, vulkan = true))["ppsspp_backend"])
        assertEquals("opengl", Systems.byId("psp")!!.core("ppsspp").deviceOptions!!(profile(8, 6, vulkan = false))["ppsspp_backend"])
    }

    @Test
    fun `threads do renderizador por software seguem os nucleos rapidos do aparelho`() {
        fun threads(p: DeviceProfile) = lrps2.deviceOptions!!(p)["pcsx2_sw_renderer_threads"]!!.toInt()
        assertEquals(0, threads(profile(8, 3)))   // EE, VU1 e GS já ocupam os três
        assertEquals(1, threads(profile(8, 4)))   // 1+3+4
        assertEquals(3, threads(profile(8, 6)))   // 1+5+2
        assertEquals(4, threads(profile(12, 10))) // teto
        // Sem as frequências, vale metade dos núcleos.
        assertEquals(1, threads(profile(8, 0)))
        // CPU fraca (classe básica): no máximo uma, mesmo com muitos núcleos iguais.
        assertEquals(1, threads(profile(8, 8, mhz = 1_800, gpu = "Mali-G52 MC2")))
    }
}
