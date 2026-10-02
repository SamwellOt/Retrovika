package com.retrovika.app.remote

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Servidor HTTP + WebSocket da rede local: entrega as páginas do controle e da tela e mantém uma conexão
 * WebSocket com cada uma. Uma thread por conexão; o envio tem thread e fila próprias, para um aparelho
 * lento não travar o encoder nem os outros.
 */
class RemoteServer(private val handler: Handler) {

    interface Handler {
        /** Pedido HTTP comum (páginas, QR code). */
        fun serve(request: HttpRequest, remoteAddress: String): HttpResponse
        /** Resposta de erro para recusar o WebSocket, ou null para aceitar. */
        fun authorize(request: HttpRequest, remoteAddress: String): HttpResponse?
        fun onOpen(connection: WsConnection, request: HttpRequest)
        fun onText(connection: WsConnection, text: String)
        fun onClose(connection: WsConnection)
    }

    private var server: ServerSocket? = null
    private val running = AtomicBoolean(false)
    /** Conexões com thread própria agora (HTTP e WebSocket). */
    private val active = AtomicInteger()
    private val connections = java.util.Collections.synchronizedSet(mutableSetOf<WsConnection>())
    /** Conexões abertas por endereço (HTTP e WebSocket). */
    private val perAddress = mutableMapOf<String, Int>()

    val port: Int get() = server?.localPort ?: 0

    /**
     * Tenta [preferredPort] (endereço fácil de digitar na TV) e cai para uma porta livre qualquer.
     * [host] é o endereço da rede local: ouvir em todas as interfaces deixaria o servidor aberto também na
     * rede da operadora (IPv6 público), onde o limite de códigos errados por endereço não segura ninguém.
     */
    fun start(preferredPort: Int, host: String? = null): Int {
        val socket = runCatching { bind(host, preferredPort) }.getOrElse { bind(host, 0) }
        server = socket
        running.set(true)
        Thread({ acceptLoop(socket) }, "RemoteServer-accept").apply { isDaemon = true }.start()
        Thread({ pingLoop() }, "RemoteServer-ping").apply { isDaemon = true }.start()
        return socket.localPort
    }

