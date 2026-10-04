package com.retrovika.app.remote

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.pm.PackageInfoCompat
import com.retrovika.app.R
import com.retrovika.app.emulation.input.PadButton
import com.retrovika.app.emulation.input.PadLayout
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom

/** O que as páginas precisam saber do jogo aberto. */
data class RemoteGame(val title: String, val system: String, val accent: Long, val layout: PadLayout)

/**
 * Jogo pela rede local: outros celulares viram controles (jogadores 2 a 4) abrindo uma página pelo QR code,
 * e um computador ou TV abre outra página que mostra o jogo com som. Nenhum dos dois precisa do app.
 *
 * O servidor continua de pé entre um jogo e outro (a Activity é recriada ao trocar de jogo) e os controles
 * se reconectam sozinhos; ele para quando o usuário desliga ou sai do jogo.
 */
class RemotePlay(private val context: Context) : RemoteServer.Handler {

    enum class Kind { PAD, SCREEN }

    /** Um aparelho conectado, para a lista do menu. [ports] vazio = só assistindo. */
    data class Client(val id: Int, val kind: Kind, val name: String, val ports: List<Int>)

    data class State(
        val running: Boolean = false,
        /** Endereço base, ex.: http://192.168.0.10:8080 */
        val address: String? = null,
        val code: String = "",
        val clients: List<Client> = emptyList(),
        val muteHost: Boolean = true,
        /** GLES 2: o quadro não pode ser copiado para o encoder, só os controles funcionam. */
        val streamSupported: Boolean = true,
        val errorRes: Int? = null,
    ) {
        val padUrl: String? get() = address?.let { "$it/pad?c=$code" }
        val screenCount: Int get() = clients.count { it.kind == Kind.SCREEN }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val code: String = SecureRandom().let { r -> (1..CODE_LENGTH).joinToString("") { r.nextInt(10).toString() } }
    private var server: RemoteServer? = null

    private val lock = Any()
    private val sessions = mutableListOf<Session>()
    /**
     * Porta de cada controle já conectado ("endereço/id/slot"): ao reconectar, volta a ser o mesmo jogador.
     * O id vem do navegador, então a lista tem teto: as entradas mais antigas saem primeiro.
     */
    private val rememberedPorts = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > MAX_REMEMBERED
    }
    /**
     * Endereços expulsos pelo menu. Pelo endereço, não pelo id: o id é o navegador que escolhe, e quem foi
     * expulso voltaria só trocando de id.
     */
    private val kicked = mutableSetOf<String>()
    /** Códigos errados por endereço: (quantidade, início da janela). */
    private val failures = mutableMapOf<String, Pair<Int, Long>>()
    /** Códigos errados de todos os endereços juntos, para quem troca de endereço a cada tentativa. */
    private var globalFailures = 0 to 0L

    /** Lido pelas threads das conexões (teclas) e do áudio. */
    @Volatile private var view: GLRetroView? = null
    private var game: RemoteGame? = null
    private var reservedPorts: () -> Set<Int> = { emptySet() }
    @Volatile private var paused = false

    /** Liga e desliga o encoder: duas telas entrando juntas não podem criar dois. */
    private val streamLock = Any()
    @Volatile private var encoder: VideoEncoder? = null
    @Volatile private var fmp4: Fmp4? = null
    /** Muda a cada encoder: o que o anterior ainda entregar depois de parado é descartado. */
    @Volatile private var streamGeneration = 0
    /** Desde quando a imagem já deveria estar chegando (encoder ligado com o jogo rodando). */
    @Volatile private var videoExpectedSince = 0L
    private val prefs by lazy { context.getSharedPreferences("remote_play", Context.MODE_PRIVATE) }
    /**
     * [VideoEncoder.mode] em uso: sobe quando o encoder morre ou não entrega nada. O que funcionou fica salvo
     * (por versão do app, que pode trazer ajustes novos), para a próxima transmissão já começar nele.
     */
    @Volatile private var encoderMode = -1
    /** O encoder atual já entregou um quadro de imagem (o SPS sozinho não prova que a captura chega nele). */
    @Volatile private var videoArrived = false
    /**
     * Algum modo foi trocado por tempo esgotado, que pode ser só um núcleo lento para mostrar o primeiro
     * quadro: o modo seguinte vale para esta sessão, mas não é salvo.
     */
    @Volatile private var modeFromTimeout = false
    private val encoderModeKey by lazy {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "encoder_mode_${PackageInfoCompat.getLongVersionCode(info)}"
    }
    private var audioPump: Thread? = null
    @Volatile private var lastKeyRequest = 0L

