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
import com.retrovika.app.core.catalog.DownloadManager
import com.retrovika.app.core.cores.CoreManager
import com.retrovika.app.core.dat.DatRepository
import com.retrovika.app.core.library.AppDatabase
import com.retrovika.app.core.library.LibraryRepository
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.settings.Languages
import com.retrovika.app.core.settings.SettingsRepository
import com.retrovika.app.core.storage.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Contêiner de dependências simples, sem framework de injeção. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val paths = StoragePaths(app)
    val settings = SettingsRepository(app, scope)
    val database = AppDatabase.build(app)
    val library = LibraryRepository(app, database.games(), paths, settings, scope)
    val cores = CoreManager(app, paths)
    val bios = BiosManager(paths, app.contentResolver)
    val catalog = CatalogRepository()
    val dat = DatRepository(app, database.dats())
    val downloads = DownloadManager(app, scope, paths, library, settings.cached) { catalog.resolve(it) }
}

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
