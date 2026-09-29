package com.retrovika.app.ui.screens.explore

import com.retrovika.app.ui.components.pressScale
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Castle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SportsMma
import androidx.compose.material.icons.rounded.SportsMotorsports
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Stairs
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.catalog.CatalogEntry
import com.retrovika.app.core.catalog.DownloadStatus
import com.retrovika.app.core.catalog.DownloadTask
import com.retrovika.app.core.catalog.Genre
import com.retrovika.app.core.catalog.RomVariant
import com.retrovika.app.core.catalog.downloadKey
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.AccentChip
import com.retrovika.app.ui.components.ChipStrip
import com.retrovika.app.ui.components.DownloadProgressBar
import com.retrovika.app.ui.components.EmptyState
import com.retrovika.app.ui.components.FilterLabel
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.HeaderIconButton
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.ScrollToTopOnReselect
import com.retrovika.app.ui.components.SearchField
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.components.bleed
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.components.regionLabel
import com.retrovika.app.ui.components.shimmer
import com.retrovika.app.ui.theme.Palette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.text.NumberFormat

/** Margem lateral da grade; as faixas de chips "sangram" por ela até a borda da tela. */
private val Gutter = 16.dp

@Composable
fun ExploreScreen(onOpenBrowser: () -> Unit, onOpenGame: (String) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val vm: ExploreViewModel = viewModel { ExploreViewModel(context.container) }
    val state by vm.state.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    ScrollToTopOnReselect("explore", gridState)
    // Um mapa por lista de downloads, não uma busca por card: títulos se repetem entre consoles e
    // fontes, e a lista muda a cada aviso de progresso. A lista vem do mais novo para o mais antigo.
    val downloadsByEntry = remember(downloads) { downloads.asReversed().filter { it.entryKey != null }.associateBy { it.entryKey } }
    val current = vm.sources.first { it.id == state.sourceId }
    val isHomebrew = state.sourceId == "homebrewhub"
    val filtersActive = state.query.isNotBlank() || state.genre != null ||
        (!current.requiresSystem && state.systemId != null) || (isHomebrew && state.kind != "game")

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    // A página e o fim do carregamento também são chaves: depois de páginas vazias (filtro sem
    // resultado nelas) a lista não cresce, e sem isso a rolagem nunca pedia a próxima.
    LaunchedEffect(nearEnd, state.entries.size, state.page, state.loading) { if (nearEnd && !state.loading) vm.loadMore() }

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().ambientGlow(primary = Palette.Cyan, secondary = Palette.Neon),
        contentPadding = PaddingValues(start = Gutter, end = Gutter, bottom = LocalBottomInset.current + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
            Column {
                val subtitle = if (state.initialLoading) stringResource(R.string.explore_searching)
                else stringResource(
                    if (state.totalApproximate) R.string.explore_subtitle_approx else R.string.explore_subtitle,
                    NumberFormat.getIntegerInstance().format(state.totalResults),
                )
                ScreenHeader(stringResource(R.string.tab_explore), subtitle = subtitle, inset = 4.dp) {
                    // Os downloads têm aba própria; aqui fica o atalho para o navegador interno.
                    HeaderIconButton(Icons.Rounded.Language, stringResource(R.string.explore_open_site), onClick = onOpenBrowser)
                }
                Spacer(Modifier.height(16.dp))
                SearchField(state.query, onChange = vm::setQuery, placeholder = stringResource(R.string.explore_search_hint), onSearch = { focus.clearFocus() })

                if (vm.sources.size > 1) {
                    FilterSection(stringResource(R.string.explore_filter_source)) {
                        vm.sources.forEach { src ->
                            SelectChip(src.nameRes?.let { stringResource(it) } ?: src.name, state.sourceId == src.id, onClick = { vm.setSource(src.id) })
                        }
                    }
                }

                FilterSection(stringResource(R.string.explore_filter_console)) {
                    if (!current.requiresSystem) AccentChip(stringResource(R.string.explore_filter_all), state.systemId == null, Palette.TextSecondary, onClick = { vm.setSystem(null) })
                    vm.systems.mapNotNull(Systems::byId).forEach { sys ->
                        AccentChip(sys.shortName, state.systemId == sys.id, sys.readableAccent(), onClick = { vm.setSystem(sys.id) })
                    }
                }

                // Filtros especiais: tocar no gênero ativo o desmarca.
                FilterSection(stringResource(R.string.explore_filter_genre)) {
                    Genre.entries.forEach { g ->
                        AccentChip(stringResource(g.label), state.genre == g, g.accent, onClick = { vm.toggleGenre(g) }, icon = g.icon)
                    }
                }

                if (isHomebrew) {
                    FilterSection(stringResource(R.string.explore_filter_type)) {
                        SelectChip(stringResource(R.string.explore_kind_games), state.kind == "game", onClick = { vm.setKind("game") })
                        SelectChip(stringResource(R.string.explore_kind_demos), state.kind == "demo", onClick = { vm.setKind("demo") })
                        SelectChip(stringResource(R.string.explore_kind_everything), state.kind == null, onClick = { vm.setKind(null) })
                    }
                }

                Spacer(Modifier.height(10.dp))
                // A explicação da fonte fica recolhida: só aparece ao tocar no "i".
                var showInfo by rememberSaveable { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val infoLabel = stringResource(R.string.explore_info_toggle)
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(onClickLabel = infoLabel) { showInfo = !showInfo }
                            .semantics { contentDescription = infoLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Info, null, tint = if (showInfo) Palette.Cyan else Palette.TextMuted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    if (filtersActive) ClearFiltersButton(vm::clearFilters)
                }
                AnimatedVisibility(visible = showInfo, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)) {
                        Text(stringResource(current.description), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                        // O gênero sai das etiquetas e descrições de cada site: é bom avisar que é aproximado.
                        if (state.genre != null) Text(stringResource(R.string.explore_genre_note), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    }
                }
            }
        }

        state.error?.let { error ->
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "message") {
                EmptyState(stringResource(R.string.explore_error_title), stringResource(error), icon = Icons.Rounded.CloudOff) {
                    GhostButton(stringResource(R.string.common_retry), vm::retry, icon = Icons.Rounded.Refresh)
                }
            }
        }

        // Primeira página chegando: cartões-esqueleto no lugar dos jogos, em vez de uma tela vazia.
        if (state.initialLoading) {
            if (state.waitingSlowSources) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "message") {
                    Text(
                        stringResource(R.string.explore_waiting_slow),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(6, key = { "skeleton-$it" }, contentType = { "skeleton" }) { SkeletonCard() }
        }

        if (!state.loading && state.error == null && state.entries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "message") {
                val message = if (state.query.isBlank()) stringResource(R.string.explore_empty_filtered)
                else stringResource(R.string.explore_empty_message, state.query)
                EmptyState(stringResource(R.string.explore_empty_title), message, icon = Icons.Rounded.SearchOff) {
                    if (filtersActive) GhostButton(stringResource(R.string.explore_clear_filters), vm::clearFilters, icon = Icons.Rounded.FilterAltOff)
                }
            }
        }

        items(state.entries, key = { it.sourceId + it.id }, contentType = { "entry" }) { entry ->
            val task = downloadsByEntry[entry.downloadKey]
            CatalogCard(
                entry, task,
                sourceLabel = if (state.aggregated) vm.sourceName(entry.sourceId) else null,
                onOpen = { onOpenGame(context.container.catalog.open(entry)) },
                onDownload = { vm.requestDownload(entry) },
                onPlay = { task?.gameId?.let { GameActivity.launch(context, it) } },
            )
        }

        // Algum site não respondeu nesta página: os jogos dos outros ficam, e dá para pedir de novo.
        if (state.partialFailure && !state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "message") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.explore_partial_failure), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                    GhostButton(stringResource(R.string.common_retry), vm::retryPartial, icon = Icons.Rounded.Refresh)
                }
            }
        }

        if (state.loading && state.entries.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = Palette.Cyan)
                }
            }
        }
    }

    prompt?.let { VariantPickerSheet(it, onPick = vm::confirmVariant, onDismiss = vm::dismissPrompt) }
    }
}

