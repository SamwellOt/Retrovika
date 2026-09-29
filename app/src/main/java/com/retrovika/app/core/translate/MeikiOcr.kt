package com.retrovika.app.core.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR treinado em texto de jogos japoneses (MeikiOCR: detector D-FINE + reconhecedor que detecta cada
 * caractere), rodando no ONNX Runtime do [OcrPack]. Lê letras de pixel, furigana e fontes estilizadas bem
 * melhor que o ML Kit, que foi feito para fotos de documentos.
 */
class MeikiOcr(pack: OcrPack) : Closeable {

    private val env: OrtEnvironment
    private val detector: OrtSession
    private val recognizer: OrtSession
    // Linhas verticais são raras em jogos antigos: o modelo delas só abre quando aparece uma.
    private val verticalLazy = lazy { env.createSession(pack.model(OcrPack.RECOGNIZER_VERTICAL).path, options) }
    private val verticalRecognizer: OrtSession get() = verticalLazy.value
    private val options = OrtSession.SessionOptions().apply {
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    }

    init {
        loadRuntime(pack)
        env = OrtEnvironment.getEnvironment()
        detector = env.createSession(pack.model(OcrPack.DETECTOR).path, options)
        recognizer = env.createSession(pack.model(OcrPack.RECOGNIZER).path, options)
    }

    fun read(image: Bitmap): List<OcrLine> {
        val boxes = detect(image)
        if (boxes.isEmpty()) return emptyList()
        val lines = arrayOfNulls<OcrLine>(boxes.size)
        val horizontal = boxes.indices.filter { boxes[it].width >= boxes[it].height }
        val vertical = boxes.indices.filter { boxes[it].width < boxes[it].height }
        recognize(image, boxes, horizontal, vertical = false, lines)
        recognize(image, boxes, vertical, vertical = true, lines)
        return lines.filterNotNull().filter { it.text.isNotBlank() }
    }

    private fun detect(image: Bitmap): List<Box> {
        val w = image.width
        val h = image.height
        val scale = min(DET_W.toFloat() / w, DET_H.toFloat() / h)
        val rw = (w * scale).toInt().coerceIn(1, DET_W)
        val rh = (h * scale).toInt().coerceIn(1, DET_H)
        val data = FloatArray(3 * DET_W * DET_H)
        fill(scaled(image, rw, rh), data, 0, DET_W, DET_H)
        val sizes = longArrayOf((DET_W / scale).toLong(), (DET_H / scale).toLong())
        return run(detector, data, longArrayOf(1, 3, DET_H.toLong(), DET_W.toLong()), sizes, 1) { result ->
            @Suppress("UNCHECKED_CAST")
            val out = result.get("boxes").get().value as Array<Array<FloatArray>>
            @Suppress("UNCHECKED_CAST")
            val scores = result.get("scores").get().value as Array<FloatArray>
            out[0].indices.filter { scores[0][it] > MeikiDecode.DET_THRESHOLD }.map { i ->
                val b = out[0][i]
                Box(
                    b[0].toInt().coerceIn(0, w), b[1].toInt().coerceIn(0, h),
                    b[2].toInt().coerceIn(0, w), b[3].toInt().coerceIn(0, h),
                )
            }.filter { !it.isEmpty }.sortedBy { it.top }
        }
    }

    /** Uma entrada do reconhecedor: a qual caixa ela pertence e onde está o conteúdo dentro dos 960 x 32 (ou 32 x 480). */
    private class Slice(val index: Int, val crop: Box, val effectiveW: Int, val effectiveH: Int)

