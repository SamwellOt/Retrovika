package com.retrovika.app.ui.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.retrovika.app.ui.components.LocalTabReselect
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.retrovika.app.container
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.screens.browser.BrowserScreen
import com.retrovika.app.ui.screens.details.GameDetailsScreen
import com.retrovika.app.ui.screens.downloads.DownloadsScreen
import com.retrovika.app.ui.screens.explore.CatalogGameScreen
import com.retrovika.app.ui.screens.explore.ExploreScreen
import com.retrovika.app.ui.screens.home.HomeScreen
import com.retrovika.app.ui.screens.library.LibraryScreen
import com.retrovika.app.ui.screens.library.SystemScreen
import com.retrovika.app.ui.screens.library.VersionsScreen
import com.retrovika.app.ui.screens.settings.BiosScreen
import com.retrovika.app.ui.screens.settings.CoresScreen
import com.retrovika.app.ui.screens.settings.SettingsScreen
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.launch

private data class Tab(val route: String, @StringRes val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", R.string.tab_home, Icons.Rounded.Home),
    Tab("library", R.string.tab_library, Icons.Rounded.VideogameAsset),
    Tab("explore", R.string.tab_explore, Icons.Rounded.Explore),
    Tab("downloads", R.string.tab_downloads, Icons.Rounded.Download),
    Tab("settings", R.string.tab_settings, Icons.Rounded.Settings),
)
private val tabRoutes = tabs.map { it.route }.toSet()

private const val TAB_FADE_MS = 150
private const val PUSH_MS = 200
private const val EXIT_FADE_MS = 90

