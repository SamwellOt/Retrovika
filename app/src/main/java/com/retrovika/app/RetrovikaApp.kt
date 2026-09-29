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
import com.retrovika.app.core.net.WebFetcher
import com.retrovika.app.core.settings.SettingsRepository
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.translate.OcrPack
import com.retrovika.app.core.update.AppUpdater
import com.retrovika.app.remote.RemotePlay
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import com.retrovika.app.ui.share.Incoming
import com.retrovika.app.ui.share.ReceiveSession

/** Contêiner de dependências simples, sem framework de injeção. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val paths = StoragePaths(app)
    val settings = SettingsRepository(app, scope)
    val database = AppDatabase.build(app)
    val library = LibraryRepository(app, database.games(), paths, settings, scope)
    val cores = CoreManager(app, paths)
    /** OCR para jogos japoneses, baixado sob demanda em Ajustes. */
    val ocrPack = OcrPack(app, scope, cores.abi)
    val bios = BiosManager(paths, app.contentResolver)
    // O RomsFun tem o próprio WebView: dividir o do Backloggd faria os dois reabrirem o site a cada troca.
    val catalog = CatalogRepository(RomsFunSource(WebFetcher(app, minGapMs = 400)))
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
    val cheats = CheatRepository(paths)
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
}

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
