package com.retrovika.app.remote

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCodes {
    private fun matrix(text: String): BitMatrix =
        QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )

    /** Um pixel por módulo: quem desenha amplia sem suavizar (FilterQuality.None). */
    fun bitmap(text: String): Bitmap {
        val m = matrix(text)
        val pixels = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) BLACK else WHITE }
        return Bitmap.createBitmap(pixels, m.width, m.height, Bitmap.Config.ARGB_8888)
    }

    /** Para a página da tela mostrar o QR do controle sem biblioteca no navegador. */
    fun svg(text: String): String {
        val m = matrix(text)
        val path = StringBuilder()
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) path.append("M$x ${y}h1v1h-1z")
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${m.width} ${m.height}" shape-rendering="crispEdges">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
