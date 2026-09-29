package com.retrovika.app.emulation

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retrovika.app.R
import com.retrovika.app.core.netplay.NetplayGuest
import com.retrovika.app.core.netplay.NetplayHost
import com.retrovika.app.core.netplay.NetplayProtocol
import com.retrovika.app.core.netplay.NetplayProtocol.readLineAscii
import com.retrovika.app.core.netplay.NetplayProtocol.writeLineAscii
import com.retrovika.app.core.share.LanTransfer
import com.retrovika.app.core.share.RetrovikaLink
import com.retrovika.app.core.share.StateManifest
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/** Situação da partida em rede, para a tela. */
sealed interface NetplayUi {
    /** Anfitrião com o QR code na tela, esperando o outro jogador. */
    data class Hosting(val link: String) : NetplayUi
    data object Connecting : NetplayUi
    /** [player]: 1 no anfitrião, 2 no convidado. [waiting]: a entrada do outro está atrasada. */
    data class Playing(val player: Int, val waiting: Boolean = false) : NetplayUi
}

/**
 * Partida em rede local de um jogo aberto. O anfitrião mostra um QR code; o convidado, que já abriu o
 * mesmo jogo pelo QR, recebe o estado do anfitrião e os dois seguem em lockstep (netplay.cpp). Qualquer
 * um pode pausar: o outro fica esperando. "Ressincronizar" manda o estado de novo (núcleos que não são
 * totalmente determinísticos podem se desencontrar com o tempo).
 */
