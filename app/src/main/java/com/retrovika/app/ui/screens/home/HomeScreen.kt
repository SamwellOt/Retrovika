package com.retrovika.app.ui.screens.home

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Calendar
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import com.retrovika.app.ui.components.HeaderIconButton
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.res.pluralStringResource
import com.retrovika.app.core.catalog.DownloadManager
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.ui.components.ScrollToTopOnReselect
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import com.retrovika.app.container
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.BrandMark
import com.retrovika.app.ui.components.GameCard
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.IconTile
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.Pill
import com.retrovika.app.ui.components.SectionHeader
import com.retrovika.app.ui.components.SurfaceCard
import com.retrovika.app.ui.components.UpdateCard
import com.retrovika.app.ui.components.cardRelease
import com.retrovika.app.core.update.UpdateState
import kotlinx.coroutines.launch
import com.retrovika.app.ui.components.Wordmark
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.theme.Palette

@Composable
fun HomeScreen(
    onOpenGame: (Long) -> Unit,
    onOpenSystem: (String) -> Unit,
    onExplore: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloads: () -> Unit,
    onAddFolder: () -> Unit,
    onReceive: () -> Unit,
) {
    val context = LocalContext.current
    val library = context.container.library
    val recent by library.recent.collectAsStateWithLifecycle()
    val favorites by library.favorites.collectAsStateWithLifecycle()
    val newest by library.newest.collectAsStateWithLifecycle()
    val counts by library.counts.collectAsStateWithLifecycle()
    val countsLoaded by library.countsLoaded.collectAsStateWithLifecycle()
    // Só o "em andamento": o contador da varredura muda várias vezes por segundo e é lido só nos
    // pedaços que o mostram (ScanBadge, ScanStatus), sem recompor a tela inteira.
    val scanning by remember { library.scan.map { it.running }.distinctUntilChanged() }
        .collectAsStateWithLifecycle(library.scan.value.running)
    // Só o contador: a lista de tarefas muda a cada aviso de progresso e recomporia a tela inteira.
    val activeDownloads by context.container.downloads.activeCount.collectAsStateWithLifecycle()
    val update by context.container.updater.state.collectAsStateWithLifecycle()
    val settings by context.container.settings.cached.collectAsStateWithLifecycle()
    val bottom = LocalBottomInset.current
    val listState = rememberLazyListState()
    ScrollToTopOnReselect("home", listState)

    LazyColumn(Modifier.fillMaxSize().ambientGlow(), state = listState, contentPadding = PaddingValues(bottom = bottom + 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(36.dp)
                    Spacer(Modifier.width(12.dp))
                    Wordmark(fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    if (scanning) ScanBadge()
                    // Estado compartilhado por um amigo ou partida em rede: QR code ou arquivo.
                    HeaderIconButton(Icons.Rounded.QrCodeScanner, stringResource(R.string.share_chooser_title), onReceive)
                }
                Spacer(Modifier.height(22.dp))
                val (salute, question) = greeting(recent.isNotEmpty())
                Text(salute, style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
                Text(question, style = MaterialTheme.typography.headlineLarge)
                if (scanning) ScanStatus()
            }
        }

        if (activeDownloads > 0) {
            item(key = "downloads") { ActiveDownloadsCard(onClick = onOpenDownloads) }
        }

        // Versão nova do app: some com "Depois" e só volta para a próxima versão (ou pelos ajustes).
        val release = update.cardRelease()
        if (release != null && (update !is UpdateState.Available || release.version != settings.dismissedUpdate)) {
            item(key = "update") {
                UpdateCard(
                    update, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    onDismiss = { context.container.scope.launch { context.container.settings.setDismissedUpdate(release.version) } },
                )
            }
        }

        val hero = recent.firstOrNull()
        if (hero != null) {
            item(key = "hero") { ContinueCard(hero, onPlay = { GameActivity.launch(context, hero.id) }, onDetails = { onOpenGame(hero.id) }) }
        }

        if (counts.isNotEmpty()) {
            item(key = "stats") {
                val total = counts.sumOf { it.count }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("$total", stringResource(R.string.home_stat_games), Palette.Neon, Modifier.weight(1f))
                    StatTile("${counts.size}", stringResource(R.string.home_stat_consoles), Palette.Cyan, Modifier.weight(1f))
                    StatTile("${favorites.size}", stringResource(R.string.home_stat_favorites), Palette.Sun, Modifier.weight(1f))
                }
            }
        }

        // Só depois da primeira resposta do banco: antes disso o cartão piscava para quem já tem jogos.
        if (countsLoaded && counts.isEmpty() && !scanning) {
            item(key = "welcome") { WelcomeCard(onAddFolder = onAddFolder, onExplore = onExplore) }
        }

        if (counts.isNotEmpty()) {
            // Chaves fixas: o cartão de downloads entra e sai acima, e sem elas a fileira de consoles
            // perdia a posição de rolagem a cada download iniciado ou concluído.
            item(key = "consoles") {
                Spacer(Modifier.height(18.dp))
                SectionHeader(stringResource(R.string.home_your_consoles), action = stringResource(R.string.common_see_all), onAction = onOpenLibrary)
                Spacer(Modifier.height(12.dp))
                val present = remember(counts) {
                    val byId = counts.associate { it.systemId to it.count }
                    Systems.all.mapNotNull { s -> byId[s.id]?.let { s to it } }
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(present, key = { it.first.id }) { (system, count) ->
                        val shape = RoundedCornerShape(16.dp)
                        Row(
                            Modifier
                                .clip(shape)
                                .background(Brush.linearGradient(listOf(system.accentColor().copy(alpha = 0.28f), Palette.SurfaceHigh)))
                                .border(1.dp, system.readableAccent().copy(alpha = 0.35f), shape)
                                .clickable { onOpenSystem(system.id) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(system.shortName, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "$count",
                                style = MaterialTheme.typography.labelMedium,
                                color = system.readableAccent(),
                                modifier = Modifier.background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }

        shelf("recent", R.string.home_shelf_recent, recent.drop(1), onOpenGame)
        shelf("favorites", R.string.home_shelf_favorites, favorites, onOpenGame)
        shelf("newest", R.string.home_shelf_newest, newest, onOpenGame)
    }
}

/** Indicador da varredura no topo, com o número de jogos achados até agora. */
@Composable
private fun ScanBadge() {
    val scan by LocalContext.current.container.library.scan.collectAsStateWithLifecycle()
    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Palette.Cyan)
    Spacer(Modifier.width(6.dp))
    Text("${scan.found}", style = MaterialTheme.typography.labelMedium, color = Palette.Cyan)
    Spacer(Modifier.width(10.dp))
}

@Composable
private fun ScanStatus() {
    val scan by LocalContext.current.container.library.scan.collectAsStateWithLifecycle()
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.home_scanning, scan.found), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
}

private fun androidx.compose.foundation.lazy.LazyListScope.shelf(key: String, @StringRes title: Int, games: List<Game>, onOpenGame: (Long) -> Unit) {
    if (games.isEmpty()) return
    item(key = "shelf-$key") {
        Spacer(Modifier.height(26.dp))
        SectionHeader(stringResource(title))
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(games, key = { it.id }) { game -> GameCard(game, onClick = { onOpenGame(game.id) }, width = 128.dp) }
        }
    }
}

/** Resumo dos downloads em andamento; toca para abrir a aba de downloads. */
@Composable
private fun ActiveDownloadsCard(onClick: () -> Unit) {
    // O progresso é lido aqui dentro: a cada aviso só este cartão recompõe.
    val tasks by LocalContext.current.container.downloads.tasks.collectAsStateWithLifecycle()
    val active = remember(tasks) { tasks.filter { it.status in DownloadManager.ACTIVE } }
    if (active.isEmpty()) return
    val known = active.filter { it.bytesTotal > 0 }
    val progress = if (known.isEmpty()) -1f else known.sumOf { it.bytesDone }.toFloat() / known.sumOf { it.bytesTotal }
    val speed = active.sumOf { it.speed }
    SurfaceCard(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Downloading, Palette.Cyan, size = 38.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.home_downloading, active.size, active.size),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    listOfNotNull(
                        active.first().title,
                        if (speed > 0) stringResource(R.string.downloads_speed, speed.formatBytes()) else null,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                val bar = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                else LinearProgressIndicator(modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextMuted)
        }
    }
}

@Composable
private fun StatTile(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.SurfaceHigh.copy(alpha = 0.7f))
            .border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
    }
}

