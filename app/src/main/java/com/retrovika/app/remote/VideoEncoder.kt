package com.retrovika.app.remote

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * Encoder H.264 do aparelho com entrada por [surface]: o LibretroDroid copia cada quadro para ela na
 * thread de emulação. A saída é lida numa thread própria e entregue em [onConfig] (SPS e PPS, que mudam
 * se o encoder for recriado) e [onFrame]. Se o codec morrer depois de iniciar, [onError] avisa (na thread
 * de saída): quem usa tenta o próximo [mode].
 *
 * [mode] vai do mais rápido ao mais compatível: 0 é o encoder de hardware em tempo real e baixa latência,
 * 1 só com baixa latência, 2 sem ajustes e 3 o encoder de software.
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    bitrate: Int,
    val mode: Int,
    private val onConfig: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onFrame: (EncodedFrame) -> Unit,
    private val onError: (Exception) -> Unit,
) {
    private val codec = if (mode >= MODE_SOFTWARE) {
        MediaCodec.createByCodecName(softwareEncoder() ?: throw IllegalStateException("No software AVC encoder"))
    } else {
        MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    }
    val name: String = codec.name
    val surface: Surface

    @Volatile private var running = true
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private val thread: Thread

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Jogo parado (menu aberto) não gera quadros: o encoder repete o último para quem chega ver algo.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            // Tempo real: alguns encoders (Qualcomm da Xiaomi) aceitam e morrem logo depois.
            if (mode == MODE_REALTIME) setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (mode <= MODE_LOW_LATENCY) {
                // Um quadro por vez, sem esperar os seguintes: cada quadro retido é atraso na outra tela.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
                setInteger("vendor.qti-ext-enc-low-latency.enable", 1)
            }
        }
        // Baseline não tem quadros B (que atrasariam a imagem); se o encoder recusar, fica o perfil padrão.
        // Vários encoders só aceitam o perfil acompanhado do nível (4.1 cobre 720p a 60 quadros).
        val baseline = MediaFormat(format).apply {
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
        }
        try {
            try {
                codec.configure(baseline, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(TAG, "Baseline profile refused, using the default: ${e.message}")
                codec.reset()
                // Antes do Android 10 não dá para proibir quadros B, que o fMP4 não descreve (sem deslocamento
                // de composição): lá só serve Baseline.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw e
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            // Encoders de hardware são poucos: um que falhou aqui precisa ser devolvido.
            codec.release()
            throw e
        }
        thread = Thread({ drain() }, "VideoEncoder").apply { isDaemon = true; start() }
    }

    fun requestKeyFrame() {
        runCatching { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun release() {
        running = false
        thread.join(500)
        runCatching { codec.stop() }
        runCatching { codec.release() }
        surface.release()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                if (index < 0) continue
                try {
                    val buffer = codec.getOutputBuffer(index) ?: continue
                    val data = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(data, 0, info.size)
                    handle(data, info)
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }
        } catch (e: Exception) {
            // Parado por release() é normal; com running ainda ligado, o codec morreu sozinho (ou um buffer
            // estranho quebrou a leitura, ou quem recebe os quadros falhou). Exceção solta nesta thread
            // derrubaria o app com o jogo junto e pularia a troca de modo: vira erro para quem usa.
            if (running) {
                val detail = (e as? MediaCodec.CodecException)?.diagnosticInfo.orEmpty()
                Log.e(TAG, "Encoder $name (mode $mode) died $detail", e)
                runCatching { onError(e) }.onFailure { Log.e(TAG, "onError failed", it) }
            }
        }
    }

    private fun handle(data: ByteArray, info: MediaCodec.BufferInfo) {
        val nals = Avc.splitAnnexB(data)
        // O SPS/PPS vem no buffer de configuração e, em alguns encoders, também antes de cada quadro-chave.
        var configChanged = false
        nals.forEach { nal ->
            when (Avc.type(nal)) {
                Avc.NAL_SPS -> if (!nal.contentEquals(sps)) { sps = nal; configChanged = true }
                Avc.NAL_PPS -> if (!nal.contentEquals(pps)) { pps = nal; configChanged = true }
            }
        }
        val s = sps
        val p = pps
        if (configChanged && s != null && p != null) onConfig(s, p)
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return

        val picture = nals.filter { Avc.type(it) !in CONFIG_TYPES }
        if (picture.isEmpty()) return
        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 || picture.any { Avc.type(it) == Avc.NAL_IDR }
        onFrame(EncodedFrame(Avc.toLengthPrefixed(picture), key, info.presentationTimeUs))
    }

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MODE_REALTIME = 0
        private const val MODE_LOW_LATENCY = 1
        private const val MODE_SOFTWARE = 3
        const val MODES = 4
        private val CONFIG_TYPES = setOf(Avc.NAL_SPS, Avc.NAL_PPS, Avc.NAL_AUD)

        /** O encoder AVC do próprio Android; alguns aparelhos não o marcam como isSoftwareOnly, daí o nome. */
        private fun softwareEncoder(): String? =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } &&
                    (info.name.startsWith("c2.android.") || info.name.startsWith("OMX.google.") ||
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isSoftwareOnly)
            }?.name
    }
}
