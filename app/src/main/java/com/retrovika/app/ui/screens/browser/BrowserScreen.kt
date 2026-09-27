package com.retrovika.app.ui.screens.browser

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.content.Intent
import android.content.ActivityNotFoundException
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import android.os.Bundle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.container
import com.retrovika.app.core.catalog.DownloadManager
import com.retrovika.app.core.library.RomNaming
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.launch

private data class BrowserSite(val name: String, val url: String, @StringRes val note: Int)

/** Atalhos da página inicial. Qualquer outro endereço pode ser digitado no campo de busca. */
private val sites = listOf(
    BrowserSite("RomsFun", "https://romsfun.com/", R.string.browser_site_romsfun),
    BrowserSite("Vimm's Lair", "https://vimm.net/vault/", R.string.browser_site_vimm),
    BrowserSite("CDRomance", "https://cdromance.org/", R.string.browser_site_cdromance),
    BrowserSite("Internet Archive", "https://archive.org/", R.string.browser_site_archive),
)

/** Download interceptado no WebView, aguardando o usuário confirmar o console. */
private data class PendingDownload(
    val url: String,
    val fileName: String,
    val size: Long,
    val headers: Map<String, String>,
    val guess: GameSystem?,
)

/**
 * Navegador interno: o usuário navega no site como em qualquer navegador (inclusive verificações
 * anti-robô, que ele mesmo resolve). Quando a página inicia um download, o arquivo é baixado com a
 * mesma sessão (cookies + User-Agent) e entra na biblioteca pelo [DownloadManager].
 */