    private val assets = mutableMapOf<String, ByteArray>()

    /**
     * Com algum aparelho conectado, o Wi-Fi do celular não entra em economia de energia: nela os pacotes esperam o
     * próximo beacon do roteador (~100 ms), atrasando a imagem que sai e os botões que chegam. Só enquanto há
     * conexão ([updateWifiLock]): o servidor ligado esperando alguém não precisa do rádio sempre acordado.
     */
    private val wifiLock by lazy {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi.createWifiLock(mode, "Retrovika:RemotePlay").apply { setReferenceCounted(false) }
    }

    // region Controle pelo app

    fun start() {
        synchronized(lock) {
            if (server != null) return
            val address = LanAddress.find()
            if (address == null) {
                _state.update { it.copy(running = false, errorRes = R.string.remote_no_network) }
                return
            }
            val s = RemoteServer(this)
            val port = try {
                s.start(PREFERRED_PORT, host = address)
            } catch (e: Exception) {
                Log.e(TAG, "Server failed", e)
                _state.update { it.copy(running = false, errorRes = R.string.remote_server_failed) }
                return
            }
            server = s
            val base = if (port == 80) "http://$address" else "http://$address:$port"
            _state.update { it.copy(running = true, address = base, code = code, errorRes = null) }
        }
    }

    fun stop() {
        val s = synchronized(lock) {
            val current = server ?: return
            server = null
            sessions.toList().forEach { releasePorts(it) }
            sessions.clear()
            kicked.clear()
            current
        }
        stopStreaming(view)
        s.stop()
        updateWifiLock()
        _state.update { State(muteHost = it.muteHost, streamSupported = it.streamSupported) }
        applyHostAudio()
    }

    /** Liga o jogo que acabou de abrir: os controles recebem o layout novo e a tela volta a receber vídeo. */
    fun attach(view: GLRetroView, game: RemoteGame, reservedPorts: () -> Set<Int>) {
        synchronized(lock) {
            this.view = view
            this.game = game
            this.reservedPorts = reservedPorts
            _state.update { it.copy(streamSupported = view.supportsCapture) }
            sessions.forEach { s -> if (s.kind == Kind.PAD) sendHello(s) else sendInfo(s) }
        }
        updateStreaming()
        applyHostAudio()
    }

    fun detach(view: GLRetroView) {
        synchronized(lock) {
            if (this.view !== view) return
            this.view = null
            // A referência aponta para a Activity do jogo: guardada aqui, ela vazaria até o próximo jogo.
            this.reservedPorts = { emptySet() }
        }
        // Com a view já fora de [view]: o nativo tem que soltar a superfície do encoder mesmo assim.
        stopStreaming(view)
    }

    fun setMuteHost(mute: Boolean) {
        _state.update { it.copy(muteHost = mute) }
        applyHostAudio()
    }

    fun kick(clientId: Int) {
        val session = synchronized(lock) {
            val s = sessions.firstOrNull { it.connection.id == clientId } ?: return
            kicked += s.connection.remoteAddress
            // Sai da lista e solta as portas já: nos instantes até a conexão fechar, o que ela mandar não chega
            // mais ao jogo (onText só aceita sessões da lista).
            sessions -= s
            releasePorts(s)
            s
        }
        closeWith(session.connection, "kicked")
        // onClose não acha mais a sessão na lista: avisa daqui.
        publishClients()
        if (session.kind == Kind.SCREEN) {
            updateStreaming()
            applyHostAudio()
        }
    }

    private fun closeWith(connection: WsConnection, message: String) {
        connection.sendText(json("t" to message))
        // Dá tempo de a mensagem sair antes de fechar.
        Thread { Thread.sleep(300); connection.close() }.start()
    }

    fun setPaused(value: Boolean) {
        val targets = synchronized(lock) {
            if (paused == value) return
            paused = value
            // Pausado, a thread de emulação para e não sai quadro nenhum: o prazo da imagem recomeça ao voltar.
            if (!value) videoExpectedSince = System.currentTimeMillis()
            sessions.toList()
        }
        val msg = json("t" to "paused", "v" to value)
        targets.forEach { it.connection.sendText(msg) }
    }

