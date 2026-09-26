package com.retrovika.app.ui.screens.library

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.container
import com.retrovika.app.core.bios.BiosManager
import com.retrovika.app.core.bios.BiosStatus
import com.retrovika.app.core.systems.BiosFile
import com.retrovika.app.core.systems.Preset
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.ui.components.Badge
import com.retrovika.app.ui.components.ChipStrip
import com.retrovika.app.ui.components.SearchField
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.settings.GameSort
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalFocusManager
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.IconTile
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.SectionHeader
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.theme.DisplayFamily
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VideogameAsset
import com.retrovika.app.ui.components.EmptyState
import com.retrovika.app.ui.components.GameCard
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun SystemScreen(systemId: String, onBack: () -> Unit, onOpenGame: (Long) -> Unit, onOpenBios: () -> Unit) {
    val system = Systems.byId(systemId) ?: return
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    // Null até o Room responder: começar com lista vazia mostrava "nenhum jogo" durante a transição.
    val loaded by remember(systemId) { app.library.bySystem(systemId) }.collectAsStateWithLifecycle(null)
    val settings by app.settings.cached.collectAsStateWithLifecycle()
    var query by rememberSaveable(systemId) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val all = loaded.orEmpty()
    // Busca e ordem aplicadas em memória: a lista de um console é pequena e já vem do Room.
    val games = remember(all, query, settings.gameSort) { all.filterByTitle(query).sortedFor(settings.gameSort) }
    val selectedCore by remember(systemId) { app.settings.coreFor(systemId) }.collectAsStateWithLifecycle(null)
    val preset by remember(systemId) { app.settings.presetFor(systemId) }.collectAsStateWithLifecycle(Preset.BALANCED)
    // BIOS com hash errado também contam como ausentes; em grupos, basta uma alternativa válida.
    val biosMissing by produceState(emptyList<List<BiosFile>>(), systemId) {
        val ok = app.bios.checkAsync(system).filter { it.status == BiosStatus.OK }.map { it.bios }.toSet()
        value = BiosManager.unsatisfied(system.bios) { it in ok }
    }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importErrors by remember { mutableStateOf<String?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // No escopo do app: sair da tela no meio da cópia não a interrompe nem deixa arquivos pela metade.
        app.scope.launch(Dispatchers.Main) {
            importMessage = context.resources.getQuantityString(R.plurals.system_importing, uris.size, uris.size)
            importErrors = null
            val result = app.library.importFiles(uris, system)
            importMessage = if (result.unknown.isEmpty()) context.getString(R.string.system_import_done)
            else context.getString(R.string.system_import_skipped, result.unknown.joinToString())
            // Cada arquivo que falhou aparece com o motivo, abaixo do botão.
            importErrors = result.failed.takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { (name, reason) -> context.getString(R.string.system_import_failed, name, reason) }
        }
    }

    val core = system.core(selectedCore)

    val accent = system.accentColor()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(settings.coverSize.minWidth.dp),
        modifier = Modifier.fillMaxSize().ambientGlow(primary = accent, secondary = Palette.Violet, height = 520.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp + LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth()) {
                    // Sigla gigante em marca d'água atrás do título.
                    // matchParentSize: a marca d'água não influencia a altura do cabeçalho.
                    Box(Modifier.matchParentSize(), contentAlignment = Alignment.BottomEnd) {
                        Text(
                            system.shortName,
                            fontFamily = DisplayFamily, fontSize = 110.sp, fontWeight = FontWeight.Black,
                            color = accent.copy(alpha = 0.12f), maxLines = 1, softWrap = false,
                            modifier = Modifier.wrapContentSize(unbounded = true, align = Alignment.BottomEnd),
                        )
                    }
                    ScreenHeader(
                        system.name,
                        kicker = "${system.manufacturer} · ${system.year}",
                        subtitle = if (loaded == null) null else pluralStringResource(R.plurals.games_count, all.size, all.size),
                        onBack = onBack,
                        inset = 4.dp,
                    ) { if (system.experimental) Badge(stringResource(R.string.system_experimental), Palette.Sun) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientButton(stringResource(R.string.system_import_games), { importer.launch(arrayOf("*/*")) }, icon = Icons.Rounded.FileOpen, height = 44.dp)
                    importMessage?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f)) }
                }
                importErrors?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Coral, modifier = Modifier.padding(horizontal = 4.dp))
                }

                if (biosMissing.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(Palette.Coral.copy(alpha = 0.10f))
                            .border(1.dp, Palette.Coral.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(Icons.Rounded.Warning, Palette.Coral, size = 36.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.system_bios_missing), style = MaterialTheme.typography.titleSmall)
                            val or = stringResource(R.string.system_bios_or)
                            Text(
                                biosMissing.joinToString("; ") { g -> g.joinToString(or) { it.fileName } },
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        GhostButton(stringResource(R.string.common_import), onOpenBios, tint = Palette.Coral)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Palette.SurfaceHigh.copy(alpha = 0.85f))
                        .border(1.dp, Palette.Outline.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                        .padding(vertical = 14.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.Memory, Palette.Cyan, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.system_core), style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(10.dp))
                    ChipStrip(contentPadding = PaddingValues(horizontal = 14.dp)) {
                        system.cores.forEach { c ->
                            SelectChip(
                                if (c.experimental) stringResource(R.string.system_core_beta, c.displayName) else c.displayName,
                                c.id == core.id,
                                onClick = { scope.launch { app.settings.setCore(system.id, c.id) } },
                            )
                        }
                    }
                    Text(
                        stringResource(core.description),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                    )

                    if (core.presets.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Palette.Outline.copy(alpha = 0.5f))
                        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Rounded.Speed, Palette.Sun, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.system_preset), style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        ChipStrip(contentPadding = PaddingValues(horizontal = 14.dp)) {
                            Preset.entries.forEach { p ->
                                SelectChip(stringResource(p.label), p == preset, onClick = { scope.launch { app.settings.setPreset(system.id, p) } })
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.system_formats, system.extensions.sorted().joinToString { ".$it" }),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                )
                if (all.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.system_games), inset = 4.dp)
                    Spacer(Modifier.height(12.dp))
                    // Só vale a pena buscar quando a lista não cabe numa olhada.
                    if (all.size > 6) {
                        SearchField(query, { query = it }, stringResource(R.string.system_search, system.shortName), onSearch = { focus.clearFocus() })
                        Spacer(Modifier.height(10.dp))
                    }
                    ChipStrip(contentPadding = PaddingValues(0.dp)) {
                        GameSort.entries.forEach { sort ->
                            SelectChip(stringResource(sort.label), sort == settings.gameSort, onClick = { scope.launch { app.settings.setGameSort(sort) } })
                        }
                    }
                }
            }
        }

        if (all.isNotEmpty() && games.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(stringResource(R.string.library_no_results_title), stringResource(R.string.system_no_match, query), icon = Icons.Rounded.SearchOff)
            }
        }

        if (loaded != null && all.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth()) {
                    EmptyState(
                        stringResource(R.string.system_empty_title, system.shortName),
                        stringResource(R.string.system_empty_message, system.id),
                        icon = Icons.Rounded.VideogameAsset,
                    )
                }
            }
        }
        items(games, key = { it.id }, contentType = { "game" }) { game -> GameCard(game, onClick = { onOpenGame(game.id) }) }
    }
}

private fun List<Game>.filterByTitle(query: String): List<Game> {
    val q = query.trim()
    return if (q.isEmpty()) this else filter { it.title.contains(q, ignoreCase = true) }
}

/** A lista chega em ordem alfabética; os outros critérios desempatam por ela (sortedBy é estável). */
private fun List<Game>.sortedFor(sort: GameSort): List<Game> = when (sort) {
    GameSort.TITLE -> this
    GameSort.RECENT -> sortedByDescending { it.lastPlayed ?: 0L }
    GameSort.ADDED -> sortedByDescending { it.addedAt }
    GameSort.PLAYTIME -> sortedByDescending { it.playTimeSeconds }
}
