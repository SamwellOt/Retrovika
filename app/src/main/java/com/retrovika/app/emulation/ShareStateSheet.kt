package com.retrovika.app.emulation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.retrovika.app.R
import com.retrovika.app.core.share.LanTransfer
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.QrCode
import com.retrovika.app.ui.theme.Palette

/** Um estado empacotado para compartilhar; [server] existe enquanto o QR code está na tela. */
class ShareSheet(val title: String, val bytes: ByteArray, val thumbnail: Bitmap?) {
    var link by mutableStateOf<String?>(null)
    var noNetwork by mutableStateOf(false)
    var server: LanTransfer.Server? = null
}

/**
 * "Tente passar desta fase": o estado vai como arquivo (menu de compartilhar do Android: WhatsApp,
 * Telegram, e-mail…) ou por QR code, que o amigo lê com o Retrovika na mesma rede Wi-Fi.
 */
@Composable
internal fun ShareStateSheet(sheet: ShareSheet, menu: MenuActions) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC07040F))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = menu::closeShare),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)).background(Palette.Surface).border(1.dp, Palette.Outline, RoundedCornerShape(24.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Kicker(stringResource(R.string.share_state_kicker))
            Spacer(Modifier.height(8.dp))
            Text(sheet.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(sheet.bytes.size.toLong().formatBytes(), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
            Spacer(Modifier.height(14.dp))
            val link = sheet.link
            if (link != null) {
                QrCode(link)
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.share_state_qr_hint), style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary, textAlign = TextAlign.Center,
                )
            } else {
                sheet.thumbnail?.let {
                    Image(
                        it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                        modifier = Modifier.width(240.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    stringResource(R.string.share_state_hint), style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary, textAlign = TextAlign.Center,
                )
                if (sheet.noNetwork) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.share_state_no_network), style = MaterialTheme.typography.bodySmall, color = Palette.Coral, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(16.dp))
                GradientButton(stringResource(R.string.share_state_send_file), menu::shareAsFile, Modifier.fillMaxWidth(), icon = Icons.Rounded.Share)
                Spacer(Modifier.height(10.dp))
                GhostButton(stringResource(R.string.share_state_show_qr), menu::shareAsQr, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCode2, tint = Palette.Cyan)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = menu::closeShare) { Text(stringResource(R.string.common_close)) }
            }
        }
    }
}
