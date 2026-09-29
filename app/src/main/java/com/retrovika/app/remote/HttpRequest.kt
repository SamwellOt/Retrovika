package com.retrovika.app.remote

import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder

/** Pedido HTTP/1.1 mínimo: só o que as páginas do controle e da tela precisam (GET e upgrade). */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    /** Nomes em minúsculas. */
    val headers: Map<String, String>,
) {
    val isWebSocket: Boolean
        get() = headers["upgrade"].equals("websocket", ignoreCase = true) && headers["sec-websocket-key"] != null

    companion object {
        private const val MAX_HEAD = 16 * 1024

        /** Lê até a linha em branco; null se a conexão fechou antes de mandar algo. */
        fun read(input: InputStream): HttpRequest? {
            val head = StringBuilder()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) return if (head.isEmpty()) null else throw IOException("Incomplete request")
                head.append(b.toChar())
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (head.length > MAX_HEAD) throw IOException("Request head too large")
            }
            return parse(head.toString())
        }

        fun parse(head: String): HttpRequest {
            val lines = head.split("\r\n").filter { it.isNotEmpty() }
            val parts = lines.firstOrNull()?.split(" ") ?: throw IOException("Empty request")
            if (parts.size < 3) throw IOException("Bad request line")
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
                decode(pair.substringBefore('=')) to decode(pair.substringAfter('=', ""))
            }
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            return HttpRequest(parts[0], decode(path), query, headers)
        }

        private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
    }
}

class HttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    fun bytes(): ByteArray {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("\r\n")
        }
        return head.toByteArray(Charsets.UTF_8) + body
    }

    companion object {
        fun text(status: Int, text: String) = HttpResponse(status, "text/plain; charset=utf-8", text.toByteArray())
        fun redirect(location: String) = HttpResponse(302, "text/plain", ByteArray(0), mapOf("Location" to location))

        private fun reason(status: Int) = when (status) {
            101 -> "Switching Protocols"
            200 -> "OK"
            302 -> "Found"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            429 -> "Too Many Requests"
            else -> "Error"
        }
    }
}
