package com.retrovika.app.core.share

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Transferência direta entre dois aparelhos na mesma rede (Wi-Fi ou roteador do celular), sem servidor.
 * Soquete TCP puro em vez de HTTP: o Android bloqueia HTTP sem TLS, e não há certificado para um IP local.
 *
 * Protocolo: quem recebe manda o token numa linha; quem envia responde com o tamanho (8 bytes) e os dados.
 */
object LanTransfer {

    /** IPv4 locais do aparelho, primeiro os de Wi-Fi e do roteador do celular (wlan, ap, swlan). */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { i -> if (listOf("wlan", "ap", "swlan", "eth").any { i.name.startsWith(it) }) 0 else 1 }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress.orEmpty() } }
            .filter { it.isNotEmpty() }
            .distinct()
    }.getOrDefault(emptyList())

    fun newToken(): String {
        val bytes = ByteArray(9).also { SecureRandom().nextBytes(it) }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    /** Serve [payload] a quem mandar o [token], até [close]. */
    class Server(private val payload: ByteArray, private val token: String) : Closeable {
        private val socket = ServerSocket(0)
        val port: Int get() = socket.localPort
        @Volatile var served = 0
            private set

        init {
            thread(name = "retrovika-share", isDaemon = true) {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { serve(client) }
                }
            }
        }

        private fun serve(client: Socket) = runCatching {
            client.use { c ->
                c.soTimeout = 10_000
                val input = DataInputStream(c.getInputStream())
                val line = input.readUtfLine()
                val out = DataOutputStream(c.getOutputStream().buffered())
                if (line != token) { out.writeLong(-1); out.flush(); return@use }
                out.writeLong(payload.size.toLong())
                out.write(payload)
                out.flush()
                served++
            }
        }

        override fun close() { runCatching { socket.close() } }
    }

    /** Baixa o que o [Server] em um dos [hosts] serve; tenta cada endereço até um responder. */
    suspend fun fetch(hosts: List<String>, port: Int, token: String, maxSize: Long, onProgress: (Long, Long) -> Unit = { _, _ -> }): ByteArray =
        withContext(Dispatchers.IO) {
            var last: Throwable? = null
            for (host in hosts) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress(host, port), 4_000)
                        s.soTimeout = 15_000
                        val out = s.getOutputStream()
                        out.write("$token\n".toByteArray()); out.flush()
                        val input = DataInputStream(s.getInputStream().buffered())
                        val size = input.readLong()
                        if (size < 0) throw IOException("token")
                        if (size > maxSize) throw IOException("size $size")
                        val data = ByteArray(size.toInt())
                        var read = 0
                        while (read < data.size) {
                            val n = input.read(data, read, minOf(64 * 1024, data.size - read))
                            if (n < 0) throw IOException("eof")
                            read += n
                            onProgress(read.toLong(), size)
                        }
                        return@withContext data
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    last = t
                }
            }
            throw last ?: IOException("no host")
        }
}

/** Lê uma linha curta (o token) sem o BufferedReader, que leria além dela. */
internal fun DataInputStream.readUtfLine(max: Int = 256): String {
    val sb = StringBuilder()
    while (sb.length < max) {
        val b = read()
        if (b < 0 || b == '\n'.code) break
        sb.append(b.toChar())
    }
    return sb.toString().trim()
}

/**
 * Links `retrovika://` que os QR codes levam. A câmera do celular também os abre, e o Retrovika atende.
 * - `retrovika://state?h=ip1,ip2&p=porta&t=token&n=título` — um estado para baixar.
 * - `retrovika://netplay?h=ip1,ip2&p=porta&t=token&n=título&s=console` — uma partida para entrar.
 */
sealed interface RetrovikaLink {
    val hosts: List<String>
    val port: Int
    val token: String
    val title: String

    data class State(override val hosts: List<String>, override val port: Int, override val token: String, override val title: String) : RetrovikaLink
    data class Netplay(override val hosts: List<String>, override val port: Int, override val token: String, override val title: String, val systemId: String) : RetrovikaLink

    fun toUri(): String {
        val kind = if (this is Netplay) "netplay" else "state"
        val b = Uri.Builder().scheme(SCHEME).authority(kind)
            .appendQueryParameter("h", hosts.joinToString(","))
            .appendQueryParameter("p", port.toString())
            .appendQueryParameter("t", token)
            .appendQueryParameter("n", title.take(60))
        if (this is Netplay) b.appendQueryParameter("s", systemId)
        return b.build().toString()
    }

    companion object {
        const val SCHEME = "retrovika"

        fun parse(text: String): RetrovikaLink? = runCatching {
            val uri = Uri.parse(text.trim())
            if (uri.scheme != SCHEME) return null
            val hosts = uri.getQueryParameter("h")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val port = uri.getQueryParameter("p")?.toIntOrNull() ?: return null
            val token = uri.getQueryParameter("t") ?: return null
            val title = uri.getQueryParameter("n").orEmpty()
            if (hosts.isEmpty()) return null
            when (uri.authority) {
                "state" -> State(hosts, port, token, title)
                "netplay" -> Netplay(hosts, port, token, title, uri.getQueryParameter("s").orEmpty())
                else -> null
            }
        }.getOrNull()
    }
}