    private fun bind(host: String?, port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(if (host != null) InetSocketAddress(host, port) else InetSocketAddress(port))
            return socket
        } catch (e: Exception) {
            // Sem isso o socket que não conseguiu a porta ficava aberto (um descritor perdido a cada tentativa).
            runCatching { socket.close() }
            throw e
        }
    }

    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        synchronized(connections) { connections.toList() }.forEach { it.close() }
    }

    /**
     * O navegador responde ao ping do servidor (o protocolo obriga), então cada conexão recebe algo a cada
     * [PING_INTERVAL_MS]. Um celular que saiu do Wi-Fi sem fechar a conexão para de responder e cai pelo
     * [IDLE_TIMEOUT_MS] da leitura, em vez de ficar segurando a vaga de jogador para sempre.
     */
    private fun pingLoop() {
        while (running.get()) {
            try {
                Thread.sleep(PING_INTERVAL_MS)
            } catch (_: InterruptedException) {
                return
            }
            synchronized(connections) { connections.toList() }.forEach {
                // Escrita parada há tempo demais (o aparelho não lê mais, mas mantém a conexão): o socket não
                // tem prazo de escrita, então fecha daqui, o que destrava a thread de envio.
                if (it.writeStalledFor() > WRITE_TIMEOUT_MS) it.close()
                else it.send(WebSocketCodec.OP_PING, ByteArray(0))
            }
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            // Uma thread por conexão: sem teto, um aparelho abrindo milhares de conexões paradas esgotaria a
            // memória, e o OutOfMemoryError nesta thread derrubaria o app com o jogo junto.
            val remote = client.inetAddress?.hostAddress.orEmpty()
            if (active.incrementAndGet() > MAX_CONNECTIONS) {
                active.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            // Teto por endereço também: um só aparelho não ocupa todas as vagas e deixa os outros de fora.
            if (!acquireFor(remote)) {
                active.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            try {
                Thread({
                    try { handle(client, remote) } finally { releaseFor(remote); active.decrementAndGet() }
                }, "RemoteServer-conn").apply { isDaemon = true }.start()
            } catch (t: Throwable) {
                releaseFor(remote)
                active.decrementAndGet()
                runCatching { client.close() }
            }
        }
    }

    private fun acquireFor(remote: String): Boolean = synchronized(perAddress) {
        val n = perAddress[remote] ?: 0
        if (n >= MAX_PER_ADDRESS) return false
        perAddress[remote] = n + 1
        true
    }

    private fun releaseFor(remote: String): Unit = synchronized(perAddress) {
        val n = (perAddress[remote] ?: 1) - 1
        if (n <= 0) perAddress.remove(remote) else perAddress[remote] = n
    }

    private fun handle(socket: Socket, remote: String) {
        try {
            socket.tcpNoDelay = true
            // Prazo para o pedido inteiro, não por leitura: um byte a cada poucos segundos (slowloris) seguraria
            // a thread e a vaga para sempre só com o soTimeout.
            val deadlineInput = DeadlineInputStream(socket, socket.getInputStream())
            deadlineInput.deadline = System.currentTimeMillis() + HEAD_TIMEOUT_MS
            val input = BufferedInputStream(deadlineInput)
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = HttpRequest.read(input) ?: return socket.close()
            if (!request.isWebSocket) {
                output.write(handler.serve(request, remote).bytes())
                output.flush()
                socket.close()
                return
            }
            handler.authorize(request, remote)?.let { refusal ->
                output.write(refusal.bytes())
                output.flush()
                socket.close()
                return
            }
            val accept = WebSocketCodec.acceptKey(request.headers.getValue("sec-websocket-key"))
            output.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            output.flush()
            // Conectado, a tela só recebe e o controle pode ficar parado: quem mantém a leitura viva é o
            // pong do navegador ao nosso ping (pingLoop). O prazo do pedido não vale mais: a conexão dura o jogo.
            deadlineInput.deadline = Long.MAX_VALUE
            socket.soTimeout = IDLE_TIMEOUT_MS
            val connection = WsConnection(socket, output, remote)
            connections += connection
            // stop() no meio do aperto de mão já passou pela lista: esta conexão fecha sozinha.
            if (!running.get()) {
                connections -= connection
                connection.close()
                return
            }
            try {
                handler.onOpen(connection, request)
                readLoop(connection, input)
            } finally {
                connections -= connection
                connection.close()
                handler.onClose(connection)
            }
        } catch (e: IOException) {
            runCatching { socket.close() }
        } catch (e: Exception) {
            // Exceção solta numa thread derruba o app inteiro no Android: um pedido estranho de um aparelho da
            // rede não pode fechar o jogo.
            Log.e(TAG, "Connection failed", e)
            runCatching { socket.close() }
        }
    }

    private fun readLoop(connection: WsConnection, input: BufferedInputStream) {
        val message = java.io.ByteArrayOutputStream()
        var messageOpcode = 0
        while (!connection.closed) {
            val frame = WebSocketCodec.readFrame(input, MAX_MESSAGE) ?: return
            when (frame.opcode) {
                WebSocketCodec.OP_CLOSE -> return
                WebSocketCodec.OP_PING -> connection.send(WebSocketCodec.OP_PONG, frame.payload)
                WebSocketCodec.OP_PONG -> Unit
                WebSocketCodec.OP_TEXT, WebSocketCodec.OP_BINARY, WebSocketCodec.OP_CONTINUATION -> {
                    if (frame.opcode != WebSocketCodec.OP_CONTINUATION) {
                        message.reset()
                        messageOpcode = frame.opcode
                    }
                    message.write(frame.payload)
                    if (message.size() > MAX_MESSAGE) throw IOException("Message too large")
                    if (frame.fin && messageOpcode == WebSocketCodec.OP_TEXT) {
                        handler.onText(connection, message.toString(Charsets.UTF_8.name()))
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "RemoteServer"
        /** Até 4 jogadores e algumas telas, com folga para os pedidos HTTP da página e do QR. */
        private const val MAX_CONNECTIONS = 64
        /**
         * Por endereço: a tela (WebSocket e os pedidos da página, que o navegador às vezes abre de antemão) e um
         * controle na mesma máquina cabem com folga.
         */
        internal const val MAX_PER_ADDRESS = 10
        /** Para receber o pedido inteiro (linha e cabeçalhos), somando todas as leituras. */
        private const val HEAD_TIMEOUT_MS = 5_000
        private const val WRITE_TIMEOUT_MS = 15_000L
        private const val PING_INTERVAL_MS = 10_000L
        private const val IDLE_TIMEOUT_MS = 35_000
        private const val MAX_MESSAGE = 64 * 1024
    }
}

/** Uma conexão WebSocket aberta. Envio assíncrono: [send] só enfileira. */
class WsConnection internal constructor(
    private val socket: Socket,
    private val output: BufferedOutputStream,
    val remoteAddress: String,
) {
    val id: Int = ids.incrementAndGet()

    /** Estado do lado do app (papel, jogador, fluxo de vídeo). */
    @Volatile var attachment: Any? = null

    @Volatile var closed = false
        private set

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val pending = AtomicLong(0)

    /** Bytes ainda na fila: o vídeo pula quadros quando o aparelho não acompanha. */
    val queuedBytes: Long get() = pending.get()

    private val writer = Thread({ writeLoop() }, "RemoteServer-write").apply { isDaemon = true; start() }

    fun sendText(text: String) = send(WebSocketCodec.OP_TEXT, text.toByteArray(Charsets.UTF_8))

    fun sendBinary(data: ByteArray) = send(WebSocketCodec.OP_BINARY, data)

    /** Quando a escrita em curso começou (0 = parada esperando a fila). */
    @Volatile private var writingSince = 0L

    /** Há quanto tempo a escrita atual está presa (0 se não há nenhuma). */
    internal fun writeStalledFor(): Long = writingSince.let { if (it == 0L) 0L else System.currentTimeMillis() - it }

    internal fun send(opcode: Int, payload: ByteArray) {
        if (closed) return
        val frame = WebSocketCodec.header(opcode, payload.size) + payload
        // Vídeo e áudio já param de entrar bem antes (RemotePlay olha [queuedBytes]); o resto (texto, segmento
        // de inicialização, ping) não tem freio: um aparelho que parou de ler encheria a memória do app.
        if (pending.get() + frame.size > MAX_PENDING_BYTES || queue.size >= MAX_PENDING_FRAMES) {
            Log.w(TAG, "Send backlog too large, closing $remoteAddress")
            close()
            return
        }
        pending.addAndGet(frame.size.toLong())
        if (!queue.offer(frame)) pending.addAndGet(-frame.size.toLong())
    }

    private fun writeLoop() {
        try {
            while (!closed) {
                val frame = queue.take()
                if (frame === CLOSE) break
                writingSince = System.currentTimeMillis()
                output.write(frame)
                pending.addAndGet(-frame.size.toLong())
                // Só esvazia o buffer quando não há mais nada na fila: quadro de vídeo e áudio seguem juntos.
                if (queue.isEmpty()) output.flush()
                writingSince = 0L
            }
        } catch (_: InterruptedException) {
        } catch (_: SocketException) {
        } catch (_: IOException) {
        }
        close()
    }

    fun close() {
        if (closed) return
        closed = true
        queue.offer(CLOSE)
        runCatching { socket.close() }
    }

    private companion object {
        const val TAG = "RemoteServer"
        /** Bem acima do que o vídeo deixa acumular (~768 KB mais um quadro-chave). */
        const val MAX_PENDING_BYTES = 4L * 1024 * 1024
        const val MAX_PENDING_FRAMES = 2048
        val ids = AtomicInteger(0)
        val CLOSE = ByteArray(0)
    }
}

/**
 * Entrada com prazo total: antes de cada leitura, o soTimeout do socket vira o tempo que ainda resta até
 * [deadline]. Com [deadline] em Long.MAX_VALUE vale o soTimeout que estiver no socket.
 */
internal class DeadlineInputStream(private val socket: Socket, private val raw: InputStream) : InputStream() {
    @Volatile var deadline: Long = Long.MAX_VALUE

    override fun read(): Int {
        arm()
        return raw.read()
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        arm()
        return raw.read(b, off, len)
    }

    override fun available(): Int = raw.available()

    override fun close() = raw.close()

    private fun arm() {
        val d = deadline
        if (d == Long.MAX_VALUE) return
        val left = d - System.currentTimeMillis()
        if (left <= 0) throw SocketTimeoutException("Request head too slow")
        socket.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
