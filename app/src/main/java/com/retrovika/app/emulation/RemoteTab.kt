package com.retrovika.app.emulation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.remote.QrCodes
import com.retrovika.app.remote.RemotePlay
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.SurfaceCard
import com.retrovika.app.ui.theme.MonoFamily
import com.retrovika.app.ui.theme.Palette

/** Cor de cada jogador, igual à do selo nas páginas do controle e da tela. */
private val PlayerColors = listOf(Palette.Neon, Palette.Cyan, Palette.Sun, Palette.Violet)

/** Aba "Tela e controles": liga o servidor da rede local, mostra o QR e quem está conectado. */
@Composable
internal fun RemoteTab() {
    val remote = LocalContext.current.container.remote
    val state by remote.state.collectAsState()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.remote_enable), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.remote_enable_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = state.running, onCheckedChange = { if (it) remote.start() else remote.stop() })
            }
        }
        state.errorRes?.let { res ->
            item { Text(stringResource(res), style = MaterialTheme.typography.bodyMedium, color = Palette.Coral) }
        }
        if (state.running) {
            item { JoinCards(state) }
            if (!state.streamSupported) {
                item { Text(stringResource(R.string.remote_no_stream), style = MaterialTheme.typography.bodySmall, color = Palette.Sun) }
            }
            item { Clients(state, remote) }
            if (state.streamSupported) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.remote_mute_host), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.remote_mute_host_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = state.muteHost, onCheckedChange = remote::setMuteHost)
                }
            }
            item { Text(stringResource(R.string.remote_paused_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted) }
        }
    }
}

/** QR do controle e endereço da tela: lado a lado em paisagem, empilhados em retrato. */
@Composable
private fun JoinCards(state: RemotePlay.State) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth > 560.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PadCard(state, Modifier.weight(1f))
                ScreenCard(state, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PadCard(state, Modifier.fillMaxWidth())
                ScreenCard(state, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PadCard(state: RemotePlay.State, modifier: Modifier) {
    val url = state.padUrl ?: return
    val qr = remember(url) { QrCodes.bitmap(url).asImageBitmap() }
    SurfaceCard(modifier) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(
                qr, null, filterQuality = FilterQuality.None,
                modifier = Modifier.size(132.dp).clip(RoundedCornerShape(12.dp)).background(Color.White),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Kicker(stringResource(R.string.remote_pad_title), color = Palette.Cyan)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.remote_pad_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun ScreenCard(state: RemotePlay.State, modifier: Modifier) {
    val address = state.address?.removePrefix("http://") ?: return
    SurfaceCard(modifier) {
        Column(Modifier.padding(14.dp)) {
            Kicker(stringResource(R.string.remote_screen_title), color = Palette.Sun)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.remote_screen_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            Spacer(Modifier.height(10.dp))
            Text(address, fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.remote_code), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                Spacer(Modifier.width(10.dp))
                // Em dois grupos de três: mais fácil de ler de longe e digitar.
                Text(state.code.chunked(3).joinToString(" "), fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Palette.Neon, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
private fun Clients(state: RemotePlay.State, remote: RemotePlay) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.remote_connected), style = MaterialTheme.typography.titleSmall)
        ClientRow(Icons.Rounded.PhoneAndroid, stringResource(R.string.remote_this_phone), listOf(0), onRemove = null)
        state.clients.forEach { c ->
            val icon = if (c.kind == RemotePlay.Kind.SCREEN) Icons.Rounded.Tv else Icons.Rounded.Gamepad
            val name = c.name.ifBlank { stringResource(if (c.kind == RemotePlay.Kind.SCREEN) R.string.remote_screen else R.string.remote_pad) }
            ClientRow(icon, name, c.ports, onRemove = { remote.kick(c.id) })
        }
        if (state.clients.isEmpty()) {
            Text(stringResource(R.string.remote_nobody), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun ClientRow(icon: androidx.compose.ui.graphics.vector.ImageVector, name: String, ports: List<Int>, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(start = 14.dp, end = 4.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Palette.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        ports.forEach { port ->
            val color = PlayerColors.getOrElse(port) { Palette.TextPrimary }
            Text(
                "P${port + 1}", fontFamily = MonoFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.Black,
                modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 9.dp, vertical = 4.dp),
            )
        }
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, stringResource(R.string.remote_remove), tint = Palette.TextSecondary) }
        } else Spacer(Modifier.width(10.dp))
    }
}
