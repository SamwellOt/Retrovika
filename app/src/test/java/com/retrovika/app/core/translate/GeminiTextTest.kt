package com.retrovika.app.core.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiTextTest {

    private val lines = listOf(
        OcrLine(Box(10, 100, 200, 120), "おはよう、ゆうしゃ", 0.9f),
        OcrLine(Box(10, 125, 150, 145), "さま。", 0.9f),
    )
    private val game = GeminiText.GameContext("Dragon Quest", "Super Nintendo")

    @Test
    fun `monta o pedido com a imagem, as linhas e o esquema`() {
        val body = Json.parseToJsonElement(GeminiText.request("AAAA", 400, 300, lines, "pt", game, listOf("はい → Sim"))).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals("AAAA", parts[0].jsonObject["inline_data"]!!.jsonObject["data"]!!.jsonPrimitive.content)
        val prompt = parts[1].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("Brazilian Portuguese" in prompt)
        assertTrue("Dragon Quest" in prompt)
        // Caixa em [ymin, xmin, ymax, xmax] de 0 a 1000.
        assertTrue("0 [333, 25, 400, 500] おはよう、ゆうしゃ" in prompt)
        assertTrue("はい → Sim" in prompt)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(JsonPrimitive("application/json"), config["responseMimeType"])
    }

    @Test
    fun `le a resposta e usa a caixa das linhas citadas ou a do modelo`() {
        val answer = """{"blocks":[
            {"lines":[0,1],"original":"おはよう、ゆうしゃさま。","translation":"Bom dia, herói."},
            {"lines":[],"box_2d":[500,500,600,750],"original":"つづく","translation":"Continua"},
            {"lines":[],"original":"?","translation":"sem caixa"},
            {"lines":[7],"box_2d":[0,0,100,100],"original":"x","translation":"id inválido cai na box_2d"}
        ]}"""
        val body = """{"candidates":[{"content":{"parts":[{"text":"pensando","thought":true},{"text":${JsonPrimitive(answer)}}]}}]}"""
        val blocks = GeminiText.parse(body, lines, 400, 300)
        assertEquals(3, blocks.size)
        assertEquals(Box(10, 100, 200, 145), blocks[0].box)
        assertEquals("Bom dia, herói.", blocks[0].translated)
        assertEquals(Box(200, 150, 300, 180), blocks[1].box)
        assertEquals(Box(0, 0, 40, 30), blocks[2].box)
    }

    @Test
    fun `resposta sem texto e mensagens de erro`() {
        assertEquals(emptyList<GeminiText.AiBlock>(), GeminiText.parse("""{"candidates":[]}""", lines, 400, 300))
        assertEquals("API key not valid.", GeminiText.errorMessage("""{"error":{"code":400,"message":"API key not valid."}}"""))
        assertNull(GeminiText.errorMessage("<html>"))
    }

    @Test
    fun `id do modelo sem o prefixo models e sem espacos`() {
        assertEquals("gemini-2.5-flash", GeminiText.modelId("  models/gemini-2.5-flash "))
        assertEquals("gemini-2.5-flash", GeminiText.modelId("gemini-2.5-flash"))
        assertEquals(GeminiText.DEFAULT_MODEL, GeminiText.modelId("models/"))
    }
}
