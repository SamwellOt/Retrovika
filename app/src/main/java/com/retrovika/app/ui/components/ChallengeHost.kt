package com.retrovika.app.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.net.ChallengePrompt
import com.retrovika.app.core.net.WebFetcher
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Mostra a verificação de um site que o WebView invisível não passou sozinho ([ChallengePrompt]): a página
 * aparece para o usuário tocar na caixa, e a janela fecha sozinha quando a verificação libera o site.
 */
@Composable
fun ChallengeHost() {
    val prompt = LocalContext.current.container.challenges
    // Só conta como "há tela" com o app à vista: em segundo plano, o pedido falha na hora em vez de esperar.
    LifecycleStartEffect(prompt) {
        val detach = prompt.attach()
        onStopOrDispose { detach() }
    }
    val request by prompt.current.collectAsStateWithLifecycle()
    request?.let { ChallengeDialog(it, onDone = { passed -> prompt.answer(it, passed) }) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ChallengeDialog(request: ChallengePrompt.Request, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val web = remember(request) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            setBackgroundColor(android.graphics.Color.WHITE)
            loadUrl(request.url)
        }
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.destroy() } }
    // Passou quando o documento do site carregou e não é mais a página da verificação.
    LaunchedEffect(web) {
        while (true) {
            delay(POLL_MS)
            val raw = CompletableDeferred<String?>()
            web.evaluateJavascript(WebFetcher.PAGE_STATE_JS) { raw.complete(it) }
            if (WebFetcher.pageCleared(raw.await())) {
                onDone(true)
                return@LaunchedEffect
            }
        }
    }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp),
        icon = { Icon(Icons.Rounded.VerifiedUser, null, tint = Palette.Cyan) },
        title = { Text(stringResource(R.string.web_check_title)) },
        text = {
            Column {
                Text(stringResource(R.string.web_check_message, request.host), style = MaterialTheme.typography.bodyMedium)
                Box(
                    Modifier.padding(top = 16.dp).fillMaxWidth().height(380.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color.White),
                ) {
                    AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().height(380.dp))
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text(stringResource(R.string.common_cancel)) } },
        containerColor = Palette.SurfaceHigh,
    )
}

private const val POLL_MS = 500L
