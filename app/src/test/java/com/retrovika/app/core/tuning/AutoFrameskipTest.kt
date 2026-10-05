package com.retrovika.app.core.tuning

import com.retrovika.app.core.systems.Systems
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** O frameskip automático desligado na medição de velocidade (ver [Tuning.AUTO_FRAMESKIP]). */
class AutoFrameskipTest {

    private val serializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    private fun declared(coreId: String): Map<String, List<String>>? =
        javaClass.getResourceAsStream("/core-options/$coreId.json")?.bufferedReader()?.use { Json.decodeFromString(serializer, it.readText()) }

    @Test
    fun `cada chave e valor da lista existe no JSON do nucleo`() {
        Tuning.AUTO_FRAMESKIP.forEach { f ->
            val json = declared(f.coreId)
            assertNotNull("${f.coreId}: sem JSON de opções", json)
            val values = json!![f.key]
            assertNotNull("${f.coreId}: opção inexistente ${f.key}", values)
            assertTrue("${f.key}: o desligado '${f.off}' não está em $values", f.off in values!!)
            f.on.forEach { assertTrue("${f.key}: '$it' não está em $values", it in values) }
            assertTrue("${f.key}: o valor ligado não pode ser o desligado", f.off !in f.on)
        }
    }

    @Test
    fun `os nucleos da lista existem no catalogo`() {
        val ids = Systems.all.flatMap { it.cores }.map { it.id }.toSet()
        Tuning.AUTO_FRAMESKIP.forEach { assertTrue(it.coreId, it.coreId in ids) }
    }

    @Test
    fun `todo frameskip automatico que o catalogo liga e desligado na medicao`() {
        // O que o Systems.kt manda (padrões, níveis e opções do aparelho, em qualquer classe) e que pula quadros sozinho.
        val tiers = listOf(
            DeviceProfile("A", "a", "s", 2_000, 8, 1_800, "Mali-G52 MC2"), DeviceProfile("A", "a", "s", 8_000, 8, 3_000, "Adreno (TM) 750"),
        )
        Systems.all.flatMap { it.cores }.forEach { core ->
            val sent = (listOf(core.defaults, core.fixed) + core.presets.values + tiers.mapNotNull { d -> if (core.vulkan) null else core.deviceOptions?.invoke(d) })
                .fold(emptyMap<String, String>()) { acc, m -> acc + m }
            val measured = Tuning.withoutAutoFrameskip(core.id, sent)
            Tuning.AUTO_FRAMESKIP.filter { it.coreId == core.id }.forEach { f ->
                assertTrue("${core.id}: ${f.key}=${measured[f.key]} segue ligado na medição", measured[f.key] !in f.on)
            }
        }
    }

    @Test
    fun `troca so o automatico e deixa o resto`() {
        val options = mapOf("ppsspp_frameskip" to "1", "ppsspp_auto_frameskip" to "enabled", "ppsspp_internal_resolution" to "480x272")
        assertEquals(
            mapOf("ppsspp_frameskip" to "1", "ppsspp_auto_frameskip" to "disabled", "ppsspp_internal_resolution" to "480x272"),
            Tuning.withoutAutoFrameskip("ppsspp", options),
        )
        // Frameskip fixo (intervalo) e a opção de outro núcleo não mudam.
        val fixed = mapOf("mgba_frameskip" to "fixed_interval", "pcsx_rearmed_frameskip_type" to "auto")
        assertEquals("fixed_interval", Tuning.withoutAutoFrameskip("mgba", fixed)["mgba_frameskip"])
        assertEquals("auto", Tuning.withoutAutoFrameskip("mgba", fixed)["pcsx_rearmed_frameskip_type"])
        assertEquals("disabled", Tuning.withoutAutoFrameskip("pcsx_rearmed", fixed)["pcsx_rearmed_frameskip_type"])
        // Sem a chave, nada a fazer (o padrão do núcleo já não pula).
        assertEquals(emptyMap<String, String>(), Tuning.withoutAutoFrameskip("ppsspp", emptyMap()))
    }
}
