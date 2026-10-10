package com.retrovika.app.emulation

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.retrovika.app.R
import com.retrovika.app.core.cheats.RamCheat
import com.retrovika.app.core.cheats.RamSearch
import com.retrovika.app.core.textmem.TextEncoding
import com.retrovika.app.core.textmem.TextHook
import com.retrovika.app.ui.components.ChipStrip
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.theme.Palette

/**
 * Tela da busca na memória (dentro da aba de trapaças): tira uma cópia da RAM, filtra o que mudou entre uma
 * cópia e outra e deixa travar o valor do endereço que sobrou.
 */
@Composable
internal fun RamSearchPanel(session: CheatSession, onClose: () -> Unit) {
    val studio = session.ram
    LaunchedEffect(studio) { studio.refreshSupport() }
    var locking by remember { mutableStateOf<RamSearch.Hit?>(null) }
    var equal by remember { mutableStateOf("") }
    var delta by remember { mutableStateOf("") }
    // Mudança aceita só o que cabe na largura (um byte: de -255 a 255), como o valor "igual a".
    fun deltaValue(): Long? = parseDelta(delta)?.takeIf { kotlin.math.abs(it) <= RamCheat.maxValue(studio.width) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ram_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text(stringResource(R.string.ram_close)) }
            }
        }
        when {
            studio.problem == RamStudio.Problem.NO_RAM -> item {
                Text(stringResource(R.string.ram_unsupported), style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            }
            else -> {
                item {
                    Text(
                        stringResource(R.string.ram_size, Formatter.formatShortFileSize(LocalContext.current, studio.ramSize.toLong())),
                        style = MaterialTheme.typography.labelMedium, color = Palette.Cyan,
                    )
                }
                item { TextSourcesSection(studio) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.ram_width_label), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                        ChipStrip(contentPadding = PaddingValues(0.dp)) {
                            SelectChip(stringResource(R.string.ram_width_1), studio.width == 1, { studio.changeWidth(1) })
                            SelectChip(stringResource(R.string.ram_width_2), studio.width == 2, { studio.changeWidth(2) })
                            SelectChip(stringResource(R.string.ram_width_4), studio.width == 4, { studio.changeWidth(4) })
                            SelectChip(stringResource(R.string.ram_big_endian), studio.bigEndian, { studio.changeEndian(!studio.bigEndian) })
                            if (studio.wordSwapSystem) {
                                SelectChip(stringResource(R.string.ram_word_swap), studio.wordSwap, { studio.changeWordSwap(!studio.wordSwap) })
                            }
                        }
                        Text(stringResource(R.string.ram_big_endian_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                        if (studio.wordSwapSystem) {
                            Text(stringResource(R.string.ram_word_swap_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                        }
                    }
                }
                if (!studio.started) {
                    item {
                        Text(stringResource(R.string.ram_intro), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                    item {
                        GradientButton(stringResource(R.string.ram_start), studio::start, Modifier.fillMaxWidth(), icon = Icons.Rounded.Memory, enabled = !studio.busy)
                    }
                } else {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                pluralStringResource(R.plurals.ram_candidates, studio.count, studio.count),
                                style = MaterialTheme.typography.titleSmall, color = Palette.Neon,
                            )
                            Text(stringResource(R.string.ram_started_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                        }
                    }
                    item {
                        ChipStrip(contentPadding = PaddingValues(0.dp)) {
                            SelectChip(stringResource(R.string.ram_filter_changed), false, { studio.filter(RamSearch.Filter.CHANGED) })
                            SelectChip(stringResource(R.string.ram_filter_unchanged), false, { studio.filter(RamSearch.Filter.UNCHANGED) })
                            SelectChip(stringResource(R.string.ram_filter_up), false, { studio.filter(RamSearch.Filter.INCREASED) })
                            SelectChip(stringResource(R.string.ram_filter_down), false, { studio.filter(RamSearch.Filter.DECREASED) })
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                equal, { equal = it.filter { c -> c.isLetterOrDigit() } },
                                label = { Text(stringResource(R.string.ram_filter_equal)) }, singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { parseValue(equal)?.let(studio::filterEqual) }, enabled = parseValue(equal) != null && !studio.busy) {
                                Text(stringResource(R.string.ram_apply))
                            }
                        }
                    }
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                delta, { delta = it.filter { c -> c.isLetterOrDigit() || c == '+' || c == '-' } },
                                label = { Text(stringResource(R.string.ram_filter_delta)) }, singleLine = true,
                                // Teclado de texto: o numérico de muitos aparelhos não tem o sinal de menos.
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Text),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { deltaValue()?.let(studio::filterDelta) }, enabled = deltaValue() != null && !studio.busy) {
                                Text(stringResource(R.string.ram_apply))
                            }
                        }
                    }
                    if (studio.count == 0) item {
                        Text(stringResource(R.string.ram_none_left), style = MaterialTheme.typography.bodySmall, color = Palette.Coral)
                    } else if (studio.hits.isEmpty()) item {
                        Text(stringResource(R.string.ram_too_many), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                    items(studio.hits, key = { it.address }) { hit ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh).padding(start = 14.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.ram_hit, hit.address.toString(16).uppercase(), hit.value.toString()),
                                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { locking = hit }) {
                                androidx.compose.material3.Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp))
                                Spacer(Modifier.padding(horizontal = 2.dp))
                                Text(stringResource(R.string.ram_lock))
                            }
                        }
                    }
                    item { GhostButton(stringResource(R.string.ram_restart), studio::reset, Modifier.fillMaxWidth()) }
                }
                if (studio.busy) item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                    }
                }
                if (studio.problem == RamStudio.Problem.READ_FAILED) item {
                    Text(stringResource(R.string.ram_read_failed), style = MaterialTheme.typography.bodySmall, color = Palette.Coral)
                }
                if (studio.problem == RamStudio.Problem.TOO_BIG) item {
                    Text(stringResource(R.string.ram_too_big), style = MaterialTheme.typography.bodySmall, color = Palette.Coral)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }

    locking?.let { hit ->
        LockDialog(
            hit = hit, width = studio.width,
            onLock = { value, name ->
                session.addRam(RamCheat(hit.address, value, studio.width, studio.bigEndian && !studio.wordSwap, studio.wordSwap, studio.coreId), name)
                locking = null
                onClose()
            },
            onDismiss = { locking = null },
        )
    }
}

