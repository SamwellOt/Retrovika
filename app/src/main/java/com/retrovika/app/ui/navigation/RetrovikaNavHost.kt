package com.retrovika.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.retrovika.app.ui.screens.explore.ExploreScreen
import com.retrovika.app.ui.screens.home.HomeScreen
import com.retrovika.app.ui.screens.library.LibraryScreen
import com.retrovika.app.ui.screens.library.SystemScreen
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
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

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
            scope.launch {
                app.settings.addFolder(uri.toString())
                app.library.rescan()
            }
        }
    }
    val addFolder = { folderPicker.launch(null) }

    LaunchedEffect(Unit) { app.library.rescan() }

    Scaffold(
        containerColor = Palette.Ink,
        bottomBar = {
            AnimatedVisibility(
                visible = route in tabRoutes,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                FloatingTabBar(route, onSelect = { nav.navigateTab(it) })
            }
        },
    ) { padding ->
        // O conteúdo rola por baixo da barra flutuante; cada tela soma LocalBottomInset ao seu padding.
        Box(Modifier.fillMaxSize()) {
          CompositionLocalProvider(LocalBottomInset provides padding.calculateBottomPadding()) {
            NavHost(
                nav, startDestination = "home",
                // Abas trocam com um fade curto; telas empilhadas entram deslizando levemente da direita.
                // Durações enxutas: durante a transição as duas telas são desenhadas ao mesmo tempo.
                enterTransition = {
                    if (targetState.destination.route in tabRoutes) fadeIn(tween(TAB_FADE_MS))
                    else fadeIn(tween(PUSH_MS)) + slideInHorizontally(tween(PUSH_MS)) { it / 10 }
                },
                exitTransition = { fadeOut(tween(EXIT_FADE_MS)) },
                popEnterTransition = { fadeIn(tween(TAB_FADE_MS)) },
                popExitTransition = {
                    if (initialState.destination.route in tabRoutes) fadeOut(tween(EXIT_FADE_MS))
                    else fadeOut(tween(EXIT_FADE_MS)) + slideOutHorizontally(tween(PUSH_MS)) { it / 10 }
                },
            ) {
                composable("home") {
                    HomeScreen(
                        onOpenGame = { nav.navigate("game/$it") },
                        onOpenSystem = { nav.navigate("system/$it") },
                        onExplore = { nav.navigateTab("explore") },
                        onAddFolder = addFolder,
                    )
                }
                composable("library") {
                    LibraryScreen(onOpenSystem = { nav.navigate("system/$it") }, onOpenGame = { nav.navigate("game/$it") })
                }
                composable("explore") {
                    ExploreScreen(onOpenDownloads = { nav.navigate("downloads") }, onOpenBrowser = { nav.navigate("browser") })
                }
                composable("browser") { BrowserScreen(onBack = nav::popBackStack, onOpenDownloads = { nav.navigate("downloads") }) }
                composable("settings") {
                    SettingsScreen(
                        onAddFolder = addFolder,
                        onOpenCores = { nav.navigate("settings/cores") },
                        onOpenBios = { nav.navigate("settings/bios") },
                    )
                }
                composable("downloads") { DownloadsScreen(onBack = nav::popBackStack) }
                composable("settings/cores") { CoresScreen(onBack = nav::popBackStack) }
                composable("settings/bios") { BiosScreen(onBack = nav::popBackStack) }
                composable("system/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    SystemScreen(
                        systemId = entry.arguments?.getString("id").orEmpty(),
                        onBack = nav::popBackStack,
                        onOpenGame = { nav.navigate("game/$it") },
                        onOpenBios = { nav.navigate("settings/bios") },
                    )
                }
                composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                    GameDetailsScreen(gameId = entry.arguments?.getLong("id") ?: -1, onBack = nav::popBackStack)
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

private fun NavHostController.navigateTab(route: String) {
    // Tocar na aba já aberta não refaz a navegação nem dispara a transição.
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Barra de abas flutuante: cápsula translúcida; a aba ativa ganha o degradê do sol e mostra o rótulo. */
@Composable
private fun FloatingTabBar(route: String?, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Transparent, 0.25f to Palette.Ink.copy(alpha = 0.94f), 0.55f to Palette.Ink))
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp)
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
            Row(
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .then(if (selected) Modifier.background(Palette.SunsetHorizontal) else Modifier)
                    .clickable { onSelect(tab.route) }
                    .animateContentSize(spring(stiffness = 500f))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(tab.icon, label, tint = tint, modifier = Modifier.size(22.dp))
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                }
            }
        }
    }
}
