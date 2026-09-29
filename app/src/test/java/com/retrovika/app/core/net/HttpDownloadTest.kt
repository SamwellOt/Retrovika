package com.retrovika.app.core.net

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class HttpDownloadTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(200_000) { (it % 251).toByte() }
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    // Servidor HTTP/1.1 mínimo. 1º pedido: 503 (ocupado). 2º: 200 que cai no meio. 3º: 206 com o resto.
    private val worker = thread(isDaemon = true) {
        var calls = 0
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            socket.use { s ->
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
                ranges.add(range)
                val out = s.getOutputStream()
                when (calls++) {
                    0 -> out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    1 -> {
                        out.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.write(content, 0, 50_000) // encerra antes do tamanho anunciado
                    }
                    else -> {
                        val from = range!!.removePrefix("bytes=").substringBefore('-').toInt()
                        out.write(
                            ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${content.size - 1}/${content.size}\r\n" +
                                "Content-Length: ${content.size - from}\r\nConnection: close\r\n\r\n").toByteArray(),
                        )
                        out.write(content, from, content.size - from)
                    }
                }
                out.flush()
            }
        }
    }

    @After fun stop() = server.close()

    @Test
    fun `espera o servidor ocupado e continua de onde a conexao caiu`() = runBlocking {
        val url = "http://127.0.0.1:${server.localPort}/rom.bin"
        val file = Http.download(url, File(tmp.root, "rom.bin"))
        assertEquals(content.toList(), file.readBytes().toList())
        assertEquals(listOf(null, null, "bytes=50000-"), ranges.toList())
    }

    @Test
    fun `retry-after em segundos ou data e limitado a cinco minutos`() {
        assertEquals(300_000L, Http.retryAfterMs("300"))
        assertEquals(1_000L, Http.retryAfterMs("0"))
        assertEquals(300_000L, Http.retryAfterMs("86400"))
        assertEquals(120_000L, Http.retryAfterMs("Mon, 28 Sep 2026 03:02:00 GMT", now = java.time.Instant.parse("2026-09-28T03:00:00Z").toEpochMilli()))
        assertEquals(null, Http.retryAfterMs(null))
        assertEquals(null, Http.retryAfterMs("logo"))
    }
}