/** Grupo de filtros: rótulo em caixa alta e uma faixa de chips que corre até a borda da tela. */
@Composable
private fun FilterSection(label: String, chips: @Composable () -> Unit) {
    Spacer(Modifier.height(16.dp))
    FilterLabel(label)
    Spacer(Modifier.height(8.dp))
    ChipStrip(Modifier.bleed(Gutter), contentPadding = PaddingValues(horizontal = Gutter)) { chips() }
}

@Composable
private fun ClearFiltersButton(onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FilterAltOff, null, tint = Palette.Neon, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(stringResource(R.string.explore_clear_filters), style = MaterialTheme.typography.labelMedium, color = Palette.Neon)
    }
}

/** Ícone de cada gênero nos chips e no selo do cartão. */
internal val Genre.icon: ImageVector
    get() = when (this) {
        Genre.ACTION -> Icons.Rounded.Bolt
        Genre.ADVENTURE -> Icons.Rounded.Map
        Genre.RPG -> Icons.Rounded.Shield
        Genre.PLATFORM -> Icons.Rounded.Stairs
        Genre.PUZZLE -> Icons.Rounded.Extension
        Genre.SHOOTER -> Icons.Rounded.RocketLaunch
        Genre.RACING -> Icons.Rounded.SportsMotorsports
        Genre.SPORTS -> Icons.Rounded.SportsSoccer
        Genre.FIGHTING -> Icons.Rounded.SportsMma
        Genre.STRATEGY -> Icons.Rounded.Castle
        Genre.HORROR -> Icons.Rounded.Nightlight
        Genre.MUSIC -> Icons.Rounded.MusicNote
    }

