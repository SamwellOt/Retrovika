package com.retrovika.app.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.retrovika.app.R
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.QrCode
import com.retrovika.app.ui.theme.Palette

/** QR code do anfitrião (esperando o outro jogador) ou "conectando", por cima de tudo. */
@Composable
internal fun NetplaySheet(state: NetplayUi, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0xE607040F))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)).background(Palette.Surface).border(1.dp, Palette.Outline, RoundedCornerShape(24.dp))
                .verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Kicker(stringResource(R.string.netplay_kicker))
            Spacer(Modifier.height(8.dp))
            when (state) {
                is NetplayUi.Hosting -> {
                    Text(stringResource(R.string.netplay_host_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    QrCode(state.link)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.netplay_host_hint), style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextSecondary, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.netplay_waiting_guest), style = MaterialTheme.typography.labelMedium, color = Palette.Cyan)
                    }
                }
                else -> {
                    Text(stringResource(R.string.netplay_connecting), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = Palette.Cyan)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.netplay_connecting_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

/** Selo da partida em rede sobre o jogo: qual jogador você é, ou o aviso de espera. */
@Composable
internal fun NetplayBadge(state: NetplayUi.Playing, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50))
            .background((if (state.waiting) Palette.Sun else Palette.Cyan).copy(alpha = 0.18f))
            .border(1.dp, (if (state.waiting) Palette.Sun else Palette.Cyan).copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Wifi, null, tint = if (state.waiting) Palette.Sun else Palette.Cyan, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (state.waiting) stringResource(R.string.netplay_waiting_other) else stringResource(R.string.netplay_player_n, state.player),
            style = MaterialTheme.typography.labelSmall, color = Palette.TextPrimary,
        )
    }
}
