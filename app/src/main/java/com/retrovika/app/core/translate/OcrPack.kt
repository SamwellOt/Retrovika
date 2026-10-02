package com.retrovika.app.core.translate

import android.content.Context
import com.retrovika.app.R
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.net.RemoteZip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest

/**
 * Pacote opcional do OCR para jogos japoneses (MeikiOCR): os três modelos ONNX e o runtime do ONNX para a
 * arquitetura do aparelho, ~58 MB baixados uma vez. O runtime (~33 MB descompactado) não vai no APK: só a
 * ponte JNI dele, pequena, vai; a biblioteca é carregada daqui antes do primeiro uso.
 */
class OcrPack(context: Context, private val scope: CoroutineScope, private val abi: String) {

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data object Ready : State
        data class Failed(val error: Throwable) : State
    }

    private class PackFile(val name: String, val url: String, val sha256: String, val size: Long)

    private val base = File(context.filesDir, "ocr")
    val dir = File(base, PACK)
    private val marker = File(dir, ".complete")
    val runtime: File get() = File(dir, "libonnxruntime.so")
    fun model(name: String) = File(dir, name)

    private val _state = MutableStateFlow(if (marker.exists()) State.Ready else State.Missing)
    val state: StateFlow<State> = _state
    private val lock = Mutex()
    private var job: Job? = null

    val installed: Boolean get() = marker.exists()

    init {
        // Pacotes de versões anteriores (outro runtime do ONNX): os modelos, iguais, passam para o atual (o
        // próximo download só busca o runtime); o resto sai, para não ficarem ~80 MB esquecidos.
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                runCatching {
                    base.listFiles()?.filter { it.isDirectory && it.name != PACK }?.forEach { old ->
                        dir.mkdirs()
                        MODELS.forEach { m ->
                            val from = File(old, m.name)
                            val to = model(m.name)
                            if (from.exists() && !to.exists()) from.renameTo(to)
                        }
                        old.walkBottomUp().forEach { it.setWritable(true) }
                        old.deleteRecursively()
                    }
                }
            }
        }
    }

    /** Baixa o que falta; chamar de novo durante o download não abre outro. */
    fun download() {
        if (job?.isActive == true || installed) return
        job = scope.launch {
            lock.withLock {
                _state.value = State.Downloading(0f)
                try {
                    install()
                    _state.value = State.Ready
                } catch (c: kotlinx.coroutines.CancellationException) {
                    _state.value = State.Missing
                    throw c
                } catch (t: Throwable) {
                    _state.value = State.Failed(t)
                }
            }
        }
    }

    fun delete() {
        job?.cancel()
        scope.launch {
            lock.withLock {
                runtime.setWritable(true)
                dir.deleteRecursively()
                _state.value = State.Missing
            }
        }
    }

    private suspend fun install() {
        dir.mkdirs()
        val runtimeSha = RUNTIME_SHA256[abi] ?: throw LocalizedException(R.string.ocr_pack_unsupported_abi, abi)
        val runtimeSize = RUNTIME_SIZE.getValue(abi)
        val total = MODELS.sumOf { it.size } + runtimeSize
        var done = 0L
        fun report(current: Long) { _state.value = State.Downloading(((done + current).toFloat() / total).coerceIn(0f, 1f)) }

        for (file in MODELS) {
            val target = model(file.name)
            if (!(target.exists() && sha256(target) == file.sha256)) {
                target.delete()
                Http.download(file.url, target, onBytes = { read, _ -> report(read) })
                if (sha256(target) != file.sha256) { target.delete(); throw LocalizedException(R.string.ocr_pack_corrupted, file.name) }
            }
            done += file.size
            report(0)
        }
        if (!(runtime.exists() && sha256(runtime) == runtimeSha)) {
            runtime.setWritable(true)
            runtime.delete()
            RemoteZip.extract(RUNTIME_AAR, "jni/$abi/libonnxruntime.so", runtime) { read, _ -> report(read) }
            if (sha256(runtime) != runtimeSha) { runtime.delete(); throw LocalizedException(R.string.ocr_pack_runtime_corrupted) }
        }
        // Android 14+ só carrega código nativo baixado se o arquivo for somente leitura (como os núcleos).
        runtime.setWritable(false, false)
        runtime.setReadOnly()
        marker.writeText(PACK)
    }

    companion object {
        /**
         * Pasta e marcador do pacote. Leva a versão do runtime: trocar o onnxruntime (libs.versions.toml e
         * [RUNTIME_AAR]) muda o nome, e o pacote antigo deixa de contar como instalado; a ponte JNI nova não
         * pode carregar a biblioteca da versão anterior.
         */
        private const val RUNTIME_VERSION = "1.30.0"
        const val PACK = "meiki-v0-ort$RUNTIME_VERSION"
        const val DETECTOR = "det.onnx"
        const val RECOGNIZER = "rec.onnx"
        const val RECOGNIZER_VERTICAL = "rec-vertical.onnx"

        private const val DET_REPO = "https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6"
        private const val REC_REPO = "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/a28cf5874dc2438ebb1c86336be26bcec51e3375"

        private val MODELS = listOf(
            PackFile(DETECTOR, "$DET_REPO/meiki.text.detect.v0.1.960x544.onnx", "40b6a016667745cae7d3055929ae3b8b1e7716aac795f5904cd3c2c7c3b8404b", 14_503_825),
            PackFile(RECOGNIZER, "$REC_REPO/meiki.text.rec.v0.960x32.onnx", "3e96bc772fbee9717e536a6353032bb944c3382dd2f6960ef4890decda43b000", 18_593_254),
            PackFile(RECOGNIZER_VERTICAL, "$REC_REPO/meiki.text.rec.v0.vertical.32x480.onnx", "2c2a83a23bc3b7e6c63962175f507ecc6c5e85cc174f17bdec37d9bbd0bf895a", 12_872_961),
        )

        /** O mesmo onnxruntime-android de libs.versions.toml: as classes Java e a biblioteca têm de ser da mesma versão. */
        private const val RUNTIME_AAR =
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$RUNTIME_VERSION/onnxruntime-android-$RUNTIME_VERSION.aar"
        private val RUNTIME_SHA256 = mapOf(
            "arm64-v8a" to "df5d25c72a868dca773597c71e2000756d43fe4d70ade516d3693c54e12e0ada",
            "armeabi-v7a" to "d8c6e57af1848c4b9b2571a8864ac53592e57105dc67fb1e7b9e49828e555279",
            "x86_64" to "f59d4d59d4c71532028d56ab6ca1dae62c3838c7982adfdad7f2560048b89ed9",
        )
        private val RUNTIME_SIZE = mapOf("arm64-v8a" to 32_990_480L, "armeabi-v7a" to 23_311_344L, "x86_64" to 39_348_488L)

        /** Tamanho aproximado do download, para o botão em Ajustes. */
        const val DOWNLOAD_MB = 58

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