    private fun recognize(image: Bitmap, boxes: List<Box>, indices: List<Int>, vertical: Boolean, lines: Array<OcrLine?>) {
        if (indices.isEmpty()) return
        val inW = if (vertical) VREC_W else REC_W
        val inH = if (vertical) VREC_H else REC_H
        val slices = mutableListOf<Pair<Slice, Bitmap>>()
        for (i in indices) {
            val box = boxes[i]
            if (!vertical) {
                var newH = REC_H
                var newW = (box.width * REC_H.toFloat() / box.height).roundToInt().coerceAtLeast(1)
                if (newW > REC_W) {
                    newH = (newH * REC_W.toFloat() / newW).roundToInt().coerceAtLeast(1)
                    newW = REC_W
                }
                slices += Slice(i, box, newW, newH) to scaled(crop(image, box), newW, newH)
            } else {
                val scale = VREC_W.toFloat() / box.width
                val (starts, segment) = MeikiDecode.verticalSegments(box.top, box.bottom, scale)
                val maxH = if (starts.size > 1) 420 else VREC_H
                for (start in starts) {
                    val top = start.roundToInt()
                    val bottom = min((start + segment).roundToInt(), box.bottom)
                    if (bottom <= top) continue
                    val part = Box(box.left, top, box.right, bottom)
                    val newH = min(((bottom - top) * scale).roundToInt(), maxH).coerceAtLeast(1)
                    slices += Slice(i, part, VREC_W, newH) to scaled(crop(image, part), VREC_W, newH)
                }
            }
        }
        val candidates = HashMap<Int, MutableList<MeikiDecode.Candidate>>()
        val session = if (vertical) verticalRecognizer else recognizer
        for (batch in slices.chunked(BATCH)) {
            val data = FloatArray(batch.size * 3 * inW * inH)
            batch.forEachIndexed { k, (_, bmp) -> fill(bmp, data, k * 3 * inW * inH, inW, inH) }
            val sizes = LongArray(batch.size * 2) { if (it % 2 == 0) inW.toLong() else inH.toLong() }
            run(session, data, longArrayOf(batch.size.toLong(), 3, inH.toLong(), inW.toLong()), sizes, batch.size) { result ->
                val codes = result.get("char_codes").get().value as Array<*>
                @Suppress("UNCHECKED_CAST")
                val outBoxes = result.get("boxes").get().value as Array<Array<FloatArray>>
                @Suppress("UNCHECKED_CAST")
                val scores = result.get("scores").get().value as Array<FloatArray>
                batch.forEachIndexed { k, (slice, _) ->
                    val row = codes[k]
                    val count = scores[k].size
                    for (j in 0 until count) {
                        val code = when (row) {
                            is IntArray -> row[j]
                            is LongArray -> row[j].toInt()
                            else -> 0
                        }
                        val b = outBoxes[k][j]
                        MeikiDecode.toImage(
                            code, b[0], b[1], b[2], b[3], scores[k][j],
                            slice.crop, slice.effectiveW, slice.effectiveH, vertical, inW, inH,
                        )?.let { candidates.getOrPut(slice.index) { mutableListOf() } += it }
                    }
                }
            }
        }
        for ((index, list) in candidates) {
            val (text, conf) = MeikiDecode.decode(list)
            lines[index] = OcrLine(boxes[index], text, conf, vertical)
        }
    }

    private fun <T> run(session: OrtSession, data: FloatArray, shape: LongArray, sizes: LongArray, batch: Int, read: (OrtSession.Result) -> T): T =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { images ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(sizes), longArrayOf(batch.toLong(), 2)).use { target ->
                session.run(mapOf("images" to images, "orig_target_sizes" to target)).use(read)
            }
        }

    /** Copia [bmp] (RGB de 0 a 1, planos separados) para o canto superior esquerdo de um tensor [W] x [H] zerado. */
    private fun fill(bmp: Bitmap, data: FloatArray, offset: Int, width: Int, height: Int) {
        val w = min(bmp.width, width)
        val h = min(bmp.height, height)
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val plane = width * height
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = px[y * w + x]
                val i = offset + y * width + x
                data[i] = ((p shr 16) and 0xFF) / 255f
                data[i + plane] = ((p shr 8) and 0xFF) / 255f
                data[i + 2 * plane] = (p and 0xFF) / 255f
            }
        }
    }

    private fun crop(image: Bitmap, box: Box): Bitmap {
        val left = box.left.coerceIn(0, image.width - 1)
        val top = box.top.coerceIn(0, image.height - 1)
        return Bitmap.createBitmap(image, left, top, (box.right - left).coerceIn(1, image.width - left), (box.bottom - top).coerceIn(1, image.height - top))
    }

    private fun scaled(bmp: Bitmap, w: Int, h: Int): Bitmap =
        if (bmp.width == w && bmp.height == h) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)

    override fun close() {
        runCatching { detector.close() }
        runCatching { recognizer.close() }
        runCatching { if (verticalLazy.isInitialized()) verticalRecognizer.close() }
        runCatching { options.close() }
    }

    companion object {
        private const val DET_W = 960
        private const val DET_H = 544
        private const val REC_W = 960
        private const val REC_H = 32
        private const val VREC_W = 32
        private const val VREC_H = 480
        private const val BATCH = 8

        @Volatile private var runtimeLoaded = false

        /**
         * A ponte JNI (libonnxruntime4j_jni.so, no APK) depende de libonnxruntime.so, que vem do pacote. Com ela
         * já carregada, o linker do Android resolve a dependência pelo nome e a ponte carrega normalmente.
         */
        @Synchronized
        private fun loadRuntime(pack: OcrPack) {
            if (runtimeLoaded) return
            System.load(pack.runtime.absolutePath)
            runtimeLoaded = true
        }
    }
}