/** Primeiro uso: apresenta o app e os dois caminhos para montar a coleção. */
@Composable
private fun WelcomeCard(onAddFolder: () -> Unit, onExplore: () -> Unit) {
    SurfaceCard(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        brush = Brush.linearGradient(listOf(Color(0xFF3A1060), Palette.SurfaceHigh, Palette.Surface)),
    ) {
        Column(Modifier.padding(22.dp)) {
            BrandMark(64.dp)
            Spacer(Modifier.height(18.dp))
            Kicker(stringResource(R.string.home_welcome_kicker))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.home_welcome_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.home_welcome_message),
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
            )
            Spacer(Modifier.height(18.dp))
            StepRow(Icons.Rounded.CreateNewFolder, Palette.Neon, stringResource(R.string.home_step_folder_title), stringResource(R.string.home_step_folder_subtitle))
            Spacer(Modifier.height(10.dp))
            StepRow(Icons.Rounded.Explore, Palette.Cyan, stringResource(R.string.home_step_explore_title), stringResource(R.string.home_step_explore_subtitle))
            Spacer(Modifier.height(20.dp))
            // FlowRow: em telas de 360dp (ou com fonte grande) os dois botões não cabem lado a lado.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GradientButton(stringResource(R.string.common_link_folder), onAddFolder, icon = Icons.Rounded.CreateNewFolder)
                GhostButton(stringResource(R.string.common_explore), onExplore, icon = Icons.Rounded.Explore)
            }
        }
    }
}

