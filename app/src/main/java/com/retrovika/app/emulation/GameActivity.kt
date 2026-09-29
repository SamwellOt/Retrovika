package com.retrovika.app.emulation

import com.retrovika.app.R
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.hardware.input.InputManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import com.retrovika.app.container
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.cores.CoreState
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.settings.AppSettings
import com.retrovika.app.core.settings.ShaderOption
import com.retrovika.app.core.storage.StorageAccess
import com.retrovika.app.core.share.LanTransfer
import com.retrovika.app.core.share.RetrovikaLink
import com.retrovika.app.core.systems.CoreInfo
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Orientation
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.input.MotionSources
import com.retrovika.app.emulation.input.PadProfile
import com.retrovika.app.emulation.input.PadListener
import com.retrovika.app.ui.theme.RetrovikaTheme
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface EmulationUi {
    data class Preparing(val message: String, val progress: Float?) : EmulationUi
    data class Running(val view: GLRetroView) : EmulationUi
    data class Failed(val title: String, val message: String, val action: FailAction? = null) : EmulationUi
}

/** Botão extra da tela de falha, para o que o usuário pode resolver na hora (ex.: conceder uma permissão). */
data class FailAction(val label: String, val run: () -> Unit)

class GameActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(com.retrovika.app.core.settings.Languages.wrap(newBase))


    private val app by lazy { container }

    /**
     * Ciclo de vida exclusivo do emulador: acompanha o da Activity, mas fica em STARTED
     * (pausado) enquanto o menu está aberto. É assim que pausamos o LibretroDroid com segurança.
     */
    private val emulationOwner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private var ui by mutableStateOf<EmulationUi>(EmulationUi.Preparing("", null))
    private var menuOpen by mutableStateOf(false)
    private var fastForward by mutableStateOf(false)
    /**
     * Um controle físico está sendo usado. Só vira verdadeiro quando chega um botão ou analógico dele:
     * vários celulares declaram sensores e leitores de digital como "gamepad", e esconder o controle
     * virtual só por o dispositivo existir deixava o jogo sem botões na tela.
     */
    private var controllerActive by mutableStateOf(false)
    private var toast by mutableStateOf<String?>(null)
    /** Controle virtual deste núcleo (visível, tamanho, posições), carregado com o jogo. */
    private var padProfile by mutableStateOf(PadProfile())
    /**
     * Gravações do perfil numa fila só, na ordem em que foram feitas (lançadas soltas no Dispatchers.Default,
     * uma mais antiga podia gravar por último). Sem descartar nenhuma: cada uma pode ir para um lugar
     * (o jogo ou o console), e (true, null) apaga o controle próprio do jogo.
     */
    private val padSaves = Channel<Pair<Boolean, PadProfile?>>(Channel.UNLIMITED)
    /** Gravações enviadas a [padSaves] e ainda não feitas (lido e escrito só na thread principal). */
    private var pendingPadSaves = 0
    /** O controle em uso é o próprio deste jogo (verdadeiro) ou o do console (falso). */
    private var padForGame by mutableStateOf(false)
    /** Há mudança do perfil ainda não gravada: o que chega do DataStore nesse meio-tempo é mais velho. */
    private var padDirty = false
    /** Editor do layout aberto a partir do menu: a emulação segue pausada por baixo. */
    private var padEditing by mutableStateOf(false)
    /** A tela do jogo em tamanho real, capturada ao abrir o menu: fundo do editor de layout. */
    private var menuFrame by mutableStateOf<Bitmap?>(null)
    private var menuSnapshot: Bitmap? = null
    private var menuOpening = false

    /**
     * Estado do jogo capturado na thread de emulação quando ela parou (menu aberto ou app em segundo plano).
     * Com a emulação parada, o núcleo não pode ser serializado de outra thread: os que desenham com a GPU
     * (N64, Dreamcast, GameCube) chamam OpenGL nesse momento e, sem o contexto, fecham o app ou gravam lixo.
     */
    private var frozenState: ByteArray? = null

    private lateinit var game: Game
    private lateinit var system: GameSystem
    private lateinit var core: CoreInfo
    /** A tela de falha pediu o acesso a todos os arquivos: ao voltar com ele concedido, o jogo recomeça. */
    private var awaitingFileAccess = false
    /**
     * Partida de outra pessoa (estado recebido): nada é gravado por cima do progresso de quem joga, nem a
     * memória do cartucho nem o salvamento automático. Os slots continuam valendo.
     */
    private var guestSession = false
    /** Estado recebido a abrir no primeiro quadro, no lugar do salvamento automático. */
    private var pendingStateFile: File? = null
    /** Estado sendo compartilhado a partir do menu. */
    private var sharing by mutableStateOf<ShareSheet?>(null)
    private lateinit var states: SaveStates
    /** Trapaças do jogo; nulo até o jogo ser encontrado. */
    private var cheats: CheatSession? = null
    private var settings = AppSettings()
    private var retroView: GLRetroView? = null
    private var sessionStart = 0L
    private var activityResumed = false

    /**
     * Só depois do primeiro quadro (e do carregamento automático) o estado do jogo é real. Antes disso,
     * sair do jogo gravaria a tela de boot por cima do salvamento automático e o progresso se perderia.
     */
    private var autoSaveReady = false

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = Unit
        // Controle desconectado: o controle virtual volta na hora.
        override fun onInputDeviceRemoved(id: Int) { if (!detectController()) controllerActive = false }
        override fun onInputDeviceChanged(id: Int) { if (!detectController()) controllerActive = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recursos só estão disponíveis depois do attachBaseContext, por isso o texto inicial entra aqui.
        ui = EmulationUi.Preparing(getString(R.string.game_loading), null)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideSystemBars()
        emulationOwner.registry.currentState = Lifecycle.State.CREATED

        getSystemService(InputManager::class.java).registerInputDeviceListener(inputDeviceListener, Handler(Looper.getMainLooper()))

        // Android 16+ (targetSdk 36) entrega o "voltar" só por callback, sem KEYCODE_BACK: sem isto,
        // o gesto fecharia o jogo em vez de abrir o menu de pausa.
        onBackPressedDispatcher.addCallback(this) { toggleMenu() }

        setContent {
            RetrovikaTheme(dark = true) {
                GameScreen(
                    state = ui,
                    game = if (::game.isInitialized) game else null,
                    system = if (::system.isInitialized) system else null,
                    settings = settings,
                    menuOpen = menuOpen,
                    fastForward = fastForward,
                    showPad = !(controllerActive && settings.hidePadWithController),
                    padProfile = padProfile,
                    padEditing = padEditing,
                    editorBackdrop = menuFrame,
                    toast = toast,
                    padListener = padListener,
                    menu = menuActions,
                    onDismissToast = { toast = null },
                )
            }
        }

        lifecycleScope.launch {
            // Qualquer falha inesperada na preparação vira a tela de erro com o motivo, em vez de fechar o app.
            try {
                prepare(intent.getLongExtra(EXTRA_GAME_ID, -1))
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(getString(R.string.game_cant_run), t.userMessage(this@GameActivity))
            }
        }
    }

    /**
     * O LibretroDroid é um singleton nativo: só pode existir um emulador por vez (daí o singleTask).
     * Se outro jogo for aberto enquanto este está em segundo plano, salvamos e reiniciamos a Activity.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newId = intent.getLongExtra(EXTRA_GAME_ID, -1)
        // Compara com o pedido atual, não com o jogo carregado: durante a preparação ele ainda não
        // existe, e um toque duplo em "Jogar" reiniciava a instalação do núcleo.
        // Mesmo jogo, sem estado recebido: é só um toque duplo em "Jogar".
        if (newId == this.intent.getLongExtra(EXTRA_GAME_ID, -1) && intent.getStringExtra(EXTRA_STATE_FILE) == null) return
        persist(auto = settings.autoSave)
        setIntent(intent)
        recreate()
    }

    private suspend fun prepare(gameId: Long) {
        game = app.library.get(gameId) ?: return fail(getString(R.string.game_not_found), getString(R.string.game_not_found_message))
        system = Systems.byId(game.systemId) ?: return fail(getString(R.string.game_unknown_system), game.systemId)
        states = SaveStates(app.paths, game)
        cheats = CheatSession(app.cheats, game, lifecycleScope, this).also { it.restore() }
        settings = app.settings.current()
        // A tela acompanha o sensor: girar o celular alterna entre retrato e paisagem em qualquer console.
        requestedOrientation = when (system.orientation) {
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Orientation.PORTRAIT, Orientation.ANY -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }

        pendingStateFile = intent.getStringExtra(EXTRA_STATE_FILE)?.let(::File)?.takeIf { it.exists() }
        guestSession = pendingStateFile != null
        // O estado só abre no núcleo que o gerou: quem enviou diz qual é.
        val sharedCore = intent.getStringExtra(EXTRA_CORE_ID)?.takeIf { id -> system.cores.any { it.id == id } }
        val coreId = sharedCore ?: game.coreOverride ?: app.settings.coreFor(system.id).first()
        core = system.core(coreId)
        val padCore = system.id
        val gameId = game.id
        // O controle próprio do jogo, quando existe, vence o do console.
        val padFlow = combine(app.settings.gamePadProfile(gameId), app.settings.padProfile(padCore)) { own, console -> (own != null) to (own ?: console) }
        padFlow.first().let { (forGame, profile) -> padForGame = forGame; padProfile = profile }
        app.scope.launch {
            for ((forGame, profile) in padSaves) {
                if (forGame) app.settings.setGamePadProfile(gameId, profile) else if (profile != null) app.settings.setPadProfile(padCore, profile)
                // Fila vazia: tudo o que foi mexido já está gravado, e o que vier do DataStore volta a valer.
                withContext(Dispatchers.Main) { if (--pendingPadSaves == 0) padDirty = false }
            }
        }
        // Acompanha o perfil gravado: "Restaurar todos" em Ajustes com o jogo aberto em segundo plano vale
        // na volta, em vez de o perfil antigo da memória ser gravado de novo na próxima mudança.
        lifecycleScope.launch { padFlow.collect { (forGame, profile) -> if (!padDirty) { padForGame = forGame; padProfile = profile } } }

        // 1. Núcleo e arquivos de sistema dele: baixados automaticamente na primeira execução.
        val corePath = app.cores.corePath(core.id)?.takeUnless { app.cores.needsInstall(core) } ?: run {
            ui = EmulationUi.Preparing(getString(R.string.game_installing_core, core.displayName), 0f)
            val progressJob = lifecycleScope.launch {
                app.cores.states.collectLatest { map ->
                    (map[core.id] as? CoreState.Downloading)?.let {
                        ui = EmulationUi.Preparing(getString(R.string.game_installing_core, core.displayName), it.progress)
                    }
                }
            }
            try {
                app.cores.install(core)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Núcleo sem versão para este processador: não é a internet, e a dica só confundiria.
                val noBuild = t is LocalizedException && t.messageRes == R.string.cores_unavailable_abi
                return fail(
                    getString(R.string.game_core_install_failed),
                    if (noBuild) t.userMessage(this) else getString(R.string.game_core_install_failed_message, t.userMessage(this)),
                )
            } finally {
                progressJob.cancel()
            }
        }

        // 2. BIOS obrigatórias (cada item pode ter alternativas: basta uma delas).
        val missing = app.bios.missingRequired(system)
        if (missing.isNotEmpty()) {
            return fail(
                getString(R.string.game_bios_required),
                getString(R.string.game_bios_required_message) + "\n\n" +
                    missing.joinToString("\n") { options ->
                        options.singleOrNull()?.let { "• ${it.fileName} — ${getString(it.description)}" }
                            ?: getString(R.string.game_bios_one_of, options.joinToString { it.fileName })
                    },
            )
        }

        // 3. Opções: padrões otimizados + preset escolhido + ajustes manuais do usuário.
        val preset = app.settings.presetFor(system.id).first()
        val options = core.defaults + core.presets[preset].orEmpty() + app.settings.coreOptions(core.id)

        ui = EmulationUi.Preparing(getString(R.string.game_starting, game.title), null)
        val sram = withContext(Dispatchers.IO) { states.sramFile().takeIf { it.exists() }?.readBytes() }

        // Alguns núcleos (Play!) montam a pasta de dados a partir de $EXTERNAL_STORAGE, que aponta para o
        // /sdcard sem permissão de escrita: o núcleo abortava ao criar a pasta. Vale para o processo
        // (é o getenv nativo) e é refeito a cada jogo.
        runCatching { android.system.Os.setenv("EXTERNAL_STORAGE", app.paths.savesFor(system.id).absolutePath, true) }

        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            val realFile = if (game.isContentUri) withContext(Dispatchers.IO) { StorageAccess.realFile(this@GameActivity, Uri.parse(game.uri)) } else null
            if (realFile != null) {
                // Com acesso aos arquivos, o núcleo recebe o caminho real: funciona até nos que não usam a VFS.
                gameFilePath = realFile.path
            } else if (game.isContentUri && core.needsRealPath) {
                awaitingFileAccess = true
                return fail(
                    getString(R.string.game_needs_file_access),
                    getString(R.string.game_needs_file_access_message, core.displayName),
                    FailAction(getString(R.string.game_allow_file_access)) { startActivity(StorageAccess.settingsIntent(this@GameActivity)) },
                )
            } else if (game.isContentUri) {
                // IPC com o provedor SAF: fora da thread principal.
                gameVirtualFiles = runCatching { withContext(Dispatchers.IO) { GameFiles.virtualFiles(contentResolver, game) } }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    return fail(getString(R.string.game_file_inaccessible), getString(R.string.game_file_inaccessible_message, game.fileName))
                }
            } else {
                if (!File(game.uri).exists()) return fail(getString(R.string.game_file_not_found), game.uri)
                gameFilePath = game.uri
            }
            systemDirectory = app.paths.system.absolutePath
            savesDirectory = app.paths.savesFor(system.id).absolutePath
            variables = options.map { Variable(it.key, it.value) }.toTypedArray()
            saveRAMState = sram
            shader = settings.shader.toShaderConfig()
            preferLowLatencyAudio = settings.lowLatencyAudio
            rumbleEventsEnabled = true
            relaxedGlesVersion = core.relaxedGlesVersion
            // Controles físicos vão para as portas 1 a 4 (controllerNumber), então todas recebem o tipo.
            core.portDevice?.let { device -> controllerTypes = IntArray(MAX_PORTS) { device } }
        }

        // Voltar durante o "Iniciando…" já chamou finish(): iniciar o núcleo agora derrubaria o do
        // próximo jogo quando esta Activity fosse destruída (o LibretroDroid é global).
        if (isFinishing) return
        val view = GLRetroView(this, data).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        retroView = view
        emulationOwner.registry.addObserver(view)
        observe(view)
        ui = EmulationUi.Running(view)
        updateEmulationState()
        view.requestFocus()
    }

    private fun observe(view: GLRetroView) {
        lifecycleScope.launch {
            view.getGLRetroErrors().collect { code ->
                val msg = when (code) {
                    GLRetroView.ERROR_LOAD_LIBRARY -> getString(R.string.game_error_load_library)
                    GLRetroView.ERROR_LOAD_GAME -> getString(R.string.game_error_load_game) +
                        app.bios.missingOptional(system).takeIf { it.isNotEmpty() }?.let { missing ->
                            "\n\n" + getString(R.string.game_error_load_game_bios) + "\n" +
                                missing.joinToString("\n") { "• ${it.fileName} — ${getString(it.description)}" }
                        }.orEmpty()
                    GLRetroView.ERROR_GL_NOT_COMPATIBLE -> getString(R.string.game_error_gl)
                    GLRetroView.ERROR_SERIALIZATION -> getString(R.string.game_error_serialization)
                    else -> getString(R.string.game_error_unexpected, code)
                }
                if (code == GLRetroView.ERROR_SERIALIZATION) toast = msg else fail(getString(R.string.game_cant_run), msg)
            }
        }
        // Carrega o salvamento automático assim que o primeiro quadro é desenhado.
        lifecycleScope.launch {
            view.getGLRetroEvents().filterIsInstance<GLRetroView.GLRetroEvents.FrameRendered>().first()
            val received = pendingStateFile
            if (received != null) {
                pendingStateFile = null
                val data = withContext(Dispatchers.IO) { runCatching { received.readBytes() }.getOrNull() }
                if (retroView !== view) return@launch
                val ok = data != null && withContext(Dispatchers.Default) { runCatching { view.unserializeState(data) }.getOrDefault(false) }
                toast = getString(if (ok) R.string.share_state_opened else R.string.share_state_open_failed)
            } else if (settings.autoLoad) {
                val saved = withContext(Dispatchers.IO) { runCatching { states.read(SaveStates.AUTO_SLOT) }.getOrNull() }
                // Sair pelo menu durante a leitura já destruiu o núcleo: carregar agora derrubaria o app.
                if (retroView !== view) return@launch
                saved?.let { data ->
                    // Roda na thread de emulação; a espera fica fora da principal (pausar no meio a travaria).
                    if (withContext(Dispatchers.Default) { runCatching { view.unserializeState(data) }.getOrDefault(false) }) {
                        toast = getString(R.string.game_progress_restored)
                    } else {
                        // Estado de outro núcleo (ou de uma versão anterior dele): fica guardado à parte,
                        // senão o próximo salvamento automático gravaria a tela de início por cima dele.
                        withContext(Dispatchers.IO) { runCatching { states.backup(SaveStates.AUTO_SLOT) } }
                        toast = getString(R.string.game_autosave_incompatible)
                    }
                }
            }
            autoSaveReady = true
            // Trapaças ligadas na última sessão voltam junto com o jogo.
            if (retroView === view && cheats?.state?.enabled?.isNotEmpty() == true) applyCheats(view)
        }
        lifecycleScope.launch { watchForBlackScreen(view) }
        lifecycleScope.launch {
            val vibrator = rumbleVibrator ?: return@launch
            // O núcleo só avisa quando a força muda: a vibração dura até chegar força zero (ou o jogo pausar),
            // não um pulso curto por aviso, que fazia o Rumble Pak parar em 60 ms.
            view.getRumbleEvents().collect { e ->
                val strength = maxOf(e.strengthStrong, e.strengthWeak)
                if (strength > 0f) vibrator.vibrate(VibrationEffect.createOneShot(RUMBLE_MAX_MS, (strength * 255).toInt().coerceIn(1, 255)))
                else vibrator.cancel()
            }
        }
    }

    /**
     * Núcleo carregado mas imagem toda preta (jogo incompatível com o núcleo, BIOS faltando…): sem aviso,
     * parece que o app travou. Aberturas escuras são comuns, então só avisa se continuar preto em duas
     * checagens seguidas.
     */
    private suspend fun watchForBlackScreen(view: GLRetroView) {
        view.getGLRetroEvents().filterIsInstance<GLRetroView.GLRetroEvents.FrameRendered>().first()
        var black = 0
        repeat(BLACK_SCREEN_CHECKS) {
            delay(BLACK_SCREEN_INTERVAL_MS)
            // Pausado ou em segundo plano a captura não diz nada sobre o jogo.
            if (menuOpen || !activityResumed) return@repeat
            val frame = suspendCancellableCoroutine { cont -> captureFrame(view) { thumb, _ -> if (cont.isActive) cont.resume(thumb) } }
            if (frame == null) return@repeat
            if (!isBlack(frame)) return
            if (++black >= 2) {
                toast = getString(R.string.game_black_screen_hint, core.displayName)
                return
            }
        }
    }

    private fun isBlack(frame: Bitmap): Boolean {
        val stepX = (frame.width / 16).coerceAtLeast(1)
        val stepY = (frame.height / 16).coerceAtLeast(1)
        for (y in 0 until frame.height step stepY) for (x in 0 until frame.width step stepX) {
            val c = frame.getPixel(x, y)
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            if (r > 16 || g > 16 || b > 16) return false
        }
        return true
    }

    private fun fail(title: String, message: String, action: FailAction? = null) {
        ui = EmulationUi.Failed(title, message, action)
    }

    // region Ciclo de vida

    override fun onResume() {
        super.onResume()
        if (awaitingFileAccess && StorageAccess.granted(this)) {
            awaitingFileAccess = false
            recreate()
            return
        }
        activityResumed = true
        hideSystemBars()
        updateEmulationState()
    }

    override fun onPause() {
        activityResumed = false
        persist(auto = settings.autoSave)
        updateEmulationState()
        super.onPause()
    }

    override fun onDestroy() {
        sharing?.server?.close()
        // O InputManager é global: sem remover o listener, cada jogo aberto vazaria esta Activity.
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputDeviceListener)
        emulationOwner.registry.currentState = Lifecycle.State.DESTROYED
        retroView = null
        // O que ficou na fila ainda é gravado; depois o consumidor termina.
        padSaves.close()
        super.onDestroy()
    }

    private fun updateEmulationState() {
        if (emulationOwner.registry.currentState == Lifecycle.State.DESTROYED) return
        val running = activityResumed && !menuOpen && ui is EmulationUi.Running
        val target = if (running) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
        if (running && sessionStart == 0L) sessionStart = System.currentTimeMillis()
        // O jogo volta a andar: o estado congelado deixa de ser o atual.
        if (running) frozenState = null
        if (!running) {
            flushPlayTime()
            rumbleVibrator?.cancel()
        }
        emulationOwner.registry.currentState = target
    }

    private fun flushPlayTime() {
        if (sessionStart == 0L || !::game.isInitialized) return
        val seconds = (System.currentTimeMillis() - sessionStart) / 1000
        sessionStart = 0L
        app.scope.launch { app.library.recordSession(game.id, seconds) }
    }

    private fun emulationRunning() = emulationOwner.registry.currentState == Lifecycle.State.RESUMED

    /** Grava a SRAM (e o estado automático) de forma síncrona antes de pausar. */
    private fun persist(auto: Boolean) {
        val view = retroView ?: return
        if (ui !is EmulationUi.Running) return
        // Partida de outra pessoa: gravar aqui trocaria o progresso de quem joga pelo dela.
        if (guestSession) return
        runCatching {
            val running = emulationRunning()
            // A SRAM é só uma cópia da memória do jogo: pode ser lida de qualquer thread.
            val sram = view.serializeSRAM(running)
            // Temporário + renomear: falta de espaço ou o processo morto no meio não zeram o save do cartucho.
            if (sram.isNotEmpty()) {
                val file = states.sramFile()
                val tmp = File(file.path + ".tmp")
                tmp.writeBytes(sram)
                if (!tmp.renameTo(file)) tmp.delete()
            }
            if (auto && autoSaveReady) {
                // Rodando, o estado sai da thread de emulação (e fica guardado para depois da pausa);
                // parado, vale o que foi capturado quando a emulação parou.
                if (running) frozenState = view.serializeState().takeIf { it.isNotEmpty() }
                frozenState?.let { states.write(SaveStates.AUTO_SLOT, it, menuSnapshot) }
            }
        }
    }

    // endregion

    // region Menu

    private val menuActions = object : MenuActions {
        override fun open() = openMenu()
        override fun close() {
            closeShare()
            menuOpen = false
            padEditing = false
            menuFrame = null
            // A captura vale só para este menu; reaproveitá-la depois ilustraria o save com uma tela antiga.
            menuSnapshot = null
            updateEmulationState()
            // O núcleo só aceita trapaças na thread de emulação, que acabou de voltar a rodar.
            val view = retroView
            if (view != null && cheats?.dirty == true) applyCheats(view)
        }
        override fun slots() = if (::states.isInitialized) states.slots() else emptyList()
        override fun thumbnail(slot: Int) = states.thumbnail(slot)

        override fun save(slot: Int, onDone: () -> Unit) {
            // Antes do primeiro quadro o jogo ainda nem carregou: o estado sairia vazio ou inútil.
            if (!autoSaveReady) { toast = getString(R.string.game_state_not_ready); return }
            // Com o menu aberto a emulação está parada: o estado é o capturado ao abrir o menu.
            val data = frozenState
            // Núcleo que não gera estado: gravar o vazio apagaria um save bom do slot.
            if (data == null) {
                toast = getString(R.string.game_state_save_failed, getString(R.string.game_error_serialization))
                return
            }
            val thumbnail = menuSnapshot
            // Estados de PS2/GameCube passam de dezenas de MB: a gravação sai da thread principal.
            lifecycleScope.launch {
                toast = try {
                    withContext(Dispatchers.IO) { states.write(slot, data, thumbnail) }
                    getString(R.string.game_state_saved, slot)
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    getString(R.string.game_state_save_failed, t.userMessage(this@GameActivity))
                }
                onDone()
            }
        }

        override fun load(slot: Int) {
            val view = retroView ?: return
            // Sem jogo carregado não há o que restaurar, e o carregamento automático ainda viria por cima.
            if (!autoSaveReady) { toast = getString(R.string.game_state_not_ready); return }
            lifecycleScope.launch {
                val data = try {
                    withContext(Dispatchers.IO) { states.read(slot) }
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    null
                }
                // A Activity pode ter trocado de jogo ou fechado durante a leitura.
                if (retroView !== view) return@launch
                if (data == null || data.isEmpty()) {
                    toast = getString(R.string.game_state_load_failed)
                    return@launch
                }
                // O núcleo só pode ler o estado na thread de emulação, que precisa estar rodando:
                // o menu fecha antes. A espera pela thread fica fora da thread principal.
                close()
                val ok = withContext(Dispatchers.Default) { runCatching { view.unserializeState(data) }.getOrDefault(false) }
                if (retroView === view) toast = getString(if (ok) R.string.game_state_loaded else R.string.game_state_load_failed)
            }
        }

        override fun toggleFastForward() {
            fastForward = !fastForward
            retroView?.frameSpeed = if (fastForward) settings.fastForwardSpeed else 1
        }

        override fun setShader(option: ShaderOption) {
            retroView?.shader = option.toShaderConfig()
            settings = settings.copy(shader = option)
            app.scope.launch { app.settings.setShader(option) }
        }

        override fun coreOptions(): List<CoreOption> =
            retroView?.getVariables()?.mapNotNull(CoreOption::parse).orEmpty()

        override fun setCoreOption(option: CoreOption, value: String) {
            retroView?.updateVariables(Variable(option.key, value))
            app.scope.launch { app.settings.setCoreOption(core.id, option.key, value) }
        }

        override fun disks(): Pair<Int, Int> {
            val v = retroView ?: return 0 to 0
            return runCatching { v.getAvailableDisks(false) to v.getCurrentDisk(false) }.getOrDefault(0 to 0)
        }

        override fun changeDisk(index: Int) {
            retroView?.changeDisk(index, false)
            toast = getString(R.string.game_disk_inserted, index + 1)
        }

        override fun reset() {
            val view = retroView ?: return
            // Como o carregamento: reiniciar mexe no núcleo, então a emulação volta a rodar antes.
            close()
            lifecycleScope.launch(Dispatchers.Default) { view.reset() }
        }

        override fun coreName() = if (::core.isInitialized) core.displayName else ""

        override fun cheats(): CheatSession? = cheats

        override fun share(slot: Int) {
            if (!::states.isInitialized) return
            lifecycleScope.launch {
                val data = withContext(Dispatchers.IO) { runCatching { states.read(slot) }.getOrNull() }
                if (data == null || data.isEmpty()) { toast = getString(R.string.game_state_load_failed); return@launch }
                val thumb = withContext(Dispatchers.IO) { states.thumbnail(slot) }
                val bytes = withContext(Dispatchers.Default) { app.sharedStates.pack(game, core.id, data, thumb) }
                sharing = ShareSheet(game.title, bytes, thumb)
            }
        }

        override fun sharing(): ShareSheet? = sharing

        override fun shareAsFile() {
            val sheet = sharing ?: return
            lifecycleScope.launch {
                runCatching { app.sharedStates.shareIntent(game, sheet.bytes) }
                    .onSuccess { startActivity(Intent.createChooser(it, getString(R.string.share_state_title))) }
                    .onFailure { toast = it.userMessage(this@GameActivity) }
            }
        }

        override fun shareAsQr() {
            val sheet = sharing ?: return
            if (sheet.server != null) return
            val hosts = LanTransfer.localAddresses()
            if (hosts.isEmpty()) { sheet.noNetwork = true; return }
            val token = LanTransfer.newToken()
            val server = runCatching { LanTransfer.Server(sheet.bytes, token) }.getOrElse { sheet.noNetwork = true; return }
            sheet.server = server
            sheet.link = RetrovikaLink.State(hosts, server.port, token, game.title).toUri()
        }

        override fun closeShare() {
            sharing?.server?.close()
            sharing = null
        }

        override fun setPadProfile(profile: PadProfile) {
            padProfile = profile
            if (::core.isInitialized) {
                padDirty = true
                sendPadSave(padForGame to profile)
            }
        }

        override fun padForGame() = padForGame

        override fun setPadForGame(forGame: Boolean) {
            if (!::game.isInitialized || forGame == padForGame) return
            padForGame = forGame
            if (forGame) {
                // Começa como cópia do controle do console, que continua valendo para os outros jogos.
                padDirty = true
                sendPadSave(true to padProfile)
            } else {
                // O jogo volta a seguir o console: o próprio é apagado e o do console reaparece pelo fluxo.
                padDirty = true
                sendPadSave(true to null)
                lifecycleScope.launch { padProfile = app.settings.padProfile(system.id).first() }
            }
        }

        override fun startPadEditor() { padEditing = true }

        override fun stopPadEditor() { padEditing = false }

        override fun exit() {
            persist(auto = settings.autoSave)
            // O LibretroDroid é global e o onDestroy desta Activity roda só depois que a próxima tela
            // aparece: abrir outro jogo logo em seguida teria o emulador novo destruído por este.
            // Encerrar aqui, de forma síncrona, fecha essa janela.
            flushPlayTime()
            retroView = null
            emulationOwner.registry.currentState = Lifecycle.State.DESTROYED
            finish()
        }
    }

    /**
     * Refaz a lista de trapaças no núcleo: limpa e liga as marcadas, em índices seguidos. Desligar uma só
     * não basta em vários núcleos (o código já gravado na memória fica), por isso a lista inteira é refeita.
     */
    private fun applyCheats(view: GLRetroView) {
        val session = cheats ?: return
        session.dirty = false
        val codes = session.state.enabled.map { it.code }
        lifecycleScope.launch(Dispatchers.Default) {
            view.resetCheat()
            codes.forEachIndexed { i, code -> view.setCheat(i, true, code) }
        }
        if (codes.isNotEmpty()) toast = resources.getQuantityString(R.plurals.cheats_applied, codes.size, codes.size)
    }

    private fun sendPadSave(save: Pair<Boolean, PadProfile?>) {
        pendingPadSaves++
        padSaves.trySend(save)
    }

    private fun toggleMenu() {
        // No editor de layout, voltar retorna ao menu (o que foi mexido e não salvo é descartado).
        if (padEditing) { padEditing = false; return }
        if (menuOpen) menuActions.close() else openMenu()
    }

    private fun openMenu() {
        if (menuOpen || menuOpening) return
        val view = retroView
        if (view == null || ui !is EmulationUi.Running) { finish(); return }
        // Com o menu aberto os eventos do controle não chegam ao núcleo: o que estava apertado ao abrir
        // ficaria preso (personagem andando sozinho) ao voltar ao jogo.
        releaseAllInputs(view)
        menuOpening = true
        // Captura a tela antes de pausar: vira a miniatura dos save states.
        captureFrame(view) { bmp, full ->
            lifecycleScope.launch {
                // O estado também sai antes de pausar, na thread de emulação (a espera fica fora da principal):
                // é ele que o menu grava nos slots e no salvamento automático.
                val state = withContext(Dispatchers.Default) { runCatching { view.serializeState() }.getOrNull() }
                menuOpening = false
                if (retroView !== view) return@launch
                frozenState = state?.takeIf { it.isNotEmpty() }
                menuSnapshot = bmp
                menuFrame = full
                menuOpen = true
                updateEmulationState()
            }
        }
    }

    /** [done] recebe a miniatura dos save states e a captura em tamanho real (fundo do editor de layout). */
    private fun captureFrame(view: GLRetroView, done: (Bitmap?, Bitmap?) -> Unit) {
        if (view.width == 0 || view.height == 0) return done(null, null)
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(view, bmp, { result ->
                if (result == PixelCopy.SUCCESS) {
                    done(Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true), bmp)
                } else done(null, null)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { done(null, null) }
    }

    // endregion

    // region Entrada

    private val padListener = object : PadListener {
        override fun onKey(keyCode: Int, pressed: Boolean) {
            retroView?.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode)
        }

        override fun onMotion(source: Int, x: Float, y: Float) {
            val s = when (source) {
                MotionSources.DPAD -> GLRetroView.MOTION_SOURCE_DPAD
                MotionSources.ANALOG_LEFT -> GLRetroView.MOTION_SOURCE_ANALOG_LEFT
                else -> GLRetroView.MOTION_SOURCE_ANALOG_RIGHT
            }
            retroView?.sendMotionEvent(s, x, y)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = retroView
        val isMenuKey = event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE
        if (isMenuKey) {
            if (event.action == KeyEvent.ACTION_UP) toggleMenu()
            return true
        }
        if (!menuOpen && view != null && isGamepadEvent(event)) {
            // Setas soltas não contam: gestos do leitor de digital chegam como D-pad em alguns aparelhos.
            val fromPad = KeyEvent.isGamepadButton(event.keyCode) || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
            if (fromPad && isPhysicalController(event.device)) controllerActive = true
            return if (event.action == KeyEvent.ACTION_DOWN) view.onKeyDown(event.keyCode, event) else view.onKeyUp(event.keyCode, event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val view = retroView
        if (!menuOpen && view != null && (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            if (isPhysicalController(event.device)) controllerActive = true
            return view.onGenericMotionEvent(event)
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun releaseAllInputs(view: GLRetroView) {
        val ports = (InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)?.takeIf { isPhysicalController(it) }?.controllerNumber?.takeIf { it > 0 }?.minus(1)
        } + 0).toSet()
        ports.forEach { port ->
            RETROPAD_KEYS.forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it, port) }
            listOf(GLRetroView.MOTION_SOURCE_DPAD, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT)
                .forEach { view.sendMotionEvent(it, 0f, 0f, port) }
        }
    }

    private fun isGamepadEvent(event: KeyEvent): Boolean {
        val src = event.source
        return KeyEvent.isGamepadButton(event.keyCode) ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            ((src and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD && event.device?.isVirtual == false)
    }

    private fun detectController(): Boolean = InputDevice.getDeviceIds().any { id -> isPhysicalController(InputDevice.getDevice(id)) }

    private fun isPhysicalController(d: InputDevice?): Boolean =
        d != null && !d.isVirtual && (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)

    // endregion

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val rumbleVibrator: Vibrator? by lazy {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        v?.takeIf { it.hasVibrator() }
    }

    companion object {
        private const val EXTRA_GAME_ID = "game_id"
        private const val BLACK_SCREEN_CHECKS = 4
        private const val BLACK_SCREEN_INTERVAL_MS = 8_000L
        /** Teto de uma vibração de rumble contínua; o núcleo manda força zero para parar antes disso. */
        private const val RUMBLE_MAX_MS = 10_000L
        /** Portas do LibretroDroid (Input::getInputState ignora port >= 4). */
        private const val MAX_PORTS = 4
        private val RETROPAD_KEYS = listOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        private const val EXTRA_STATE_FILE = "state_file"
        private const val EXTRA_CORE_ID = "core_id"

        /** [stateFile]: estado recebido para abrir no lugar do salvamento automático, no núcleo [coreId]. */
        fun launch(context: Context, gameId: Long, stateFile: File? = null, coreId: String? = null) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_GAME_ID, gameId)
                    .apply { stateFile?.let { putExtra(EXTRA_STATE_FILE, it.absolutePath) } }
                    .apply { coreId?.let { putExtra(EXTRA_CORE_ID, it) } }
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

fun ShaderOption.toShaderConfig(): ShaderConfig = when (this) {
    ShaderOption.DEFAULT -> ShaderConfig.Default
    ShaderOption.SHARP -> ShaderConfig.Sharp
    ShaderOption.CRT -> ShaderConfig.CRT
    ShaderOption.LCD -> ShaderConfig.LCD
}

interface MenuActions {
    fun open()
    fun close()
    fun slots(): List<SaveSlot>
    fun thumbnail(slot: Int): Bitmap?
    fun save(slot: Int, onDone: () -> Unit)
    fun load(slot: Int)
    fun toggleFastForward()
    fun setShader(option: ShaderOption)
    fun coreOptions(): List<CoreOption>
    fun setCoreOption(option: CoreOption, value: String)
    fun disks(): Pair<Int, Int>
    fun changeDisk(index: Int)
    fun reset()
    fun coreName(): String
    fun cheats(): CheatSession?
    fun share(slot: Int)
    fun sharing(): ShareSheet?
    fun shareAsFile()
    fun shareAsQr()
    fun closeShare()
    fun setPadProfile(profile: PadProfile)
    fun padForGame(): Boolean
    fun setPadForGame(forGame: Boolean)
    fun startPadEditor()
    fun stopPadEditor()
    fun exit()
}
