package com.retrovika.app.ui.screens.settings

import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.container
import com.retrovika.app.core.cores.CoreState
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.ui.components.Badge
import com.retrovika.app.ui.components.IconTile
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.ambientGlow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.ui.draw.clip
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun CoresScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cores = context.container.cores
    val scope = rememberCoroutineScope()
    val states by cores.states.collectAsStateWithLifecycle()

    // Um núcleo pode servir vários consoles (ex.: Genesis Plus GX).
    val all = Systems.all.flatMap { s -> s.cores.map { it to s } }
        .groupBy({ it.first.id }, { it.second })
        .map { (id, systems) -> Triple(id, Systems.all.flatMap { it.cores }.first { it.id == id }, systems) }

    val installed = all.count { states[it.first] is CoreState.Installed }
    LazyColumn(Modifier.fillMaxSize().ambientGlow(primary = Palette.Cyan, secondary = Palette.Violet), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ScreenHeader(stringResource(R.string.cores_title), subtitle = stringResource(R.string.cores_subtitle, installed, all.size, cores.abi), onBack = onBack)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.cores_intro),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(10.dp))
        }
        items(all, key = { it.first }) { (id, core, systems) ->
            val state = states[id] ?: CoreState.NotInstalled
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.dp, if (state is CoreState.Installed) Palette.Success.copy(alpha = 0.3f) else Palette.Outline.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconTile(Icons.Rounded.Memory, if (state is CoreState.Installed) Palette.Success else Palette.Cyan, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(core.displayName, style = MaterialTheme.typography.titleMedium)
                        if (core.experimental) { Spacer(Modifier.width(8.dp)); Badge("BETA", Palette.Sun) }
                    }
                    Text(systems.joinToString(" · ") { it.shortName }, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                    val status = when (state) {
                        is CoreState.Installed -> if (state.bundled) stringResource(R.string.cores_bundled) else stringResource(R.string.cores_installed, state.size.formatBytes())
                        is CoreState.Downloading -> stringResource(R.string.cores_downloading, (state.progress * 100).toInt())
                        is CoreState.Failed -> stringResource(R.string.cores_error, state.message)
                        CoreState.NotInstalled -> stringResource(R.string.cores_not_installed)
                    }
                    Text(status, style = MaterialTheme.typography.labelSmall, color = when (state) {
                        is CoreState.Installed -> Palette.Success
                        is CoreState.Failed -> Palette.Coral
                        is CoreState.Downloading -> Palette.Cyan
                        else -> Palette.TextMuted
                    })
                }
                when (state) {
                    is CoreState.Downloading -> CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 3.dp, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
                    is CoreState.Installed -> if (!state.bundled) IconButton(onClick = { cores.uninstall(id) }) { Icon(Icons.Rounded.Delete, stringResource(R.string.common_remove), tint = Palette.TextMuted) }
                    else -> IconButton(
                        onClick = { scope.launch { runCatching { cores.install(core) } } },
                        modifier = Modifier.background(Palette.Cyan.copy(alpha = 0.14f), CircleShape),
                    ) { Icon(Icons.Rounded.Download, stringResource(R.string.common_download), tint = Palette.Cyan) }
                }
            }
        }
    }
}
