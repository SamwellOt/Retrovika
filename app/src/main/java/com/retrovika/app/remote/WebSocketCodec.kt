package com.retrovika.app.remote

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/** O pedaço do protocolo WebSocket (RFC 6455) que o servidor usa: handshake e quadros. */
object WebSocketCodec {
    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun acceptKey(key: String): String {
        val sha1 = MessageDigest.getInstance("SHA-1").digest((key.trim() + GUID).toByteArray(Charsets.US_ASCII))
        return Base64.getEncoder().encodeToString(sha1)
    }

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** Lê um quadro do cliente (sempre mascarado). Null no fim da conexão. */
    fun readFrame(input: InputStream, maxPayload: Int): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.readByte()
        val fin = b0 and 0x80 != 0
        val opcode = b0 and 0x0F
        val masked = b1 and 0x80 != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = ((input.readByte() shl 8) or input.readByte()).toLong()
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.readByte().toLong() }
        }
        if (length < 0 || length > maxPayload) throw IOException("WebSocket frame too large: $length")
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        input.readFully(payload)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(fin, opcode, payload)
    }

    /** Cabeçalho de um quadro do servidor (sem máscara, como manda o protocolo). */
    fun header(opcode: Int, length: Int): ByteArray = when {
        length < 126 -> byteArrayOf((0x80 or opcode).toByte(), length.toByte())
        length < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (length shr 8).toByte(), length.toByte())
        else -> ByteArray(10).also { h ->
            h[0] = (0x80 or opcode).toByte()
            h[1] = 127
            for (i in 0 until 8) h[9 - i] = (length.toLong() shr (8 * i)).toByte()
        }
    }

    /** Quadro mascarado, como um navegador envia (usado nos testes). */
    fun clientFrame(opcode: Int, payload: ByteArray, mask: ByteArray = byteArrayOf(0x12, 0x34, 0x56, 0x78)): ByteArray {
        val h = header(opcode, payload.size)
        h[1] = (h[1].toInt() or 0x80).toByte()
        val masked = ByteArray(payload.size) { i -> (payload[i].toInt() xor mask[i and 3].toInt()).toByte() }
        return h + mask + masked
    }

    private fun InputStream.readByte(): Int {
        val b = read()
        if (b < 0) throw EOFException()
        return b
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var off = 0
        while (off < buffer.size) {
            val n = read(buffer, off, buffer.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }
}