@Composable
fun RetrovikaNavHost() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val app = context.container
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    // Só o que a raiz usa: observar a lista inteira de downloads recompunha o NavHost a cada aviso de progresso.
    val reduceMotion by remember { app.settings.cached.map { it.reduceMotion }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(app.settings.cached.value.reduceMotion)
    val activeDownloads by app.downloads.activeCount.collectAsStateWithLifecycle()
    // Tocar de novo na aba aberta: a tela daquela aba rola de volta ao topo.
    val reselect = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    val selectTab: (String) -> Unit = { if (!nav.navigateTab(it)) reselect.tryEmit(it) }
    // "Ver downloads" (Início, Ajustes, navegador) quer a lista, não a última tela empilhada na aba.
    val openDownloads = { nav.openTabRoot("downloads") }

    // Vincular pasta: permissão persistente de leitura via Storage Access Framework.
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        // Alguns provedores não concedem permissão persistente; sem ela a pasta não sobreviveria a um reinício.
        val granted = uri != null && runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.isSuccess
        if (uri != null && !granted) {
            Toast.makeText(context, R.string.folder_link_failed, Toast.LENGTH_LONG).show()
        }
        if (uri != null && granted) {
            // No escopo do app: trocar de aba durante a varredura não a interrompe.
            app.scope.launch {
                app.settings.addFolder(uri.toString())
                app.library.rescan()
            }
        }
    }
    val addFolder = { folderPicker.launch(null) }

    // Uma vez por processo: girar a tela ou trocar o idioma recria a Activity, e refazer a varredura
    // de todas as pastas a cada vez deixava o menu lento. Roda no escopo do app: se a Activity for
    // recriada no meio da varredura, ela não é cancelada (e a flag já marcada impediria refazê-la).
    LaunchedEffect(Unit) { app.scope.launch { app.library.rescanOnStartup() } }

    Scaffold(
        containerColor = Palette.Ink,
        bottomBar = {
            AnimatedVisibility(
                visible = route in tabRoutes,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                FloatingTabBar(route, activeDownloads, onSelect = selectTab)
            }
        },
    ) { padding ->
        // O conteúdo rola por baixo da barra flutuante; cada tela soma LocalBottomInset ao seu padding.
        Box(Modifier.fillMaxSize()) {
          CompositionLocalProvider(
              LocalBottomInset provides padding.calculateBottomPadding(),
              LocalTabReselect provides reselect,
          ) {
            NavHost(
                nav, startDestination = "home",
                // Abas trocam com um fade curto; telas empilhadas entram deslizando levemente da direita.
                // Durações enxutas: durante a transição as duas telas são desenhadas ao mesmo tempo.
                // Com "reduzir animações" a troca é imediata: só uma tela é desenhada por vez.
                enterTransition = {
                    when {
                        reduceMotion -> EnterTransition.None
                        targetState.destination.route in tabRoutes -> fadeIn(tween(TAB_FADE_MS))
                        else -> fadeIn(tween(PUSH_MS)) + slideInHorizontally(tween(PUSH_MS)) { it / 10 }
                    }
                },
                exitTransition = { if (reduceMotion) ExitTransition.None else fadeOut(tween(EXIT_FADE_MS)) },
                popEnterTransition = { if (reduceMotion) EnterTransition.None else fadeIn(tween(TAB_FADE_MS)) },
                popExitTransition = {
                    when {
                        reduceMotion -> ExitTransition.None
                        initialState.destination.route in tabRoutes -> fadeOut(tween(EXIT_FADE_MS))
                        else -> fadeOut(tween(EXIT_FADE_MS)) + slideOutHorizontally(tween(PUSH_MS)) { it / 10 }
                    }
                },
            ) {
                composable("home") { entry ->
                    HomeScreen(
                        onOpenGame = { nav.open(entry, "game/$it") },
                        onOpenSystem = { nav.open(entry, "system/$it") },
                        onExplore = { selectTab("explore") },
                        onOpenLibrary = { selectTab("library") },
                        onOpenDownloads = openDownloads,
                        onAddFolder = addFolder,
                    )
                }
                composable("library") { entry ->
                    LibraryScreen(onOpenSystem = { nav.open(entry, "system/$it") }, onOpenGame = { nav.open(entry, "game/$it") })
                }
                composable("explore") { entry ->
                    ExploreScreen(
                        onOpenBrowser = { nav.open(entry, "browser") },
                        onOpenGame = { key -> nav.open(entry, "catalog/${Uri.encode(key)}") },
                    )
                }
                composable("catalog/{key}", arguments = listOf(navArgument("key") { type = NavType.StringType })) { entry ->
                    CatalogGameScreen(
                        entryKey = entry.arguments?.getString("key").orEmpty(),
                        onBack = { nav.back(entry) },
                        onOpenDownloads = openDownloads,
                    )
                }
                composable("browser") { entry ->
                    BrowserScreen(onBack = { nav.back(entry) }, onOpenDownloads = openDownloads)
                }
                composable("downloads") { entry ->
                    DownloadsScreen(
                        onOpenGame = { nav.open(entry, "game/$it") },
                        onExplore = { selectTab("explore") },
                        onOpenBrowser = { nav.open(entry, "browser") },
                    )
                }
                composable("settings") { entry ->
                    SettingsScreen(
                        onAddFolder = addFolder,
                        onOpenCores = { nav.open(entry, "settings/cores") },
                        onOpenBios = { nav.open(entry, "settings/bios") },
                        onOpenDownloads = openDownloads,
                    )
                }
                composable("settings/cores") { entry -> CoresScreen(onBack = { nav.back(entry) }) }
                composable("settings/bios") { entry -> BiosScreen(onBack = { nav.back(entry) }) }
                composable("system/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    SystemScreen(
                        systemId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { nav.back(entry) },
                        onOpenGame = { nav.open(entry, "game/$it") },
                        onOpenBios = { nav.open(entry, "settings/bios") },
                        onOpenVersions = { nav.open(entry, "system/${entry.arguments?.getString("id").orEmpty()}/versions") },
                    )
                }
                composable("system/{id}/versions", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    VersionsScreen(
                        systemId = entry.arguments?.getString("id").orEmpty(),
                        onBack = { nav.back(entry) },
                        onOpenGame = { nav.open(entry, "game/$it") },
                    )
                }
                composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    GameDetailsScreen(
                        gameId = entry.arguments?.getLong("id") ?: -1,
                        onBack = { nav.back(entry) },
                        onOpenSystem = { id ->
                            // Veio da tela desse console: volta para ela em vez de empilhar console → jogo → console…
                            val previous = nav.previousBackStackEntry
                            if (previous?.destination?.route == "system/{id}" && previous.arguments?.getString("id") == id) nav.back(entry)
                            else nav.open(entry, "system/$id")
                        },
                        onOpenGame = { nav.open(entry, "game/$it") },
                        onOpenVersions = { nav.open(entry, "system/$it/versions") },
                    )
                }
            }
          }
            // Véu sob a barra de status: o conteúdo rolado não briga com o relógio e os ícones.
            Box(
                Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(Brush.verticalGradient(listOf(Palette.Ink.copy(alpha = 0.92f), Palette.Ink.copy(alpha = 0.6f)))),
            )
        }
    }
}

