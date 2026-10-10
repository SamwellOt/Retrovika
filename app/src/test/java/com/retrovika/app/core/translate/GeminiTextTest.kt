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

    private val items = listOf(
        GeminiText.TextItem(0, "HELLO WORLD", 8),
        GeminiText.TextItem(1, "NAME ENTRY", null),
    )

    @Test
    fun `pedido de textos so tem texto e o esquema de items`() {
        val body = Json.parseToJsonElement(GeminiText.textRequest(items, "pt", game, listOf("PRESS START → APERTE START"))).jsonObject
        val parts = body["contents"]!!.jsonArray[0].jsonObject["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertNull(parts[0].jsonObject["inline_data"])
        val prompt = parts[0].jsonObject["text"]!!.jsonPrimitive.content
        assertTrue("HELLO WORLD" in prompt)
        assertTrue("\"max_chars\": 8" in prompt)
        assertTrue("\"max_chars\": none" in prompt)
        assertTrue("PRESS START → APERTE START" in prompt)
        val config = body["generationConfig"]!!.jsonObject
        assertEquals(JsonPrimitive(0.2), config["temperature"])
        assertEquals(JsonPrimitive("application/json"), config["responseMimeType"])
        val schema = config["responseSchema"]!!.jsonObject
        assertTrue("items" in schema["properties"]!!.jsonObject)
        assertEquals(JsonPrimitive("items"), schema["required"]!!.jsonArray[0])
    }

    @Test
    fun `le a resposta dos textos ignorando o raciocinio, ids invalidos e traducoes vazias`() {
        val answer = """{"items":[{"id":0,"translation":"Olá"},{"id":5,"translation":"x"},{"id":1,"translation":"  "}]}"""
        val body = """{"candidates":[{"content":{"parts":[{"text":"pensando","thought":true},{"text":${JsonPrimitive(answer)}}]}}]}"""
        assertEquals(mapOf("HELLO WORLD" to "Olá"), GeminiText.parseTexts(body, items))
    }

    @Test
    fun `le a resposta dos textos dentro de cerca json`() {
        val answer = "```json\n{\"items\":[{\"id\":1,\"translation\":\"Nome\"}]}\n```"
        val body = """{"candidates":[{"content":{"parts":[{"text":${JsonPrimitive(answer)}}]}}]}"""
        assertEquals(mapOf("NAME ENTRY" to "Nome"), GeminiText.parseTexts(body, items))
    }

    @Test
    fun `resposta de textos sem candidatos e vazia`() {
        assertEquals(emptyMap<String, String>(), GeminiText.parseTexts("""{"candidates":[]}""", items))
    }

    @Test
    fun `id do modelo sem o prefixo models e sem espacos`() {
        assertEquals("gemini-2.5-flash", GeminiText.modelId("  models/gemini-2.5-flash "))
        assertEquals("gemini-2.5-flash", GeminiText.modelId("gemini-2.5-flash"))
        assertEquals(GeminiText.DEFAULT_MODEL, GeminiText.modelId("models/"))
    }
}
