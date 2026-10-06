package com.retrovika.app.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.update.AppRelease
import com.retrovika.app.core.update.UpdateState
import com.retrovika.app.ui.theme.Palette

/** Release de [state] que tem algo a mostrar no cartão, ou nulo (sem novidade, verificando, erro de consulta). */
fun UpdateState.cardRelease(): AppRelease? = when (this) {
    is UpdateState.Available -> release
    is UpdateState.Downloading -> release
    is UpdateState.Downloaded -> release
    is UpdateState.Installing -> release
    is UpdateState.Failed -> release
    else -> null
}

/**
 * Aviso de versão nova com as notas da release e o andamento: baixar, instalar ou tentar de novo.
 * [onDismiss] mostra o botão "Depois" (a tela inicial usa; os ajustes, não).
 */
@Composable
fun UpdateCard(state: UpdateState, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val release = state.cardRelease() ?: return
    val context = LocalContext.current
    val updater = context.container.updater
    var expanded by remember(release.version) { mutableStateOf(false) }
    SurfaceCard(
        modifier.fillMaxWidth(),
        brush = Palette.HeroGradient,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Rounded.SystemUpdate, Palette.Neon, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Kicker(stringResource(R.string.update_kicker))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.update_available_title, release.version), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.update_current, updater.currentVersion), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                }
            }
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                // Toque para ler as notas inteiras.
                Text(
                    release.notes,
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded },
                )
            }
            Spacer(Modifier.height(14.dp))
            when (state) {
                is UpdateState.Downloading -> {
                    Text(stringResource(R.string.update_downloading, release.version), style = MaterialTheme.typography.labelMedium, color = Palette.Cyan)
                    Spacer(Modifier.height(8.dp))
                    val bar = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                    if (state.progress >= 0f) LinearProgressIndicator(progress = { state.progress }, modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                    else LinearProgressIndicator(modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                }
                is UpdateState.Installing -> Text(stringResource(R.string.update_installing), style = MaterialTheme.typography.labelMedium, color = Palette.Cyan)
                is UpdateState.Downloaded -> {
                    Text(stringResource(R.string.update_install_hint), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    GradientButton(stringResource(R.string.update_install), { updater.install(release) }, icon = Icons.Rounded.InstallMobile)
                }
                is UpdateState.Failed -> {
                    Text(stringResource(R.string.update_failed), style = MaterialTheme.typography.titleSmall, color = Palette.Coral)
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.retry) GradientButton(stringResource(R.string.update_retry), { updater.download(release) }, icon = Icons.Rounded.Refresh)
                        GhostButton(stringResource(R.string.update_open_page), {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl))) }
                        }, icon = Icons.AutoMirrored.Rounded.OpenInNew)
                    }
                }
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val label = if (release.apkSize > 0) stringResource(R.string.update_action, release.apkSize.formatBytes())
                    else stringResource(R.string.update_install)
                    GradientButton(label, { updater.download(release) }, icon = Icons.Rounded.Download)
                    if (onDismiss != null) GhostButton(stringResource(R.string.update_later), onDismiss)
                }
            }
        }
    }
}
