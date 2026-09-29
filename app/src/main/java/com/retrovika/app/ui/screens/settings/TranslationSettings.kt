package com.retrovika.app.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.net.userMessage
import com.retrovika.app.core.settings.AppSettings
import com.retrovika.app.core.translate.GeminiText
import com.retrovika.app.core.translate.OcrPack
import com.retrovika.app.ui.components.IconTile
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.launch

/** OCR para jogos japoneses: baixar (~58 MB), acompanhar o download e apagar. */
@Composable
internal fun OcrPackRow() {
    val context = LocalContext.current
    val pack = context.container.ocrPack
    val state by pack.state.collectAsStateWithLifecycle()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Rounded.DocumentScanner, Palette.Orange, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_ocr_pack), style = MaterialTheme.typography.titleSmall)
            Text(
                when (val s = state) {
                    OcrPack.State.Ready -> stringResource(R.string.settings_ocr_pack_ready)
                    is OcrPack.State.Downloading -> stringResource(R.string.settings_ocr_pack_downloading, (s.progress * 100).toInt())
                    is OcrPack.State.Failed -> stringResource(R.string.settings_ocr_pack_failed, s.error.userMessage(context))
                    OcrPack.State.Missing -> stringResource(R.string.settings_ocr_pack_subtitle, OcrPack.DOWNLOAD_MB)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state is OcrPack.State.Failed) Palette.Coral else Palette.TextSecondary,
            )
            (state as? OcrPack.State.Downloading)?.let {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { it.progress }, modifier = Modifier.fillMaxWidth(), color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
            }
        }
        Spacer(Modifier.width(8.dp))
        when (state) {
            is OcrPack.State.Downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Cyan)
            OcrPack.State.Ready -> TextButton(onClick = { pack.delete() }) { Text(stringResource(R.string.settings_ocr_pack_delete), color = Palette.Coral) }
            else -> TextButton(onClick = { pack.download() }) { Text(stringResource(R.string.settings_ocr_pack_download)) }
        }
    }
}

/** Tradução com IA (Gemini): mostra se está ligada e abre o diálogo da chave. */
@Composable
internal fun AiTranslationRow(settings: AppSettings) {
    var editing by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { editing = true }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(Icons.Rounded.AutoAwesome, Palette.Neon, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_ai_translate), style = MaterialTheme.typography.titleSmall)
            Text(
                if (settings.geminiKey != null) stringResource(R.string.settings_ai_translate_on, settings.geminiModel)
                else stringResource(R.string.settings_ai_translate_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = if (settings.geminiKey != null) Palette.Success else Palette.TextSecondary,
            )
        }
    }
    if (editing) AiKeyDialog(settings) { editing = false }
}

@Composable
private fun AiKeyDialog(settings: AppSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = context.container.settings
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf(settings.geminiKey.orEmpty()) }
    var model by remember { mutableStateOf(settings.geminiModel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null, tint = Palette.Neon) },
        title = { Text(stringResource(R.string.settings_ai_translate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.settings_ai_dialog_message), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                TextButton(
                    onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(KEY_PAGE))) } },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(stringResource(R.string.settings_ai_get_key), color = Palette.Cyan) }
                OutlinedTextField(
                    value = key, onValueChange = { key = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_ai_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it }, singleLine = true,
                    label = { Text(stringResource(R.string.settings_ai_model)) },
                    supportingText = { Text(stringResource(R.string.settings_ai_model_hint, GeminiText.DEFAULT_MODEL)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { repo.setGeminiKey(key); repo.setGeminiModel(model) }
                onDismiss()
            }) { Text(stringResource(R.string.settings_ai_save)) }
        },
        dismissButton = {
            if (settings.geminiKey != null) {
                TextButton(onClick = { scope.launch { repo.setGeminiKey("") }; onDismiss() }) {
                    Text(stringResource(R.string.settings_ai_remove), color = Palette.Coral)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
        containerColor = Palette.SurfaceHigh,
    )
}

private const val KEY_PAGE = "https://aistudio.google.com/apikey"
