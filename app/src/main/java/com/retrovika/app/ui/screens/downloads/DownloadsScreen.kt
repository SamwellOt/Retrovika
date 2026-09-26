package com.retrovika.app.ui.screens.downloads

import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.container
import com.retrovika.app.core.catalog.DownloadManager
import com.retrovika.app.core.catalog.DownloadStatus
import com.retrovika.app.core.catalog.DownloadTask
import com.retrovika.app.core.settings.SettingsRepository
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.DownloadProgressBar
import com.retrovika.app.ui.components.EmptyState
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.HeaderIconButton
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.ScrollToTopOnReselect
import com.retrovika.app.ui.components.SectionHeader
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.SurfaceCard
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.launch

/**
 * Aba de downloads: o que está baixando (com velocidade e tempo restante), o que terminou e o que
 * falhou, com ações para cada um. O limite de downloads simultâneos pode ser trocado aqui mesmo.
 */
@Composable
fun DownloadsScreen(onOpenGame: (Long) -> Unit, onExplore: () -> Unit, onOpenBrowser: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val manager = app.downloads
    val tasks by manager.tasks.collectAsStateWithLifecycle()
    val settings by app.settings.cached.collectAsStateWithLifecycle()
    var showLinkDialog by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    ScrollToTopOnReselect("downloads", listState)

    // A fila mostra primeiro quem já está baixando; entre os que esperam, o mais antigo sai antes.
    val active = remember(tasks) {
        tasks.filter { it.status in DownloadManager.ACTIVE }
            .sortedWith(compareBy<DownloadTask> { it.status == DownloadStatus.QUEUED }.thenBy { it.createdAt })
    }
    val done = remember(tasks) { tasks.filter { it.status == DownloadStatus.DONE } }
    val problems = remember(tasks) { tasks.filter { it.status in DownloadManager.RETRYABLE } }
    val speed = active.sumOf { it.speed }

    LazyColumn(
        Modifier.fillMaxSize().ambientGlow(primary = Palette.Cyan),
        state = listState,
        contentPadding = PaddingValues(bottom = 32.dp + LocalBottomInset.current),
    ) {
        item(key = "header") {
            ScreenHeader(
                stringResource(R.string.downloads_title),
                subtitle = when {
                    active.isEmpty() -> stringResource(R.string.downloads_none_active)
                    speed > 0 -> stringResource(R.string.downloads_active, active.size) + " · " + stringResource(R.string.downloads_speed, speed.formatBytes())
                    else -> stringResource(R.string.downloads_active, active.size)
                },
            ) {
                HeaderIconButton(Icons.Rounded.Link, stringResource(R.string.downloads_from_link), { showLinkDialog = true }, tint = Palette.Cyan)
                HeaderIconButton(Icons.Rounded.Language, stringResource(R.string.explore_open_site), onOpenBrowser)
            }
        }

        item(key = "parallel") { ParallelPicker(settings.maxDownloads) { n -> app.scope.launch { app.settings.setMaxDownloads(n) } } }

        if (tasks.isEmpty()) {
            item(key = "empty") {
                EmptyState(stringResource(R.string.downloads_empty_title), stringResource(R.string.downloads_empty_message), icon = Icons.Rounded.Download) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        GradientButton(stringResource(R.string.common_explore), onExplore, icon = Icons.Rounded.Explore)
                        GhostButton(stringResource(R.string.downloads_from_link), { showLinkDialog = true }, icon = Icons.Rounded.Link, tint = Palette.Cyan)
                    }
                }
            }
        }

        section(
            "active", R.string.downloads_section_active, active,
            action = if (active.size > 1) R.string.downloads_cancel_all else null, onAction = manager::cancelAll,
        ) { task ->
            DownloadRow(task, onClick = null) {
                IconButton(onClick = { manager.cancel(task.id) }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_cancel), tint = Palette.TextSecondary) }
            }
        }

        section("problems", R.string.downloads_section_failed, problems) { task ->
            DownloadRow(task, onClick = null) {
                IconButton(
                    onClick = { manager.retry(task.id) },
                    modifier = Modifier.background(Palette.Coral.copy(alpha = 0.14f), CircleShape),
                ) { Icon(Icons.Rounded.Refresh, stringResource(R.string.common_retry), tint = Palette.Coral) }
                IconButton(onClick = { manager.remove(task.id) }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_remove), tint = Palette.TextMuted) }
            }
        }

        section(
            "done", R.string.downloads_section_done, done,
            action = R.string.common_clear, onAction = manager::clearCompleted,
        ) { task ->
            DownloadRow(task, onClick = task.gameId?.let { id -> { onOpenGame(id) } }) {
                task.gameId?.let { id ->
                    IconButton(
                        onClick = { GameActivity.launch(context, id) },
                        modifier = Modifier.clip(CircleShape).background(Palette.SunsetGradient),
                    ) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.common_play), tint = Color(0xFF1C0010)) }
                }
                IconButton(onClick = { manager.remove(task.id) }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_remove), tint = Palette.TextMuted) }
            }
        }
    }

    if (showLinkDialog) {
        LinkDialog(
            onDismiss = { showLinkDialog = false },
            onConfirm = { url, system -> manager.enqueueUrl(url, system); showLinkDialog = false },
        )
    }
}

