package com.retrovika.app.ui.screens.details

import com.retrovika.app.ui.components.regionLabel
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.ChipStrip
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.SectionHeader
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.ambientGlow
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
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
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun GameDetailsScreen(gameId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val game by remember(gameId) { app.library.observe(gameId) }.collectAsStateWithLifecycle(null)
    var confirmDelete by remember { mutableStateOf(false) }
    var identifying by remember(gameId) { mutableStateOf(false) }
    var identification by remember(gameId) { mutableStateOf<DatRepository.Identification?>(null) }
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
                RoundAction(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onClick = onBack)
                Spacer(Modifier.weight(1f))
                RoundAction(
                    if (g.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, stringResource(R.string.common_favorite),
                    tint = if (g.favorite) Palette.Neon else Color.White,
                ) { scope.launch { app.library.toggleFavorite(g.id) } }
                RoundAction(Icons.Rounded.Delete, stringResource(R.string.common_remove)) { confirmDelete = true }
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
                val preferred by remember(system.id) { app.settings.coreFor(system.id) }.collectAsStateWithLifecycle(null)
                CoreNotice(system.core(g.coreOverride ?: preferred))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                g.region?.let { Pill(regionLabel(it), icon = Icons.Rounded.Public) }
                Pill(g.size.formatBytes(), icon = Icons.Rounded.Storage)
                Pill(g.fileName.substringAfterLast('.').uppercase(), icon = Icons.AutoMirrored.Rounded.InsertDriveFile)
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
                    identifying -> Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.details_identifying), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                    else -> when (val id = identification) {
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
                                    scope.launch {
                                        identifying = true
                                        val result = runCatching { app.dat.identify(g) }.getOrNull()
                                        identifying = false
                                        identification = result
                                        if (result is DatRepository.Identification.Found) {
                                            app.library.setIdentified(g.id, result.match.name, result.match.region)
                                        }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 20.dp),
                            )
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
                    scope.launch { app.library.delete(g, deleteFile = !g.isContentUri); onBack() }
                }) { Text(stringResource(R.string.common_remove), color = Palette.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

/** Botão redondo translúcido usado sobre o fundo desfocado. */
@Composable
private fun RoundAction(icon: ImageVector, description: String, tint: Color = Color.White, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.background(Palette.Ink.copy(alpha = 0.45f), CircleShape).border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
    ) { Icon(icon, description, tint = tint) }
}

/** Avisa quando o núcleo que vai rodar o jogo ainda não está instalado, com opção de instalar já. */
@Composable
private fun CoreNotice(core: CoreInfo) {
    val app = LocalContext.current.container
    val states by app.cores.states.collectAsStateWithLifecycle()
    val state = states[core.id] ?: CoreState.NotInstalled
    // needsInstall confere o .so e os pacotes de sistema no disco: refeito só quando o tipo de estado
    // muda (instalado, falhou…), não a cada aviso de progresso do download.
    val missing = remember(state::class, core.id) { app.cores.needsInstall(core) }
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
