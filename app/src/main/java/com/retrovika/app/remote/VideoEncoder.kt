package com.retrovika.app.remote

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * Encoder H.264 do aparelho com entrada por [surface]: o LibretroDroid copia cada quadro para ela na
 * thread de emulação. A saída é lida numa thread própria e entregue em [onConfig] (SPS e PPS, que mudam
 * se o encoder for recriado) e [onFrame].
 */
class VideoEncoder(
    val width: Int,
    val height: Int,
    bitrate: Int,
    private val onConfig: (sps: ByteArray, pps: ByteArray) -> Unit,
    private val onFrame: (EncodedFrame) -> Unit,
) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
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
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LATENCY, 1)
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
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(info, 20_000)
            } catch (e: IllegalStateException) {
                break
            }
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

    private companion object {
        const val TAG = "VideoEncoder"
        val CONFIG_TYPES = setOf(Avc.NAL_SPS, Avc.NAL_PPS, Avc.NAL_AUD)
    }
}
