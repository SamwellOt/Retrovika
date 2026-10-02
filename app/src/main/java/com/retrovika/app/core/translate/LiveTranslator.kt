package com.retrovika.app.core.translate

import android.graphics.Bitmap
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * O quadro que o OCR lê e onde ele aparece na view do jogo ([area], em pixels da view). O melhor é o quadro
 * do núcleo na resolução nativa, sem shader nem filtro; sem ele (núcleos de GPU), a captura da própria view.
 */
class OcrFrame(val image: Bitmap, val area: Box)

/** Tradução com IA: a chave do Gemini do usuário e o modelo. */
data class AiConfig(val apiKey: String, val model: String)

/**
 * Tradução da tela do jogo, em três partes:
 * - leitura: o quadro é ampliado pelo vizinho mais próximo (letras de pixel ficam nítidas e grandes) e lido
 *   pelo MeikiOCR, treinado em jogos japoneses, quando o [OcrPack] está instalado; o ML Kit lê o texto latino
 *   e faz o japonês sem o pacote;
 * - com uma chave do Gemini, o modelo vê a imagem e as linhas lidas e devolve os trechos já traduzidos;
 * - sem ela (ou se ela falhar), cada trecho vai para o Google Tradutor online e, sem internet, para o
 *   tradutor do ML Kit no aparelho.
 */
class LiveTranslator(private val pack: OcrPack) : Closeable {

    enum class Stage { READING, ASKING_AI, DOWNLOADING_MODEL, TRANSLATING }

    /** [aiError]: a IA falhou e a tradução comum assumiu. [suggestPack]: japonês lido sem o OCR para jogos. */
    data class Result(val blocks: List<TranslatedBlock>, val aiError: Throwable? = null, val suggestPack: Boolean = false)

    private val recognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private var meiki: MeikiOcr? = null
    @Volatile private var meikiBroken = false
    private val translators = mutableMapOf<String, Translator>()
    /** Pares de idiomas com o modelo do ML Kit já baixado; os trechos são traduzidos em paralelo. */
    private val downloaded: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var onDeviceBroken = false
    /** Últimos trechos traduzidos nesta sessão: a IA mantém os nomes e o tom entre uma tela e outra. */
    private val history = ArrayDeque<String>()

    /** Traduções em andamento e se [close] já foi pedido: os modelos só são liberados quando a última termina. */
    private val lifecycle = Any()
    private var running = 0
    private var closed = false

    suspend fun translate(
        frame: OcrFrame, target: String, ai: AiConfig?, game: GeminiText.GameContext, onStage: (Stage) -> Unit,
    ): Result {
        synchronized(lifecycle) {
            if (closed) throw CancellationException("tradutor fechado")
            running++
        }
        try {
            return translateOpen(frame, target, ai, game, onStage)
        } finally {
            // O cancelamento não interrompe a leitura nativa do MeikiOCR: fechar as sessões dele no meio dela
            // derrubaria o app. Quem fecha por último é quem libera.
            val release = synchronized(lifecycle) { running--; closed && running == 0 }
            if (release) releaseAll()
        }
    }

    private suspend fun translateOpen(
        frame: OcrFrame, target: String, ai: AiConfig?, game: GeminiText.GameContext, onStage: (Stage) -> Unit,
    ): Result {
        onStage(Stage.READING)
        val image = frame.image
        val k = TranslationText.upscaleFor(image.width, image.height)
        val prepared = withContext(Dispatchers.Default) {
            if (k > 1) Bitmap.createScaledBitmap(image, image.width * k, image.height * k, false) else image
        }
        // Da imagem ampliada para a view: desfaz a ampliação e posiciona na área do jogo.
        val sx = frame.area.width.toFloat() / prepared.width
        val sy = frame.area.height.toFloat() / prepared.height
        fun toView(box: Box) = box.scaled(sx, sy, frame.area.left, frame.area.top)

        val (lines, usedMeiki) = readLines(prepared, target)

        var aiError: Throwable? = null
        if (ai != null) {
            onStage(Stage.ASKING_AI)
            try {
                val png = withContext(Dispatchers.Default) { png(prepared) }
                val blocks = GeminiTranslator(ai.apiKey, ai.model)
                    .translate(png, prepared.width, prepared.height, lines, target, game, synchronized(history) { history.toList() })
                remember(blocks.map { "${it.original} → ${it.translated}" })
                return Result(blocks.map { TranslatedBlock(toView(it.box), it.original, it.translated) })
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                aiError = t
            }
        }

        val blocks = TranslationText.groupLines(lines).mapNotNull { block ->
            TranslationText.sourceFor(block.text, target)?.let { block to it }
        }
        val suggestPack = !usedMeiki && ai == null && blocks.any { it.second == "ja" }
        if (blocks.isEmpty()) return Result(emptyList(), aiError, suggestPack)
        onStage(Stage.TRANSLATING)
        val translated = coroutineScope {
            blocks.map { (block, source) ->
                async { TranslatedBlock(toView(block.box), block.text, translateText(block.text, source, target, onStage)) }
            }.awaitAll()
        }
        return Result(translated, aiError, suggestPack)
    }

