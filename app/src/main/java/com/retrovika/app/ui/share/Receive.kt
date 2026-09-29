package com.retrovika.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.share.LanTransfer
import com.retrovika.app.core.share.RetrovikaLink
import com.retrovika.app.core.share.SharedStates
import com.retrovika.app.core.share.StatePackage
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.CancellationException

/** O que chegou de fora: um arquivo .rvstate ou um link de QR code. */
sealed interface Incoming {
    data class File(val uri: Uri) : Incoming
    data class Link(val link: RetrovikaLink) : Incoming
}

/** O Intent que abriu o app traz algo para receber? */
fun Intent.toIncoming(): Incoming? {
    val data = data
    return when (action) {
        Intent.ACTION_VIEW -> when {
            data == null -> null
            data.scheme == RetrovikaLink.SCHEME -> RetrovikaLink.parse(data.toString())?.let { Incoming.Link(it) }
            data.scheme == "content" || data.scheme == "file" -> Incoming.File(data)
            else -> null
        }
        Intent.ACTION_SEND -> {
            val stream = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)
            stream?.let { Incoming.File(it) }
        }
        else -> null
    }
}

private sealed interface Phase {
    data class Working(val progress: Float?) : Phase
    data class Done(val result: SharedStates.Received) : Phase
    data class Failed(val message: String) : Phase
}

/**
 * Recebe o que chega em [com.retrovika.app.AppContainer.incoming] (Intent, QR code lido, arquivo escolhido)
 * e mostra o resultado. Partidas em rede vão para [onNetplay].
 */
@Composable
fun ReceiveHost(onNetplay: (RetrovikaLink.Netplay) -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val incoming by app.incoming.collectAsStateWithLifecycle()
    val current = incoming ?: return
    if (current is Incoming.Link && current.link is RetrovikaLink.Netplay) {
        LaunchedEffect(current) {
            app.incoming.value = null
            onNetplay(current.link)
        }
        return
    }
    var phase by remember(current) { mutableStateOf<Phase>(Phase.Working(null)) }
    LaunchedEffect(current) {
        phase = try {
            val bytes = when (current) {
                is Incoming.File -> app.sharedStates.readUri(current.uri)
                is Incoming.Link -> LanTransfer.fetch(current.link.hosts, current.link.port, current.link.token, StatePackage.MAX_SIZE) { done, total ->
                    phase = Phase.Working(if (total > 0) done / total.toFloat() else null)
                }
            }
            Phase.Done(app.sharedStates.receive(bytes))
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Phase.Failed(if (current is Incoming.Link) context.getString(R.string.share_receive_lan_failed) else t.userMessage(context))
        }
    }
    val dismiss = { app.incoming.value = null }
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(stringResource(R.string.share_receive_title)) },
        text = {
            when (val p = phase) {
                is Phase.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        p.progress?.let { stringResource(R.string.share_receive_progress, (it * 100).toInt()) } ?: stringResource(R.string.share_receive_working),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is Phase.Failed -> Text(p.message, style = MaterialTheme.typography.bodyMedium, color = Palette.Coral)
                is Phase.Done -> Received(p.result)
            }
        },
        confirmButton = {
            val done = (phase as? Phase.Done)?.result as? SharedStates.Received.Ready
            if (done != null) {
                GradientButton(stringResource(R.string.share_receive_play), {
                    dismiss()
                    GameActivity.launch(context, done.game.id, done.stateFile, done.manifest.coreId)
                }, icon = Icons.Rounded.PlayArrow, height = 44.dp)
            } else {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) }
            }
        },
        dismissButton = {
            if (phase is Phase.Done && (phase as Phase.Done).result is SharedStates.Received.Ready) {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun Received(result: SharedStates.Received) {
    val system = Systems.byId(result.manifest.systemId)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val thumb = when (result) {
            is SharedStates.Received.Ready -> result.thumbnail
            is SharedStates.Received.MissingGame -> result.thumbnail
        }
        thumb?.let {
            Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(14.dp)))
        }
        Text(result.manifest.title, style = MaterialTheme.typography.titleMedium)
        Text(
            listOfNotNull(system?.name, Systems.byId(result.manifest.systemId)?.cores?.firstOrNull { it.id == result.manifest.coreId }?.displayName).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
        )
        when (result) {
            is SharedStates.Received.Ready -> Text(stringResource(R.string.share_receive_ready), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            is SharedStates.Received.MissingGame -> Text(
                stringResource(R.string.share_receive_missing, result.manifest.datName ?: result.manifest.rawName),
                style = MaterialTheme.typography.bodySmall, color = Palette.Coral,
            )
        }
    }
}

/** Câmera (QR code) e seletor de arquivo; ficam na raiz da navegação para o resultado não se perder. */
class ReceiveLaunchers(val scan: () -> Unit, val openFile: () -> Unit)

@Composable
fun rememberReceiveLaunchers(): ReceiveLaunchers {
    val context = LocalContext.current
    val app = context.container
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        val link = RetrovikaLink.parse(text)
        if (link == null) Toast.makeText(context, R.string.share_qr_not_ours, Toast.LENGTH_LONG).show()
        else app.incoming.value = Incoming.Link(link)
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) app.incoming.value = Incoming.File(uri)
    }
    return remember(scanner, picker) {
        ReceiveLaunchers(
            scan = {
                scanner.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(context.getString(R.string.share_scan_prompt))
                        .setBeepEnabled(false)
                        .setOrientationLocked(false),
                )
            },
            openFile = { picker.launch(arrayOf("*/*")) },
        )
    }
}

/** Escolha de como receber, aberta pelo botão da tela inicial: ler um QR code ou abrir um arquivo .rvstate. */
@Composable
fun ReceiveChooser(launchers: ReceiveLaunchers, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(stringResource(R.string.share_chooser_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.share_chooser_message), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                Spacer(Modifier.height(4.dp))
                GradientButton(stringResource(R.string.share_chooser_scan), { onDismiss(); launchers.scan() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.QrCodeScanner)
                GhostButton(stringResource(R.string.share_chooser_file), { onDismiss(); launchers.openFile() }, Modifier.fillMaxWidth(), icon = Icons.Rounded.FileOpen, tint = Palette.Cyan)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