@Composable
private fun LockDialog(hit: RamSearch.Hit, width: Int, onLock: (Long, String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(hit.value.toString()) }
    var name by remember { mutableStateOf("") }
    val parsed = parseValue(value)?.takeIf { it <= RamCheat.maxValue(width) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ram_lock_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value, { value = it.filter { c -> c.isLetterOrDigit() } }, label = { Text(stringResource(R.string.ram_lock_value)) }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = parsed == null,
                )
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.ram_lock_description)) }, singleLine = true)
                Text(stringResource(R.string.ram_lock_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = { parsed?.let { onLock(it, name) } }, enabled = parsed != null) { Text(stringResource(R.string.ram_lock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        containerColor = Palette.SurfaceHigh,
    )
}

/** Decimal, ou hexadecimal com prefixo `0x`; nulo se não for número ou for negativo. */
internal fun parseValue(text: String): Long? {
    val t = text.trim()
    return if (t.startsWith("0x", ignoreCase = true)) t.substring(2).toLongOrNull(16) else t.toLongOrNull()
}

/**
 * Diferença para o filtro "mudou em N": sinal opcional (`-3`, `+5`, `5`), valor decimal ou hexadecimal (`0x10`,
 * `-0x10`). Nulo se não for número; o sinal de mais ou de menos repetido não conta.
 */
internal fun parseDelta(text: String): Long? {
    val t = text.trim()
    val negative = t.startsWith("-")
    val body = if (negative || t.startsWith("+")) t.substring(1) else t
    if (body.startsWith("+") || body.startsWith("-")) return null
    val magnitude = parseValue(body)?.takeIf { it >= 0 } ?: return null
    return if (negative) -magnitude else magnitude
}

/**
 * Fontes de texto para a tradução: o jogador digita um trecho que está vendo na tela, a busca o acha na RAM e o lugar
 * achado vira um gancho que a tradução lê no lugar do OCR.
 */
@Composable
private fun TextSourcesSection(studio: RamStudio) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(Icons.Rounded.Translate, null, tint = Palette.Cyan, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ram_text_title), style = MaterialTheme.typography.titleSmall)
        }
        Text(stringResource(R.string.ram_text_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        ChipStrip(contentPadding = PaddingValues(0.dp)) {
            SelectChip(stringResource(R.string.ram_text_grid_off), studio.gridWidth == 0, { studio.changeGridWidth(0) })
            listOf(32, 40, 80).forEach { columns ->
                SelectChip(stringResource(R.string.ram_text_grid_n, columns), studio.gridWidth == columns, { studio.changeGridWidth(columns) })
            }
        }
        if (studio.gridWidth > 0) {
            Text(stringResource(R.string.ram_text_grid_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                text, { text = it; studio.clearTextMatches() }, label = { Text(stringResource(R.string.ram_text_label)) },
                singleLine = true, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { studio.findText(text) }, enabled = text.trim().length >= 3 && !studio.busy) {
                Text(stringResource(R.string.ram_text_find))
            }
        }
        if (studio.textSearched && studio.textMatches.isEmpty()) {
            Text(stringResource(R.string.ram_text_none), style = MaterialTheme.typography.bodySmall, color = Palette.Coral)
        }
        studio.textMatches.forEach { match ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Surface).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        "0x${match.address.toString(16).uppercase()} · " + stringResource(encodingLabel(match.encoding)),
                        style = MaterialTheme.typography.labelSmall, color = Palette.Cyan,
                    )
                    Text(match.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                TextButton(onClick = {
                    studio.addTextHook(studio.hookFor(match, match.preview.take(24).ifBlank { "0x${match.address.toString(16)}" }))
                    studio.clearTextMatches()
                    text = ""
                }) { Text(stringResource(R.string.ram_text_use)) }
            }
        }
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        var shareNote by remember { mutableStateOf<Int?>(null) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (studio.textHooks.isNotEmpty()) {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(studio.exportHooks()))
                    shareNote = R.string.ram_text_copied
                }) { Text(stringResource(R.string.ram_text_copy)) }
            }
            TextButton(onClick = {
                val added = clipboard.getText()?.text?.let(studio::importHooks) ?: -1
                shareNote = if (added < 0) R.string.ram_text_paste_invalid else if (added == 0) R.string.ram_text_paste_none else R.string.ram_text_pasted
            }) { Text(stringResource(R.string.ram_text_paste)) }
        }
        shareNote?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
        if (studio.textHooks.isNotEmpty()) {
            Text(stringResource(R.string.ram_text_hooks), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            studio.textHooks.forEach { hook ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Palette.Surface).padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(hook.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        Text(
                            "0x${hook.address.toString(16).uppercase()} · " + stringResource(encodingLabel(hook.encoding)),
                            style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted,
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = { studio.removeTextHook(hook) }) {
                        androidx.compose.material3.Icon(Icons.Rounded.Close, stringResource(R.string.common_remove), tint = Palette.TextSecondary)
                    }
                }
            }
        }
    }
}

private fun encodingLabel(encoding: TextEncoding): Int = when (encoding) {
    TextEncoding.ASCII -> R.string.ram_text_enc_ascii
    TextEncoding.UTF16LE, TextEncoding.UTF16BE -> R.string.ram_text_enc_utf16
    TextEncoding.SHIFT_JIS -> R.string.ram_text_enc_sjis
    TextEncoding.TABLE -> R.string.ram_text_enc_table
}
