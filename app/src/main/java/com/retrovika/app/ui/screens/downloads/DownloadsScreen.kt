package com.retrovika.app.ui.screens.downloads

import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.container
import com.retrovika.app.core.catalog.DownloadManager
import com.retrovika.app.core.catalog.DownloadStatus
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.EmptyState
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.ambientGlow
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Download
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.retrovika.app.ui.theme.Palette

@Composable
fun DownloadsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val manager = context.container.downloads
    val tasks by manager.tasks.collectAsStateWithLifecycle()
    var showLinkDialog by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().ambientGlow(primary = Palette.Cyan), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ScreenHeader(
                stringResource(R.string.downloads_title),
                subtitle = tasks.count { it.status in DownloadManager.ACTIVE }.let {
                    if (it == 0) stringResource(R.string.downloads_none_active) else stringResource(R.string.downloads_active, it)
                },
                onBack = onBack,
            ) { if (tasks.isNotEmpty()) TextButton(onClick = manager::clearFinished) { Text(stringResource(R.string.common_clear), color = Palette.Neon) } }
            GhostButton(
                stringResource(R.string.downloads_from_link), { showLinkDialog = true },
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp), icon = Icons.Rounded.Link, tint = Palette.Cyan,
            )
        }
        if (tasks.isEmpty()) item { EmptyState(stringResource(R.string.downloads_empty_title), stringResource(R.string.downloads_empty_message), icon = Icons.Rounded.Download) }
        items(tasks, key = { it.id }) { task ->
            val system = Systems.byId(task.systemId)
            Row(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.dp, Palette.Outline.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameCover(task.title, system, task.coverUrl, Modifier.size(60.dp), corner = 14.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val label = when (task.status) {
                        DownloadStatus.QUEUED -> stringResource(R.string.downloads_queued)
                        // Progresso -1 = servidor não informou o tamanho.
                        DownloadStatus.DOWNLOADING -> if (task.progress < 0f) stringResource(R.string.downloads_downloading) else stringResource(R.string.downloads_downloading_percent, (task.progress * 100).toInt())
                        DownloadStatus.EXTRACTING -> stringResource(R.string.downloads_extracting)
                        DownloadStatus.DONE -> stringResource(R.string.downloads_done)
                        DownloadStatus.FAILED -> stringResource(R.string.downloads_failed, task.error.orEmpty())
                        DownloadStatus.CANCELED -> stringResource(R.string.downloads_canceled)
                    }
                    Text(
                        label, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                        color = when (task.status) {
                            DownloadStatus.FAILED -> Palette.Coral
                            DownloadStatus.DONE -> Palette.Success
                            else -> Palette.TextSecondary
                        },
                    )
                    if (task.status in DownloadManager.ACTIVE) {
                        Spacer(Modifier.height(6.dp))
                        val bar = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                        if (task.progress > 0f) LinearProgressIndicator(progress = { task.progress }, modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                        else LinearProgressIndicator(modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                    }
                }
                when {
                    task.status in DownloadManager.ACTIVE -> IconButton(onClick = { manager.cancel(task.id) }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_cancel)) }
                    task.status == DownloadStatus.DONE && task.gameId != null ->
                        IconButton(
                            onClick = { GameActivity.launch(context, task.gameId) },
                            modifier = Modifier.clip(CircleShape).background(Palette.SunsetGradient),
                        ) { Icon(Icons.Rounded.PlayArrow, stringResource(R.string.common_play), tint = Color(0xFF1C0010)) }
                }
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
