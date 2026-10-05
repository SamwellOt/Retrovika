package com.retrovika.app.ui.screens.details

import com.retrovika.app.ui.components.regionLabel
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.retrovika.app.core.settings.localized
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.retrovika.app.container
import com.retrovika.app.core.dat.DatRepository
import com.retrovika.app.core.library.GameSource
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.tuning.DeviceProfile
import com.retrovika.app.core.tuning.Tuning
import com.retrovika.app.ui.components.tuneSourceText
import androidx.compose.material.icons.rounded.Speed
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.ChipStrip
import com.retrovika.app.ui.components.HeaderIconButton
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.SectionHeader
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.ambientGlow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Memory
import com.retrovika.app.core.cores.CoreState
import com.retrovika.app.core.systems.CoreInfo
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import com.retrovika.app.ui.components.Pill
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.screens.home.formatPlayTime
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.theme.Palette
import com.retrovika.app.core.library.Versions
import com.retrovika.app.core.settings.uiLanguage
import com.retrovika.app.ui.screens.library.label
import com.retrovika.app.ui.components.Badge
import androidx.compose.material.icons.rounded.Star
import com.retrovika.app.core.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Identificação pelo DAT de um jogo: roda no escopo do app e o resultado fica num ViewModel. Num
 * `remember` com o escopo da tela, girar a tela cancelava a busca (o hash de uma ROM grande demora) e
 * esquecia o resultado.
 */