/** Uma seção da lista (título + tarefas); some quando não há tarefas. */
private fun LazyListScope.section(
    key: String,
    title: Int,
    tasks: List<DownloadTask>,
    action: Int? = null,
    onAction: () -> Unit = {},
    row: @Composable (DownloadTask) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "section-$key") {
        Spacer(Modifier.height(18.dp))
        SectionHeader(
            stringResource(title) + " · ${tasks.size}",
            action = action?.let { stringResource(it) },
            onAction = if (action != null) onAction else null,
        )
        Spacer(Modifier.height(6.dp))
    }
    items(tasks, key = { it.id }, contentType = { "task" }) { row(it) }
}

/** Limite de downloads simultâneos, direto na tela de downloads. */
@Composable
private fun ParallelPicker(selected: Int, onSelect: (Int) -> Unit) {
    SurfaceCard(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp).fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(stringResource(R.string.downloads_parallel), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.downloads_parallel_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..SettingsRepository.MAX_PARALLEL_DOWNLOADS).forEach { n -> SelectChip("$n", n == selected, onClick = { onSelect(n) }) }
            }
        }
    }
}

@Composable
private fun DownloadRow(task: DownloadTask, onClick: (() -> Unit)?, actions: @Composable () -> Unit) {
    val system = Systems.byId(task.systemId)
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline.copy(alpha = 0.7f), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GameCover(task.title, system, task.coverUrl, Modifier.size(56.dp), corner = 14.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            system?.let { Text(it.name, style = MaterialTheme.typography.labelSmall, color = it.readableAccent(), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(
                statusLine(task), style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = when (task.status) {
                    DownloadStatus.FAILED -> Palette.Coral
                    DownloadStatus.DONE -> Palette.Success
                    else -> Palette.TextSecondary
                },
            )
            if (task.status in DownloadManager.ACTIVE) {
                Spacer(Modifier.height(6.dp))
                DownloadProgressBar(task)
            }
        }
        Spacer(Modifier.width(4.dp))
        actions()
    }
}

/** "12 MB de 40 MB · 1,2 MB/s · faltam 30 s", ou o estado da tarefa. */
@Composable
private fun statusLine(task: DownloadTask): String = when (task.status) {
    DownloadStatus.QUEUED -> stringResource(R.string.downloads_queued)
    DownloadStatus.DOWNLOADING -> {
        val amount = when {
            task.bytesTotal > 0 -> stringResource(R.string.downloads_progress, task.bytesDone.formatBytes(), task.bytesTotal.formatBytes())
            task.bytesDone > 0 -> task.bytesDone.formatBytes()
            else -> stringResource(R.string.downloads_downloading)
        }
        val speed = if (task.speed > 0) stringResource(R.string.downloads_speed, task.speed.formatBytes()) else null
        val eta = task.secondsLeft?.let { stringResource(R.string.downloads_eta, formatDuration(it)) }
        listOfNotNull(amount, speed, eta).joinToString(" · ")
    }
    DownloadStatus.EXTRACTING -> stringResource(R.string.downloads_extracting)
    DownloadStatus.DONE -> if (task.bytesTotal > 0) stringResource(R.string.downloads_done) + " · " + task.bytesTotal.formatBytes() else stringResource(R.string.downloads_done)
    DownloadStatus.FAILED -> stringResource(R.string.downloads_failed, task.error.orEmpty())
    DownloadStatus.CANCELED -> stringResource(R.string.downloads_canceled)
}

/** Duração curta: "45 s", "3 min", "1 h 20 min". Arredonda para o minuto antes de escolher a unidade: 59 min 50 s é "1 h 0 min". */
@Composable
private fun formatDuration(seconds: Long): String {
    if (seconds < 60) return stringResource(R.string.downloads_duration_seconds, seconds.toInt())
    val minutes = ((seconds + 30) / 60).toInt()
    return if (minutes < 60) stringResource(R.string.downloads_duration_minutes, minutes)
    else stringResource(R.string.downloads_duration_hours, minutes / 60, minutes % 60)
}

/** Baixa um backup do próprio usuário (ex.: link do Google Drive/Dropbox/servidor pessoal). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkDialog(onDismiss: () -> Unit, onConfirm: (String, GameSystem) -> Unit) {
    var url by remember { mutableStateOf("") }
    var system by remember { mutableStateOf<GameSystem?>(null) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.downloads_from_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.downloads_link_message),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                )
                OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text(stringResource(R.string.downloads_link_url)) }, singleLine = true)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = system?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.downloads_link_console)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Systems.all.forEach { s ->
                            DropdownMenuItem(text = { Text(s.name) }, onClick = { system = s; expanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { system?.let { onConfirm(url, it) } },
                enabled = system != null && (url.startsWith("https://") || url.startsWith("http://")),
            ) { Text(stringResource(R.string.common_download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        containerColor = Palette.SurfaceHigh,
    )
}