    /** Vibração do núcleo para o jogador [port]: o celular que é o controle dele vibra. True se era de um deles. */
    fun rumble(port: Int, strength: Float): Boolean {
        if (port == 0) return false
        val target = synchronized(lock) { sessions.firstOrNull { s -> s.kind == Kind.PAD && port in s.slots.values } } ?: return false
        target.connection.sendText(json("t" to "rumble", "s" to strength))
        return true
    }

    // endregion

    // region Servidor

    override fun serve(request: HttpRequest, remoteAddress: String): HttpResponse {
        if (request.method != "GET") return HttpResponse.text(400, "")
        return when (request.path) {
            "/" -> HttpResponse.redirect("/tv")
            "/tv" -> page("tv.html")
            "/pad" -> page("pad.html")
            "/qr.svg" -> {
                // O QR leva o código: responde só a quem já o tem, e errar aqui conta como no WebSocket
                // (sem isso, dava para testar os 6 dígitos por esta rota sem limite nenhum).
                val url = state.value.padUrl
                checkCode(request, remoteAddress)
                    ?: if (url == null) HttpResponse.text(404, "")
                    else HttpResponse(200, "image/svg+xml", QrCodes.svg(url).toByteArray())
            }
            "/favicon.ico" -> HttpResponse(404, "text/plain", ByteArray(0))
            else -> HttpResponse.text(404, "Not found")
        }
    }

    private fun page(name: String): HttpResponse {
        val body = synchronized(assets) {
            assets.getOrPut(name) { context.assets.open("remote/$name").use { it.readBytes() } }
        }
        return HttpResponse(200, "text/html; charset=utf-8", body)
    }

    override fun authorize(request: HttpRequest, remoteAddress: String): HttpResponse? {
        // Fora da trava: um código errado acima do limite espera antes de responder (checkCode).
        checkCode(request, remoteAddress)?.let { return it }
        return synchronized(lock) { if (remoteAddress in kicked) HttpResponse.text(403, "") else null }
    }

