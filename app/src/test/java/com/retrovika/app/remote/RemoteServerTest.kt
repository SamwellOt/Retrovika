package com.retrovika.app.remote

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class RemoteServerTest {

    // region Codec

    @Test
    fun acceptKeyMatchesRfcExample() {
        // Exemplo da RFC 6455, seção 1.3.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketCodec.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun readsMaskedClientFramesOfEverySize() {
        for (size in listOf(0, 5, 125, 126, 300, 65535, 65536, 70000)) {
            val payload = ByteArray(size) { (it * 7).toByte() }
            val frame = WebSocketCodec.readFrame(ByteArrayInputStream(WebSocketCodec.clientFrame(WebSocketCodec.OP_BINARY, payload)), 1 shl 20)!!
            assertEquals(WebSocketCodec.OP_BINARY, frame.opcode)
            assertTrue(frame.fin)
            assertArrayEquals("size $size", payload, frame.payload)
        }
    }

    @Test
    fun serverHeaderUsesExtendedLengths() {
        assertArrayEquals(byteArrayOf(0x82.toByte(), 10), WebSocketCodec.header(WebSocketCodec.OP_BINARY, 10))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 126, 0x01, 0x2C), WebSocketCodec.header(WebSocketCodec.OP_TEXT, 300))
        val big = WebSocketCodec.header(WebSocketCodec.OP_BINARY, 70000)
        assertEquals(10, big.size)
        assertEquals(127, big[1].toInt())
        assertEquals(70000L, (2 until 10).fold(0L) { acc, i -> (acc shl 8) or (big[i].toLong() and 0xFF) })
    }

    @Test
    fun endOfStreamIsNull() {
        assertNull(WebSocketCodec.readFrame(ByteArrayInputStream(ByteArray(0)), 100))
    }

    @Test
    fun parsesRequestLineQueryAndHeaders() {
        val r = HttpRequest.parse("GET /ws?role=pad&c=123456&name=Galaxy%20S21 HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nSec-WebSocket-Key: abc\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/ws", r.path)
        assertEquals(mapOf("role" to "pad", "c" to "123456", "name" to "Galaxy S21"), r.query)
        assertEquals("abc", r.headers["sec-websocket-key"])
        assertTrue(r.isWebSocket)
        assertTrue(!HttpRequest.parse("GET /tv HTTP/1.1\r\n\r\n").isWebSocket)
    }

    // endregion

    // region Servidor de verdade, com um cliente por socket

    private val events = LinkedBlockingQueue<String>()
    private val opened = LinkedBlockingQueue<WsConnection>()
    private var server: RemoteServer? = null

    private fun startServer(): Int {
        val s = RemoteServer(object : RemoteServer.Handler {
            override fun serve(request: HttpRequest, remoteAddress: String) = HttpResponse.text(200, "page ${request.path}")
            override fun authorize(request: HttpRequest, remoteAddress: String) =
                if (request.query["c"] == "42") null else HttpResponse.text(403, "")
            override fun onOpen(connection: WsConnection, request: HttpRequest) { opened.put(connection); events.put("open") }
            override fun onText(connection: WsConnection, text: String) { events.put("text:$text"); connection.sendText("echo:$text") }
            override fun onClose(connection: WsConnection) { events.put("close") }
        })
        server = s
        // Porta 0: livre qualquer, para não depender da 8080 da máquina de testes.
        return s.start(0)
    }

    @After
    fun tearDown() { server?.stop() }

    @Test
    fun servesPlainHttp() {
        val port = startServer()
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET /tv HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.endsWith("page /tv"))
        }
    }

    @Test
    fun refusesWebSocketWithWrongCode() {
        val port = startServer()
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(upgrade("/ws?c=1").toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(response, response.startsWith("HTTP/1.1 403"))
        }
        assertNull(events.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun webSocketRoundTripAndBinaryPush() {
        val port = startServer()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val out = socket.getOutputStream()
            val input = socket.getInputStream()
            out.write(upgrade("/ws?c=42&role=pad").toByteArray())
            val head = readHead(input)
            assertTrue(head, head.startsWith("HTTP/1.1 101"))
            assertTrue(head, head.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
            assertEquals("open", events.poll(5, TimeUnit.SECONDS))

            // Mensagem de texto partida em dois quadros (FIN só no segundo).
            val first = WebSocketCodec.clientFrame(WebSocketCodec.OP_TEXT, "hel".toByteArray()).also { it[0] = (it[0].toInt() and 0x7F).toByte() }
            out.write(first)
            out.write(WebSocketCodec.clientFrame(WebSocketCodec.OP_CONTINUATION, "lo".toByteArray()))
            assertEquals("text:hello", events.poll(5, TimeUnit.SECONDS))
            val echo = WebSocketCodec.readFrame(input, 1 shl 20)!!
            assertEquals(WebSocketCodec.OP_TEXT, echo.opcode)
            assertEquals("echo:hello", echo.payload.toString(Charsets.UTF_8))

            // O servidor empurra binário grande (quadro de vídeo) sem o cliente pedir.
            val connection = opened.poll(5, TimeUnit.SECONDS)!!
            val video = ByteArray(200_000) { it.toByte() }
            connection.sendBinary(video)
            val pushed = WebSocketCodec.readFrame(input, 1 shl 20)!!
            assertEquals(WebSocketCodec.OP_BINARY, pushed.opcode)
            assertArrayEquals(video, pushed.payload)

            // Ping do cliente recebe pong com o mesmo conteúdo.
            out.write(WebSocketCodec.clientFrame(WebSocketCodec.OP_PING, byteArrayOf(1, 2, 3)))
            val pong = WebSocketCodec.readFrame(input, 100)!!
            assertEquals(WebSocketCodec.OP_PONG, pong.opcode)
            assertArrayEquals(byteArrayOf(1, 2, 3), pong.payload)

            out.write(WebSocketCodec.clientFrame(WebSocketCodec.OP_CLOSE, ByteArray(0)))
            assertEquals("close", events.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun abruptDisconnectStillCallsOnClose() {
        val port = startServer()
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(upgrade("/ws?c=42").toByteArray())
            readHead(socket.getInputStream())
            assertEquals("open", events.poll(5, TimeUnit.SECONDS))
        }
        assertEquals("close", events.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun slowRequestHeadIsDroppedAtTheDeadline() {
        val port = startServer()
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            val out = socket.getOutputStream()
            out.write("GET /tv HTTP/1.1\r\n".toByteArray())
            // Um byte por segundo: cada leitura chega antes do soTimeout, mas o pedido inteiro passa do prazo.
            val started = System.currentTimeMillis()
            val closed = runCatching {
                repeat(12) {
                    Thread.sleep(1000)
                    out.write('X'.code)
                    out.flush()
                }
            }.isFailure || socket.getInputStream().read() < 0
            assertTrue("connection should be closed", closed)
            assertTrue(System.currentTimeMillis() - started < 12_000)
        }
    }

    @Test
    fun capsConnectionsPerAddress() {
        val port = startServer()
        // Conexões paradas do mesmo endereço ocupam as vagas dele até o prazo do pedido.
        val idle = (1..RemoteServer.MAX_PER_ADDRESS).map { Socket("127.0.0.1", port) }
        try {
            Thread.sleep(300)
            Socket("127.0.0.1", port).use { extra ->
                extra.soTimeout = 2000
                assertEquals(-1, extra.getInputStream().read())
            }
        } finally {
            idle.forEach { it.close() }
        }
        // Soltas as vagas, o mesmo endereço volta a ser atendido.
        Thread.sleep(300)
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET /tv HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(response, response.startsWith("HTTP/1.1 200 OK"))
        }
    }

    private fun upgrade(target: String) =
        "GET $target HTTP/1.1\r\nHost: x\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"

    private fun readHead(input: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) sb.append(input.read().toChar())
        return sb.toString()
    }

    // endregion
}
