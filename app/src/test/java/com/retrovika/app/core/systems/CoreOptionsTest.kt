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

    /** Todas as declarações, sem juntar as do mesmo núcleo: o mGBA e o Dolphin têm uma por console, cada uma com o seu mapa. */
    private val everyCore = Systems.all.flatMap { it.cores }

    private val entry = profile(8, 8, mhz = 1_800, gpu = "Mali-G52 MC2")
    private val mid = profile(8, 6, mhz = 2_300)
    private val high = profile(8, 6, mhz = 2_800)
    private val top = profile(8, 6)

    @Test
    fun `defaults, niveis, fixos e opcoes do aparelho usam chaves e valores do nucleo`() {
        everyCore.forEach { core ->
            check(core, core.defaults, "defaults")
            core.presets.forEach { (level, options) -> check(core, options, "preset $level") }
            check(core, core.fixed, "fixed")
            // O Vulkan tem teste próprio (os valores dependem do aparelho ter a ponte).
            if (!core.vulkan) listOf(entry, mid, high, top).forEach { d -> core.deviceOptions?.let { check(core, it(d), "deviceOptions ${d.tier}") } }
        }
    }

    @Test
    fun `os nucleos que tem JSON de opcoes sao os que o catalogo mexe`() {
        // Um JSON de núcleo que o catálogo não usa mais é lixo que engana quem o consulta.
        val ids = cores.map { it.id }.toSet()
        listOf(
            "pcsx2", "play", "mupen64plus_next_gles3", "pcsx_rearmed", "swanstation", "ppsspp", "flycast", "genesis_plus_gx", "picodrive",
            "mgba", "snes9x2010", "gpsp", "handy", "desmume", "dolphin", "citra", "yabasanshiro", "armsx2",
        ).forEach { id ->
            assertTrue("$id: sem JSON de opções", declared(id) != null)
            assertTrue("$id: JSON de um núcleo que o catálogo não tem", id in ids)
        }
    }

    @Test
    fun `niveis de um nucleo mudam o que roda e nenhum fica igual a outro`() {
        everyCore.filter { it.presets.size > 1 }.forEach { core ->
            val effective = core.presets.mapValues { (_, options) -> core.defaults + options }
            assertEquals("${core.id}: níveis iguais", effective.size, effective.values.toSet().size)
        }
    }

    @Test
    fun `os nucleos com escada de resolucao`() {
        listOf("citra" to "citra_resolution_factor", "yabasanshiro" to "yabasanshiro_resolution_mode").forEach { (id, key) ->
            val cores = everyCore.filter { it.id == id }
            assertTrue("$id sem declaração", cores.isNotEmpty())
            cores.forEach { core ->
                assertEquals("$id: três níveis", Preset.entries.toSet(), core.presets.keys)
                assertEquals("$id: a resolução tem de mudar em cada nível", 3, core.presets.values.map { it[key] }.toSet().size)
            }
        }
    }

    @Test
    fun `Dolphin sem ubershaders, equilibrado igual ao padrao do nucleo e Auto ate ele`() {
        val dolphins = everyCore.filter { it.id == "dolphin" }
        assertEquals(2, dolphins.size)
        dolphins.forEach { core ->
            assertEquals(Preset.BALANCED, core.autoMax)
            core.presets.forEach { (level, options) ->
                assertTrue("Dolphin $level usa ubershaders", options["dolphin_shader_compilation_mode"] !in setOf("1", "2"))
            }
            // O que rodava até a 0.6.4, sem níveis: os padrões do núcleo (Options.cpp do libretro/dolphin).
            assertEquals(
                mapOf("dolphin_efb_scale" to "1", "dolphin_shader_compilation_mode" to "0"),
                core.defaults + core.presets.getValue(Preset.BALANCED),
            )
        }
    }

    @Test
    fun `nucleos 3D novos nos niveis tem teto no Auto`() {
        // A abertura do jogo não diz nada da cena 3D: o teste de velocidade subia para resoluções que não aguentam.
        assertEquals(Preset.PERFORMANCE, Systems.byId("3ds")!!.core("citra").autoMax)
        assertEquals(Preset.BALANCED, Systems.byId("saturn")!!.core("yabasanshiro").autoMax)
    }

    @Test
    fun `N64 mantem o buffer de quadro em todos os niveis e nao liga o renderizador em thread`() {
        val n64 = Systems.byId("n64")!!.core("mupen64plus_next_gles3")
        assertEquals("True", n64.defaults["mupen64plus-EnableFBEmulation"])
        n64.presets.forEach { (level, options) ->
            assertTrue("N64 $level desliga o buffer de quadro", options["mupen64plus-EnableFBEmulation"] != "False")
            assertTrue("N64 $level liga o renderizador em thread", options["mupen64plus-ThreadedRenderer"] != "True")
        }
        assertTrue(n64.defaults["mupen64plus-ThreadedRenderer"] != "True")
    }

    @Test
    fun `frameskip automatico so nas classes basica e media e so no valor guiado pelo audio`() {
        val auto = mapOf(
            "genesis" to ("genesis_plus_gx" to "genesis_plus_gx_frameskip"), "segacd" to ("genesis_plus_gx" to "genesis_plus_gx_frameskip"),
            "32x" to ("picodrive" to "picodrive_frameskip"), "gba" to ("mgba" to "mgba_frameskip"), "snes" to ("snes9x2010" to "snes9x_2010_frameskip"),
        )
        auto.forEach { (system, pair) ->
            val (id, key) = pair
            val core = Systems.byId(system)!!.core(id)
            listOf(entry, mid).forEach { assertEquals("$system/$id ${it.tier}", mapOf(key to "auto"), core.deviceOptions!!(it)) }
            listOf(high, top).forEach { assertEquals("$system/$id ${it.tier}", emptyMap<String, String>(), core.deviceOptions!!(it)) }
        }
        assertEquals("auto", Systems.byId("gba")!!.core("gpsp").deviceOptions!!(entry)["gpsp_frameskip"])
        // O "auto" desses núcleos não é um valor qualquer: no mGBA, no gpSP e no PCSX ReARMed há também "auto_threshold" e "fixed_interval".
        assertTrue("auto" in declared("mgba")!!.getValue("mgba_frameskip"))
        // PCSX ReARMed: o frameskip está só no nível leve (o equilibrado e o de qualidade rodam todos os quadros).
        val psx = Systems.byId("psx")!!.core("pcsx_rearmed")
        assertEquals("auto", psx.presets[Preset.PERFORMANCE]!!["pcsx_rearmed_frameskip_type"])
        assertTrue(psx.presets.filterKeys { it != Preset.PERFORMANCE }.values.none { it["pcsx_rearmed_frameskip_type"] == "auto" })
    }

    @Test
    fun `Flycast nao conta com o auto skip que so vale com renderizacao em thread`() {
        val dc = Systems.byId("dreamcast")!!.core("flycast")
        assertEquals("disabled", dc.defaults["reicast_threaded_rendering"])
        assertTrue(dc.presets.values.none { "reicast_auto_skip_frame" in it } && "reicast_auto_skip_frame" !in dc.defaults)
    }

    @Test
    fun `PPSSPP so liga o auto frameskip junto do frameskip maior que zero`() {
        val psp = Systems.byId("psp")!!.core("ppsspp")
        psp.presets.forEach { (level, options) ->
            val merged = psp.defaults + options
            if (merged["ppsspp_auto_frameskip"] == "enabled") assertTrue("PPSSPP $level: auto frameskip com frameskip 0 não faz nada", merged["ppsspp_frameskip"] != "disabled")
        }
    }

    @Test
    fun `os nucleos de PS2 tem os tres niveis e cada um muda alguma coisa`() {
        listOf("play", "pcsx2", "armsx2").forEach { id ->
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
    fun `ARMSX2 usa Vulkan quando o aparelho tem e o OpenGL ES quando nao`() {
        val armsx2 = Systems.byId("ps2")!!.core("armsx2")
        assertEquals("Vulkan", armsx2.deviceOptions!!(profile(8, 6, vulkan = true))["armsx2_renderer"])
        // Ao contrário do LRPS2, sem Vulkan ele continua na GPU.
        assertEquals("OpenGL", armsx2.deviceOptions!!(profile(8, 6, vulkan = false))["armsx2_renderer"])
    }

    @Test
    fun `nucleos com Vulkan escolhem outro renderizador quando o aparelho nao tem`() {
        assertEquals(setOf("pcsx2", "ppsspp", "armsx2"), cores.filter { it.vulkan }.map { it.id }.toSet())
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
