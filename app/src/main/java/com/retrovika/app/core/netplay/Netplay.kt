package com.retrovika.app.core.netplay

import android.os.ParcelFileDescriptor
import com.retrovika.app.core.share.StateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Partida em rede local, parte Java: pareamento, troca do estado inicial e mensagens de controle. A
 * entrada de cada quadro vai por um segundo soquete, entregue ao LibretroDroid (netplay.cpp).
 *
 * Linhas de texto no soquete de controle:
 * - convidado → anfitrião: `INFO <token>` (antes de abrir o jogo: qual jogo e núcleo) ou `JOIN <token>`;
 * - anfitrião → convidado: `MANIFEST <json>`, `OK <atraso>`, `STATE <bytes> <rodada>` seguido do estado, `RESYNC`;
 * - qualquer um: `BYE`.
 * O soquete de entrada começa com `INPUT <token>`.
 */
object NetplayProtocol {
    const val VERSION = 1
    /** Quadros de atraso de entrada: ~67 ms a 60 fps, folga para a latência de um Wi-Fi comum. */
    const val DELAY_FRAMES = 4
    const val MAX_STATE = 512 * 1024 * 1024

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Prazo total para a primeira linha de quem conecta (token, INFO/JOIN/INPUT, MANIFEST). */
    const val HANDSHAKE_MS = 10_000L
    /** Conexões ainda no aperto de mão ao mesmo tempo; as demais são fechadas de cara. */
    const val MAX_HANDSHAKES = 8

    /**
     * Lê uma linha até '\n' (no máximo [max] bytes) e decodifica em UTF-8: o MANIFEST leva o título do
     * jogo, que pode ter acentos ou japonês. Lê byte a byte para não consumir o que vem depois da linha.
     */
    fun DataInputStream.readLineAscii(max: Int = 64 * 1024): String = readLineUtf8(max) { read() }

    /**
     * Como [readLineAscii], com prazo total de [timeoutMs]: o soTimeout vale por leitura, e um par que
     * manda um byte a cada poucos segundos seguraria a thread por horas. Restaura o soTimeout anterior.
     */
    fun Socket.readLineWithin(timeoutMs: Long, max: Int = 64 * 1024): String {
        val input = getInputStream()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        val previous = soTimeout
        try {
            return readLineUtf8(max) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw SocketTimeoutException("deadline")
                soTimeout = left.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                input.read()
            }
        } finally {
            runCatching { soTimeout = previous }
        }
    }

    internal fun readLineUtf8(max: Int, next: () -> Int): String {
        val buf = ByteArrayOutputStream()
        while (buf.size() < max) {
            val b = next()
            if (b < 0) { if (buf.size() == 0) throw IOException("eof"); break }
            if (b == '\n'.code) break
            buf.write(b)
        }
        return buf.toByteArray().decodeToString().trim()
    }

    fun DataOutputStream.writeLineAscii(line: String) {
        write((line + "\n").toByteArray(Charsets.UTF_8))
        flush()
    }

    /** Entrega o soquete ao código nativo: devolve um descritor próprio (duplicado) e fecha o objeto Java. */
    fun detach(socket: Socket): Int {
        socket.tcpNoDelay = true
        val fd = ParcelFileDescriptor.fromSocket(socket).detachFd()
        runCatching { socket.close() }
        return fd
    }
}

/** Quem convida: escuta na rede local enquanto o QR code está na tela e durante a partida. */
class NetplayHost(private val manifest: StateManifest, private val token: String) : Closeable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    private val joins = LinkedBlockingQueue<Socket>()
    private val inputs = LinkedBlockingQueue<Socket>()
    @Volatile private var closed = false
    private val handshaking = AtomicInteger()

    init {
        thread(name = "retrovika-netplay-host", isDaemon = true) {
            while (!closed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                // Limite de conexões no aperto de mão: alguém na rede abrindo muitas não esgota as threads.
                if (handshaking.incrementAndGet() > NetplayProtocol.MAX_HANDSHAKES) {
                    handshaking.decrementAndGet()
                    runCatching { client.close() }
                    continue
                }
                thread(isDaemon = true) {
                    try { route(client) } finally { handshaking.decrementAndGet() }
                }
            }
        }
    }

    private fun route(client: Socket) {
        try {
            client.soTimeout = 10_000
            val out = DataOutputStream(client.getOutputStream())
            with(NetplayProtocol) {
                val (cmd, arg) = client.readLineWithin(HANDSHAKE_MS).split(' ', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (arg != token) { out.writeLineAscii("NO token"); client.close(); return }
                when (cmd) {
                    "INFO" -> {
                        out.writeLineAscii("MANIFEST " + json.encodeToString(StateManifest.serializer(), manifest))
                        client.close()
                    }
                    "JOIN" -> joins.put(client)
                    "INPUT" -> inputs.put(client)
                    else -> client.close()
                }
            }
        } catch (t: Throwable) {
            runCatching { client.close() }
        }
    }

    /** Espera o convidado pedir para entrar (bloqueia; chamar fora da thread principal). */
    suspend fun awaitJoin(): Socket = withContext(Dispatchers.IO) {
        while (!closed) {
            joins.poll(500, TimeUnit.MILLISECONDS)?.let { return@withContext it }
        }
        throw IOException("closed")
    }

    suspend fun awaitInput(timeoutMs: Long = 15_000): Socket = withContext(Dispatchers.IO) {
        inputs.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("input")
    }

    override fun close() {
        closed = true
        runCatching { server.close() }
    }
}

/** Quem entra: fala com o anfitrião a partir do link do QR code. */
object NetplayGuest {
    /** Qual jogo e núcleo o anfitrião está rodando, para achar o jogo na biblioteca antes de abrir. */
    suspend fun info(hosts: List<String>, port: Int, token: String): Pair<String, StateManifest> = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (host in hosts) {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 4_000)
                    s.soTimeout = 8_000
                    with(NetplayProtocol) {
                        DataOutputStream(s.getOutputStream()).writeLineAscii("INFO $token")
                        val line = s.readLineWithin(HANDSHAKE_MS)
                        if (!line.startsWith("MANIFEST ")) throw IOException(line)
                        return@withContext host to json.decodeFromString(StateManifest.serializer(), line.removePrefix("MANIFEST "))
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                last = t
            }
        }
        throw last ?: IOException("no host")
    }

    suspend fun connect(host: String, port: Int, line: String): Socket = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5_000)
        s.tcpNoDelay = true
        with(NetplayProtocol) { DataOutputStream(s.getOutputStream()).writeLineAscii(line) }
        s
    }
}