@Composable
private fun StepRow(icon: ImageVector, tint: Color, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun ContinueCard(game: Game, onPlay: () -> Unit, onDetails: () -> Unit) {
    val system = Systems.byId(game.systemId)
    val accent = system?.accentColor() ?: Palette.Violet
    val shape = RoundedCornerShape(28.dp)
    Box(
        Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
            .shadow(28.dp, shape, ambientColor = accent, spotColor = accent)
            .clip(shape)
            .background(Palette.Surface)
            .clickable(onClick = onDetails),
    ) {
        // Fundo: a capa ampliada e desfocada, coberta por um degradê na cor do console.
        // A capa é decodificada pequena: vai ser desfocada de qualquer jeito, e o bitmap menor pesa bem menos.
        game.coverUrl?.let { url ->
            val platform = LocalPlatformContext.current
            val small = remember(url) { ImageRequest.Builder(platform).data(url).size(96).build() }
            AsyncImage(small, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(28.dp).graphicsLayer { alpha = 0.55f })
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.55f), Color(0xE6130E21), Color(0xF20B0714))),
            ),
        )
        Box(Modifier.matchParentSize().border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.2f), Color.Transparent)), shape))
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            GameCover(game.title, system, game.coverUrl, Modifier.width(104.dp).height(140.dp), corner = 16.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Kicker(stringResource(R.string.common_continue), color = Palette.Sun)
                Spacer(Modifier.height(8.dp))
                Text(game.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    system?.let { Pill(it.shortName, color = it.readableAccent()) }
                    if (game.playTimeSeconds > 60) Pill(formatPlayTime(game.playTimeSeconds), icon = Icons.Rounded.Schedule)
                }
                Spacer(Modifier.height(14.dp))
                GradientButton(stringResource(R.string.common_play), onPlay, icon = Icons.Rounded.PlayArrow, height = 44.dp)
            }
        }
    }
}

@Composable
fun formatPlayTime(seconds: Long): String {
    val h = (seconds / 3600).toInt()
    val m = ((seconds % 3600) / 60).toInt()
    return if (h > 0) stringResource(R.string.common_playtime_hm, h, m) else stringResource(R.string.common_playtime_m, m)
}

@Composable
private fun greeting(returning: Boolean): Pair<String, String> {
    // A tela de início fica aberta por horas (o app volta do segundo plano nela): a hora é relida a cada
    // retorno ao primeiro plano e na virada de cada hora, para o "bom dia" não continuar à noite.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val hour by produceState(currentHour(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = currentHour()
                val now = Calendar.getInstance()
                val msToNextHour = (60 - now.get(Calendar.MINUTE)) * 60_000L - now.get(Calendar.SECOND) * 1_000L
                delay(msToNextHour.coerceAtLeast(1_000L) + 1_000L)
            }
        }
    }
    val base = when (hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..17 -> R.string.home_greeting_afternoon
        else -> R.string.home_greeting_evening
    }
    return stringResource(base) to stringResource(if (returning) R.string.home_question_returning else R.string.home_question_new)
}

private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