/** Cor de cada gênero, tirada da paleta synthwave e de tons vizinhos a ela. */
internal val Genre.accent: Color
    get() = when (this) {
        Genre.ACTION -> Palette.Orange
        Genre.ADVENTURE -> Palette.Success
        Genre.RPG -> Palette.Violet
        Genre.PLATFORM -> Palette.Sun
        Genre.PUZZLE -> Palette.Cyan
        Genre.SHOOTER -> Palette.Neon
        Genre.RACING -> Palette.Coral
        Genre.SPORTS -> Color(0xFF7FD4FF)
        Genre.FIGHTING -> Color(0xFFFF6A5C)
        Genre.STRATEGY -> Color(0xFFB9A4FF)
        Genre.HORROR -> Color(0xFFC3BCDA)
        Genre.MUSIC -> Color(0xFFFF9EDB)
    }

private val CardShape = RoundedCornerShape(20.dp)

@Composable
private fun CatalogCard(entry: CatalogEntry, task: DownloadTask?, sourceLabel: String?, onOpen: () -> Unit, onDownload: () -> Unit, onPlay: () -> Unit) {
    val system = Systems.byId(entry.systemId)
    val genre = remember(entry.tags) { Genre.of(entry.tags).firstOrNull() }
    val source = remember { MutableInteractionSource() }
    // O cartão inteiro abre a página do jogo; o botão de baixo continua baixando direto.
    Column(
        Modifier
            .pressScale(source, pressed = 0.98f)
            .clip(CardShape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Palette.Outline.copy(alpha = 0.5f))), CardShape)
            .clickable(source, null, onClickLabel = stringResource(R.string.explore_open_game), onClick = onOpen),
    ) {
        Box {
            GameCover(
                entry.title, system, entry.coverUrl,
                Modifier.fillMaxWidth().aspectRatio(10f / 9f), corner = 0.dp,
            )
            sourceLabel?.let { CoverTag(it, Palette.TextPrimary, Modifier.align(Alignment.TopStart)) }
            genre?.let { g ->
                Icon(
                    g.icon, stringResource(g.label), tint = g.accent,
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(26.dp)
                        .background(Palette.Ink.copy(alpha = 0.75f), RoundedCornerShape(8.dp)).padding(5.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            // Duas linhas fixas: os cartões da mesma fileira ficam com a mesma altura.
            Text(entry.title, style = MaterialTheme.typography.titleSmall, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(system?.shortName, entry.developer).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = system?.readableAccent() ?: Palette.TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            when (task?.status) {
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED, DownloadStatus.EXTRACTING -> Column(Modifier.height(44.dp), verticalArrangement = Arrangement.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val label = when (task.status) {
                            DownloadStatus.EXTRACTING -> R.string.explore_extracting
                            // A fila é real: com o limite de downloads simultâneos atingido, o jogo espera a vez.
                            DownloadStatus.QUEUED -> R.string.downloads_queued
                            else -> R.string.explore_downloading
                        }
                        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (task.status == DownloadStatus.DOWNLOADING && task.progress >= 0f) Text("${(task.progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Palette.Cyan)
                    }
                    Spacer(Modifier.height(6.dp))
                    DownloadProgressBar(task)
                }
                DownloadStatus.FAILED -> Column {
                    // Sem o motivo, "Tentar de novo" não diz o que deu errado (sem espaço, página no lugar do arquivo…).
                    task.error?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.Coral, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                    }
                    GhostButton(stringResource(R.string.common_retry), onDownload, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, tint = Palette.Coral)
                }
                DownloadStatus.DONE -> GradientButton(stringResource(R.string.common_play), onPlay, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow, height = 44.dp)
                else -> GhostButton(stringResource(R.string.common_download), onDownload, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download)
            }
        }
    }
}

/** Etiqueta pequena sobre a capa (fonte do resultado na busca unificada). */
@Composable
private fun CoverTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Ink.copy(alpha = 0.75f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Cartão-esqueleto com as mesmas proporções do [CatalogCard]. */
@Composable
private fun SkeletonCard() {
    Column(
        Modifier
            .clip(CardShape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline.copy(alpha = 0.5f), CardShape),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(10f / 9f).shimmer(RectangleShape))
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.fillMaxWidth(0.85f).height(14.dp).shimmer())
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.55f).height(14.dp).shimmer())
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth(0.4f).height(10.dp).shimmer())
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(44.dp).shimmer(RoundedCornerShape(50)))
        }
    }
}