    /** Linhas lidas na imagem e se o MeikiOCR participou. */
    private suspend fun readLines(image: Bitmap, target: String): Pair<List<OcrLine>, Boolean> {
        val fromMeiki = if (pack.installed && !meikiBroken) {
            withContext(Dispatchers.Default) {
                try {
                    (meiki ?: MeikiOcr(pack).also { meiki = it }).read(image)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // Runtime que não carrega neste aparelho ou modelo danificado: o ML Kit segue sozinho.
                    meikiBroken = true
                    null
                }
            }
        } else null
        // O MeikiOCR só conhece japonês: o texto latino (jogos americanos, para quem lê em português) vem do ML Kit.
        val needsMlKit = fromMeiki == null || target != "en"
        val fromMlKit = if (needsMlKit) mlKitLines(image) else emptyList()
        val lines = if (fromMeiki == null) fromMlKit else {
            fromMeiki + fromMlKit.filter { latin ->
                !TranslationText.hasJapanese(latin.text) && fromMeiki.none { it.box.overlapOfSmaller(latin.box) > 0.3f }
            }
        }
        return lines.filter { !TranslationText.isNoise(it.text) && it.confidence >= MIN_CONFIDENCE } to (fromMeiki != null)
    }

    private suspend fun mlKitLines(image: Bitmap): List<OcrLine> {
        val text = recognizer.process(InputImage.fromBitmap(image, 0)).await()
        return text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val r = line.boundingBox ?: return@mapNotNull null
            OcrLine(Box(r.left, r.top, r.right, r.bottom), line.text, line.confidence.takeIf { it > 0f } ?: 1f)
        }
    }

    private fun remember(entries: List<String>) = synchronized(history) {
        entries.forEach { history.addLast(it.take(200)) }
        while (history.size > HISTORY) history.removeFirst()
    }

    /** Google Tradutor online primeiro (bem melhor que o modelo compacto do aparelho); sem internet, o ML Kit. */
    private suspend fun translateText(text: String, source: String, target: String, onStage: (Stage) -> Unit): String {
        val web = try {
            withTimeout(WEB_TIMEOUT_MS) { translateOnWeb(text, source, target) }
        } catch (c: CancellationException) {
            if (c !is TimeoutCancellationException) throw c
            null
        } catch (t: Throwable) {
            null
        }
        if (web != null) return web
        if (!onDeviceBroken) {
            try {
                val translator = translatorFor(source, target)
                // Primeira vez: baixa o modelo (~30 MB). Sem os serviços do Google, isso falha.
                if (!downloaded.contains("$source>$target")) {
                    onStage(Stage.DOWNLOADING_MODEL)
                    withTimeout(MODEL_TIMEOUT_MS) { translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await() }
                    downloaded += "$source>$target"
                    onStage(Stage.TRANSLATING)
                }
                return translator.translate(text).await()
            } catch (c: CancellationException) {
                if (c is TimeoutCancellationException) onDeviceBroken = true else throw c
            } catch (t: Throwable) {
                onDeviceBroken = true
            }
        }
        // Nem online nem no aparelho: uma última tentativa online, agora deixando o erro subir para a tela.
        return translateOnWeb(text, source, target)
    }

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
        TranslationText.parseWebResponse(body) ?: throw java.io.IOException("resposta vazia do tradutor")
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    override fun close() {
        val now = synchronized(lifecycle) { closed = true; running == 0 }
        if (now) releaseAll()
    }

    private fun releaseAll() {
        runCatching { recognizer.close() }
        runCatching { meiki?.close() }
        meiki = null
        // A mesma trava de translatorFor.
        synchronized(this) {
            translators.values.forEach { runCatching { it.close() } }
            translators.clear()
        }
    }

    companion object {
        private const val MODEL_TIMEOUT_MS = 90_000L
        private const val WEB_TIMEOUT_MS = 8_000L
        private const val MIN_CONFIDENCE = 0.35f
        private const val HISTORY = 12
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