@Composable
fun BrowserScreen(onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val manager = context.container.downloads
    // Só o contador: observar a lista inteira recompunha a tela (e o WebView) a cada avanço de download.
    val active by manager.activeCount.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf<String?>(null) }
    // Abrir Downloads tira o navegador da tela e destrói o WebView; o histórico e a página (já depois
    // de um desafio do Cloudflare, por exemplo) voltam deste pacote em vez de recarregar do zero.
    val webState = rememberSaveable { Bundle() }
    var pending by remember { mutableStateOf<PendingDownload?>(null) }

    Box(Modifier.fillMaxSize()) {
        val current = url
        if (current == null) {
            StartPage(onBack = onBack, onOpen = { webState.clear(); url = it })
        } else {
            BrowserView(
                startUrl = current,
                savedState = webState,
                activeDownloads = active,
                onClose = { url = null },
                onOpenDownloads = onOpenDownloads,
                onUrlChanged = { url = it },
                onDownload = { pending = it },
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    val resources = LocalContext.current.resources
    pending?.let { download ->
        ConfirmDownloadDialog(
            download = download,
            onDismiss = { pending = null },
            onConfirm = { system ->
                manager.enqueueBrowser(download.url, download.fileName, system, download.headers)
                pending = null
                scope.launch {
                    val result = snackbar.showSnackbar(resources.getString(R.string.browser_downloading, download.fileName), actionLabel = resources.getString(R.string.browser_view))
                    if (result == SnackbarResult.ActionPerformed) onOpenDownloads()
                }
            },
        )
    }
}

@Composable
private fun StartPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val focus = LocalFocusManager.current
    var address by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) }
                    Text(stringResource(R.string.browser_title), style = MaterialTheme.typography.headlineMedium)
                }
                Text(
                    stringResource(R.string.browser_intro),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    placeholder = { Text(stringResource(R.string.browser_address_hint)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        if (address.isNotBlank()) { focus.clearFocus(); onOpen(toUrl(address)) }
                    }),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Palette.SurfaceHigh, focusedContainerColor = Palette.SurfaceHigh),
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        items(sites, key = { it.url }) { site ->
            Row(
                Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(Palette.SurfaceHigh, RoundedCornerShape(16.dp))
                    .clickable { onOpen(site.url) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Language, null, tint = Palette.Neon)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(site.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${Uri.parse(site.url).host} · ${stringResource(site.note)}",
                        style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserView(
    startUrl: String,
    savedState: Bundle,
    activeDownloads: Int,
    onClose: () -> Unit,
    onOpenDownloads: () -> Unit,
    onUrlChanged: (String) -> Unit,
    onDownload: (PendingDownload) -> Unit,
) {
    val context = LocalContext.current
    var progress by remember { mutableFloatStateOf(0f) }
    var title by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf(startUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    val defaultName = stringResource(R.string.download_default_name)

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            // O WebView se identifica com "; wv" e "Version/4.0" no User-Agent; alguns sites (Vimm's Lair
            // entre eles) escondem o conteúdo ou os downloads para WebViews. Aqui ele se apresenta como o Chrome.
            settings.userAgentString = chromeUserAgent(settings.userAgentString)
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Links com target=_blank abrem na mesma aba; janelas abertas por script (pop-ups de anúncio) ficam bloqueadas.
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                // Esquemas que não são web (intent:, market:…) costumam ser redirecionamentos de anúncio.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme !in setOf("http", "https")

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { pageUrl = url }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    pageUrl = url
                    canGoBack = view.canGoBack()
                    onUrlChanged(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress / 100f }
                override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
            }
            setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                if (!url.startsWith("http")) {
                    Toast.makeText(context, R.string.browser_blob_unsupported, Toast.LENGTH_LONG).show()
                    return@setDownloadListener
                }
                val fileName = fileNameFor(url, contentDisposition, mimeType, defaultName)
                val headers = buildMap {
                    put("User-Agent", userAgent)
                    CookieManager.getInstance().getCookie(url)?.let { put("Cookie", it) }
                    this@apply.url?.let { put("Referer", it) }
                }
                val guess = RomNaming.guessSystem(fileName, listOfNotNull(this@apply.url, this@apply.title, url))
                onDownload(PendingDownload(url, fileName, contentLength, headers, guess))
            }
            if (savedState.isEmpty || restoreState(savedState) == null) loadUrl(startUrl)
            canGoBack = this.canGoBack()
        }
    }
    DisposableEffect(webView) {
        onDispose {
            savedState.clear()
            webView.saveState(savedState)
            CookieManager.getInstance().flush()
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler(enabled = canGoBack) { webView.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Palette.Surface).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.browser_close_site)) }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { stringResource(R.string.browser_loading) }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    Uri.parse(pageUrl).host.orEmpty(),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, maxLines = 1,
                )
            }
            IconButton(onClick = { webView.reload() }) { Icon(Icons.Rounded.Refresh, stringResource(R.string.browser_reload)) }
            // Plano B para sites que não funcionam no navegador interno.
            IconButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.browser_no_external, Toast.LENGTH_SHORT).show()
                }
            }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.browser_open_external)) }
            IconButton(onClick = onOpenDownloads) {
                BadgedBox(badge = { if (activeDownloads > 0) androidx.compose.material3.Badge { Text("$activeDownloads") } }) {
                    Icon(Icons.Rounded.Downloading, stringResource(R.string.explore_downloads))
                }
            }
        }
        if (progress < 1f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmDownloadDialog(download: PendingDownload, onDismiss: () -> Unit, onConfirm: (GameSystem) -> Unit) {
    var system by remember(download) { mutableStateOf(download.guess) }
    var expanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_add_to_library)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(download.fileName, style = MaterialTheme.typography.bodyMedium)
                if (download.size > 0) Text(download.size.formatBytes(), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = system?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.browser_console)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        Systems.all.forEach { s ->
                            DropdownMenuItem(text = { Text(s.name) }, onClick = { system = s; expanded = false })
                        }
                    }
                }
                if (download.guess == null) {
                    Text(
                        stringResource(R.string.browser_console_unknown),
                        style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { system?.let(onConfirm) }, enabled = system != null) { Text(stringResource(R.string.common_download)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Texto digitado vira endereço quando parece um domínio; caso contrário, vira busca. */
private fun toUrl(input: String): String {
    val text = input.trim()
    return when {
        text.startsWith("http://") || text.startsWith("https://") -> text
        ' ' !in text && '.' in text -> "https://$text"
        else -> "https://duckduckgo.com/?q=${Uri.encode(text)}"
    }
}

/**
 * Nome do arquivo: Content-Disposition (filename* ou filename), depois o fim da URL.
 * O [URLUtil.guessFileName] fica por último porque às vezes troca a extensão (.7z -> .bin).
 */
private fun fileNameFor(url: String, contentDisposition: String?, mimeType: String?, defaultName: String): String {
    val fromHeader = contentDisposition?.let { cd ->
        Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(cd)?.groupValues?.get(1)?.let(Uri::decode)
            ?: Regex("""filename\s*=\s*"?([^";]+)"?""").find(cd)?.groupValues?.get(1)
    }
    // Só vale o fim da URL se ele tiver cara de ROM ou pacote: "download.php?id=1" salvaria o jogo como .php.
    val fromUrl = Uri.parse(url).lastPathSegment?.takeIf { segment ->
        val ext = segment.substringAfterLast('.', "").lowercase()
        ext.isNotEmpty() && (ext in ARCHIVE_EXTENSIONS || Systems.all.any { ext in it.extensions })
    }
    val name = (fromHeader ?: fromUrl ?: URLUtil.guessFileName(url, contentDisposition, mimeType)).trim()
    return name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { defaultName }
}

private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar")

/** User-Agent do WebView sem as marcas de WebView ("; wv" e "Version/x.y"), igual ao do Chrome no mesmo aparelho. */
internal fun chromeUserAgent(webView: String): String =
    webView.replace("; wv)", ")").replace(Regex("""Version/[\d.]+ """), "")
