package com.retrovika.app

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.retrovika.app.core.bios.BiosManager
import com.retrovika.app.core.catalog.CatalogRepository
import com.retrovika.app.core.catalog.CatalogSnapshots
import com.retrovika.app.core.cheats.CheatRepository
import com.retrovika.app.core.share.SharedStates
import com.retrovika.app.core.catalog.RomsFunSource
import com.retrovika.app.core.catalog.DownloadManager
import com.retrovika.app.core.cores.CoreManager
import com.retrovika.app.core.dat.DatRepository
import com.retrovika.app.core.library.AppDatabase
import com.retrovika.app.core.library.LibraryRepository
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.settings.Languages
import com.retrovika.app.core.gameinfo.BackloggdClient
import com.retrovika.app.core.gameinfo.GameInfoRepository
import com.retrovika.app.core.net.ChallengePrompt
import com.retrovika.app.core.net.WebFetcher
import com.retrovika.app.core.settings.SettingsRepository
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.translate.OcrPack
import com.retrovika.app.core.update.AppUpdater
import com.retrovika.app.remote.RemotePlay
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import com.retrovika.app.core.tuning.DeviceProfile
import com.retrovika.app.core.tuning.SessionGuard
import com.retrovika.app.core.tuning.VulkanHealth
import com.swordfish.libretrodroid.LibretroDroid
import com.retrovika.app.ui.share.Incoming
import com.retrovika.app.ui.share.ReceiveSession

/** Contêiner de dependências simples, sem framework de injeção. */
class AppContainer(app: Application) {
    // Uma falha num trabalho de fundo (varredura, download, migração) fica no log: sem o handler, uma exceção
    // não tratada em launch derrubaria o processo inteiro, inclusive um jogo aberto.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> android.util.Log.e("Retrovika", "Falha em tarefa de fundo", t) },
    )
    val paths = StoragePaths(app)
    val settings = SettingsRepository(app, scope)
    val database = AppDatabase.build(app)
    val library = LibraryRepository(app, database.games(), paths, settings, scope)
    val cores = CoreManager(app, paths)
    /** Quais núcleos Vulkan deram certo neste aparelho (ver VulkanHealth). */
    val vulkanHealth = VulkanHealth(app.getSharedPreferences("vulkan_health", android.content.Context.MODE_PRIVATE))
    /** Processador, GPU, memória e Vulkan deste aparelho, lidos uma vez (e só quando algum ajuste pede). */
    val deviceProfile: Deferred<DeviceProfile> = scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
        val detected = DeviceProfile.detectOrDefault(app)
        // Declarar Vulkan 1.1 não basta: a ponte do LibretroDroid (buffer compartilhado com o GL) precisa funcionar de verdade.
        if (detected.vulkan && vulkanHealth.bridgeWorks(bridgeBuild(app)) { LibretroDroid.probeVulkan() }) detected
        else detected.copy(vulkan = false)
    }
    /** O jogo na frente do usuário, para a abertura seguinte saber se o processo morreu com ele (ver SessionGuard). */
    val sessions = SessionGuard(java.io.File(app.filesDir, "${com.retrovika.app.emulation.GameActivity.BENCH_DIR}/session.json"))

    /** Como o [vulkanHealth] identifica o Vulkan de [coreId] aqui: este aparelho e a versão instalada do núcleo. */
    suspend fun vulkanKey(coreId: String): String =
        "${deviceProfile.await().signature}|${kotlinx.coroutines.withContext(Dispatchers.IO) { cores.buildId(coreId) }}"
    /** OCR para jogos japoneses, baixado sob demanda em Ajustes. */
    val ocrPack = OcrPack(app, scope, cores.abi)
    val bios = BiosManager(paths, app.contentResolver)
    /** Verificações de sites que o WebView invisível não passou, à espera do usuário (ChallengeHost). */
    val challenges = ChallengePrompt()
    // O RomsFun tem o próprio WebView: dividir o do Backloggd faria os dois reabrirem o site a cada troca.
    private val catalogWeb = WebFetcher(app, minGapMs = 400, prompt = challenges)
    val catalog = CatalogRepository(RomsFunSource(catalogWeb))
    /** A última lista de cada filtro do Explorar, para ele abrir na hora. */
    val catalogSnapshots = CatalogSnapshots(File(app.cacheDir, "explore"))
    private val web = WebFetcher(app)
    private val netHints = app.getSharedPreferences("net_hints", android.content.Context.MODE_PRIVATE)
    val gameInfo = GameInfoRepository(
        BackloggdClient(
            web = { url -> web.get(BACKLOGGD, url) },
            warm = { web.warm(BACKLOGGD) },
            challengedAt = netHints.getLong("backloggd_challenge", 0),
            onChallenge = { at -> netHints.edit().putLong("backloggd_challenge", at).apply() },
        ),
        dir = File(app.cacheDir, "gameinfo"),
    )
    val dat = DatRepository(app, database.dats())
    val cheats = CheatRepository(app, paths)
    val sharedStates = SharedStates(app, paths, library)
    /** Estado ou partida recebidos de fora (Intent, QR code), à espera da tela que os mostra. */
    val incoming = MutableStateFlow<Incoming?>(null)
    /** Recebimento de [incoming] em andamento; fica aqui para sobreviver à recriação da Activity. */
    val receive = ReceiveSession()
    val updater = AppUpdater(app, scope)
    val remote = RemotePlay(app)
    val downloads = DownloadManager(
        app, scope, paths, library, settings.cached,
        resolve = { catalog.resolve(it) },
        link = { entry, variant -> catalog.directLink(entry, variant) },
    )

    /**
     * A interface do app saiu da frente (ou voltou). Os WebViews ociosos fecham logo (ver [WebFetcher.setBackground]);
     * o do RomsFun fica no prazo normal enquanto há downloads na fila, que ainda podem precisar dele.
     */
    fun setUiInBackground(value: Boolean) {
        web.setBackground(value)
        catalogWeb.setBackground(value && downloads.activeCount.value == 0)
    }

    init {
        // Faxina de fundo, uma vez por processo e depois que a abertura do app passou: caches vencidos e os
        // estados gravados crus pelas versões anteriores. Thread própria em prioridade de fundo: com um jogo
        // aberto, ela não tira CPU da emulação.
        kotlin.concurrent.thread(name = "Retrovika-housekeeping", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            Thread.sleep(HOUSEKEEPING_DELAY_MS)
            runCatching { gameInfo.prune() }
            runCatching { catalogSnapshots.prune() }
            runCatching { sharedStates.prune() }
            runCatching { com.retrovika.app.core.storage.RZip.compactTree(paths.states) }
        }
    }
}

private const val HOUSEKEEPING_DELAY_MS = 20_000L

/**
 * Identifica o que foi testado: o sistema (driver) e a versão do app (a ponte muda com ela). Um "falhou" de uma versão
 * antiga, com a ponte ainda com defeito, não pode valer para sempre.
 */
private fun bridgeBuild(app: Application): String =
    "${android.os.Build.FINGERPRINT}|${runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()}"

private const val BACKLOGGD = "https://backloggd.com/"

class RetrovikaApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun attachBaseContext(base: android.content.Context) = super.attachBaseContext(Languages.wrap(base))

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.client })) }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(256L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
}

val android.content.Context.container: AppContainer get() = (applicationContext as RetrovikaApp).container
