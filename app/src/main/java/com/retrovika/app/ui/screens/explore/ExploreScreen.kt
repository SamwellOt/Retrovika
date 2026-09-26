package com.retrovika.app.ui.screens.explore

import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import com.retrovika.app.ui.components.regionLabel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.retrovika.app.container
import com.retrovika.app.core.catalog.CatalogEntry
import com.retrovika.app.core.catalog.RomVariant
import com.retrovika.app.core.catalog.downloadKey
import com.retrovika.app.core.catalog.DownloadStatus
import com.retrovika.app.core.catalog.DownloadTask
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.ChipStrip
import com.retrovika.app.ui.components.HeaderIconButton
import com.retrovika.app.ui.components.ScrollToTopOnReselect
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.ui.components.EmptyState
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.SearchField
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.ambientGlow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.theme.Palette

@Composable
fun ExploreScreen(onOpenBrowser: () -> Unit) {
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

    val nearEnd by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
        }
    }
    LaunchedEffect(nearEnd, state.entries.size) { if (nearEnd) vm.loadMore() }

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().ambientGlow(primary = Palette.Cyan, secondary = Palette.Neon),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottomInset.current + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ScreenHeader(stringResource(R.string.tab_explore), subtitle = stringResource(R.string.explore_subtitle, state.totalResults), inset = 4.dp) {
                    // Os downloads têm aba própria; aqui fica o atalho para o navegador interno.
                    HeaderIconButton(Icons.Rounded.Language, stringResource(R.string.explore_open_site), onClick = onOpenBrowser)
                }
                Spacer(Modifier.height(16.dp))
                SearchField(state.query, onChange = vm::setQuery, placeholder = stringResource(R.string.explore_search_hint), onSearch = { focus.clearFocus() })
                Spacer(Modifier.height(12.dp))
                if (vm.sources.size > 1) {
                    ChipStrip(contentPadding = PaddingValues(0.dp)) {
                        vm.sources.forEach { src -> SelectChip(src.nameRes?.let { stringResource(it) } ?: src.name, state.sourceId == src.id, onClick = { vm.setSource(src.id) }) }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                ChipStrip(contentPadding = PaddingValues(0.dp)) {
                    val current = vm.sources.first { it.id == state.sourceId }
                    if (!current.requiresSystem) SystemChip(stringResource(R.string.explore_filter_all), state.systemId == null, Palette.TextSecondary) { vm.setSystem(null) }
                    vm.systems.mapNotNull(Systems::byId).forEach { sys ->
                        SystemChip(sys.shortName, state.systemId == sys.id, sys.readableAccent()) { vm.setSystem(sys.id) }
                    }
                    if (state.sourceId == "homebrewhub") {
                        Spacer(Modifier.width(8.dp))
                        SystemChip(stringResource(R.string.explore_kind_games), state.kind == "game", Palette.Sun) { vm.setKind("game") }
                        SystemChip(stringResource(R.string.explore_kind_demos), state.kind == "demo", Palette.Sun) { vm.setKind("demo") }
                        SystemChip(stringResource(R.string.explore_kind_everything), state.kind == null, Palette.Sun) { vm.setKind(null) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Info, null, tint = Palette.TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(vm.sources.first { it.id == state.sourceId }.description), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                }
            }
        }

        state.error?.let { error ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(stringResource(R.string.explore_error_title), stringResource(error), icon = Icons.Rounded.CloudOff) {
                    GhostButton(stringResource(R.string.common_retry), vm::retry, icon = Icons.Rounded.Refresh)
                }
            }
        }

        if (!state.loading && state.error == null && state.entries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(stringResource(R.string.explore_empty_title), stringResource(R.string.explore_empty_message, state.query), icon = Icons.Rounded.SearchOff)
            }
        }

        items(state.entries, key = { it.sourceId + it.id }, contentType = { "entry" }) { entry ->
            val task = downloadsByEntry[entry.downloadKey]
            CatalogCard(
                entry, task,
                sourceLabel = if (state.aggregated) vm.sourceName(entry.sourceId) else null,
                onDownload = { vm.requestDownload(entry) },
                onPlay = { task?.gameId?.let { GameActivity.launch(context, it) } },
            )
        }

        if (state.loading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }

    prompt?.let { VariantPickerSheet(it, onPick = vm::confirmVariant, onDismiss = vm::dismissPrompt) }
    }
}

@Composable
private fun CatalogCard(entry: CatalogEntry, task: DownloadTask?, sourceLabel: String?, onDownload: () -> Unit, onPlay: () -> Unit) {
    val system = Systems.byId(entry.systemId)
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.SurfaceHigh)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f), Palette.Outline.copy(alpha = 0.5f))), RoundedCornerShape(20.dp)),
    ) {
        Box {
            GameCover(
                entry.title, system, entry.coverUrl,
                Modifier.fillMaxWidth().aspectRatio(10f / 9f), corner = 0.dp,
            )
            sourceLabel?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextPrimary,
                    modifier = Modifier
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.SurfaceHigh.copy(alpha = 0.85f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Column(Modifier.padding(12.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    if (task.status == DownloadStatus.QUEUED) {
                        LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                    } else if (task.progress > 0f && task.status == DownloadStatus.DOWNLOADING) {
                        LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)), color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                    }
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
                else -> GhostButton(
                    stringResource(if (task?.status == DownloadStatus.FAILED) R.string.common_retry else R.string.common_download),
                    onDownload, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download,
                    tint = if (task?.status == DownloadStatus.FAILED) Palette.Coral else Palette.TextPrimary,
                )
            }
        }
    }
}

/** Seletor de "qual ROM baixar": lista as versões (região/revisão/formato) da entrada. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VariantPickerSheet(
    prompt: VariantPrompt,
    onPick: (CatalogEntry, RomVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp)) {
            Text(prompt.entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.explore_pick_rom), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            Spacer(Modifier.height(12.dp))
            when (prompt) {
                is VariantPrompt.Loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
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
                            .clickable { onPick(prompt.entry, variant) }
                            .padding(vertical = 14.dp),
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
                        Icon(Icons.Rounded.Download, stringResource(R.string.common_download), Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Chip de filtro com ponto na cor do console. */
@Composable
private fun SystemChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(if (selected) color.copy(alpha = 0.18f) else Palette.SurfaceHigh)
            .border(1.dp, if (selected) color.copy(alpha = 0.8f) else Palette.Outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.TextPrimary else Palette.TextSecondary)
    }
}