/** Seletor de "qual ROM baixar": lista as versões (região/revisão/formato) da entrada. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VariantPickerSheet(
    prompt: VariantPrompt,
    onPick: (CatalogEntry, RomVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Palette.Surface) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(prompt.entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.explore_pick_rom), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            Spacer(Modifier.height(12.dp))
            when (prompt) {
                is VariantPrompt.Loading -> Column(Modifier.fillMaxWidth()) {
                    repeat(3) {
                        Box(Modifier.fillMaxWidth().height(52.dp).padding(vertical = 6.dp).shimmer(RoundedCornerShape(12.dp)))
                    }
                }
                is VariantPrompt.Failed -> Text(
                    stringResource(R.string.explore_variants_failed),
                    color = Palette.TextSecondary, modifier = Modifier.padding(vertical = 12.dp),
                )
                is VariantPrompt.Ready -> prompt.variants.forEachIndexed { index, variant ->
                    if (index > 0) HorizontalDivider(color = Palette.Outline)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onPick(prompt.entry, variant) }
                            .padding(vertical = 14.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(variant.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(variant.region?.let { regionLabel(it) }, variant.sizeBytes?.takeIf { it > 0 }?.formatBytes(), variant.note)
                            if (meta.isNotEmpty()) {
                                Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Icon(Icons.Rounded.Download, stringResource(R.string.common_download), Modifier.size(20.dp), tint = Palette.Cyan)
                    }
                }
            }
        }
    }
}