    /**
     * Recusa (403/429) ou null se o código confere. O código certo passa sempre, sem olhar contador nenhum:
     * se os erros bloqueassem todo mundo, qualquer aparelho da rede (ou uma página aberta no navegador da TV,
     * com um `<img src=…/qr.svg?c=0>`) deixaria os jogadores de verdade de fora errando de propósito.
     *
     * Seis dígitos: sem limite, dava para adivinhar tentando todos. Por isso o erro acima do limite (por
     * endereço ou de todos juntos, para quem troca de endereço) demora [FAILURE_DELAY_MS] para responder:
     * com o teto de conexões do servidor, isso segura o ritmo de quem chuta. A janela zera sozinha, porque
     * uma TV que ficou tentando com o código de antes (o app reiniciou) não pode ficar lenta para sempre.
     */
    private fun checkCode(request: HttpRequest, remoteAddress: String): HttpResponse? {
        val given = request.query["c"]
        if (given != null && MessageDigest.isEqual(given.toByteArray(), code.toByteArray())) return null
        val limited = synchronized(lock) {
            val now = System.currentTimeMillis()
            val (count, since) = failures[remoteAddress]?.takeIf { now - it.second < FAILURE_WINDOW_MS } ?: (0 to now)
            val (total, totalSince) = globalFailures.takeIf { now - it.second < FAILURE_WINDOW_MS } ?: (0 to now)
            failures.entries.removeAll { now - it.value.second >= FAILURE_WINDOW_MS }
            failures[remoteAddress] = (count + 1) to since
            globalFailures = (total + 1) to totalSince
            count >= MAX_FAILURES || total >= MAX_GLOBAL_FAILURES
        }
        if (!limited) return HttpResponse.text(403, "")
        try {
            Thread.sleep(FAILURE_DELAY_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return HttpResponse.text(429, "")
    }

    override fun onOpen(connection: WsConnection, request: HttpRequest) {
        val kind = if (request.query["role"] == "screen") Kind.SCREEN else Kind.PAD
        val session = Session(connection, kind, clientKey(request, connection.remoteAddress), request.query["name"].orEmpty().take(40))
        connection.attachment = session
        val stale = synchronized(lock) {
            // O mesmo aparelho voltando (o Wi-Fi caiu sem fechar a conexão antiga): a antiga sai já, senão
            // continuaria com a porta dele e o controle voltaria como outro jogador.
            sessions.filter { it.clientKey == session.clientKey && it.kind == kind }.onEach {
                sessions -= it
                releasePorts(it)
            }
        }
        // Se a antiga ainda está viva (outra aba do mesmo navegador), o aviso faz ela parar de reconectar:
        // sem isso, as duas abas tomariam a vaga uma da outra sem fim.
        stale.forEach { closeWith(it.connection, "replaced") }
        synchronized(lock) {
            sessions += session
            if (kind == Kind.PAD) {
                val port = assignPort(session, 0)
                if (port == null) {
                    sessions -= session
                    closeWith(connection, "full")
                    return
                }
                sendHello(session)
            } else {
                sendInfo(session)
                fmp4?.let { startVideo(session, it) }
            }
            if (paused) connection.sendText(json("t" to "paused", "v" to true))
        }
        publishClients()
        if (kind == Kind.SCREEN) {
            updateStreaming()
            applyHostAudio()
            requestKeyFrame(force = true)
        }
    }

    override fun onClose(connection: WsConnection) {
        val session = connection.attachment as? Session ?: return
        val removed = synchronized(lock) {
            val was = sessions.remove(session)
            if (was) releasePorts(session)
            was
        }
        if (!removed) return
        publishClients()
        if (session.kind == Kind.SCREEN) {
            updateStreaming()
            applyHostAudio()
        }
    }

    override fun onText(connection: WsConnection, text: String) {
        val session = connection.attachment as? Session ?: return
        val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val slot = msg.int("p") ?: 0
        when (msg.string("t")) {
            "k" -> synchronized(lock) {
                // Com a trava: releasePort mexe em [held] de outras threads, e uma sessão já removida
                // (substituída, expulsa) não pode apertar nada que ninguém soltaria depois.
                if (session !in sessions) return
                val port = session.slots[slot] ?: return
                val key = msg.int("c")?.takeIf { it in RETROPAD_KEYS } ?: return
                val down = msg.int("d") == 1
                view?.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key, port)
                if (down) session.held += port to key else session.held -= port to key
            }
            "m" -> synchronized(lock) {
                // Como nas teclas: uma sessão expulsa ou substituída não move mais nada, nem no meio da saída.
                if (session !in sessions) return
                val port = session.slots[slot] ?: return
                val source = MOTION_SOURCES.getOrNull(msg.int("s") ?: return) ?: return
                val x = msg.float("x")?.coerceIn(-1f, 1f) ?: 0f
                val y = msg.float("y")?.coerceIn(-1f, 1f) ?: 0f
                view?.sendMotionEvent(source, x, y, port)
            }
            // A tela pede um jogador para o teclado ou um controle ligado no computador.
            "join" -> {
                if (session.kind != Kind.SCREEN) return
                // Slot fora da faixa recebe "cheio": a página para de pedir e a lista de portas lembradas não
                // ganha uma entrada por número inventado.
                val port = if (slot !in 0 until MAX_SLOTS) null
                else synchronized(lock) { if (session in sessions) assignPort(session, slot) else null }
                session.connection.sendText(
                    if (port == null) json("t" to "full", "p" to slot)
                    else json("t" to "player", "p" to slot, "port" to port),
                )
                // Sem vaga nada mudou: avisar todas as telas só geraria outro pedido.
                if (port != null) publishClients()
            }
            "leave" -> {
                val left = synchronized(lock) {
                    val port = session.slots.remove(slot) ?: return@synchronized false
                    releasePort(session, port)
                    // Saída de propósito (o controle foi desligado do computador): não há porta a guardar.
                    rememberedPorts.remove("${session.clientKey}/$slot")
                    true
                }
                if (left) publishClients()
            }
            "name" -> {
                val name = msg.string("v").orEmpty().take(40)
                val now = System.currentTimeMillis()
                // Cada troca avisa todas as telas e o menu: em rajada, só a primeira de cada intervalo vale.
                if (name == session.name || now - session.lastNameAt < CONTROL_INTERVAL_MS) return
                session.lastNameAt = now
                session.name = name
                publishClients()
            }
            // A tela descartou quadros (fila cheia no navegador): precisa de um quadro-chave para voltar.
            "key" -> if (session.kind == Kind.SCREEN) requestKeyFrame()
            // O player da tela deu erro: recomeça do segmento de inicialização. Cada pedido força um
            // quadro-chave (pesado para o encoder): no máximo um por intervalo.
            "restart" -> if (session.kind == Kind.SCREEN) {
                val now = System.currentTimeMillis()
                if (now - session.lastRestartAt < CONTROL_INTERVAL_MS) return
                session.lastRestartAt = now
                synchronized(lock) {
                    if (session !in sessions) return
                    fmp4?.let { startVideo(session, it) }
                }
                requestKeyFrame(force = true)
            }
        }
    }

    // endregion

    // region Jogadores

    /** Chame com [lock]. Jogador 1 (porta 0) é sempre o celular do jogo. */
    private fun assignPort(session: Session, slot: Int): Int? {
        session.slots[slot]?.let { return it }
        val used = sessions.flatMap { it.slots.values }.toSet() + reservedPorts() + 0
        val key = "${session.clientKey}/$slot"
        val port = rememberedPorts[key]?.takeIf { it !in used }
            ?: (1 until MAX_PORTS).firstOrNull { it !in used }
            ?: return null
        rememberedPorts[key] = port
        session.slots[slot] = port
        return port
    }

    private fun releasePorts(session: Session) {
        session.slots.values.toList().forEach { releasePort(session, it) }
        // Uma conexão substituída ainda vive uns instantes: sem portas, o que ela mandar não chega ao jogo.
        session.slots.clear()
    }

    /** Solta o que estava apertado: sem isso, o personagem do jogador que caiu continua andando. */
    private fun releasePort(session: Session, port: Int) {
        val v = view ?: return
        session.held.filter { it.first == port }.forEach { (_, key) -> v.sendKeyEvent(KeyEvent.ACTION_UP, key, port) }
        session.held.removeAll { it.first == port }
        MOTION_SOURCES.forEach { v.sendMotionEvent(it, 0f, 0f, port) }
    }

    /** Trava própria: a decisão e o acquire/release andam juntos, sem segurar o [lock] da entrada numa chamada ao sistema. */
    private val wifiGuard = Any()

    private fun updateWifiLock() = synchronized(wifiGuard) {
        val wanted = synchronized(lock) { server != null && sessions.isNotEmpty() }
        runCatching {
            if (wanted && !wifiLock.isHeld) wifiLock.acquire()
            else if (!wanted && wifiLock.isHeld) wifiLock.release()
        }.onFailure { Log.w(TAG, "Wi-Fi lock failed", it) }
    }

    private fun publishClients() {
        val list = synchronized(lock) {
            sessions.map { Client(it.connection.id, it.kind, it.name, it.slots.values.sorted()) }
        }
        _state.update { it.copy(clients = list) }
        updateWifiLock()
        // A tela esconde o QR quando alguém já entrou.
        val info = synchronized(lock) { sessions.filter { it.kind == Kind.SCREEN } }
        info.forEach { sendInfo(it) }
    }

    private fun sendHello(session: Session) {
        val g = game
        val port = session.slots[0] ?: return
        session.connection.sendText(
            buildJsonObject {
                put("t", "hello")
                put("port", port)
                put("game", g?.title.orEmpty())
                put("system", g?.system.orEmpty())
                put("accent", g?.accent?.let { color(it) })
                g?.layout?.let { put("layout", layoutJson(it)) }
            }.toString(),
        )
    }

    private fun sendInfo(session: Session) {
        val g = game
        val s = state.value
        val players = synchronized(lock) { sessions.sumOf { it.slots.size } }
        session.connection.sendText(
            buildJsonObject {
                put("t", "info")
                put("game", g?.title.orEmpty())
                put("system", g?.system.orEmpty())
                put("join", s.padUrl)
                put("players", players)
                put("video", s.streamSupported)
            }.toString(),
        )
    }

    // endregion

    // region Vídeo e áudio

    private fun updateStreaming(): Unit = synchronized(streamLock) {
        val v = view
        val wanted = synchronized(lock) { v != null && sessions.any { it.kind == Kind.SCREEN } && v.supportsCapture }
        if (wanted && encoder == null) startStreaming() else if (!wanted && encoder != null) stopStreaming(v)
    }

    private fun startStreaming(): Unit = synchronized(streamLock) {
        val v = view ?: return
        // Os retornos do encoder chegam na thread dele, às vezes depois de ele ser trocado: cada um só vale
        // para a geração em que foi criado.
        val generation = ++streamGeneration
        if (encoderMode < 0) encoderMode = prefs.getInt(encoderModeKey, 0).coerceIn(0, VideoEncoder.MODES - 1)
        var enc: VideoEncoder? = null
        while (enc == null) {
            enc = try {
                VideoEncoder(
                    VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_BITRATE, encoderMode,
                    onConfig = { sps, pps -> if (streamGeneration == generation) onVideoConfig(sps, pps) },
                    onFrame = { frame -> if (streamGeneration == generation) onVideoFrame(frame) },
                    // Fora da thread do encoder: trocá-lo espera essa thread terminar.
                    onError = { Thread { encoderFailed(generation) }.start() },
                )
            } catch (e: Exception) {
                Log.e(TAG, "Encoder failed (mode $encoderMode)", e)
                if (encoderMode + 1 >= VideoEncoder.MODES) {
                    _state.update { it.copy(errorRes = R.string.remote_encoder_failed) }
                    return
                }
                encoderMode++
                null
            }
        }
        Log.i(TAG, "Encoder ${enc.name} (mode ${enc.mode})")
        encoder = enc
        videoArrived = false
        videoExpectedSince = System.currentTimeMillis()
        v.setCaptureSurface(enc.surface, enc.width, enc.height)
        v.setAudioCapture(true)
        audioPump = Thread({ pumpAudio(v, generation) }, "RemoteAudio").apply { isDaemon = true; start() }
    }

    private fun stopStreaming(v: GLRetroView?): Unit = synchronized(streamLock) {
        val enc = encoder ?: return
        encoder = null
        streamGeneration++
        // A captura é do LibretroDroid (um só para o app): qualquer view serve para desligá-la.
        v?.setCaptureSurface(null, 0, 0)
        v?.setAudioCapture(false)
        audioPump?.interrupt()
        audioPump = null
        fmp4 = null
        // Espera a thread de emulação trocar de superfície antes de liberar a do encoder.
        Thread { Thread.sleep(200); enc.release() }.start()
    }

    private fun onVideoConfig(sps: ByteArray, pps: ByteArray) {
        // Só o SPS do segmento de inicialização vale: o VideoEncoder tira os que vêm junto dos quadros.
        val mp4 = Fmp4(VIDEO_WIDTH, VIDEO_HEIGHT, Avc.lowDelaySps(sps), pps)
        synchronized(lock) {
            fmp4 = mp4
            sessions.filter { it.kind == Kind.SCREEN }.forEach { startVideo(it, mp4) }
        }
        if (_state.value.errorRes == R.string.remote_stream_failed) _state.update { it.copy(errorRes = null) }
    }

    /**
     * O encoder da geração [generation] morreu ou não entregou nada: troca pelo próximo modo, mais compatível.
     * Sem outro modo, avisa. Parar a captura também evita que cada quadro vire um erro no log.
     */
    private fun encoderFailed(generation: Int): Unit = synchronized(streamLock) {
        if (generation != streamGeneration || encoder == null) return
        stopStreaming(view)
        if (encoderMode + 1 < VideoEncoder.MODES) {
            encoderMode++
            Log.w(TAG, "Retrying the encoder in mode $encoderMode")
            updateStreaming()
        } else {
            showStreamFailed()
        }
    }

    /**
     * Encoder ligado, jogo rodando e nenhum quadro depois de alguns segundos: o encoder não funciona ou o
     * driver recusou a superfície dele (o nativo só registra no log). Tenta o próximo modo; no último, avisa
     * no menu e na tela em vez de deixá-la preta sem motivo.
     */
    private fun checkVideoArrived() {
        if (videoArrived || paused || encoder == null) return
        if (System.currentTimeMillis() - videoExpectedSince < VIDEO_TIMEOUT_MS) return
        if (_state.value.errorRes == R.string.remote_stream_failed) return
        Log.e(TAG, "No video from the capture surface (mode $encoderMode)")
        if (encoderMode + 1 < VideoEncoder.MODES) {
            val generation = streamGeneration
            modeFromTimeout = true
            Thread { encoderFailed(generation) }.start()
        } else {
            showStreamFailed()
        }
    }

    private fun showStreamFailed() {
        _state.update { it.copy(errorRes = R.string.remote_stream_failed) }
        val screens = synchronized(lock) { sessions.filter { it.kind == Kind.SCREEN } }
        screens.forEach { it.connection.sendText(json("t" to "videoFailed")) }
    }

    /** Chame com [lock]. A tela recria o player com o novo SPS/PPS e espera o próximo quadro-chave. */
    private fun startVideo(session: Session, mp4: Fmp4) {
        session.video = VideoTrack()
        session.connection.sendText(json("t" to "video", "codec" to mp4.codec, "w" to VIDEO_WIDTH, "h" to VIDEO_HEIGHT))
        session.connection.sendBinary(byteArrayOf(PACKET_INIT) + mp4.initSegment())
    }

    private fun onVideoFrame(frame: EncodedFrame) {
        if (!videoArrived) {
            videoArrived = true
            if (!modeFromTimeout && prefs.getInt(encoderModeKey, -1) != encoderMode) {
                prefs.edit().putInt(encoderModeKey, encoderMode).apply()
            }
        }
        val mp4 = fmp4 ?: return
        val screens = synchronized(lock) { sessions.filter { it.kind == Kind.SCREEN && it.video != null } }
        for (s in screens) {
            val track = s.video ?: continue
            if (s.connection.queuedBytes > MAX_BACKLOG) {
                // O aparelho não está dando conta (Wi-Fi fraco): pula até um quadro-chave, para não acumular atraso.
                track.needKey = true
                requestKeyFrame()
                continue
            }
            if (track.needKey) {
                if (!frame.key) { requestKeyFrame(); continue }
                track.needKey = false
            }
            val fragment = track.fragment(mp4, frame) ?: continue
            // O tipo diz se o fragmento é quadro-chave: a tela, quando precisa jogar quadros fora, recomeça dele.
            s.connection.sendBinary(byteArrayOf(if (fragment.key) PACKET_VIDEO_KEY else PACKET_VIDEO) + fragment.data)
        }
    }

    private fun requestKeyFrame(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastKeyRequest < 500) return
        lastKeyRequest = now
        encoder?.requestKeyFrame()
    }

