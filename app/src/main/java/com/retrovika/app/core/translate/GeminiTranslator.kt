package com.retrovika.app.core.translate

import android.util.Base64
import com.retrovika.app.R
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.HttpStatusException
import com.retrovika.app.core.net.LocalizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Tradução com um modelo multimodal (Gemini, com a chave do próprio usuário): ele vê a imagem, corrige o
 * que o OCR leu errado, acha o texto que o OCR perdeu e traduz sabendo de que jogo se trata e o que foi
 * dito antes. É o que dá o resultado mais natural hoje; o OCR local continua dando as caixas precisas.
 */
class GeminiTranslator(private val apiKey: String, private val model: String) {

    suspend fun translate(
        png: ByteArray, width: Int, height: Int, lines: List<OcrLine>,
        target: String, game: GeminiText.GameContext, history: List<String>,
    ): List<GeminiText.AiBlock> = generate(
        { GeminiText.request(Base64.encodeToString(png, Base64.NO_WRAP), width, height, lines, target, game, history) },
    ) { GeminiText.parse(it, lines, width, height) }

    /** Textos lidos da memória do jogo, sem imagem: uma só requisição, com o limite de cada trecho. */
    suspend fun translateTexts(
        items: List<GeminiText.TextItem>, target: String, game: GeminiText.GameContext, history: List<String>,
    ): Map<String, String> = generate({ GeminiText.textRequest(items, target, game, history) }) { GeminiText.parseTexts(it, items) }

    /** A chamada HTTP e o erro da API: [body] monta o pedido e [read] lê a resposta, ambos fora da thread principal. */
    private suspend fun <T> generate(body: () -> String, read: (String) -> T): T = withContext(Dispatchers.IO) {
        val json = body()
        // O nome vem digitado pelo usuário: vai como um trecho do caminho, codificado (uma "/" ou "?" a mais não
        // muda o endereço chamado).
        val url = "https://generativelanguage.googleapis.com/v1beta/models".toHttpUrl().newBuilder()
            .addPathSegment("${GeminiText.modelId(model)}:generateContent")
            .build()
        val request = Request.Builder().url(url)
            .header("x-goog-api-key", apiKey.trim())
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        with(Http) { aiClient.newCall(request).executeCancellable { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                val message = GeminiText.errorMessage(text)
                throw when {
                    res.code == 429 -> LocalizedException(R.string.translate_ai_quota)
                    res.code == 400 && message?.contains("API key", ignoreCase = true) == true -> LocalizedException(R.string.translate_ai_bad_key)
                    res.code == 401 || res.code == 403 -> LocalizedException(R.string.translate_ai_bad_key)
                    res.code == 404 -> LocalizedException(R.string.translate_ai_bad_model, model)
                    message != null -> LocalizedException(R.string.translate_ai_error, message)
                    else -> HttpStatusException(res.code, url.toString())
                }
            }
            read(text)
        } }
    }

    private companion object {
        // Um modelo que pensa antes de responder pode passar do minuto do cliente comum.
        val aiClient = Http.client.newBuilder().readTimeout(90, TimeUnit.SECONDS).callTimeout(120, TimeUnit.SECONDS).build()
    }
}
