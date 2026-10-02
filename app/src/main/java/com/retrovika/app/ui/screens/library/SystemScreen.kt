package com.retrovika.app.ui.screens.library

import androidx.lifecycle.viewmodel.compose.viewModel
import com.retrovika.app.core.settings.localized
import com.retrovika.app.ui.components.countString
import com.retrovika.app.ui.components.ScreenMessages
import kotlinx.coroutines.flow.map
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
import androidx.compose.runtime.LaunchedEffect
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
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.core.tuning.DeviceProfile
import com.retrovika.app.core.tuning.Tuning
import com.retrovika.app.ui.components.tuneSourceText
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
import com.retrovika.app.ui.components.SurfaceCard
import com.retrovika.app.core.library.Versions
import com.retrovika.app.core.settings.uiLanguage
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import com.retrovika.app.ui.components.GameCard
import com.retrovika.app.ui.components.GameCardSkeleton
import com.retrovika.app.ui.components.bleed
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.theme.Palette
import com.retrovika.app.core.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SystemScreen(systemId: String, onBack: () -> Unit, onOpenGame: (Long) -> Unit, onOpenBios: () -> Unit, onOpenVersions: () -> Unit) {
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
    // null = ainda carregando; "" = sem escolha (o núcleo padrão).
    val selectedCore by remember(systemId) { app.settings.coreFor(systemId).map { it.orEmpty() } }.collectAsStateWithLifecycle(null)
    // Embrulhado em Loaded: null é "Auto" (escolha válida), e sem o embrulho não dava para distinguir de
    // "ainda carregando" (o chip "Auto" aparecia marcado até a preferência chegar).
    val presetLoaded by remember(systemId) { app.settings.presetChoice(systemId).map { Loaded(it) } }.collectAsStateWithLifecycle(null)
    val presetChoice = presetLoaded?.value
    // BIOS com hash errado também contam como ausentes; em grupos, basta uma alternativa válida.
    val biosMissing by produceState(emptyList<List<BiosFile>>(), systemId) {
        val ok = app.bios.checkAsync(system).filter { it.status == BiosStatus.OK }.map { it.bios }.toSet()
        value = BiosManager.unsatisfied(system.bios) { it in ok }
    }
    val importStatus: ScreenMessages = viewModel { ScreenMessages() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // No escopo do app: sair da tela no meio da cópia não a interrompe nem deixa arquivos pela metade.
        // Contexto da aplicação, pego fora da corrotina: a Activity pode ser recriada (rotação) antes da
        // importação terminar, e capturá-la aqui a manteria viva até o fim.
        val res = context.localized()
        app.scope.launch(Dispatchers.Main) {
            importStatus.message = res.resources.getQuantityString(R.plurals.system_importing, uris.size, uris.size)
            importStatus.errors = null
            // O escopo do app não tem tratador: uma exceção solta aqui derrubaria o processo.
            try {
                val result = app.library.importFiles(uris, system)
                importStatus.message = if (result.unknown.isEmpty()) res.getString(R.string.system_import_done)
                else res.getString(R.string.system_import_skipped, result.unknown.joinToString())
                // Cada arquivo que falhou aparece com o motivo, abaixo do botão.
                importStatus.errors = result.failed.takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { (name, reason) -> res.getString(R.string.system_import_failed, name, reason) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                importStatus.message = null
                importStatus.errors = t.userMessage(res)
            }
        }
    }

    val bench by remember(systemId) { app.settings.benchmark(systemId) }.collectAsStateWithLifecycle(null)
    // Sem escolha do usuário, vale o núcleo do teste automático (quando houve teste).
    val core = system.core(selectedCore?.ifEmpty { bench?.takeIf { !it.skipped }?.chosen })
    val device by produceState<DeviceProfile?>(null) { value = app.deviceProfile.await() }
    val tuned by remember(systemId, core.id) { app.settings.tuning(systemId, core.id) }.collectAsStateWithLifecycle(null)
    val effective = device?.takeIf { presetLoaded != null }?.let { Tuning.effective(core, it, presetChoice, null, tuned) }
    val language = remember { context.uiLanguage() }
    // Só a contagem: a tela de versões refaz os grupos com os detalhes.
    // Fora da thread principal: com milhares de ROMs o agrupamento travava a transição e a rolagem.
    val repeated by produceState(0, all, language) { value = withContext(Dispatchers.Default) { Versions.groups(all, language).size } }

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
                        subtitle = if (loaded == null) null else countString(R.plurals.games_count, all.size),
                        onBack = onBack,
                        inset = 4.dp,
                    ) { if (system.experimental) Badge(stringResource(R.string.system_experimental), Palette.Sun) }
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientButton(stringResource(R.string.system_import_games), { importer.launch(arrayOf("*/*")) }, icon = Icons.Rounded.FileOpen, height = 44.dp)
                    importStatus.message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.weight(1f)) }
                }
                importStatus.errors?.let {
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
                                // Até a preferência chegar, nenhum chip aparece marcado (o padrão piscava selecionado).
                                c.id == core.id && selectedCore != null,
                                onClick = { scope.launch { app.settings.setCore(system.id, c.id) } },
                            )
                        }
                    }
                    Text(
                        stringResource(core.description),
                        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp),
                    )
                    // Núcleo com Vulkan e aparelho que o sustenta: dá para voltar ao renderizador comum se o resultado não agradar.
                    if (core.vulkan && device?.vulkan == true) {
                        val vulkanOff by remember(core.id) { app.settings.vulkanDisabled(core.id) }.collectAsStateWithLifecycle(false)
                        Spacer(Modifier.height(10.dp))
                        ChipStrip(contentPadding = PaddingValues(horizontal = 14.dp)) {
                            // O que foi medido vale para o renderizador de antes: o novo é medido de novo na próxima abertura.
                            SelectChip(stringResource(R.string.system_vulkan_auto), !vulkanOff, onClick = { scope.launch { app.settings.setVulkanDisabled(core.id, false); app.settings.clearTuning(system.id, all.map { it.id }) } })
                            SelectChip(stringResource(R.string.system_vulkan_off), vulkanOff, onClick = { scope.launch { app.settings.setVulkanDisabled(core.id, true); app.settings.clearTuning(system.id, all.map { it.id }) } })
                        }
                        // Desligado pelas quedas neste aparelho (VulkanHealth): o usuário pode querer tentar de novo.
                        var failedHere by remember(core.id) { mutableStateOf(false) }
                        LaunchedEffect(core.id) { failedHere = app.vulkanHealth.isOff(core.id, app.vulkanKey(core.id)) }
                        if (failedHere && !vulkanOff) {
                            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.system_vulkan_failed), style = MaterialTheme.typography.labelSmall, color = Palette.Coral, modifier = Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = {
                                    app.vulkanHealth.forget(core.id)
                                    failedHere = false
                                    scope.launch { app.settings.clearTuning(system.id, all.map { it.id }) }
                                }) {
                                    Text(stringResource(R.string.system_vulkan_retry), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    bench?.let { b ->
                        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (b.skipped) stringResource(R.string.bench_system_skipped)
                                else stringResource(
                                    R.string.bench_system_result, system.core(b.chosen).displayName,
                                    ((b.speedOf(b.chosen) ?: 0f) * 100).toInt(),
                                ) + if (selectedCore?.isNotEmpty() == true) " " + stringResource(R.string.bench_system_overridden) else "",
                                style = MaterialTheme.typography.labelSmall, color = Palette.Cyan, modifier = Modifier.weight(1f),
                            )
                            androidx.compose.material3.TextButton(onClick = { scope.launch { app.settings.setBenchmark(system.id, null); app.settings.clearTuning(system.id, all.map { it.id }) } }) {
                                Text(stringResource(R.string.bench_again), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    if (core.presets.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Palette.Outline.copy(alpha = 0.5f))
                        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Rounded.Speed, Palette.Sun, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(R.string.system_preset), style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(10.dp))
                        ChipStrip(contentPadding = PaddingValues(horizontal = 14.dp)) {
                            SelectChip(stringResource(R.string.tune_auto), presetLoaded != null && presetChoice == null, onClick = { scope.launch { app.settings.setPresetChoice(system.id, null) } })
                            // Só os níveis que este núcleo declara: os outros não mudariam nada nele.
                            Tuning.ladder(core).forEach { p ->
                                SelectChip(stringResource(p.label), p == presetChoice, onClick = { scope.launch { app.settings.setPresetChoice(system.id, p) } })
                            }
                        }
                        effective?.let { e ->
                            Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tuneSourceText(e), style = MaterialTheme.typography.labelSmall, color = Palette.Cyan, modifier = Modifier.weight(1f))
                                if (tuned != null) {
                                    androidx.compose.material3.TextButton(onClick = { scope.launch { app.settings.clearTuning(system.id, all.map { it.id }) } }) {
                                        Text(stringResource(R.string.tune_reset), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.system_formats, system.extensions.sorted().joinToString { ".$it" }),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                )
                if (repeated > 0) {
                    SurfaceCard(Modifier.fillMaxWidth().padding(bottom = 14.dp), onClick = onOpenVersions) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Rounded.Layers, Palette.Cyan, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(countString(R.plurals.system_versions_title, repeated), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.system_versions_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                            }
                            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextSecondary)
                        }
                    }
                }
                if (all.isNotEmpty()) {
                    SectionHeader(stringResource(R.string.system_games), inset = 4.dp)
                    Spacer(Modifier.height(12.dp))
                    // Só vale a pena buscar quando a lista não cabe numa olhada. Com uma busca ativa o campo fica,
                    // mesmo que a lista encolha: senão o filtro continuaria valendo sem ter como apagá-lo.
                    if (all.size > 6 || query.isNotEmpty()) {
                        SearchField(query, { query = it }, stringResource(R.string.system_search, system.shortName), onSearch = { focus.clearFocus() })
                        Spacer(Modifier.height(10.dp))
                    }
                    // Corre até a borda da tela, em vez de cortar os chips na margem da grade.
                    ChipStrip(Modifier.bleed(16.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        GameSort.entries.forEach { sort ->
                            SelectChip(stringResource(sort.label), sort == settings.gameSort, onClick = { scope.launch { app.settings.setGameSort(sort) } })
                        }
                    }
                }
            }
        }

        // Room ainda respondendo: capas-esqueleto em vez de um espaço vazio.
        if (loaded == null) {
            items(6, key = { "skeleton-$it" }, contentType = { "skeleton" }) { GameCardSkeleton() }
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

/** Valor de preferência já lido (mesmo que nulo); o estado nulo fora dele é "ainda carregando". */
private data class Loaded<T>(val value: T)
