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
import androidx.compose.runtime.snapshotFlow
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
import com.retrovika.app.core.settings.SettingsRepository
import com.retrovika.app.core.settings.ShaderOption
import com.retrovika.app.core.storage.RZip
import com.retrovika.app.core.storage.StorageAccess
import com.retrovika.app.core.share.LanTransfer
import com.retrovika.app.core.share.StatePackage
import com.retrovika.app.core.cores.CoreBenchmark
import com.retrovika.app.core.cores.CoreSpeed
import com.retrovika.app.core.cores.SystemBenchmark
import com.retrovika.app.core.net.Http
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import com.retrovika.app.core.settings.uiLanguage
import com.retrovika.app.core.translate.AiConfig
import com.retrovika.app.core.translate.Box
import com.retrovika.app.core.translate.GeminiText
import com.retrovika.app.core.translate.LiveTranslator
import com.retrovika.app.core.translate.OcrFrame
import com.retrovika.app.core.share.RetrovikaLink
import com.retrovika.app.core.systems.CoreInfo
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Orientation
import com.retrovika.app.core.systems.Preset
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.core.tuning.DeviceProfile
import com.retrovika.app.core.tuning.EffectivePreset
import com.retrovika.app.core.tuning.ExitKind
import com.retrovika.app.core.tuning.PlaySession
import com.retrovika.app.core.tuning.SpeedWatch
import com.retrovika.app.core.tuning.TuneSource
import com.retrovika.app.core.tuning.TuneResult
import com.retrovika.app.core.tuning.Tuning
import com.retrovika.app.emulation.input.MotionSources
import com.retrovika.app.emulation.input.PadProfile
import com.retrovika.app.emulation.input.PadListener
import com.retrovika.app.remote.RemoteGame
import com.retrovika.app.ui.theme.RetrovikaTheme
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface EmulationUi {
    data class Preparing(val message: String, val progress: Float?) : EmulationUi
    data class Running(val view: GLRetroView) : EmulationUi
    data class Failed(val title: String, val message: String, val action: FailAction? = null) : EmulationUi
    /**
     * Teste na primeira vez do console (ou do jogo): [view] é o que está sendo medido (nulo entre um e outro);
     * [items] são os núcleos ou os níveis de qualidade, conforme [kind].
     */
    data class Benchmarking(val view: GLRetroView?, val kind: BenchKind, val items: List<BenchItem>, val current: Int, val results: List<CoreSpeed>) : EmulationUi
}

enum class BenchKind { CORES, QUALITY, GAME }

/** Uma linha do teste: [id] é o que [CoreSpeed.coreId] guarda (o núcleo ou o nível de qualidade). */
data class BenchItem(val id: String, val label: String)

/** Botão extra da tela de falha, para o que o usuário pode resolver na hora (ex.: conceder uma permissão). */
data class FailAction(val label: String, val run: () -> Unit)

class GameActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(com.retrovika.app.core.settings.Languages.wrap(newBase))


    private val app by lazy { container }

    /**
     * Ciclo de vida exclusivo do emulador: acompanha o da Activity, mas fica em STARTED
     * (pausado) enquanto o menu está aberto. É assim que pausamos o LibretroDroid com segurança.
     */
    private val emulationOwner = EmulationOwner()

    private class EmulationOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /** Ciclo de vida do núcleo em teste: um por núcleo, destruído ao fim da medição. */
    private var benchOwner: EmulationOwner? = null
    @Volatile private var benchSkip = false

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
    /** O estado demora a sair (PS2, GameCube): um indicador na tela mostra que o toque no menu chegou. */
    private var busy by mutableStateOf(false)
    /** Quantas vezes a emulação parou: compara antes e depois de uma operação para saber se ela pausou no meio. */
    private var emulationPauses = 0

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
    /** Tradução da tela aberta: a emulação fica pausada até ela fechar. */
    private var translation by mutableStateOf<TranslationUi?>(null)
    /** Criado só quando alguém traduz: os clientes do ML Kit ficam fora dos jogos que não usam. */
    private var translator: LiveTranslator? = null
    private var translateJob: kotlinx.coroutines.Job? = null
    /** Partida em rede local (anfitrião ou convidado). */
    private val netplay by lazy {
        NetplayController(lifecycleScope, { retroView }, ::resumeForNetplay) { res -> toast = getString(res) }
    }
    /** Convidado de uma partida em rede: endereço, porta e token do anfitrião, vindos do QR code. */
    private var netGuest: Triple<String, Int, String>? = null
    /** Estado sendo compartilhado a partir do menu. */
    private var sharing by mutableStateOf<ShareSheet?>(null)
    private lateinit var states: SaveStates
    /** Trapaças do jogo; nulo até o jogo ser encontrado. */
    private var cheats: CheatSession? = null
    private var settings = AppSettings()
    private var retroView: GLRetroView? = null
    /** O GLRetroView ligado ao jogo pela rede; continua aqui depois do "Sair" zerar o [retroView]. */
    private var remoteView: GLRetroView? = null
    private var sessionStart = 0L
    private var activityResumed = false

    /**
     * Só depois do primeiro quadro (e do carregamento automático) o estado do jogo é real. Antes disso,
     * sair do jogo gravaria a tela de boot por cima do salvamento automático e o progresso se perderia.
     */
    private var autoSaveReady = false
    /**
     * Primeiro quadro desenhado: o núcleo terminou de carregar o jogo. Antes disso a thread de emulação está
     * presa no carregamento (pedir a SRAM por ela travaria a thread principal até ele acabar), e a SRAM ainda
     * é a do boot, ou nem existe: gravá-la por cima do .srm apagaria o save do cartucho.
     */
    private var gameLoaded = false
    /** Esta abertura conta como tentativa de Vulkan, ainda sem o primeiro quadro (ver VulkanHealth). */
    private var vulkanAttempt = false
    /** Esta abertura usa Vulkan: uma perda do contexto no meio do jogo também desliga o Vulkan do núcleo. */
    private var vulkanLaunch = false
    /** O jogo na frente do usuário, para o SessionGuard; nulo antes do primeiro quadro (aí vale a tentativa de Vulkan). */
    private var playSession: PlaySession? = null
    /** Quando o primeiro quadro chegou (elapsedRealtime): uma sessão longa que termina bem conta a favor do Vulkan. */
    private var playSessionStart = 0L
    private val sramLock = Any()
    /** A última SRAM gravada: sem mudança, o arquivo não é escrito de novo. */
    private var lastSram: ByteArray? = null

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
        keepScreenOnWhileActive()
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
                    busy = busy,
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
        if (newId == this.intent.getLongExtra(EXTRA_GAME_ID, -1) && intent.getStringExtra(EXTRA_STATE_FILE) == null &&
            intent.getStringExtra(EXTRA_NET_HOST) == null && !intent.getBooleanExtra(EXTRA_RETUNE, false)) return
        netplay.end()
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
        netGuest = intent.getStringExtra(EXTRA_NET_HOST)?.let { h ->
            val token = intent.getStringExtra(EXTRA_NET_TOKEN) ?: return@let null
            Triple(h, intent.getIntExtra(EXTRA_NET_PORT, 0), token)
        }
        guestSession = pendingStateFile != null || netGuest != null
        // O estado só abre no núcleo que o gerou: quem enviou diz qual é.
        val sharedCore = intent.getStringExtra(EXTRA_CORE_ID)?.takeIf { id -> system.cores.any { it.id == id } }
        val userCore = sharedCore ?: game.coreOverride ?: app.settings.coreFor(system.id).first()
        core = system.core(userCore ?: app.settings.effectiveCoreFor(system.id).first())
        // A versão nova do núcleo baixada na sessão anterior entra agora, antes de qualquer abertura dele.
        if (app.cores.applyStagedUpdate(core.id)) toast = getString(R.string.core_updated, core.displayName)
        handleDeadSession()
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

        // 1. BIOS obrigatórias (cada item pode ter alternativas: basta uma delas).
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

        // 2. Primeira vez do console neste aparelho, sem núcleo escolhido: testa os núcleos e fica com o ideal.
        if (userCore == null && !guestSession && settings.autoBenchmark && app.settings.benchmark(system.id).first() == null) {
            benchmarkCandidates().takeIf { it.size > 1 }?.let { candidates ->
                val chosen = runBenchmark(candidates) ?: return
                core = system.core(chosen)
            }
        }

        // 2b. Nível de qualidade: mede neste aparelho o maior que roda com folga (ou refaz só para este jogo).
        val retune = intent.getBooleanExtra(EXTRA_RETUNE, false)
        if (!guestSession && core.presets.size >= 2 && (retune || shouldTune(core))) {
            runTuning(core, perGame = retune) ?: return
        }

        // 3. Núcleo e arquivos de sistema dele: baixados automaticamente na primeira execução.
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

        // 4. Opções: padrões otimizados + preset escolhido + ajustes manuais do usuário.
        val options = optionsFor(core)
        // Vulkan só para o núcleo que o pede e quando o aparelho e o histórico dele deixam; cada abertura com
        // Vulkan é uma tentativa que o primeiro quadro confirma (um driver ruim derruba o app, e isso se aprende).
        val vulkan = vulkanAllowed(core)
        vulkanLaunch = vulkan
        if (vulkan) {
            vulkanAttempt = true
            app.vulkanHealth.attemptStarted(core.id)
        } else if (core.vulkan && intent.getBooleanExtra(EXTRA_NO_VULKAN, false)) {
            toast = getString(R.string.vulkan_fallback)
        }

        ui = EmulationUi.Preparing(getString(R.string.game_starting, game.title), null)
        val sram = withContext(Dispatchers.IO) { states.sramFile().takeIf { it.exists() }?.readBytes() }

        // Alguns núcleos (Play!) montam a pasta de dados a partir de $EXTERNAL_STORAGE, que aponta para o
        // /sdcard sem permissão de escrita: o núcleo abortava ao criar a pasta. Vale para o processo
        // (é o getenv nativo) e é refeito a cada jogo.
        runCatching { android.system.Os.setenv("EXTERNAL_STORAGE", app.paths.savesFor(system.id).absolutePath, true) }

        val data = GLRetroViewData(this).apply {
            coreFilePath = corePath
            when (setGameSource(this, core)) {
                GameSource.Ok -> Unit
                GameSource.NeedsFileAccess -> {
                    awaitingFileAccess = true
                    return fail(
                        getString(R.string.game_needs_file_access),
                        getString(R.string.game_needs_file_access_message, core.displayName),
                        FailAction(getString(R.string.game_allow_file_access)) { startActivity(StorageAccess.settingsIntent(this@GameActivity)) },
                    )
                }
                GameSource.Inaccessible -> return fail(getString(R.string.game_file_inaccessible), getString(R.string.game_file_inaccessible_message, game.fileName))
                GameSource.NotFound -> return fail(getString(R.string.game_file_not_found), game.uri)
            }
            configure(this, core, options, vulkan)
            saveRAMState = sram
        }

        // Voltar durante o "Iniciando…" já chamou finish(): iniciar o núcleo agora derrubaria o do
        // próximo jogo quando esta Activity fosse destruída (o LibretroDroid é global).
        if (isFinishing) { closeVirtualFiles(data); return }
        val view = GLRetroView(this, data).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // O GLRetroView pede a tela acesa enquanto existe; quem decide é keepScreenOnWhileActive (apaga no menu).
            keepScreenOn = false
        }
        retroView = view
        emulationOwner.registry.addObserver(view)
        observe(view)
        ui = EmulationUi.Running(view)
        remoteView = view
        app.remote.attach(view, RemoteGame(game.title, system.name, system.accent, system.layout), ::physicalControllerPorts)
        updateEmulationState()
        view.requestFocus()
        watchSpeed(view)
    }

    /**
     * Vigia a velocidade durante o jogo: se ela fica abaixo do nativo por vários segundos (a medição da
     * abertura não pegou uma cena pesada), baixa um nível de qualidade só deste jogo, para a próxima vez.
     * Não conta com o menu aberto, avanço rápido, tela transmitida (o encoder divide o aparelho) ou rede.
     */
    private fun watchSpeed(view: GLRetroView) {
        if (guestSession || core.presets.size < 2) return
        lifecycleScope.launch {
            val watch = SpeedWatch()
            // O estado do próprio emulador, não só o menu: a tradução da tela e o segundo plano também o pausam.
            fun active() = retroView === view && ui is EmulationUi.Running && emulationOwner.registry.currentState == Lifecycle.State.RESUMED &&
                !fastForward && !netplay.playing && app.remote.state.value.screenCount == 0
            while (retroView === view) {
                val frames = view.runCount()
                // Antes do primeiro quadro o núcleo ainda carrega o jogo: não é lentidão.
                if (frames == 0L || !active()) { delay(WATCH_INTERVAL_MS); continue }
                val t0 = android.os.SystemClock.elapsedRealtime()
                delay(WATCH_INTERVAL_MS)
                if (!active()) continue
                if (watch.sample(view.runCount() - frames, android.os.SystemClock.elapsedRealtime() - t0, view.contentFps())) lowerQualityForGame()
            }
        }
    }

    private suspend fun lowerQualityForGame() {
        val current = effectivePreset(core) ?: return
        val lower = Tuning.lower(core, current.preset) ?: return
        // Nível escolhido pelo usuário para o console: a escolha é dele, só avisa que um nível menor pode ajudar.
        if (current.source == TuneSource.USER) {
            toast = getString(R.string.tune_slowdown_user, getString(current.preset.label))
            return
        }
        // Os ajustes manuais das opções cobrem tudo o que o nível muda: baixar não mudaria nada no jogo.
        if (optionsFor(core, lower) == optionsFor(core, current.preset)) return
        val profile = app.deviceProfile.await()
        app.settings.setGameTuning(game.id, TuneResult(core.id, lower.name, null, profile.signature, System.currentTimeMillis(), slowdown = true))
        toast = getString(R.string.tune_slowdown, getString(lower.label))
    }

    // region Quedas no meio do jogo

    /** O primeiro quadro chegou: daqui em diante, se o processo morrer com o jogo na frente, a próxima abertura sabe. */
    private suspend fun openSession() {
        val level = if (guestSession) null else effectivePreset(core)
        val session = PlaySession(
            systemId = system.id, coreId = core.id, gameId = game.id,
            vulkan = vulkanLaunch, vulkanKey = if (vulkanLaunch) app.vulkanKey(core.id) else null,
            preset = level?.preset?.name, userPreset = level?.source == TuneSource.USER,
            pid = android.os.Process.myPid(),
        )
        if (isFinishing || isDestroyed) return
        playSession = session
        playSessionStart = android.os.SystemClock.elapsedRealtime()
        if (activityResumed) app.sessions.open(session)
    }

    /** O jogo saiu da frente sem queda. Uma sessão longa com Vulkan conta a favor dele (uma vez por sessão). */
    private fun closeSession() {
        val session = playSession ?: return
        app.sessions.close()
        if (session.vulkan && playSessionStart > 0 && android.os.SystemClock.elapsedRealtime() - playSessionStart >= CLEAN_SESSION_MS) {
            playSessionStart = 0
            app.vulkanHealth.sessionEnded(session.coreId)
        }
    }

    /**
     * O processo anterior morreu com um jogo na frente (ver SessionGuard). Aquele jogo passa a abrir um nível abaixo
     * (resolução alta é o que mais gasta memória de vídeo) e, se rodava com Vulkan, a queda conta contra o Vulkan do
     * núcleo. Avisa quando é o jogo, ou o núcleo, que está abrindo agora.
     */
    private suspend fun handleDeadSession() {
        val (session, exit) = app.sessions.takeDead(android.os.Process.myPid(), ::exitReason) ?: return
        if (exit == ExitKind.OTHER) return
        val crashedCore = Systems.byId(session.systemId)?.cores?.firstOrNull { it.id == session.coreId } ?: return
        if (session.vulkan && exit == ExitKind.CRASH && session.vulkanKey != null &&
            app.vulkanHealth.sessionCrashed(session.coreId, session.vulkanKey)
        ) {
            // O nível daquele jogo foi escolhido para o Vulkan: sem ele, o console é medido de novo.
            app.settings.clearTuning(session.systemId, listOf(session.gameId))
            if (session.coreId == core.id) toast = getString(R.string.vulkan_off_crashes, crashedCore.displayName)
            return
        }
        if (session.userPreset) return
        val lower = session.preset?.let { runCatching { Preset.valueOf(it) }.getOrNull() }?.let { Tuning.lower(crashedCore, it) } ?: return
        val profile = app.deviceProfile.await()
        app.settings.setGameTuning(session.gameId, TuneResult(crashedCore.id, lower.name, null, profile.signature, System.currentTimeMillis(), crashed = true))
        if (session.gameId == game.id) toast = getString(R.string.tune_crash_lowered, getString(lower.label))
    }

    /** O motivo que o sistema guardou para a morte do processo [pid] (Android 11+); nulo quando não sabe. */
    private fun exitReason(pid: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching {
            getSystemService(android.app.ActivityManager::class.java).getHistoricalProcessExitReasons(packageName, pid, 1).firstOrNull()?.reason
        }.getOrNull()
    }

    // endregion

    private enum class GameSource { Ok, NeedsFileAccess, Inaccessible, NotFound }

    /** Aponta [data] para o arquivo do jogo: caminho real quando dá, senão os arquivos virtuais do SAF. */
    private suspend fun setGameSource(data: GLRetroViewData, core: CoreInfo): GameSource {
        val realFile = if (game.isContentUri) withContext(Dispatchers.IO) { StorageAccess.realFile(this@GameActivity, Uri.parse(game.uri)) } else null
        when {
            // Com acesso aos arquivos, o núcleo recebe o caminho real: funciona até nos que não usam a VFS.
            realFile != null -> data.gameFilePath = realFile.path
            game.isContentUri && core.needsRealPath -> return GameSource.NeedsFileAccess
            game.isContentUri -> {
                // IPC com o provedor SAF: fora da thread principal. Descritores novos a cada carga (o núcleo fica com eles).
                data.gameVirtualFiles = runCatching { withContext(Dispatchers.IO) { GameFiles.virtualFiles(contentResolver, game) } }.getOrElse {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    return GameSource.Inaccessible
                }
            }
            else -> {
                if (!File(game.uri).exists()) return GameSource.NotFound
                data.gameFilePath = game.uri
            }
        }
        return GameSource.Ok
    }

    /** Fecha os descritores do SAF de uma carga que não vai acontecer (a GLRetroView nunca foi criada). */
    private fun closeVirtualFiles(data: GLRetroViewData) {
        data.gameVirtualFiles.forEach { runCatching { it.fileDescriptor.close() } }
        data.gameVirtualFiles = emptyList()
    }

    /** A predefinição que vale agora para [core] neste jogo e aparelho, e por quê; nulo se o núcleo não tem predefinições. */
    private suspend fun effectivePreset(core: CoreInfo): EffectivePreset? = Tuning.effective(
        core, app.deviceProfile.await(), app.settings.presetChoice(system.id).first(),
        app.settings.gameTuning(game.id).first(), app.settings.tuning(system.id, core.id).first(),
    )

    /** [preset] força um nível (o teste de qualidade); sem ele vale o [effectivePreset]. */
    private suspend fun optionsFor(core: CoreInfo, preset: Preset? = null): Map<String, String> {
        val level = preset ?: effectivePreset(core)?.preset
        // Sem Vulkan para este núcleo (o aparelho não tem, ou ele falhou), o perfil diz isso e o núcleo escolhe outro renderizador.
        val allowed = vulkanAllowed(core)
        val profile = app.deviceProfile.await().let { if (it.vulkan && !allowed) it.copy(vulkan = false) else it }
        val device = core.deviceOptions?.invoke(profile).orEmpty()
        val user = app.settings.coreOptions(core.id).let { if (core.vulkan && !allowed) withoutVulkan(core, profile, it) else it }
        return core.defaults + level?.let { core.presets[it] }.orEmpty() + device + user + core.fixed
    }

    /**
     * Sem Vulkan nesta abertura, um ajuste manual que pede Vulkan (ppsspp_backend=vulkan, pcsx2_renderer=Vulkan,
     * escolhido quando ainda funcionava) faria o núcleo abortar a cada abertura. As chaves do renderizador são as
     * que as [CoreInfo.deviceOptions] mudam conforme o aparelho tem Vulkan; nelas, o valor do usuário que fala
     * em Vulkan sai e fica o do aparelho sem ele.
     */
    private fun withoutVulkan(core: CoreInfo, profile: DeviceProfile, user: Map<String, String>): Map<String, String> {
        val options = core.deviceOptions ?: return user
        val off = options(profile.copy(vulkan = false))
        val on = options(profile.copy(vulkan = true))
        return user.filter { (key, value) -> !(on[key] != off[key] && value.contains("vulkan", ignoreCase = true)) }
    }

    /** Vulkan vale para [core] agora: ele o pede, o aparelho declara Vulkan 1.1+ e ele não falhou aqui. */
    private suspend fun vulkanAllowed(core: CoreInfo): Boolean {
        if (!core.vulkan || intent.getBooleanExtra(EXTRA_NO_VULKAN, false) || app.settings.vulkanDisabled(core.id).first()) return false
        val device = app.deviceProfile.await()
        return device.vulkan && app.vulkanHealth.shouldTry(core.id, app.vulkanKey(core.id))
    }

    private fun configure(data: GLRetroViewData, core: CoreInfo, options: Map<String, String>, vulkan: Boolean) = data.apply {
        allowVulkan = vulkan
        systemDirectory = app.paths.system.absolutePath
        savesDirectory = app.paths.savesFor(system.id).absolutePath
        variables = options.map { Variable(it.key, it.value) }.toTypedArray()
        shader = settings.shader.toShaderConfig()
        preferLowLatencyAudio = settings.lowLatencyAudio
        rumbleEventsEnabled = true
        relaxedGlesVersion = core.relaxedGlesVersion
        // Controles físicos vão para as portas 1 a 4 (controllerNumber), então todas recebem o tipo.
        core.portDevice?.let { device -> controllerTypes = IntArray(MAX_PORTS) { device } }
    }

    // region Teste dos núcleos

    @kotlinx.serialization.Serializable
    private data class BenchProgress(
        val results: List<CoreSpeed>,
        val running: String?,
        /** Itens que derrubaram o app em tentativas anteriores. */
        val crashed: List<String> = emptyList(),
    )

    /** Núcleos que entram no teste: os estáveis que conseguem abrir este arquivo. */
    private suspend fun benchmarkCandidates(): List<CoreInfo> {
        val realPath = !game.isContentUri || withContext(Dispatchers.IO) { StorageAccess.realFile(this@GameActivity, Uri.parse(game.uri)) } != null
        return system.cores.filter { !it.experimental && (realPath || !it.needsRealPath) }
    }

    /**
     * Roda cada núcleo com este jogo por alguns segundos, sem som e sem o limite de velocidade, e fica com
     * o mais fiel que roda com folga ([CoreBenchmark.choose]). O progresso vai para um arquivo antes de cada
     * núcleo: se um deles derrubar o app, na próxima abertura ele conta como falho e o teste continua.
     * Nulo quando a Activity está fechando.
     */
    /**
     * Marcador de teste em andamento, em filesDir/benchmark/ (fora do backup: num aparelho restaurado ele apontaria
     * uma queda que não aconteceu). Um marcador antigo, da raiz de filesDir, é apagado.
     */
    private fun benchMarker(name: String): File {
        File(filesDir, name).delete()
        return File(File(filesDir, BENCH_DIR).apply { mkdirs() }, name)
    }

    private suspend fun runBenchmark(candidates: List<CoreInfo>): String? {
        val marker = benchMarker("benchmark_${system.id}.json")
        val previous = runCatching { Http.json.decodeFromString(BenchProgress.serializer(), marker.readText()) }.getOrNull()
        val results = previous?.results.orEmpty().toMutableList()
        previous?.running?.let { crashed -> if (results.none { it.coreId == crashed }) results += CoreSpeed(crashed, null) }
        // Os que derrubaram o app (agora ou antes): continuam pulados mesmo quando o teste não chega a valer.
        val crashed = (previous?.crashed.orEmpty() + listOfNotNull(previous?.running)).distinct()
        benchSkip = false
        val items = candidates.map { BenchItem(it.id, it.displayName) }
        var finished = false
        try {
            for ((i, c) in candidates.withIndex()) {
                if (benchSkip) break
                if (results.any { it.coreId == c.id }) continue
                writeBenchProgress(marker, BenchProgress(results, c.id, crashed))
                val speed = measure(c, optionsFor(c), BenchKind.CORES, items, i, results)
                if (isFinishing) return null
                if (!benchSkip) results += CoreSpeed(c.id, speed)
            }
            finished = true
        } finally {
            // Sair no meio do teste não é falha do núcleo: só um crash (que pula este bloco) deixa "running" no arquivo.
            withContext(kotlinx.coroutines.NonCancellable) { finishBenchProgress(marker, finished, ran = benchSkip || results.any { it.speed != null }, results, crashed) }
        }
        // Nenhum núcleo rodou (arquivo ilegível, sem internet para instalar, jogo que não abre): não há o que
        // guardar, e gravar "o padrão" travaria essa escolha para sempre. Fica o padrão só nesta abertura.
        if (!benchSkip && results.none { it.speed != null }) {
            ui = EmulationUi.Preparing(getString(R.string.game_loading), null)
            return system.defaultCore.id
        }
        val chosen = if (benchSkip) system.defaultCore.id else CoreBenchmark.choose(candidates.map { it.id }, results)
        app.settings.setBenchmark(
            system.id,
            SystemBenchmark(results, chosen, SettingsRepository.BENCH_DEVICE, System.currentTimeMillis(), skipped = benchSkip),
        )
        if (!benchSkip) {
            val speed = results.firstOrNull { it.coreId == chosen }?.speed
            toast = getString(R.string.bench_chosen, system.core(chosen).displayName, speed?.let { (it * 100).toInt() } ?: 0)
        }
        ui = EmulationUi.Preparing(getString(R.string.game_loading), null)
        return chosen
    }

    private suspend fun writeBenchProgress(marker: File, progress: BenchProgress) = withContext(Dispatchers.IO) {
        runCatching { marker.writeText(Http.json.encodeToString(BenchProgress.serializer(), progress)) }
    }

    /**
     * Fim do teste: terminado e com algo medido ([ran]), o arquivo some; interrompido, guarda o que já foi medido.
     * Terminado sem nada medido, o resultado não é gravado e o teste volta na próxima abertura: o arquivo fica
     * só com os itens que derrubaram o app ([crashed]), para eles não derrubarem de novo.
     */
    private suspend fun finishBenchProgress(marker: File, finished: Boolean, ran: Boolean, results: List<CoreSpeed>, crashed: List<String>) {
        when {
            finished && ran -> withContext(Dispatchers.IO) { marker.delete() }
            finished -> writeBenchProgress(marker, BenchProgress(results.filter { it.coreId in crashed }, null, crashed))
            else -> writeBenchProgress(marker, BenchProgress(results, null, crashed))
        }
    }

    /** O teste de qualidade só roda sozinho na primeira vez do núcleo neste aparelho, e sem o usuário ter escolhido um nível. */
    private suspend fun shouldTune(core: CoreInfo): Boolean {
        if (!settings.autoBenchmark || app.settings.presetChoice(system.id).first() != null) return false
        return app.settings.tuning(system.id, core.id).first()?.appliesTo(app.deviceProfile.await(), core) != true
    }

    /**
     * Mede os níveis de qualidade de [core] com este jogo e guarda o maior que roda com folga, para o console
     * ou, com [perGame], só para este jogo. Devolve nulo se a Activity foi fechada no meio.
     */
    private suspend fun runTuning(core: CoreInfo, perGame: Boolean): Preset? {
        val profile = app.deviceProfile.await()
        val ladder = Tuning.ladder(core)
        val items = ladder.map { BenchItem(it.name, getString(it.label)) }
        val kind = if (perGame) BenchKind.GAME else BenchKind.QUALITY
        val marker = benchMarker(if (perGame) "benchmark_game_${game.id}.json" else "benchmark_${system.id}_${core.id}_quality.json")
        val previous = runCatching { Http.json.decodeFromString(BenchProgress.serializer(), marker.readText()) }.getOrNull()
        val results = previous?.results.orEmpty().toMutableList()
        previous?.running?.let { crashed -> if (results.none { it.coreId == crashed }) results += CoreSpeed(crashed, null) }
        val crashed = (previous?.crashed.orEmpty() + listOfNotNull(previous?.running)).distinct()
        // Sem zerar benchSkip: quem pulou o teste dos núcleos também pulou este (vale o chute pela classe do aparelho).
        val start = Tuning.estimate(profile.tier, core)!!
        var finished = false
        val (preset, speed) = try {
            Tuning.search(ladder, start) { level ->
                if (benchSkip || isFinishing) return@search null
                // Nível já medido numa tentativa anterior (que travou o app no seguinte): não repete.
                results.firstOrNull { it.coreId == level.name }?.let { return@search it.speed }
                writeBenchProgress(marker, BenchProgress(results, level.name, crashed))
                val measured = measure(core, optionsFor(core, level), kind, items, ladder.indexOf(level), results)
                if (!benchSkip && !isFinishing) results += CoreSpeed(level.name, measured)
                measured
            }.also { finished = !isFinishing }
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { finishBenchProgress(marker, finished, ran = benchSkip || results.any { it.speed != null }, results, crashed) }
        }
        if (isFinishing) return null
        // Pulou: fica o chute pela classe do aparelho, sem repetir o teste a cada abertura.
        val skipped = benchSkip || speed == null
        val result = TuneResult(core.id, (if (skipped) start else preset).name, speed.takeUnless { skipped }, profile.signature, System.currentTimeMillis(), skipped = skipped)
        // Nenhum nível rodou (núcleo sem instalar, arquivo ilegível, jogo que não abre): não grava nada, senão o
        // chute viraria "medido" para sempre; o teste volta na próxima abertura. O jogo que o usuário mandou
        // otimizar e pulou continua seguindo o console: um "pulado" por jogo não é ajuste.
        val nothingRan = !benchSkip && speed == null
        if (!nothingRan && !(perGame && skipped)) {
            if (perGame) app.settings.setGameTuning(game.id, result) else app.settings.setTuning(system.id, core.id, result)
        }
        intent.removeExtra(EXTRA_RETUNE)
        // "Otimizar este jogo" sem nenhum nível rodando: o usuário pediu e precisa saber que não deu.
        if (nothingRan && perGame) toast = getString(R.string.tune_failed)
        if (!skipped) toast = getString(R.string.tune_chosen, getString(preset.label), ((speed ?: 0f) * 100).toInt())
        ui = EmulationUi.Preparing(getString(R.string.game_loading), null)
        return result.presetOrNull
    }

    /** Velocidade de [core] com este jogo e estas [options], ou nulo se ele não instalou, não abriu o jogo ou deu erro. */
    private suspend fun measure(core: CoreInfo, options: Map<String, String>, kind: BenchKind, items: List<BenchItem>, index: Int, results: List<CoreSpeed>): Float? {
        ui = EmulationUi.Benchmarking(null, kind, items, index, results.toList())
        val path = try {
            app.cores.corePath(core.id)?.takeUnless { app.cores.needsInstall(core) } ?: app.cores.install(core)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            return null
        }
        val data = GLRetroViewData(this).apply { coreFilePath = path }
        if (setGameSource(data, core) != GameSource.Ok) return null
        val vulkan = vulkanAllowed(core)
        try {
            configure(data, core, options, vulkan)
        } catch (t: Throwable) {
            // Inclui o cancelamento (Activity fechando): os descritores do SAF já abertos não podem vazar.
            closeVirtualFiles(data)
            throw t
        }
        data.rumbleEventsEnabled = false
        runCatching { android.system.Os.setenv("EXTERNAL_STORAGE", app.paths.savesFor(system.id).absolutePath, true) }
        if (isFinishing || benchSkip) { closeVirtualFiles(data); return null }

        // O teste também abre o núcleo com Vulkan: conta como tentativa (um driver ruim derruba o app aqui também).
        if (vulkan) app.vulkanHealth.attemptStarted(core.id)
        // A tentativa precisa terminar com um resultado: cancelada (Voltar, recreate) ela contaria como falha.
        var vulkanSettled = !vulkan
        // A primeira abertura de um núcleo Vulkan compila shaders e pipelines: leva bem mais que um núcleo comum.
        val loadTimeout = if (core.vulkan) BENCH_LOAD_TIMEOUT_VULKAN_MS else BENCH_LOAD_TIMEOUT_MS
        val owner = EmulationOwner()
        val view = GLRetroView(this, data).apply { keepScreenOn = false }
        owner.registry.currentState = Lifecycle.State.CREATED
        owner.registry.addObserver(view)
        // O create do LibretroDroid liga o som: desliga logo depois, antes do primeiro quadro.
        view.audioEnabled = false
        benchOwner = owner
        ui = EmulationUi.Benchmarking(view, kind, items, index, results.toList())
        updateEmulationState()
        try {
            // (primeiro quadro?, código do erro); nulo = estourou o tempo. Só conta o tempo com o app na frente:
            // em segundo plano o núcleo nem desenha, e esperar lá não é falha do núcleo nem do nível.
            val outcome = coroutineScope {
                val first = async {
                    merge(
                        view.getGLRetroEvents().filterIsInstance<GLRetroView.GLRetroEvents.FrameRendered>().map { true to 0 },
                        view.getGLRetroErrors().map { false to it },
                    ).first()
                }
                var waited = 0L
                while (!first.isCompleted && waited < loadTimeout && !benchSkip) {
                    delay(BENCH_SAMPLE_MS)
                    if (activityResumed) waited += BENCH_SAMPLE_MS
                }
                if (first.isCompleted) first.await() else { first.cancel(); null }
            }
            if (vulkan) {
                vulkanSettled = true
                when {
                    outcome?.first == true -> app.vulkanHealth.attemptSucceeded(core.id)
                    outcome?.second == GLRetroView.ERROR_GL_NOT_COMPATIBLE -> app.vulkanHealth.attemptFailed(core.id, app.vulkanKey(core.id))
                    // Outro erro, ou demorou: não prova nada sobre o Vulkan.
                    else -> app.vulkanHealth.attemptAborted(core.id)
                }
            }
            if (outcome?.first != true) return null
            view.audioEnabled = false
            view.frameSpeed = CoreBenchmark.FRAME_SPEED
            delay(BENCH_WARMUP_MS)
            // Mede só o tempo com o app na frente: se ele for para o fundo, a contagem espera.
            var frames = 0L
            var millis = 0L
            while (millis < BENCH_MEASURE_MS && !benchSkip && !isFinishing) {
                val r0 = view.runCount()
                val t0 = android.os.SystemClock.elapsedRealtime()
                delay(BENCH_SAMPLE_MS)
                if (activityResumed && benchOwner === owner) {
                    frames += view.runCount() - r0
                    millis += android.os.SystemClock.elapsedRealtime() - t0
                }
            }
            if (benchSkip) return null
            return CoreBenchmark.speed(frames, millis, view.contentFps())
        } finally {
            // Cancelado antes do primeiro quadro (ou do erro): não prova nada sobre o Vulkan.
            if (!vulkanSettled) app.vulkanHealth.attemptAborted(core.id)
            // Destruir com o retro_load_game ainda rodando (demorou demais, ou o usuário saiu) faria o
            // onDestroy esperar o carregamento na thread principal: a espera fica aqui, suspensa e com teto.
            // NonCancellable: com a Activity fechando o escopo já está cancelado e o delay sairia na hora.
            withContext(kotlinx.coroutines.NonCancellable) {
                withTimeoutOrNull(loadTimeout) { while (view.isLoading) delay(BENCH_SAMPLE_MS) }
            }
            owner.registry.currentState = Lifecycle.State.DESTROYED
            benchOwner = null
            // Tira a view da tela (a thread GL termina) antes de o próximo núcleo criar o dele.
            ui = EmulationUi.Benchmarking(null, kind, items, index, results.toList())
            delay(BENCH_TEARDOWN_MS)
        }
    }

    // endregion

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
                    GLRetroView.ERROR_GL_NOT_COMPATIBLE -> {
                        // O contexto Vulkan não saiu (ou se perdeu no meio do jogo): o núcleo volta ao renderizador sem
                        // Vulkan, sem o usuário ver a falha.
                        if ((vulkanAttempt || vulkanLaunch) && retroView === view) {
                            vulkanAttempt = false
                            vulkanLaunch = false
                            app.vulkanHealth.attemptFailed(core.id, app.vulkanKey(core.id))
                            // O que foi medido (com Vulkan) não vale mais: o próximo jogo mede de novo, sem ele.
                            app.settings.clearTuning(system.id, listOf(game.id))
                            setIntent(intent.putExtra(EXTRA_NO_VULKAN, true))
                            recreate()
                            return@collect
                        }
                        getString(R.string.game_error_gl)
                    }
                    GLRetroView.ERROR_SERIALIZATION -> getString(R.string.game_error_serialization)
                    else -> getString(R.string.game_error_unexpected, code)
                }
                if (code == GLRetroView.ERROR_SERIALIZATION) toast = msg else fail(getString(R.string.game_cant_run), msg)
            }
        }
        // Carrega o salvamento automático assim que o primeiro quadro é desenhado.
        lifecycleScope.launch {
            view.getGLRetroEvents().filterIsInstance<GLRetroView.GLRetroEvents.FrameRendered>().first()
            if (retroView === view) gameLoaded = true
            if (vulkanAttempt) { vulkanAttempt = false; app.vulkanHealth.attemptSucceeded(core.id) }
            if (retroView === view) openSession()
            // Núcleo experimental: a versão nova, se houver, é baixada agora (sem atrapalhar o carregamento) e entra na
            // próxima abertura. Eles recebem correções de quedas quase todo dia.
            if (core.experimental) { val c = core; app.scope.launch { runCatching { app.cores.stageUpdate(c) } } }
            val received = pendingStateFile
            val guestOf = netGuest
            if (guestOf != null) {
                // O jogo começa do estado do anfitrião: nada de salvamento automático aqui.
                if (retroView !== view) return@launch
                netplay.join(guestOf.first, guestOf.second, guestOf.third)
            } else if (received != null) {
                pendingStateFile = null
                if (!statesSupported()) {
                    toast = getString(R.string.game_states_unsupported, core.displayName)
                } else {
                    val data = withContext(Dispatchers.IO) { runCatching { RZip.read(received) }.getOrNull() }
                    if (retroView !== view) return@launch
                    val ok = data != null && withContext(Dispatchers.Default) { runCatching { view.unserializeState(data) }.getOrDefault(false) }
                    toast = getString(if (ok) R.string.share_state_opened else R.string.share_state_open_failed)
                }
            } else if (settings.autoLoad && statesSupported()) {
                val read = withContext(Dispatchers.IO) { runCatching { states.read(SaveStates.AUTO_SLOT) } }
                // O arquivo existe mas não abriu (corrompido): guardado à parte, senão o próximo salvamento
                // automático o apagaria sem ninguém saber.
                if (read.isFailure) {
                    withContext(Dispatchers.IO) { runCatching { states.backup(SaveStates.AUTO_SLOT) } }
                    toast = getString(R.string.game_state_load_failed)
                }
                val saved = read.getOrNull()
                saved?.let { data ->
                    // Roda na thread de emulação; a espera fica fora da principal (pausar no meio a travaria).
                    // Pausou no meio (menu, segundo plano) ou a thread não respondeu: não prova que o estado é
                    // incompatível, e a tentativa se repete quando o jogo voltar a rodar.
                    var applied: Boolean? = null
                    var attempts = 0
                    while (applied == null && attempts < AUTOLOAD_ATTEMPTS) {
                        // Menu aberto ou app em segundo plano: a thread de emulação está parada (sem contexto GL)
                        // e o estado só é aplicado quando o jogo voltar a rodar.
                        while (retroView === view && !emulationRunning()) delay(100)
                        // Sair pelo menu durante a leitura já destruiu o núcleo: carregar agora derrubaria o app.
                        if (retroView !== view) return@launch
                        attempts++
                        applied = applyState(view, data)
                    }
                    if (retroView !== view) return@launch
                    when (applied) {
                        true -> {
                            // Se a emulação parou no meio (app para o fundo), a cópia de pausa ainda é a da tela
                            // de início: gravá-la no próximo salvamento automático apagaria o progresso restaurado.
                            if (!emulationRunning()) frozenState = data
                            toast = getString(R.string.game_progress_restored)
                        }
                        // Estado de outro núcleo (ou de uma versão anterior dele): fica guardado à parte,
                        // senão o próximo salvamento automático gravaria a tela de início por cima dele.
                        false -> {
                            withContext(Dispatchers.IO) { runCatching { states.backup(SaveStates.AUTO_SLOT) } }
                            toast = getString(R.string.game_autosave_incompatible)
                        }
                        // Não deu para aplicar nas tentativas: guardado à parte do mesmo jeito (o salvamento
                        // automático não pode apagá-lo), mas sem dizer que é incompatível.
                        null -> {
                            withContext(Dispatchers.IO) { runCatching { states.backup(SaveStates.AUTO_SLOT) } }
                            toast = getString(R.string.game_state_load_failed)
                        }
                    }
                }
            }
            autoSaveReady = true
            // Trapaças ligadas na última sessão voltam junto com o jogo (menos em rede: o convidado parte do
            // estado do anfitrião, e só um lado com trapaças desencontraria os dois jogos).
            if (retroView === view && cheats?.state?.enabled?.isNotEmpty() == true) {
                if (cheatsBlocked()) cheats?.dirty = true else applyCheats(view)
            }
        }
        lifecycleScope.launch { watchForBlackScreen(view) }
        lifecycleScope.launch { saveSramPeriodically(view) }
        lifecycleScope.launch {
            val vibrator = rumbleVibrator
            // O núcleo só avisa quando a força muda: a vibração dura até chegar força zero (ou o jogo pausar),
            // não um pulso curto por aviso, que fazia o Rumble Pak parar em 60 ms.
            view.getRumbleEvents().collect { e ->
                val strength = maxOf(e.strengthStrong, e.strengthWeak)
                // Jogador de um controle pela rede: vibra o celular dele, não este.
                if (app.remote.rumble(e.port, strength)) return@collect
                // Os outros jogadores (controle físico, teclado da TV) não fazem vibrar o celular do jogador 1.
                if (vibrator == null || e.port != 0) return@collect
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
        playSession?.let { app.sessions.open(it) }
        hideSystemBars()
        updateEmulationState()
    }

    override fun onPause() {
        activityResumed = false
        persist(auto = settings.autoSave)
        updateEmulationState()
        // Fora da frente, uma morte do processo (o sistema liberando memória) não diz nada do jogo. Saindo da tela, o
        // registro fica até o núcleo ser descarregado no onDestroy: uma queda ao fechar o Vulkan também conta.
        if (!isFinishing && !isChangingConfigurations) closeSession()
        super.onPause()
    }

    override fun onDestroy() {
        // Saiu (ou a Activity foi recriada para outro jogo) antes do primeiro quadro: não é prova de que o Vulkan
        // falha. Um driver que derruba o app não chega aqui, e é isso que a tentativa conta.
        if (vulkanAttempt) { vulkanAttempt = false; app.vulkanHealth.attemptAborted(core.id) }
        sharing?.server?.close()
        netplay.end()
        translateJob?.cancel()
        translator?.let { runCatching { it.close() } }
        // O InputManager é global: sem remover o listener, cada jogo aberto vazaria esta Activity.
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputDeviceListener)
        // Descarrega o núcleo (o GLRetroView observa este ciclo de vida) e só então dá a sessão por encerrada.
        emulationOwner.registry.currentState = Lifecycle.State.DESTROYED
        closeSession()
        benchOwner?.registry?.currentState = Lifecycle.State.DESTROYED
        retroView = null
        remoteView?.let { app.remote.detach(it) }
        remoteView = null
        // Trocar de jogo recria a Activity e o servidor segue (os controles reconectam); sair do jogo o desliga.
        if (isFinishing) app.remote.stop()
        // O que ficou na fila ainda é gravado; depois o consumidor termina.
        padSaves.close()
        super.onDestroy()
    }

    /**
     * A tela fica acesa sozinha enquanto há algo andando (o jogo, o carregamento, o teste de núcleos, uma partida
     * em rede ou um QR code esperando alguém). Com o jogo parado no menu de pausa, ou na tela de erro, ela apaga no
     * tempo normal do sistema: o celular esquecido na mesa não gasta bateria com a tela por horas, e o onPause
     * grava o salvamento automático como sempre.
     */
    private fun keepScreenOnWhileActive() {
        lifecycleScope.launch {
            combine(snapshotFlow { screenBusy() }, app.remote.state.map { it.running }) { busy, remote -> busy || remote }
                .distinctUntilChanged()
                .collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
        }
    }

    private fun screenBusy(): Boolean = when (ui) {
        is EmulationUi.Failed -> false
        is EmulationUi.Running -> !menuOpen || netplay.ui != null || sharing != null
        else -> true
    }

    private fun updateEmulationState() {
        // O núcleo em teste só roda com o app na frente (a medição desconta o tempo parado).
        benchOwner?.registry?.let { if (it.currentState != Lifecycle.State.DESTROYED) it.currentState = if (activityResumed) Lifecycle.State.RESUMED else Lifecycle.State.STARTED }
        if (emulationOwner.registry.currentState == Lifecycle.State.DESTROYED) return
        val running = activityResumed && !menuOpen && translation == null && ui is EmulationUi.Running
        val target = if (running) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
        if (running && sessionStart == 0L) sessionStart = System.currentTimeMillis()
        // O jogo volta a andar: o estado congelado deixa de ser o atual.
        if (running) frozenState = null
        if (!running) {
            flushPlayTime()
            rumbleVibrator?.cancel()
        }
        if (!running && emulationOwner.registry.currentState == Lifecycle.State.RESUMED) emulationPauses++
        emulationOwner.registry.currentState = target
        if (ui is EmulationUi.Running) app.remote.setPaused(!running)
    }

    private fun flushPlayTime() {
        if (sessionStart == 0L || !::game.isInitialized) return
        val seconds = (System.currentTimeMillis() - sessionStart) / 1000
        sessionStart = 0L
        app.scope.launch { app.library.recordSession(game.id, seconds) }
    }

    /** Temporário + renomear: falta de espaço ou o processo morto no meio não zeram o save do cartucho. */
    private fun writeSram(sram: ByteArray) {
        if (sram.isEmpty()) return
        synchronized(sramLock) {
            if (sram.contentEquals(lastSram)) return
            val file = states.sramFile()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(sram)
            if (tmp.renameTo(file)) lastSram = sram else tmp.delete()
        }
    }

    /**
     * A SRAM (e o memory card 1 do PlayStation, que é ela) só era gravada ao sair ou pausar: se o núcleo
     * derrubasse o app, o que o jogo salvou na sessão sumia. Enquanto roda, grava de tempos em tempos
     * quando mudou.
     */
    private suspend fun saveSramPeriodically(view: GLRetroView) {
        while (retroView === view) {
            delay(SRAM_SAVE_INTERVAL_MS)
            if (retroView !== view || !gameLoaded || guestSession || !emulationRunning()) continue
            // Cópia simples da memória: não passa pela thread de emulação, que pode parar no meio.
            withContext(Dispatchers.IO) { runCatching { writeSram(view.serializeSRAM(false)) } }
        }
    }

    /** Falso para núcleos cujo estado sai corrompido ([CoreInfo.saveStates]): nem se lê nem se grava estado. */
    private fun statesSupported() = ::core.isInitialized && core.saveStates

    private fun emulationRunning() = emulationOwner.registry.currentState == Lifecycle.State.RESUMED

    /** Grava a SRAM (e o estado automático) de forma síncrona antes de pausar. */
    private fun persist(auto: Boolean) {
        val view = retroView ?: return
        // Partida de outra pessoa: gravar aqui trocaria o progresso de quem joga pelo dela.
        if (guestSession) return
        // Jogo ainda carregando: nem a SRAM nem o estado são reais.
        if (!gameLoaded) return
        runCatching {
            val running = emulationRunning()
            // A SRAM é só uma cópia da memória do jogo (protegida pelo lock do núcleo): lida daqui mesmo, sem
            // esperar a thread de emulação. Vale também na tela de erro: o núcleo carregou o jogo, e o que ele
            // salvou na sessão não pode se perder porque ele falhou depois.
            writeSram(view.serializeSRAM(false))
            // O estado inteiro só com o jogo de pé: depois de um erro o núcleo pode estar num estado inválido.
            if (auto && autoSaveReady && ui is EmulationUi.Running && statesSupported()) {
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
            // Em rede as trapaças esperam (também com o QR na tela ou conectando): só um lado com elas
            // desencontraria os dois jogos. Continuam pendentes e voltam depois que a partida acaba.
            if (view != null && cheats?.dirty == true && !cheatsBlocked()) applyCheats(view)
        }
        override fun slots() = if (::states.isInitialized) states.slots() else emptyList()
        override fun thumbnail(slot: Int) = states.thumbnail(slot)

        override fun save(slot: Int, onDone: () -> Unit) {
            if (!statesSupported()) { toast = getString(R.string.game_states_unsupported, core.displayName); return }
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
            if (netplay.playing) { toast = getString(R.string.netplay_unavailable); return }
            if (!statesSupported()) { toast = getString(R.string.game_states_unsupported, core.displayName); return }
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
                // O núcleo só pode ler o estado na thread de emulação, que precisa estar rodando (e com um
                // quadro desenhado, para ter o contexto GL): o menu fecha antes. A espera fica fora da thread principal.
                close()
                val ok = awaitEmulationFrame(view) && applyState(view, data) == true
                if (retroView === view) toast = getString(if (ok) R.string.game_state_loaded else R.string.game_state_load_failed)
            }
        }

        override fun toggleFastForward() {
            // Em rede os dois lados andam no mesmo ritmo: acelerar só aqui travaria o outro.
            if (netplay.playing) { toast = getString(R.string.netplay_unavailable); return }
            fastForward = !fastForward
            retroView?.frameSpeed = if (fastForward) settings.fastForwardSpeed else 1
        }

        override fun setShader(option: ShaderOption) {
            retroView?.shader = option.toShaderConfig()
            settings = settings.copy(shader = option)
            app.scope.launch { app.settings.setShader(option) }
        }

        override fun coreOptions(): List<CoreOption> =
            retroView?.getVariables()?.mapNotNull(CoreOption::parse).orEmpty().filter { it.key !in core.fixed }

        override fun setCoreOption(option: CoreOption, value: String) {
            if (netplay.playing) { toast = getString(R.string.netplay_unavailable); return }
            retroView?.updateVariables(Variable(option.key, value))
            app.scope.launch { app.settings.setCoreOption(core.id, option.key, value) }
        }

        override fun disks(): Pair<Int, Int> {
            val v = retroView ?: return 0 to 0
            return runCatching { v.getAvailableDisks(false) to v.getCurrentDisk(false) }.getOrDefault(0 to 0)
        }

        override fun changeDisk(index: Int) {
            // Trocar o disco só de um lado desencontraria os dois jogos.
            if (netplay.playing) { toast = getString(R.string.netplay_unavailable); return }
            retroView?.changeDisk(index, false)
            toast = getString(R.string.game_disk_inserted, index + 1)
        }

        override fun reset() {
            val view = retroView ?: return
            if (netplay.playing) { toast = getString(R.string.netplay_unavailable); return }
            // Como o carregamento: reiniciar mexe no núcleo, então a emulação volta a rodar antes.
            close()
            lifecycleScope.launch {
                if (!awaitEmulationFrame(view)) return@launch
                withContext(Dispatchers.Default) { runCatching { view.reset() } }
            }
        }

        override fun coreName() = if (::core.isInitialized) core.displayName else ""

        override fun cheats(): CheatSession? = cheats

        override fun share(slot: Int) {
            if (!::states.isInitialized) return
            if (!statesSupported()) { toast = getString(R.string.game_states_unsupported, core.displayName); return }
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

        override fun translation(): TranslationUi? = translation

        override fun canTranslate(): Boolean =
            ::game.isInitialized && (settings.translateEverywhere || game.region == "Japão" || game.rawName.contains("(Japan", ignoreCase = true))

        override fun translate() = startTranslation()

        override fun closeTranslation() {
            translateJob?.cancel()
            translateJob = null
            translation = null
            updateEmulationState()
        }

        override fun skipBenchmark() { benchSkip = true }

        override fun netplay(): NetplayController = netplay

        override fun hostNetplay() {
            if (!::game.isInitialized || !::core.isInitialized || !autoSaveReady) { toast = getString(R.string.game_state_not_ready); return }
            // A partida começa mandando o estado do anfitrião ao convidado.
            if (!statesSupported()) { toast = getString(R.string.game_states_unsupported, core.displayName); return }
            val view = retroView ?: return
            // Trapaças ligadas só aqui desencontrariam os jogos: saem durante a partida e voltam depois.
            if (cheats?.state?.enabled?.isNotEmpty() == true) {
                cheats?.dirty = true
                lifecycleScope.launch(Dispatchers.Default) { view.resetCheat() }
            }
            if (fastForward) toggleFastForward()
            netplay.startHosting(StatePackage.manifestFor(game, core.id, System.currentTimeMillis()), game.title)
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
            netplay.end()
            persist(auto = settings.autoSave)
            // O LibretroDroid é global e o onDestroy desta Activity roda só depois que a próxima tela
            // aparece: abrir outro jogo logo em seguida teria o emulador novo destruído por este.
            // Encerrar aqui, de forma síncrona, fecha essa janela.
            flushPlayTime()
            retroView = null
            emulationOwner.registry.currentState = Lifecycle.State.DESTROYED
            closeSession()
            finish()
        }
    }

    /** Partida em rede montada ou em andamento (anfitrião esperando, conectando ou jogando). */
    private fun cheatsBlocked(): Boolean = netplay.ui != null

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

    /** Fecha o menu (e a tradução) e espera a emulação rodar: o núcleo só é tocado na thread de emulação ativa. */
    private suspend fun resumeForNetplay(): Boolean {
        if (translation != null) menuActions.closeTranslation()
        if (menuOpen) menuActions.close()
        repeat(100) {
            if (emulationRunning()) return true
            delay(50)
        }
        return emulationRunning()
    }

    /**
     * Tradução ao vivo: captura a tela, pausa (o estado sai antes, como ao abrir o menu, para o salvamento
     * automático valer se o app for para o fundo) e mostra o texto traduzido por cima da própria captura.
     */
    private fun startTranslation() {
        val view = retroView ?: return
        if (ui !is EmulationUi.Running || menuOpen || menuOpening || translation != null) return
        if (gameLoaded && !autoSaveReady) return
        releaseAllInputs(view)
        menuOpening = true
        // O quadro do núcleo, sem shader nem escala, chega no próximo quadro desenhado (antes da pausa).
        LibretroDroid.requestFrameSnapshot()
        captureFrame(view) { _, full ->
            lifecycleScope.launch {
                // Como no menu: com o app em segundo plano a thread de emulação parou, e serializar nela sem
                // contexto GL derruba os núcleos de GPU.
                if (!emulationRunning()) { menuOpening = false; return@launch }
                if (full == null) {
                    menuOpening = false
                    if (retroView === view) toast = getString(R.string.translate_capture_failed)
                    return@launch
                }
                val state = if (!statesSupported()) null else withBusyHint { withContext(Dispatchers.Default) { runCatching { view.serializeState() }.getOrNull() } }
                menuOpening = false
                if (retroView !== view) return@launch
                frozenState = state?.takeIf { it.isNotEmpty() }
                menuSnapshot = null
                translation = TranslationUi.Working(full, LiveTranslator.Stage.READING)
                updateEmulationState()
                val target = uiLanguage()
                translateJob = lifecycleScope.launch {
                    translation = try {
                        val frame = nativeFrame(full) ?: OcrFrame(full, Box(0, 0, full.width, full.height))
                        val current = app.settings.current()
                        val ai = current.geminiKey?.takeIf { it.isNotBlank() }?.let { AiConfig(it, current.geminiModel) }
                        val tr = translator ?: LiveTranslator(app.ocrPack).also { translator = it }
                        val result = tr.translate(frame, target, ai, GeminiText.GameContext(game.title, system.name)) { stage ->
                            if (translation is TranslationUi.Working) translation = TranslationUi.Working(full, stage)
                        }
                        TranslationUi.Ready(
                            full, result.blocks,
                            aiError = result.aiError?.userMessage(this@GameActivity),
                            suggestPack = result.suggestPack,
                        )
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        TranslationUi.Failed(full, t.userMessage(this@GameActivity))
                    }
                }
            }
        }
    }

    /**
     * O quadro nativo pedido em [startTranslation], se chegou: núcleos de GPU não têm (a captura da view
     * fica valendo), e jogos girados também não servem, porque a área na tela está em outra orientação.
     */
    private suspend fun nativeFrame(full: Bitmap): OcrFrame? = withContext(Dispatchers.Default) {
        var data: IntArray? = null
        for (i in 0 until 10) {
            data = runCatching { LibretroDroid.takeFrameSnapshot() }.getOrNull()
            if (data != null) break
            kotlinx.coroutines.delay(20)
        }
        val d = data ?: return@withContext null
        val w = d[0]
        val h = d[1]
        if (w <= 0 || h <= 0 || d.size < 6 + w * h) return@withContext null
        val area = Box(d[2], d[3], d[4], d[5])
        if (area.isEmpty || area.right > full.width || area.bottom > full.height) return@withContext null
        if ((w > h) != (area.width > area.height) && kotlin.math.abs(w - h) > h / 8) return@withContext null
        OcrFrame(Bitmap.createBitmap(d, 6, w, w, h, Bitmap.Config.ARGB_8888), area)
    }

    private fun toggleMenu() {
        if (translation != null) { menuActions.closeTranslation(); return }
        // No editor de layout, voltar retorna ao menu (o que foi mexido e não salvo é descartado).
        if (padEditing) { padEditing = false; return }
        if (menuOpen) menuActions.close() else openMenu()
    }

    private fun openMenu() {
        // Falha e tela de erro primeiro: um menuOpening preso não pode impedir o Voltar de sair.
        val view = retroView
        // Tela de erro: o núcleo já foi criado, então sair passa por exit() (que o destrói na hora);
        // um finish() simples o deixaria para o onDestroy, que poderia destruir o núcleo do próximo jogo.
        if (view == null) { finish(); return }
        if (ui !is EmulationUi.Running) { menuActions.exit(); return }
        if (menuOpen || menuOpening) return
        // Salvamento automático sendo carregado: pausar agora carregaria o estado sem contexto GL
        // e deixaria o menu com a cópia da tela de início. Leva só um instante.
        if (gameLoaded && !autoSaveReady) return
        // Com o menu aberto os eventos do controle não chegam ao núcleo: o que estava apertado ao abrir
        // ficaria preso (personagem andando sozinho) ao voltar ao jogo.
        releaseAllInputs(view)
        menuOpening = true
        // Captura a tela antes de pausar: vira a miniatura dos save states.
        captureFrame(view) { bmp, full ->
            lifecycleScope.launch {
                // O app foi para segundo plano durante a captura: a thread de emulação já parou, e serializar
                // nela sem contexto GL derruba os núcleos de GPU. O onPause já guardou o que precisava.
                if (!emulationRunning()) { menuOpening = false; return@launch }
                // O estado também sai antes de pausar, na thread de emulação (a espera fica fora da principal):
                // é ele que o menu grava nos slots e no salvamento automático.
                val state = if (!statesSupported()) null else withBusyHint { withContext(Dispatchers.Default) { runCatching { view.serializeState() }.getOrNull() } }
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

    /** Mostra o indicador de espera se [block] passar de um instante (abaixo disso ele só piscaria). */
    private suspend fun <T> withBusyHint(block: suspend () -> T): T {
        val hint = lifecycleScope.launch { delay(BUSY_HINT_DELAY_MS); busy = true }
        try {
            return block()
        } finally {
            hint.cancel()
            busy = false
        }
    }

    /**
     * Espera a emulação de [view] voltar a rodar (o menu acabou de fechar) e desenhar um quadro: só então a
     * thread de emulação tem o contexto GL para carregar um estado ou reiniciar. Falso se ela não voltou a
     * tempo (app para o fundo, menu reaberto) ou a view mudou.
     */
    private suspend fun awaitEmulationFrame(view: GLRetroView): Boolean {
        var waited = 0L
        while (retroView === view && !emulationRunning()) {
            if (waited >= RESUME_WAIT_MS) return false
            delay(50); waited += 50
        }
        if (retroView !== view) return false
        val frames = view.runCount()
        waited = 0L
        while (retroView === view && emulationRunning() && view.runCount() == frames && waited < RESUME_WAIT_MS) { delay(16); waited += 16 }
        return retroView === view && emulationRunning() && view.runCount() != frames
    }

    /**
     * Aplica um estado em [view] na thread de emulação. Verdadeiro aplicado, falso recusado pelo núcleo (de
     * outro núcleo ou versão) e nulo quando não dá para saber: a emulação parou no meio ou a thread não
     * respondeu a tempo, o que não diz nada sobre o estado.
     */
    private suspend fun applyState(view: GLRetroView, data: ByteArray): Boolean? {
        val pauses = emulationPauses
        val result = withContext(Dispatchers.Default) { runCatching { view.unserializeState(data) } }
        if (result.getOrNull() == true) return true
        val timedOut = (result.exceptionOrNull() as? com.swordfish.libretrodroid.RetroException)?.errorCode == GLRetroView.ERROR_GENERIC
        if (timedOut || pauses != emulationPauses || !emulationRunning() || retroView !== view) return null
        return false
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

    /** Portas dos controles físicos além do primeiro: os controles pela rede não podem cair nelas. */
    private fun physicalControllerPorts(): Set<Int> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
        InputDevice.getDevice(id)?.takeIf { isPhysicalController(it) }?.controllerNumber?.takeIf { it > 1 }?.minus(1)
    }.toSet()

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
        private const val SRAM_SAVE_INTERVAL_MS = 30_000L
        /** Teto de uma vibração de rumble contínua; o núcleo manda força zero para parar antes disso. */
        private const val RUMBLE_MAX_MS = 10_000L
        private const val BENCH_LOAD_TIMEOUT_MS = 25_000L
        private const val BENCH_LOAD_TIMEOUT_VULKAN_MS = 60_000L
        /** Uma sessão com Vulkan que passa disto e termina sem queda desconta uma queda anterior (VulkanHealth). */
        private const val CLEAN_SESSION_MS = 5 * 60_000L
        private const val BENCH_WARMUP_MS = 1_500L
        private const val BENCH_MEASURE_MS = 4_000L
        private const val BENCH_SAMPLE_MS = 250L
        private const val BENCH_TEARDOWN_MS = 350L
        private const val WATCH_INTERVAL_MS = 1_000L
        private const val BUSY_HINT_DELAY_MS = 300L
        /** Quanto esperar a emulação voltar depois de fechar o menu, antes de desistir de carregar ou reiniciar. */
        private const val RESUME_WAIT_MS = 5_000L
        /** Tentativas do carregamento automático quando a emulação para no meio dele. */
        private const val AUTOLOAD_ATTEMPTS = 3
        /** Portas do LibretroDroid (Input::getInputState ignora port >= 4). */
        private const val MAX_PORTS = 4
        private val RETROPAD_KEYS = listOf(
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )

        private const val EXTRA_STATE_FILE = "state_file"
        private const val EXTRA_NET_HOST = "net_host"
        private const val EXTRA_NET_PORT = "net_port"
        private const val EXTRA_NET_TOKEN = "net_token"
        private const val EXTRA_CORE_ID = "core_id"
        private const val EXTRA_RETUNE = "retune"
        private const val EXTRA_NO_VULKAN = "no_vulkan"
        /** Pasta dos marcadores de teste; também usada por LibraryRepository e pelas regras de backup. */
        const val BENCH_DIR = "benchmark"

        /** Entra na partida em rede do anfitrião em [host]:[port], com o mesmo núcleo dele. */
        fun launchNetplay(context: Context, gameId: Long, host: String, port: Int, token: String, coreId: String) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_GAME_ID, gameId)
                    .putExtra(EXTRA_NET_HOST, host)
                    .putExtra(EXTRA_NET_PORT, port)
                    .putExtra(EXTRA_NET_TOKEN, token)
                    .putExtra(EXTRA_CORE_ID, coreId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }

        /** [stateFile]: estado recebido para abrir no lugar do salvamento automático, no núcleo [coreId]. */
        fun launch(context: Context, gameId: Long, stateFile: File? = null, coreId: String? = null, retune: Boolean = false) {
            context.startActivity(
                Intent(context, GameActivity::class.java)
                    .putExtra(EXTRA_GAME_ID, gameId)
                    .apply { if (retune) putExtra(EXTRA_RETUNE, true) }
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
    fun skipBenchmark()
    fun netplay(): NetplayController
    fun hostNetplay()
    fun translation(): TranslationUi?
    fun canTranslate(): Boolean
    fun translate()
    fun closeTranslation()
    fun setPadProfile(profile: PadProfile)
    fun padForGame(): Boolean
    fun setPadForGame(forGame: Boolean)
    fun startPadEditor()
    fun stopPadEditor()
    fun exit()
}
