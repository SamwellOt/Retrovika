package com.retrovika.app.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import com.retrovika.app.ui.components.ReadableWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.library.Versions
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.settings.localized
import com.retrovika.app.core.settings.uiLanguage
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.ui.components.Badge
import com.retrovika.app.ui.components.EmptyState
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.IconTile
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.SurfaceCard
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.components.countString
import com.retrovika.app.ui.screens.home.formatPlayTime
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Verificação em lote pelo DAT: sobrevive à rotação e segue no escopo do app se a tela fechar. */
class VersionsCheck : ViewModel() {
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
    var message by mutableStateOf<String?>(null)
    var error by mutableStateOf(false)
}

/**
 * Jogos com mais de uma versão no console: agrupados pelo título, com a recomendada no topo (região do
 * idioma do app, tradução para ele, verificação por hash, dump bom, revisão mais nova) e o motivo de cada
 * nota. Dá para remover as outras uma a uma ou todas de uma vez.
 */
@Composable
fun VersionsScreen(systemId: String, onBack: () -> Unit, onOpenGame: (Long) -> Unit) {
    val system = Systems.byId(systemId) ?: return
    val context = LocalContext.current
    val app = context.container
    val games by remember(systemId) { app.library.bySystem(systemId) }.collectAsStateWithLifecycle(null)
    val language = remember { context.uiLanguage() }
    val groups by produceState<List<Versions.Group>?>(null, games, language) {
        val list = games ?: return@produceState
        value = withContext(Dispatchers.Default) { Versions.groups(list, language) }
    }
    val check: VersionsCheck = viewModel { VersionsCheck() }
    // Só os ids, guardados: girar a tela com a confirmação aberta não a fecha. Os jogos vêm da lista atual.
    var confirmIds by rememberSaveable { mutableStateOf<LongArray?>(null) }
    val confirm = confirmIds?.let { ids -> games?.let { list -> ids.toList().mapNotNull { id -> list.firstOrNull { it.id == id } } } }

    ReadableWidth { side ->
    LazyColumn(
        Modifier.fillMaxSize().ambientGlow(primary = system.accentColor(), secondary = Palette.Cyan, height = 420.dp),
        contentPadding = PaddingValues(start = side, end = side, bottom = 32.dp + LocalBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            ScreenHeader(
                stringResource(R.string.versions_title),
                kicker = system.name,
                subtitle = groups?.let { countString(R.plurals.versions_groups, it.size) },
                onBack = onBack,
            )
        }
        item {
            Text(
                stringResource(R.string.versions_intro),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (app.dat.supports(systemId)) item {
            val unverified = games.orEmpty().count { !it.verified }
            SurfaceCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconTile(Icons.Rounded.Verified, Palette.Cyan, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.versions_verify_title), style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (unverified == 0) stringResource(R.string.versions_verify_all_done)
                                else countString(R.plurals.versions_verify_pending, unverified),
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                            )
                        }
                    }
                    val progress = check.progress
                    if (progress != null) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { if (progress.second == 0) 0f else progress.first / progress.second.toFloat() },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)),
                            color = Palette.Cyan, trackColor = Palette.SurfaceHighest,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.versions_verify_progress, progress.first, progress.second), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                    } else if (unverified > 0) {
                        Spacer(Modifier.height(12.dp))
                        GhostButton(
                            stringResource(R.string.versions_verify_button), tint = Palette.Cyan, icon = Icons.Rounded.Verified,
                            onClick = {
                                val list = games.orEmpty()
                                check.message = null
                                check.progress = 0 to 0
                                // No escopo do app: calcular o hash de dezenas de ROMs leva tempo, e sair da tela não cancela.
                                // Fora da corrotina: capturar a Activity a manteria viva (rotação) até o fim.
                                val res = context.localized()
                                app.scope.launch(Dispatchers.Main) {
                                    try {
                                        val found = app.dat.identifyAll(list) { done, total -> check.progress = done to total }
                                        app.library.setIdentifiedAll(found.map { (game, entry) -> Triple(game.id, entry.name, entry.region) })
                                        check.error = false
                                        // Mesmo truque do countString: em português o 0 cai em "one" ("0 jogo")
                                        check.message = res.resources.getQuantityString(R.plurals.versions_verify_result, if (found.isEmpty()) 2 else found.size, found.size)
                                    } catch (c: CancellationException) {
                                        throw c
                                    } catch (t: Throwable) {
                                        check.error = true
                                        check.message = t.userMessage(res)
                                    } finally {
                                        check.progress = null
                                    }
                                }
                            },
                        )
                    }
                    check.message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = if (check.error) Palette.Coral else Palette.Success)
                    }
                }
            }
        }
        if (groups?.isEmpty() == true) item {
            EmptyState(stringResource(R.string.versions_empty_title), stringResource(R.string.versions_empty_message), icon = Icons.Rounded.Layers)
        }
        items(groups.orEmpty(), key = { it.best.game.id }) { group ->
            SurfaceCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                Column(Modifier.padding(vertical = 14.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(group.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Badge(countString(R.plurals.versions_count, group.all.size), Palette.Cyan)
                    }
                    Spacer(Modifier.height(8.dp))
                    group.all.forEach { rated ->
                        VersionRow(rated, recommended = rated === group.best, onOpen = { onOpenGame(rated.game.id) }, onRemove = { confirmIds = longArrayOf(rated.game.id) })
                    }
                    Spacer(Modifier.height(6.dp))
                    GhostButton(
                        stringResource(R.string.versions_keep_best), { confirmIds = group.others.map { it.game.id }.toLongArray() },
                        icon = Icons.Rounded.CleaningServices, tint = Palette.Neon,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
    }

    // Lista vazia: os jogos já saíram da biblioteca (outra tela, varredura) e não há o que confirmar.
    confirm?.takeIf { it.isNotEmpty() }?.let { toRemove ->
        AlertDialog(
            onDismissRequest = { confirmIds = null },
            title = { Text(countString(R.plurals.versions_remove_title, toRemove.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    toRemove.forEach { Text("• ${it.fileName}", style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.versions_remove_message), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmIds = null
                    // No escopo do app, como a verificação: sair da tela no meio não pode interromper a remoção
                    // entre apagar o arquivo e apagar o registro no banco.
                    val res = context.localized()
                    app.scope.launch(Dispatchers.Main) {
                        var failure: String? = null
                        toRemove.forEach { game ->
                            try {
                                // Falso: o arquivo não pôde ser apagado e o jogo continua na biblioteca.
                                if (!app.library.delete(game, deleteFile = !game.isContentUri)) {
                                    failure = res.getString(R.string.details_remove_failed, game.fileName)
                                }
                            } catch (c: CancellationException) {
                                throw c
                            } catch (t: Throwable) {
                                failure = t.userMessage(res)
                            }
                        }
                        failure?.let {
                            check.error = true
                            check.message = it
                        }
                    }
                }) { Text(stringResource(R.string.common_remove), color = Palette.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmIds = null }) { Text(stringResource(R.string.common_cancel)) } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

@Composable
private fun VersionRow(rated: Versions.Rated, recommended: Boolean, onOpen: () -> Unit, onRemove: (() -> Unit)?) {
    val game = rated.game
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (recommended) {
                Icon(Icons.Rounded.Star, null, tint = Palette.Sun, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                game.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (recommended) Palette.TextPrimary else Palette.TextSecondary, modifier = Modifier.weight(1f),
            )
            if (!recommended && onRemove != null) {
                // Alvo de 48dp: o texto sozinho tinha uns 28dp de altura, dentro de uma linha que também é tocável.
                Text(
                    stringResource(R.string.common_remove), style = MaterialTheme.typography.labelMedium, color = Palette.Coral,
                    modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onRemove).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (recommended) Badge(stringResource(R.string.versions_recommended), Palette.Sun)
            rated.tags.forEach { tag -> Badge(stringResource(tag.label()), tag.color()) }
            Badge(game.size.formatBytes(), Palette.TextSecondary)
            if (game.playTimeSeconds >= 60) Badge(formatPlayTime(game.playTimeSeconds), Palette.Violet)
        }
    }
}

fun Versions.Tag.label(): Int = when (this) {
    Versions.Tag.VERIFIED -> R.string.versions_tag_verified
    Versions.Tag.GOOD_DUMP -> R.string.versions_tag_good_dump
    Versions.Tag.BAD_DUMP -> R.string.versions_tag_bad_dump
    Versions.Tag.HACK -> R.string.versions_tag_hack
    Versions.Tag.TRAINER -> R.string.versions_tag_trainer
    Versions.Tag.OVERDUMP -> R.string.versions_tag_overdump
    Versions.Tag.PIRATE -> R.string.versions_tag_pirate
    Versions.Tag.PRERELEASE -> R.string.versions_tag_prerelease
    Versions.Tag.UNLICENSED -> R.string.versions_tag_unlicensed
    Versions.Tag.TRANSLATION -> R.string.versions_tag_translation
    Versions.Tag.REVISION -> R.string.versions_tag_revision
}

private fun Versions.Tag.color(): Color = when (this) {
    Versions.Tag.VERIFIED, Versions.Tag.GOOD_DUMP -> Palette.Success
    Versions.Tag.TRANSLATION, Versions.Tag.REVISION -> Palette.Cyan
    Versions.Tag.UNLICENSED -> Palette.TextSecondary
    else -> Palette.Coral
}
