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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

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
    private var resyncJob: Job? = null
    private var monitor: Job? = null
    /**
     * Uma escrita por vez no soquete de controle: sem isto o BYE podia cair no meio dos bytes de um STATE.
     * Lock de thread (não Mutex) porque as escritas são bloqueantes e o BYE sai de uma thread própria.
     */
    private val writeLock = ReentrantLock()
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
                withContext(Dispatchers.IO) { writeControl { out.writeLineAscii("OK ${NetplayProtocol.DELAY_FRAMES}") } }
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
        // Até o nativo assumir o fd, qualquer saída (inclusive cancelamento) precisa fechá-lo.
        val v = try {
            if (!resumeEmulation()) throw IOException("not running")
            view() ?: throw IOException("no view")
        } catch (t: Throwable) {
            closeFd(fd)
            throw t
        }
        val round = ++epoch
        // Sem cancelamento no meio: com a partida já iniciada o fd é do nativo, e fechá-lo aqui também
        // fecharia duas vezes (talvez um descritor já reaproveitado por outra coisa).
        val state = withContext(NonCancellable + Dispatchers.Default) { v.serializeAndStartNetplay(fd, 0, NetplayProtocol.DELAY_FRAMES, round) }
        if (state == null || state.isEmpty()) { closeFd(fd); throw IOException("state") }
        withContext(Dispatchers.IO) {
            writeControl {
                // A rodada vai junto: o convidado pode ser uma Activity nova (contador zerado), e o nativo
                // descarta todo pacote de outra rodada; sem isso a segunda partida da sessão travaria.
                out.writeLineAscii("STATE ${state.size} $round")
                out.write(state)
                out.flush()
            }
        }
        ui = NetplayUi.Playing(1)
        startMonitor()
        message(R.string.netplay_connected_host)
    }

    /** Só o anfitrião: manda o estado atual de novo e os dois recomeçam dele. */
    fun resync() {
        val server = host ?: return
        val out = controlOut ?: return
        if (!playing || resyncJob?.isActive == true) return
        resyncJob = scope.launch {
            try {
                view()?.let { v -> withContext(Dispatchers.Default) { v.stopNetplay() } }
                ui = NetplayUi.Connecting
                withContext(Dispatchers.IO) { writeControl { out.writeLineAscii("RESYNC") } }
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
                // Prazo no próprio soquete: um withTimeout não interrompe um read() bloqueante. Estourar o prazo
                // lança SocketTimeoutException, que cai no catch abaixo como falha ao entrar.
                val ok = withContext(Dispatchers.IO) {
                    socket.soTimeout = JOIN_TIMEOUT_MS
                    try { input.readLineAscii() } finally { socket.soTimeout = 0 }
                }
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
        val (state, round) = try {
            withContext(Dispatchers.IO) {
                val header = input.readLineAscii()
                val parts = header.split(' ')
                val size = parts.getOrNull(1)?.toIntOrNull()
                val round = parts.getOrNull(2)?.toIntOrNull()
                if (parts[0] != "STATE" || size == null || size <= 0 || size > NetplayProtocol.MAX_STATE || round == null) throw IOException(header)
                ByteArray(size).also { input.readFully(it) } to round
            }
        } catch (t: Throwable) {
            closeFd(fd)
            throw t
        }
        // A rodada é a do anfitrião: o nativo descarta os pacotes de outra rodada.
        epoch = round
        val v = try {
            if (!resumeEmulation()) throw IOException("not running")
            view() ?: throw IOException("no view")
        } catch (t: Throwable) {
            closeFd(fd)
            throw t
        }
        val ok = withContext(NonCancellable + Dispatchers.Default) { v.unserializeAndStartNetplay(state, fd, 1, delayFrames, round) }
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
                    status == -2L -> {
                        // Numa ressincronização o anfitrião fecha a entrada antes de o RESYNC chegar aqui:
                        // só é queda se, passado um instante, a mesma partida continua sem conexão.
                        delay(RESYNC_GRACE_MS)
                        if (ui === current && view()?.netplayStatus() == -2L) { end(R.string.netplay_lost); return@launch }
                    }
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
        resyncJob?.cancel()
        resyncJob = null
        job?.cancel()
        host?.close()
        host = null
        control = null
        controlOut = null
        // Thread própria, não o [scope]: no onDestroy da Activity o lifecycleScope já está cancelado e
        // nem o BYE nem o fechamento do soquete aconteceriam (o outro lado só cairia pelo prazo da rede).
        if (socket != null) {
            thread(name = "retrovika-netplay-bye", isDaemon = true) {
                // Um STATE grande ainda saindo: espera um pouco pela vez; senão fecha sem o BYE.
                if (out != null && runCatching { writeLock.tryLock(BYE_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrDefault(false)) {
                    try { runCatching { out.writeLineAscii("BYE") } } finally { writeLock.unlock() }
                }
                runCatching { socket.close() }
            }
        }
        if (v != null) scope.launch(Dispatchers.Default) { runCatching { v.stopNetplay() } }
        reason?.let(message)
    }

    private fun closeFd(fd: Int) = runCatching { ParcelFileDescriptor.adoptFd(fd).close() }

    /** Escreve no soquete de controle sem se misturar a outra escrita (chamar fora da thread principal). */
    private inline fun writeControl(block: () -> Unit) {
        writeLock.lock()
        try { block() } finally { writeLock.unlock() }
    }

    companion object {
        private const val WAIT_NOTICE_MS = 700L
        private const val RESYNC_GRACE_MS = 1_000L
        private const val JOIN_TIMEOUT_MS = 10_000
        private const val BYE_WAIT_MS = 2_000L
    }
}