class NetplayController(
    private val scope: CoroutineScope,
    private val view: () -> GLRetroView?,
    /** Fecha o menu e espera a emulação voltar a rodar (o estado só sai da thread de emulação ativa). */
    private val resumeEmulation: suspend () -> Boolean,
    private val message: (Int) -> Unit,
) {
    var ui by mutableStateOf<NetplayUi?>(null)
        private set
    val playing: Boolean get() = ui is NetplayUi.Playing
    val isHost: Boolean get() = host != null

    private var host: NetplayHost? = null
    private var control: Socket? = null
    private var controlOut: DataOutputStream? = null
    private var job: Job? = null
    private var monitor: Job? = null
    private var epoch = 0

    // region Anfitrião

    fun startHosting(manifest: StateManifest, title: String) {
        if (ui != null) return
        val hosts = LanTransfer.localAddresses()
        if (hosts.isEmpty()) { message(R.string.netplay_no_network); return }
        val token = LanTransfer.newToken()
        val server = runCatching { NetplayHost(manifest, token) }.getOrElse { message(R.string.netplay_no_network); return }
        host = server
        ui = NetplayUi.Hosting(RetrovikaLink.Netplay(hosts, server.port, token, title, manifest.systemId).toUri())
        job = scope.launch {
            try {
                val socket = server.awaitJoin()
                socket.soTimeout = 0
                control = socket
                val out = DataOutputStream(socket.getOutputStream()).also { controlOut = it }
                val input = DataInputStream(socket.getInputStream())
                withContext(Dispatchers.IO) { out.writeLineAscii("OK ${NetplayProtocol.DELAY_FRAMES}") }
                ui = NetplayUi.Connecting
                hostRound(server, out)
                listen(input)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                end(R.string.netplay_failed)
            }
        }
    }

    private suspend fun hostRound(server: NetplayHost, out: DataOutputStream) {
        val fd = NetplayProtocol.detach(server.awaitInput())
        if (!resumeEmulation()) { closeFd(fd); throw IOException("not running") }
        val v = view() ?: run { closeFd(fd); throw IOException("no view") }
        val state = withContext(Dispatchers.Default) { v.serializeAndStartNetplay(fd, 0, NetplayProtocol.DELAY_FRAMES, ++epoch) }
        if (state == null || state.isEmpty()) { closeFd(fd); throw IOException("state") }
        withContext(Dispatchers.IO) {
            out.writeLineAscii("STATE ${state.size}")
            out.write(state)
            out.flush()
        }
        ui = NetplayUi.Playing(1)
        startMonitor()
        message(R.string.netplay_connected_host)
    }

    /** Só o anfitrião: manda o estado atual de novo e os dois recomeçam dele. */
    fun resync() {
        val server = host ?: return
        val out = controlOut ?: return
        if (!playing) return
        scope.launch {
            try {
                view()?.let { v -> withContext(Dispatchers.Default) { v.stopNetplay() } }
                ui = NetplayUi.Connecting
                withContext(Dispatchers.IO) { out.writeLineAscii("RESYNC") }
                hostRound(server, out)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                end(R.string.netplay_failed)
            }
        }
    }

    // endregion

    // region Convidado

    fun join(hostAddress: String, port: Int, token: String) {
        if (ui != null) return
        ui = NetplayUi.Connecting
        job = scope.launch {
            try {
                val socket = NetplayGuest.connect(hostAddress, port, "JOIN $token")
                control = socket
                controlOut = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                val ok = withContext(Dispatchers.IO) { withTimeout(10_000) { input.readLineAscii() } }
                val delayFrames = ok.removePrefix("OK ").toIntOrNull()
                if (!ok.startsWith("OK") || delayFrames == null) throw IOException(ok)
                guestRound(hostAddress, port, token, input, delayFrames)
                // Depois de entrar: o anfitrião pode pedir para ressincronizar ou sair.
                while (isActive) {
                    val line = withContext(Dispatchers.IO) { input.readLineAscii() }
                    when (line) {
                        "RESYNC" -> {
                            view()?.let { v -> withContext(Dispatchers.Default) { v.stopNetplay() } }
                            ui = NetplayUi.Connecting
                            guestRound(hostAddress, port, token, input, delayFrames)
                        }
                        "BYE" -> { end(R.string.netplay_other_left); return@launch }
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                end(if (ui is NetplayUi.Playing) R.string.netplay_lost else R.string.netplay_join_failed)
            }
        }
    }

    private suspend fun guestRound(hostAddress: String, port: Int, token: String, input: DataInputStream, delayFrames: Int) {
        val fd = NetplayProtocol.detach(NetplayGuest.connect(hostAddress, port, "INPUT $token"))
        val state = try {
            withContext(Dispatchers.IO) {
                val header = input.readLineAscii()
                val size = header.removePrefix("STATE ").toIntOrNull()
                if (!header.startsWith("STATE") || size == null || size <= 0 || size > NetplayProtocol.MAX_STATE) throw IOException(header)
                ByteArray(size).also { input.readFully(it) }
            }
        } catch (t: Throwable) {
            closeFd(fd)
            throw t
        }
        if (!resumeEmulation()) { closeFd(fd); throw IOException("not running") }
        val v = view() ?: run { closeFd(fd); throw IOException("no view") }
        val ok = withContext(Dispatchers.Default) { v.unserializeAndStartNetplay(state, fd, 1, delayFrames, ++epoch) }
        if (!ok) { closeFd(fd); throw IOException("state") }
        ui = NetplayUi.Playing(2)
        startMonitor()
        message(R.string.netplay_connected_guest)
    }

    // endregion

    /** O anfitrião escuta o convidado sair. */
    private suspend fun listen(input: DataInputStream) {
        try {
            while (scope.isActive) {
                val line = withContext(Dispatchers.IO) { input.readLineAscii() }
                if (line == "BYE") { end(R.string.netplay_other_left); return }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            if (ui != null) end(R.string.netplay_lost)
        }
    }

    /** Acompanha o núcleo: conexão perdida encerra a partida; entrada atrasada vira o aviso de espera. */
    private fun startMonitor() {
        monitor?.cancel()
        monitor = scope.launch {
            while (isActive) {
                delay(300)
                val current = ui as? NetplayUi.Playing ?: continue
                val status = view()?.netplayStatus() ?: -1L
                when {
                    status == -2L -> { end(R.string.netplay_lost); return@launch }
                    status >= 0 -> {
                        val waiting = status > WAIT_NOTICE_MS
                        if (waiting != current.waiting) ui = current.copy(waiting = waiting)
                    }
                }
            }
        }
    }

    /** Encerra a partida (o jogo continua só neste aparelho) e avisa [reason], se houver. */
    fun end(reason: Int? = null) {
        if (ui == null && host == null) return
        val out = controlOut
        val socket = control
        val v = view()
        ui = null
        monitor?.cancel()
        job?.cancel()
        host?.close()
        host = null
        control = null
        controlOut = null
        scope.launch(Dispatchers.IO) {
            runCatching { out?.writeLineAscii("BYE") }
            runCatching { socket?.close() }
        }
        if (v != null) scope.launch(Dispatchers.Default) { runCatching { v.stopNetplay() } }
        reason?.let(message)
    }

    private fun closeFd(fd: Int) = runCatching { ParcelFileDescriptor.adoptFd(fd).close() }

    companion object {
        private const val WAIT_NOTICE_MS = 700L
    }
}