class DetailsIdentify : ViewModel() {
    var running by mutableStateOf(false)
    var result by mutableStateOf<DatRepository.Identification?>(null)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun GameDetailsScreen(gameId: Long, onBack: () -> Unit, onOpenSystem: (String) -> Unit, onOpenGame: (Long) -> Unit, onOpenVersions: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val game by remember(gameId) { app.library.observe(gameId) }.collectAsStateWithLifecycle(null)
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val identify: DetailsIdentify = viewModel(key = "identify-$gameId") { DetailsIdentify() }
    // O jogo sumiu com a tela aberta (pasta desvinculada, removido numa varredura): volta em vez de
    // deixar uma tela vazia sem botão de voltar.
    var loaded by remember(gameId) { mutableStateOf(false) }
    LaunchedEffect(game) {
        if (game != null) loaded = true else if (loaded) onBack()
    }
    val g = game ?: return
    val system = Systems.byId(g.systemId)

    Box(Modifier.fillMaxSize()) {
        // Fundo: a própria capa, desfocada
        val accent = system?.accentColor() ?: Palette.Violet
        Box(Modifier.fillMaxWidth().height(420.dp).ambientGlow(primary = accent, secondary = Palette.Neon, height = 420.dp))
        g.coverUrl?.let {
            AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(380.dp).blur(40.dp).graphicsLayer { alpha = 0.7f })
        }
        Box(Modifier.fillMaxWidth().height(382.dp).background(Brush.verticalGradient(listOf(Color(0x330B0714), Color(0xB30B0714), Palette.Ink))))

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeaderIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onBack)
                Spacer(Modifier.weight(1f))
                HeaderIconButton(
                    if (g.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    // O leitor de tela diz o que o toque faz agora, não só "Favorito" nos dois estados.
                    stringResource(if (g.favorite) R.string.details_favorite_remove else R.string.details_favorite_add),
                    onClick = { scope.launch { app.library.toggleFavorite(g.id) } },
                    tint = if (g.favorite) Palette.Neon else Palette.TextPrimary,
                )
                HeaderIconButton(Icons.Rounded.Delete, stringResource(R.string.common_remove), { confirmDelete = true })
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
                GameCover(
                    g.title, system, g.coverUrl,
                    Modifier.width(140.dp).height(190.dp),
                    corner = 18.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Kicker(system?.shortName ?: stringResource(R.string.details_game), color = system?.readableAccent() ?: Palette.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Text(g.title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(g.developer ?: system?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    system?.let {
                        // Atalho para a coleção do console, sem voltar pela pilha.
                        Text(
                            stringResource(R.string.details_open_system, it.shortName),
                            style = MaterialTheme.typography.labelLarge, color = it.readableAccent(),
                            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onOpenSystem(it.id) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            GradientButton(
                stringResource(if (g.lastPlayed != null) R.string.common_continue else R.string.common_play),
                onClick = { GameActivity.launch(context, g.id) },
                modifier = Modifier.padding(horizontal = 20.dp).fillMaxWidth(),
                icon = Icons.Rounded.PlayArrow,
                height = 56.dp,
            )
            if (system != null) {
                // null = preferência ainda carregando: sem isso o aviso do núcleo padrão piscava na tela.
                val preferred by remember(system.id) { app.settings.effectiveCoreFor(system.id).map { it.orEmpty() } }.collectAsStateWithLifecycle(null)
                preferred?.let { CoreNotice(system.core(g.coreOverride ?: it)) }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                g.region?.let { Pill(regionLabel(it), icon = Icons.Rounded.Public) }
                Pill(g.size.formatBytes(), icon = Icons.Rounded.Storage)
                // Arquivo sem extensão: sem o filtro, a pílula mostrava o nome inteiro.
                g.fileName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let {
                    Pill(it.uppercase(), icon = Icons.AutoMirrored.Rounded.InsertDriveFile)
                }
                if (g.playTimeSeconds >= 60) Pill(formatPlayTime(g.playTimeSeconds), icon = Icons.Rounded.Schedule, color = Palette.Sun)
                Pill(
                    icon = Icons.Rounded.Folder,
                    text = 
                    when (g.source) {
                        GameSource.LOCAL -> stringResource(R.string.details_source_local)
                        GameSource.IMPORTED -> stringResource(R.string.details_source_imported)
                        GameSource.DOWNLOADED -> stringResource(R.string.details_source_downloaded)
                    },
                )
            }
            g.lastPlayed?.let {
                Text(
                    stringResource(R.string.details_last_session, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))),
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
            g.description?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }

            if (system != null && system.cores.size > 1) {
                Spacer(Modifier.height(24.dp))
                SectionHeader(stringResource(R.string.details_core_title))
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.details_core_subtitle),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(10.dp))
                ChipStrip {
                    SelectChip(stringResource(R.string.details_core_default), g.coreOverride == null, onClick = { scope.launch { app.library.setCoreOverride(g.id, null) } })
                    system.cores.forEach { c ->
                        SelectChip(c.displayName, g.coreOverride == c.id, onClick = { scope.launch { app.library.setCoreOverride(g.id, c.id) } })
                    }
                }
            }

            if (system != null) GameTuning(g, system)

            LibraryVersions(g, onOpenGame = onOpenGame, onOpenAll = { onOpenVersions(g.systemId) })

            if (app.dat.supports(g.systemId)) {
                Spacer(Modifier.height(24.dp))
                SectionHeader(stringResource(R.string.details_identify_title))
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.details_identify_subtitle),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Spacer(Modifier.height(10.dp))
                when {
                    identify.running -> Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.details_identifying), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                    else -> when (val id = identify.result) {
                        is DatRepository.Identification.Found -> {
                            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = Palette.Success)
                                Spacer(Modifier.width(6.dp))
                                Text(id.match.name, style = MaterialTheme.typography.bodyMedium)
                            }
                            if (id.versions.isNotEmpty()) {
                                Text(
                                    stringResource(R.string.details_other_versions, id.versions.size),
                                    style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                                id.versions.forEach { v ->
                                    Text(
                                        "• ${listOfNotNull(v.region?.let { r -> regionLabel(r) }, v.revision).joinToString(" · ").ifBlank { v.name }}",
                                        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 1.dp),
                                    )
                                }
                            }
                        }
                        is DatRepository.Identification.NotFound -> Text(
                            stringResource(R.string.details_not_found),
                            style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                        else -> {
                            if (g.verified && g.datName != null) {
                                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = Palette.Success)
                                    Spacer(Modifier.width(6.dp))
                                    Text(g.datName!!, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            GhostButton(
                                stringResource(if (g.verified) R.string.details_see_versions else R.string.details_identify_version),
                                icon = Icons.Rounded.Verified,
                                tint = Palette.Cyan,
                                onClick = {
                                    identify.running = true
                                    identify.error = null
                                    // Contexto localizado da aplicação, pego fora da corrotina: capturar a Activity a
                                    // manteria viva (rotação) até o hash terminar.
                                    val res = context.localized()
                                    val game = g
                                    // No escopo do app: girar a tela ou sair da página não interrompe a identificação.
                                    app.scope.launch(Dispatchers.Main) {
                                        // Sem internet na primeira vez (o DAT é baixado), o motivo aparece abaixo do botão.
                                        val result = try {
                                            app.dat.identify(game)
                                        } catch (c: CancellationException) {
                                            throw c
                                        } catch (t: Throwable) {
                                            identify.error = t.userMessage(res)
                                            null
                                        } finally {
                                            identify.running = false
                                        }
                                        identify.result = result
                                        if (result is DatRepository.Identification.Found) {
                                            // O escopo do app não tem tratador: uma falha do banco aqui derrubaria o processo.
                                            runCatching { app.library.setIdentified(game.id, result.match.name, result.match.region) }
                                        }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
                            identify.error?.let {
                                Text(
                                    it, style = MaterialTheme.typography.bodySmall, color = Palette.Coral,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(40.dp + LocalBottomInset.current))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.details_remove_title, g.title)) },
            text = {
                Text(
                    stringResource(if (g.isContentUri) R.string.details_remove_linked else R.string.details_remove_file),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    val failedText = context.localized().getString(R.string.details_remove_failed, g.title)
                    scope.launch {
                        // Falso: o arquivo da ROM não pôde ser apagado e o jogo continua na biblioteca; a tela fica.
                        if (app.library.delete(g, deleteFile = !g.isContentUri)) onBack()
                        else Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
                    }
                }) { Text(stringResource(R.string.common_remove), color = Palette.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

/** Outras versões deste jogo na biblioteca, com a recomendada marcada; tocar abre a página dela. */
@Composable
private fun LibraryVersions(game: Game, onOpenGame: (Long) -> Unit, onOpenAll: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val all by remember(game.systemId) { app.library.bySystem(game.systemId) }.collectAsStateWithLifecycle(emptyList())
    val language = remember { context.uiLanguage() }
    val computed by produceState<Versions.Group?>(null, all, game, language) {
        value = withContext(Dispatchers.Default) { Versions.groupOf(game, all, language) }
    }
    val group = computed ?: return
    Spacer(Modifier.height(24.dp))
    SectionHeader(stringResource(R.string.details_library_versions), action = stringResource(R.string.details_library_versions_all), onAction = onOpenAll)
    Spacer(Modifier.height(6.dp))
    group.all.forEach { rated ->
        val current = rated.game.id == game.id
        Row(
            Modifier.fillMaxWidth()
                .clickable(enabled = !current) { onOpenGame(rated.game.id) }
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (rated === group.best) Icons.Rounded.Star else Icons.AutoMirrored.Rounded.InsertDriveFile, null,
                tint = if (rated === group.best) Palette.Sun else Palette.TextMuted, modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    rated.game.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (current) Palette.TextPrimary else Palette.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) {
                    if (rated === group.best) Badge(stringResource(R.string.versions_recommended), Palette.Sun)
                    if (current) Badge(stringResource(R.string.details_library_versions_this), Palette.Neon)
                    rated.tags.take(2).forEach { Badge(stringResource(it.label()), Palette.Cyan) }
                }
            }
        }
    }
}

/** Avisa quando o núcleo que vai rodar o jogo ainda não está instalado, com opção de instalar já. */
@Composable
private fun CoreNotice(core: CoreInfo) {
    val app = LocalContext.current.container
    val states by app.cores.states.collectAsStateWithLifecycle()
    val state = states[core.id] ?: CoreState.NotInstalled
    // needsInstall confere o .so e os pacotes de sistema no disco: refeito só quando o tipo de estado
    // muda (instalado, falhou…), não a cada aviso de progresso do download. Fora da thread principal:
    // começa como "não falta" para o aviso não piscar enquanto o disco é lido.
    val missing by produceState(false, state::class, core.id) {
        value = withContext(Dispatchers.IO) { app.cores.needsInstall(core) }
    }
    if (!missing && state !is CoreState.Downloading) return
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .fillMaxWidth()
            .background(Palette.Sun.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .border(1.dp, Palette.Sun.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Memory, null, tint = Palette.Sun, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.details_core_missing, core.displayName), style = MaterialTheme.typography.titleSmall)
            Text(
                when (state) {
                    is CoreState.Downloading -> stringResource(R.string.details_core_installing, core.displayName, (state.progress * 100).toInt())
                    is CoreState.Failed -> stringResource(R.string.details_core_failed, core.displayName, state.message)
                    else -> stringResource(R.string.details_core_missing_message)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state is CoreState.Failed) Palette.Coral else Palette.TextSecondary,
            )
        }
        if (state !is CoreState.Downloading) {
            Spacer(Modifier.width(10.dp))
            // No escopo do app: a instalação continua mesmo se o usuário sair da tela.
            TextButton(onClick = { app.scope.launch { runCatching { app.cores.install(core) } } }) {
                Text(stringResource(R.string.details_core_install), color = Palette.Sun)
            }
        } else {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Sun)
        }
    }
}

/**
 * Nível de qualidade que vale para o jogo neste aparelho e de onde ele veio. "Otimizar" mede só este jogo
 * (uma cena pesada de um jogo não deve baixar a qualidade dos outros); "Seguir o console" apaga o ajuste dele.
 */
@Composable
private fun GameTuning(game: Game, system: GameSystem) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val coreId by remember(system.id) { app.settings.effectiveCoreFor(system.id) }.collectAsStateWithLifecycle(null)
    val core = system.core(game.coreOverride ?: coreId)
    if (core.presets.size < 2) return
    val device by produceState<DeviceProfile?>(null) { value = app.deviceProfile.await() }
    val choice by remember(system.id) { app.settings.presetChoice(system.id) }.collectAsStateWithLifecycle(null)
    val own by remember(game.id) { app.settings.gameTuning(game.id) }.collectAsStateWithLifecycle(null)
    val console by remember(system.id, core.id) { app.settings.tuning(system.id, core.id) }.collectAsStateWithLifecycle(null)
    val effective = device?.let { Tuning.effective(core, it, choice, own, console) }

    Spacer(Modifier.height(24.dp))
    SectionHeader(stringResource(R.string.tune_game_title))
    Spacer(Modifier.height(4.dp))
    Text(
        stringResource(R.string.tune_game_subtitle),
        style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp),
    )
    effective?.let {
        Text(
            tuneSourceText(it), style = MaterialTheme.typography.labelMedium, color = Palette.Cyan,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
    // FlowRow: em telas de 360dp os dois botões não cabem lado a lado e o segundo era cortado.
    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Núcleo com teto no Auto não tem teste (ver CoreInfo.autoMax): só o ajuste que o vigia gravou pode ser desfeito.
        if (Tuning.measurable(core)) {
            GhostButton(stringResource(R.string.tune_game_optimize), { GameActivity.launch(context, game.id, retune = true) }, icon = Icons.Rounded.Speed)
        }
        if (own != null) GhostButton(stringResource(R.string.tune_game_reset), { scope.launch { app.settings.setGameTuning(game.id, null) } })
    }
}