/**
 * Navega só se a tela de origem ainda é a ativa. A tela que está saindo continua clicável durante a
 * transição: sem isso, um toque duplo empilhava o mesmo jogo duas vezes.
 */
private fun NavHostController.open(from: NavBackStackEntry, route: String) {
    if (from.lifecycle.currentState != Lifecycle.State.RESUMED) return
    navigate(route) { launchSingleTop = true }
}

/** Voltar pela tela que pediu: um toque duplo em "voltar" não desempilha também a tela de baixo (nem esvazia a pilha). */
private fun NavHostController.back(from: NavBackStackEntry) {
    if (from.lifecycle.currentState != Lifecycle.State.RESUMED) return
    popBackStack()
}

/** Troca de aba guardando a pilha de cada uma. Devolve falso quando a aba já estava aberta (nada a fazer). */
private fun NavHostController.navigateTab(route: String): Boolean {
    // Tocar na aba já aberta não refaz a navegação nem dispara a transição.
    if (currentDestination?.route == route) return false
    // Numa tela dentro da aba (console, página do jogo, navegador): volta à raiz dela. Navegar para a aba
    // guardaria e restauraria a mesma pilha, e a tela só piscava.
    if (route != "home" && runCatching { getBackStackEntry(route) }.isSuccess) {
        popBackStack(route, inclusive = false)
        return true
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
    return true
}

/**
 * Abre a tela raiz de uma aba. Trocar de aba restaura a pilha guardada dela; se ela terminava em outra tela
 * (ex.: navegador aberto a partir de Downloads), essa tela é tirada para mostrar a raiz. Não serve para
 * "home": ela fica na base da pilha de todas as abas.
 */
private fun NavHostController.openTabRoot(route: String) {
    val inStack = runCatching { getBackStackEntry(route) }.isSuccess
    if (!inStack) navigateTab(route)
    popBackStack(route, inclusive = false)
}

/**
 * Barra de abas flutuante: cápsula translúcida; a aba ativa ganha o degradê do sol e mostra o rótulo.
 * A aba de downloads mostra quantos estão em andamento.
 */
@Composable
private fun FloatingTabBar(route: String?, activeDownloads: Int, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Palette.Ink.copy(alpha = 0.94f), 0.55f to Palette.Ink))
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp)
            .shadow(24.dp, shape, ambientColor = Palette.Neon, spotColor = Palette.Neon)
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline, shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = route == tab.route
            val label = stringResource(tab.label)
            val tint by animateColorAsState(if (selected) Color(0xFF1C0010) else Palette.TextSecondary, label = "tab")
            val badge = if (tab.route == "downloads") activeDownloads else 0
            Row(
                Modifier
                    // Só a aba ativa cresce, e só até o espaço que sobra: em telas estreitas o rótulo encurta em vez de empurrar as outras.
                    .then(if (selected) Modifier.weight(1f, fill = false) else Modifier)
                    .clip(RoundedCornerShape(22.dp))
                    .then(if (selected) Modifier.background(Palette.SunsetHorizontal) else Modifier)
                    .clickable(onClickLabel = label) { onSelect(tab.route) }
                    .animateContentSize(spring(stiffness = 500f))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BadgedBox(badge = {
                    if (badge > 0) Badge(containerColor = Palette.Cyan, contentColor = Palette.Ink) { Text("$badge") }
                }) {
                    Icon(tab.icon, label, tint = tint, modifier = Modifier.size(22.dp))
                }
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