    private fun pumpAudio(v: GLRetroView, generation: Int) {
        val buffer = ShortArray(8192)
        var chunk = ShortArray(0)
        var chunkSize = 0
        var lastCheck = 0L
        try {
            while (!Thread.currentThread().isInterrupted && streamGeneration == generation) {
                val now = System.currentTimeMillis()
                if (now - lastCheck > 1000) { lastCheck = now; checkVideoArrived() }
                val rate = v.audioSampleRate
                val n = if (rate > 0) v.readCapturedAudio(buffer) else 0
                if (n > 0) {
                    // Pacotes de ~20 ms: menos que isso vira sobrecarga no navegador; mais, atraso.
                    val target = (rate / 50) * 2
                    if (chunk.size != target) { chunk = ShortArray(target); chunkSize = 0 }
                    var i = 0
                    while (i < n) {
                        val take = minOf(n - i, target - chunkSize)
                        System.arraycopy(buffer, i, chunk, chunkSize, take)
                        chunkSize += take
                        i += take
                        if (chunkSize == target) {
                            broadcastAudio(rate, chunk, chunkSize)
                            chunkSize = 0
                        }
                    }
                } else {
                    Thread.sleep(5)
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun broadcastAudio(rate: Int, samples: ShortArray, count: Int) {
        val screens = synchronized(lock) { sessions.filter { it.kind == Kind.SCREEN } }
        if (screens.isEmpty()) return
        // Cabeçalho de 8 bytes: as amostras ficam alinhadas para o Int16Array do navegador.
        val packet = ByteArray(8 + count * 2)
        packet[0] = PACKET_AUDIO
        packet[4] = rate.toByte(); packet[5] = (rate shr 8).toByte(); packet[6] = (rate shr 16).toByte(); packet[7] = (rate shr 24).toByte()
        for (i in 0 until count) {
            val s = samples[i].toInt()
            packet[8 + i * 2] = s.toByte()
            packet[9 + i * 2] = (s shr 8).toByte()
        }
        screens.forEach { if (it.connection.queuedBytes < MAX_BACKLOG) it.connection.sendBinary(packet) }
    }

    private fun applyHostAudio() {
        val s = state.value
        val v = synchronized(lock) { view } ?: return
        // O som sai na TV: no celular ficaria dobrado, e um pouco adiantado.
        // Sem encoder (o aparelho recusou) a TV não recebe som nem imagem: o celular não pode ficar mudo.
        val enabled = !(s.muteHost && s.running && s.screenCount > 0 && s.streamSupported && encoder != null)
        if (v.audioEnabled != enabled) v.audioEnabled = enabled
    }

    // endregion

    /**
     * Quem é o aparelho: o id que o navegador guarda, preso ao endereço dele. Sem o endereço, outro aparelho
     * com o código podia mandar o mesmo id e tomar a vaga (e a porta) de quem já joga.
     */
    private fun clientKey(request: HttpRequest, remoteAddress: String) =
        remoteAddress + "/" + request.query["id"].orEmpty().take(64).ifEmpty { "anon-${request.hashCode()}" }

    private class Session(val connection: WsConnection, val kind: Kind, val clientKey: String, var name: String) {
        /**
         * Jogadores desta conexão: slot local (0 = o próprio controle; na tela, teclado e controles) → porta.
         * Mudado com [lock], mas lido sem ele a cada tecla.
         */
        val slots: MutableMap<Int, Int> = java.util.concurrent.ConcurrentHashMap()
        /** Escrito pela thread da conexão e lido por quem solta as teclas (saída, parar o servidor). */
        val held: MutableSet<Pair<Int, Int>> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        @Volatile var video: VideoTrack? = null
        /** Só a thread da conexão mexe: limitam "restart" e "name" em rajada. */
        var lastRestartAt = 0L
        var lastNameAt = 0L
    }

    companion object {
        private const val TAG = "RemotePlay"
        private const val CODE_LENGTH = 6
        private const val PREFERRED_PORT = 8080
        private const val MAX_FAILURES = 20
        private const val MAX_GLOBAL_FAILURES = 60
        private const val FAILURE_WINDOW_MS = 60_000L
        private const val FAILURE_DELAY_MS = 5_000L
        /** Slots locais de uma conexão: o controle (0) e, na tela, teclado e controles do computador. */
        private const val MAX_SLOTS = 16
        /** Portas lembradas para quem reconectar: as mais antigas saem antes. */
        private const val MAX_REMEMBERED = 64
        /** Mínimo entre dois "restart" (cada um força um quadro-chave) ou duas trocas de nome de uma conexão. */
        private const val CONTROL_INTERVAL_MS = 1_000L
        /** Núcleos pesados (PS2, GameCube) demoram a mostrar o primeiro quadro: folga antes de dar erro. */
        private const val VIDEO_TIMEOUT_MS = 12_000L
        /** Portas do LibretroDroid (Input::getInputState ignora port >= 4). */
        private const val MAX_PORTS = 4
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_BITRATE = 6_000_000
        /** ~1 s de vídeo nessa taxa: acima disso a conexão não acompanha. */
        private const val MAX_BACKLOG = 768 * 1024L

        private const val PACKET_INIT: Byte = 1
        private const val PACKET_VIDEO: Byte = 2
        private const val PACKET_AUDIO: Byte = 3
        private const val PACKET_VIDEO_KEY: Byte = 4

        private val MOTION_SOURCES = listOf(
            GLRetroView.MOTION_SOURCE_DPAD, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
        )

        private val RETROPAD_KEYS = setOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        )

        private fun color(argb: Long) = "#%06X".format(argb and 0xFFFFFF)

        fun layoutJson(layout: PadLayout): JsonObject = buildJsonObject {
            fun buttons(list: List<PadButton>) = JsonArray(
                list.map { b ->
                    buildJsonObject {
                        put("label", b.label)
                        put("key", b.keyCode)
                        b.color?.let { put("color", color(it)) }
                    }
                },
            )
            put("arrangement", layout.arrangement.name)
            put("face", buttons(layout.face))
            put("left", buttons(layout.leftShoulders))
            put("right", buttons(layout.rightShoulders))
            put("center", buttons(layout.center))
            put("dpad", layout.dpad)
            put("leftStick", layout.leftStick)
            put("rightStick", layout.rightStick)
            put("cButtons", layout.cButtons)
        }

        private fun json(vararg pairs: Pair<String, Any?>): String = buildJsonObject {
            pairs.forEach { (k, v) ->
                when (v) {
                    is String -> put(k, v)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    null -> put(k, null as String?)
                }
            }
        }.toString()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.int(key: String): Int? = runCatching { (this[key] as? JsonPrimitive)?.int }.getOrNull()
        private fun JsonObject.float(key: String): Float? = runCatching { (this[key] as? JsonPrimitive)?.float }.getOrNull()
    }
}
