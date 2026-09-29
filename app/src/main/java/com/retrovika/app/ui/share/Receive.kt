package com.retrovika.app.ui.share

import android.content.Context
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
import com.retrovika.app.AppContainer
import com.retrovika.app.core.settings.localized
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.retrovika.app.core.netplay.NetplayGuest

/** O que chegou de fora: um arquivo .rvstate ou um link de QR code. */
sealed interface Incoming {
    data class File(val uri: Uri) : Incoming

    /**
     * [confirmed] = o usuário já aceitou a conexão. Links que chegam por Intent (qualquer app ou página pode abrir
     * `retrovika://`) começam sem confirmação; o QR code lido dentro do app já é uma escolha do usuário.
     */
    data class Link(val link: RetrovikaLink, val confirmed: Boolean = false) : Incoming
}

/** O Intent que abriu o app traz algo para receber? */
fun Intent.toIncoming(): Incoming? {
    val data = data
    return when (action) {
        Intent.ACTION_VIEW -> when {
            data == null -> null
            data.scheme == RetrovikaLink.SCHEME -> RetrovikaLink.parse(data.toString())?.let { Incoming.Link(it, confirmed = false) }
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

/** No máximo tantos endereços por link: cada um é uma tentativa de conexão. */
private const val MAX_LINK_HOSTS = 4

private val IPV4_LITERAL = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
private val IPV6_LITERAL = Regex("""^[0-9a-fA-F:.]+(%[0-9A-Za-z_.-]+)?$""")

/**
 * Só aceita IPs literais de rede local (privados, link-local, loopback), sem nomes de DNS: um link não pode
 * fazer o app conectar a um servidor qualquer da internet. O teste de literal vem antes de [InetAddress.getByName],
 * que com um nome faria uma consulta DNS.
 */
fun lanHosts(hosts: List<String>): List<String> = hosts.asSequence()
    .map { it.trim().removePrefix("[").removeSuffix("]") }
    .mapNotNull { h ->
        val addr = when {
            // IPv4 montado byte a byte: "300.1.1.1" iria parar no DNS se passasse por getByName
            IPV4_LITERAL.matches(h) -> {
                val parts = h.split('.').map { it.toInt() }
                if (parts.any { it > 255 }) null
                else runCatching { InetAddress.getByAddress(ByteArray(4) { i -> parts[i].toByte() }) }.getOrNull()
            }
            // Só hexadecimal e ':' não é um nome resolvível; o literal é interpretado sem consulta
            h.contains(':') && IPV6_LITERAL.matches(h) -> runCatching { InetAddress.getByName(h) }.getOrNull()
            else -> null
        } ?: return@mapNotNull null
        val local = addr.isSiteLocalAddress || addr.isLinkLocalAddress || addr.isLoopbackAddress ||
            // IPv6 ULA (fc00::/7): o Java só considera "site local" o antigo fec0::/10
            (addr is Inet6Address && (addr.address[0].toInt() and 0xfe) == 0xfc)
        // Devolve a forma normalizada, para a conexão usar exatamente o endereço verificado
        if (local) addr.hostAddress else null
    }
    .distinct()
    .take(MAX_LINK_HOSTS)
    .toList()

internal sealed interface ReceivePhase {
    data class Working(val progress: Float?) : ReceivePhase
    data class Done(val result: SharedStates.Received) : ReceivePhase
    data class Failed(val message: String) : ReceivePhase
}

/**
 * Recebimento em andamento, guardado no [com.retrovika.app.AppContainer] ao lado de `incoming`: girar a tela
 * recria a Activity, e o download pela rede local (e a gravação do estado) não pode recomeçar nem se perder.
 * Só é usado na thread principal (pela composição); o trabalho roda no escopo do app.
 */
class ReceiveSession {
    internal val phase = MutableStateFlow<ReceivePhase>(ReceivePhase.Working(null))
    private var source: Incoming? = null
    private var job: Job? = null

    /** Começa a receber [incoming]; se já está recebendo (ou recebeu) esse mesmo, não faz nada. */
    internal fun start(app: AppContainer, context: Context, incoming: Incoming) {
        if (source == incoming) return
        job?.cancel()
        source = incoming
        phase.value = ReceivePhase.Working(null)
        job = app.scope.launch {
            val result = try {
                val bytes = when (incoming) {
                    is Incoming.File -> app.sharedStates.readUri(incoming.uri)
                    is Incoming.Link -> LanTransfer.fetch(lanHosts(incoming.link.hosts), incoming.link.port, incoming.link.token, StatePackage.MAX_SIZE) { done, total ->
                        if (isActive) phase.value = ReceivePhase.Working(if (total > 0) done / total.toFloat() else null)
                    }
                }
                ReceivePhase.Done(app.sharedStates.receive(bytes))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                ReceivePhase.Failed(
                    if (incoming is Incoming.Link) context.localized().getString(R.string.share_receive_lan_failed) else t.userMessage(context),
                )
            }
            if (isActive) phase.value = result
        }
    }

    /** Fecha o que está sendo recebido (cancela a transferência, se ainda estiver rodando). */
    fun clear(app: AppContainer) {
        job?.cancel()
        job = null
        source = null
        phase.value = ReceivePhase.Working(null)
        app.incoming.value = null
    }
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
    val dismiss = { app.receive.clear(app) }
    if (current is Incoming.Link) {
        val hosts = remember(current) { lanHosts(current.link.hosts) }
        if (hosts.isEmpty()) {
            AlertDialog(
                onDismissRequest = dismiss,
                containerColor = Palette.SurfaceHigh,
                title = { Text(stringResource(R.string.share_receive_title)) },
                text = { Text(stringResource(R.string.share_link_bad_host), style = MaterialTheme.typography.bodyMedium, color = Palette.Coral) },
                confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) } },
            )
            return
        }
        if (!current.confirmed) {
            // Nada de rede antes do usuário aceitar: mostra de onde vem e o que vai acontecer.
            val netplay = current.link is RetrovikaLink.Netplay
            AlertDialog(
                onDismissRequest = dismiss,
                containerColor = Palette.SurfaceHigh,
                title = { Text(stringResource(if (netplay) R.string.share_link_confirm_netplay_title else R.string.share_link_confirm_state_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                if (netplay) R.string.share_link_confirm_netplay else R.string.share_link_confirm_state,
                                current.link.title.ifBlank { "?" },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.share_link_confirm_host, hosts.joinToString(", ") + ":" + current.link.port),
                            style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { app.incoming.value = current.copy(confirmed = true) }) {
                        Text(stringResource(R.string.share_link_confirm_accept))
                    }
                },
                dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.common_cancel)) } },
            )
            return
        }
        val link = current.link
        if (link is RetrovikaLink.Netplay) {
            LaunchedEffect(current) {
                app.receive.clear(app)
                onNetplay(link.copy(hosts = hosts))
            }
            return
        }
    }
    LaunchedEffect(current) { app.receive.start(app, context.applicationContext, current) }
    val phase by app.receive.phase.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = dismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(stringResource(R.string.share_receive_title)) },
        text = {
            when (val p = phase) {
                is ReceivePhase.Working -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        p.progress?.let { stringResource(R.string.share_receive_progress, (it * 100).toInt()) } ?: stringResource(R.string.share_receive_working),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is ReceivePhase.Failed -> Text(p.message, style = MaterialTheme.typography.bodyMedium, color = Palette.Coral)
                is ReceivePhase.Done -> Received(p.result)
            }
        },
        confirmButton = {
            val done = (phase as? ReceivePhase.Done)?.result as? SharedStates.Received.Ready
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
            if ((phase as? ReceivePhase.Done)?.result is SharedStates.Received.Ready) {
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

/**
 * Entrar numa partida em rede pelo QR code: pergunta ao anfitrião qual jogo e núcleo ele está rodando,
 * acha o jogo na biblioteca e abre direto como jogador 2.
 */
@Composable
fun NetplayJoin(link: RetrovikaLink.Netplay, onDone: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    var error by remember(link) { mutableStateOf<String?>(null) }
    LaunchedEffect(link) {
        try {
            val (host, manifest) = NetplayGuest.info(link.hosts, link.port, link.token)
            val game = StatePackage.match(manifest, app.library.all.first())
            if (game == null) {
                error = context.getString(R.string.netplay_missing_game, manifest.datName ?: manifest.rawName, Systems.byId(manifest.systemId)?.name ?: manifest.systemId)
                return@LaunchedEffect
            }
            onDone()
            GameActivity.launchNetplay(context, game.id, host, link.port, link.token, manifest.coreId)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            error = context.getString(R.string.netplay_join_failed)
        }
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Palette.SurfaceHigh,
        title = { Text(stringResource(R.string.netplay_kicker)) },
        text = {
            val e = error
            if (e != null) Text(e, style = MaterialTheme.typography.bodyMedium, color = Palette.Coral)
            else Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.netplay_joining, link.title), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(if (error != null) R.string.common_close else R.string.common_cancel)) } },
    )
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
        else app.incoming.value = Incoming.Link(link, confirmed = true)
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
