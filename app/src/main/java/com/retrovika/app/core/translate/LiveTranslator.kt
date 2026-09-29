package com.retrovika.app.core.translate

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.retrovika.app.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Um trecho de texto achado na tela, onde ele está (em pixels da captura) e a tradução. */
data class TranslatedBlock(val box: Rect, val original: String, val translated: String)

/**
 * Tradução da tela do jogo: o ML Kit lê o texto da captura (o reconhecedor japonês também lê o alfabeto
 * latino) e traduz no próprio aparelho, com modelos baixados uma vez. Se o modelo de tradução não puder
 * ser baixado (aparelho sem os serviços do Google), o trecho vai para o tradutor web do Google.
 */
class LiveTranslator : Closeable {

    enum class Stage { READING, DOWNLOADING_MODEL, TRANSLATING }

    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val translators = mutableMapOf<String, Translator>()
    /** O tradutor no aparelho falhou: os próximos trechos vão direto para o web. */
    private var onDeviceBroken = false

    suspend fun translate(frame: Bitmap, target: String, onStage: (Stage) -> Unit): List<TranslatedBlock> {
        onStage(Stage.READING)
        val text = recognizer.process(InputImage.fromBitmap(frame, 0)).await()
        val blocks = text.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val joined = TranslationText.joinLines(block.lines.map { it.text })
            val source = TranslationText.sourceFor(joined, target) ?: return@mapNotNull null
            Triple(box, joined, source)
        }
        if (blocks.isEmpty()) return emptyList()
        onStage(Stage.TRANSLATING)
        return coroutineScope {
            blocks.map { (box, original, source) ->
                async { TranslatedBlock(box, original, translateText(original, source, target, onStage)) }
            }.awaitAll()
        }
    }

    private suspend fun translateText(text: String, source: String, target: String, onStage: (Stage) -> Unit): String {
        if (!onDeviceBroken) {
            try {
                val translator = translatorFor(source, target)
                // Primeira vez: baixa o modelo (~30 MB). Sem os serviços do Google, isso falha e o web assume.
                if (!downloaded.contains("$source>$target")) {
                    onStage(Stage.DOWNLOADING_MODEL)
                    withTimeout(MODEL_TIMEOUT_MS) { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                    downloaded += "$source>$target"
                    onStage(Stage.TRANSLATING)
                }
                return translator.translate(text).await()
            } catch (c: kotlinx.coroutines.CancellationException) {
                if (c is kotlinx.coroutines.TimeoutCancellationException) onDeviceBroken = true else throw c
            } catch (t: Throwable) {
                onDeviceBroken = true
            }
        }
        return translateOnWeb(text, source, target)
    }

    private val downloaded = mutableSetOf<String>()

    @Synchronized
    private fun translatorFor(source: String, target: String): Translator = translators.getOrPut("$source>$target") {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(if (source == "ja") TranslateLanguage.JAPANESE else TranslateLanguage.ENGLISH)
                .setTargetLanguage(if (target == "pt") TranslateLanguage.PORTUGUESE else TranslateLanguage.ENGLISH)
                .build(),
        )
    }

    private suspend fun translateOnWeb(text: String, source: String, target: String): String = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(text, "UTF-8")
        val body = Http.getString("https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q=$q")
        TranslationText.parseWebResponse(body) ?: text
    }

    override fun close() {
        runCatching { recognizer.close() }
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }

    companion object {
        private const val MODEL_TIMEOUT_MS = 90_000L
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
