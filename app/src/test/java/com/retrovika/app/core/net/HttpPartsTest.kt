package com.retrovika.app.core.net

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Download em partes contra um servidor HTTP/1.1 local que atende Range, uma thread por conexão. */
class HttpPartsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = ByteArray(20 * 1024 * 1024 + 12_345) { (it * 7 % 251).toByte() }
    private val ranges = Collections.synchronizedList(mutableListOf<String?>())
    private val calls = AtomicInteger()
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    /** Como o servidor responde: com Range (206), ignorando o Range (200), ou ocupado no 2º pedido. */
    private var mode = "ranges"

    private val acceptor = thread(isDaemon = true) {
        while (!server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) {
                runCatching {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val lines = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                        val range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
                        ranges.add(range)
                        val n = calls.incrementAndGet()
                        val out = s.getOutputStream().buffered()
                        if (mode == "busy" && n == 2) {
                            out.write("HTTP/1.1 503 Service Unavailable\r\nRetry-After: 1\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        } else if (range == null || mode == "noranges") {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(content)
                        } else {
                            val spec = range.removePrefix("bytes=")
                            val from = spec.substringBefore('-').toInt()
                            val to = spec.substringAfter('-').ifEmpty { null }?.toInt() ?: (content.size - 1)
                            out.write(
                                ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-$to/${content.size}\r\n" +
                                    "Content-Disposition: attachment; filename=\"Jogo (USA).zip\"\r\n" +
                                    "Content-Length: ${to - from + 1}\r\nConnection: close\r\n\r\n").toByteArray(),
                            )
                            out.write(content, from, to - from + 1)
                        }
                        out.flush()
                    }
                }
            }
        }
    }

    @After fun stop() = server.close()

    private val url get() = "http://127.0.0.1:${server.localPort}/rom.zip"

    @Test
    fun `baixa em partes com varias conexoes e junta na ordem`() = runBlocking {
        val file = Http.download(url, File(tmp.root, "rom.zip"), parallel = 4, serverName = true)
        assertTrue(content.contentEquals(file.readBytes()))
        assertEquals("Jogo (USA).zip", file.name)
        assertTrue("pedidos: $ranges", ranges.size > 4)
        assertTrue(ranges.all { it != null && it.startsWith("bytes=") })
    }

    @Test
    fun `servidor que ignora o range cai no download comum`() = runBlocking {
        mode = "noranges"
        val file = Http.download(url, File(tmp.root, "rom.zip"), parallel = 4)
        assertTrue(content.contentEquals(file.readBytes()))
        // O teste (ignorado) e o download comum, sem Range.
        assertEquals(listOf("bytes=0-", null), ranges.toList())
    }

    @Test
    fun `pedaco com servidor ocupado volta para a fila`() = runBlocking {
        mode = "busy"
        val file = Http.download(url, File(tmp.root, "rom.zip"), parallel = 4)
        assertTrue(content.contentEquals(file.readBytes()))
    }
}
