package com.retrovika.app.core.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** O pedido ao Gemini e a leitura da resposta, sem Android (testado na JVM). */
object GeminiText {

    /** O jogo e o console: o modelo usa para acertar nomes, termos e o tom. */
    data class GameContext(val title: String, val system: String)

    /** Um trecho traduzido, com a caixa em pixels da imagem enviada. */
    data class AiBlock(val box: Box, val original: String, val translated: String)

    const val DEFAULT_MODEL = "gemini-flash-latest"

    /**
     * O id do modelo como a API espera no caminho: o usuário pode colar "models/gemini-…" (como a própria
     * API lista) ou com espaços. Vazio volta ao padrão.
     */
    fun modelId(raw: String): String = raw.trim().removePrefix("models/").trim().ifEmpty { DEFAULT_MODEL }

    fun languageName(target: String) = if (target == "pt") "Brazilian Portuguese" else "English"

    fun prompt(lines: List<OcrLine>, width: Int, height: Int, target: String, game: GameContext, history: List<String>): String = buildString {
        val language = languageName(target)
        appendLine("This is a screenshot of the video game \"${game.title}\" (${game.system}). Translate the text shown on screen into $language for a player who can't read the original.")
        appendLine()
        appendLine("OCR lines (id, box as [ymin, xmin, ymax, xmax] scaled to 0-1000, text). The OCR may misread characters, split or merge lines, miss text, or read decorations as text; trust the image over it:")
        if (lines.isEmpty()) appendLine("(none)")
        lines.forEachIndexed { i, line ->
            val b = line.box
            val box = listOf(b.top * 1000 / height, b.left * 1000 / width, b.bottom * 1000 / height, b.right * 1000 / width)
            appendLine("$i $box ${line.text}")
        }
        if (history.isNotEmpty()) {
            appendLine()
            appendLine("Text translated earlier in this session (for context and consistent names only; do not repeat it):")
            history.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("Rules:")
        appendLine("- Group lines that form one unit (a dialogue box, a sign, one menu option). Keep separate menu options separate.")
        appendLine("- In \"lines\" give the ids of the OCR lines of that unit. For text the OCR missed, leave \"lines\" empty and give \"box_2d\" as [ymin, xmin, ymax, xmax] scaled to 0-1000.")
        appendLine("- \"original\" is the corrected original text exactly as on screen; \"translation\" is a natural $language game localization: keep it about as short as the original, keep character names consistent, and keep menu labels short.")
        appendLine("- Skip text already in $language, lone numbers, stats, button prompts and stylized logos.")
    }

    fun request(pngBase64: String, width: Int, height: Int, lines: List<OcrLine>, target: String, game: GameContext, history: List<String>): String =
        buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/png")
                                put("data", pngBase64)
                            }
                        }
                        addJsonObject { put("text", prompt(lines, width, height, target, game, history)) }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0.2)
                put("responseMimeType", "application/json")
                put("responseSchema", SCHEMA)
            }
        }.toString()

    private val SCHEMA: JsonObject = Json.parseToJsonElement(
        """
        {"type":"OBJECT","properties":{"blocks":{"type":"ARRAY","items":{"type":"OBJECT","properties":{
          "lines":{"type":"ARRAY","items":{"type":"INTEGER"}},
          "box_2d":{"type":"ARRAY","items":{"type":"INTEGER"}},
          "original":{"type":"STRING"},
          "translation":{"type":"STRING"}},
          "required":["original","translation"]}}},"required":["blocks"]}
        """.trimIndent(),
    ).jsonObject

    /**
     * Lê a resposta: o texto das partes (menos as de raciocínio) é o JSON pedido. A caixa de cada trecho é a
     * união das linhas do OCR citadas; sem elas, a box_2d do modelo. Trecho sem caixa nenhuma é descartado.
     */
    fun parse(body: String, lines: List<OcrLine>, width: Int, height: Int): List<AiBlock> {
        val root = Json.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        val text = parts.mapNotNull { part ->
            val obj = part.jsonObject
            if (obj["thought"]?.jsonPrimitive?.booleanOrNull == true) null else obj["text"]?.jsonPrimitive?.contentOrNull
        }.joinToString("").trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (text.isEmpty()) return emptyList()
        val blocks = Json.parseToJsonElement(text).jsonObject["blocks"]?.jsonArray.orEmpty()
        return blocks.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val translated = obj["translation"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val original = obj["original"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (translated.isEmpty()) return@mapNotNull null
            val ids = (obj["lines"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it in lines.indices }
            val box = if (ids.isNotEmpty()) {
                ids.map { lines[it].box }.reduce { acc, b -> acc.union(b) }
            } else {
                val b = (obj["box_2d"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                if (b.size != 4) return@mapNotNull null
                Box(
                    (b[1].coerceIn(0, 1000) * width / 1000), (b[0].coerceIn(0, 1000) * height / 1000),
                    (b[3].coerceIn(0, 1000) * width / 1000), (b[2].coerceIn(0, 1000) * height / 1000),
                ).takeIf { !it.isEmpty } ?: return@mapNotNull null
            }
            AiBlock(box, original, translated)
        }
    }

    /** A mensagem de erro da API ({"error": {"message": …}}), quando houver. */
    fun errorMessage(body: String): String? = runCatching {
        (Json.parseToJsonElement(body).jsonObject["error"] as? JsonObject)?.get("message")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
