package com.retrovika.app.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.retrovika.app.R
import com.retrovika.app.core.cheats.Cheat
import com.retrovika.app.ui.components.Badge
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.SearchField
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * Aba "Trapaças" do menu de pausa: o arquivo da libretro-database achado para o jogo, uma busca pelos
 * códigos, um interruptor por código e códigos próprios. As mudanças valem ao voltar ao jogo.
 */
@Composable
internal fun CheatsTab(session: CheatSession) {
    LaunchedEffect(session) { session.ensureLoaded() }
    var query by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val state = session.state
    val shown = remember(state, query) {
        val q = query.trim()
        if (q.isEmpty()) state.cheats else state.cheats.filter { it.description.contains(q, ignoreCase = true) }
    }

    if (picking) {
        FilePicker(session, onDone = { picking = false })
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.SurfaceHigh)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Description, null, tint = Palette.Cyan, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.cheats_source), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                    Text(
                        state.file?.removeSuffix(".cht") ?: stringResource(if (session.loading) R.string.cheats_searching else R.string.cheats_no_file),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (session.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                else TextButton(onClick = { picking = true }) {
                    Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (state.file == null) R.string.cheats_find else R.string.cheats_change))
                }
            }
        }
        session.error?.let { err ->
            item {
                Column {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = Palette.Coral)
                    TextButton(onClick = session::retry) { Text(stringResource(R.string.cheats_retry)) }
                }
            }
        }
        if (state.cheats.size > 6) item {
            SearchField(query, { query = it }, stringResource(R.string.cheats_filter), onSearch = {})
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.cheats_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (state.enabled.isNotEmpty()) TextButton(onClick = session::disableAll) { Text(stringResource(R.string.cheats_disable_all)) }
            }
        }
        if (!session.loading && state.cheats.isEmpty() && session.error == null) item {
            Text(
                stringResource(if (state.file == null) R.string.cheats_not_found else R.string.cheats_empty_file),
                style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
            )
        }
        items(shown, key = { "${it.custom}:${it.code}:${it.description}" }) { cheat -> CheatRow(cheat, session) }
        item {
            GhostButton(stringResource(R.string.cheats_add_code), { adding = true }, icon = Icons.Rounded.Add, tint = Palette.Cyan)
        }
    }

    if (adding) AddCheatDialog(onAdd = { d, c -> session.addCustom(d, c); adding = false }, onDismiss = { adding = false })
}

@Composable
private fun CheatRow(cheat: Cheat, session: CheatSession) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)
            .clickable { session.toggle(cheat) }.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(cheat.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(cheat.code, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (cheat.custom) Badge(stringResource(R.string.cheats_custom), Palette.Violet)
            }
        }
        if (cheat.custom) {
            IconButton(onClick = { session.remove(cheat) }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_remove), tint = Palette.TextSecondary) }
        }
        Switch(checked = cheat.enabled, onCheckedChange = { session.toggle(cheat) })
    }
}

/** Troca de arquivo: primeiro as sugestões para o jogo; digitando, a busca em todos os arquivos do console. */
@Composable
private fun FilePicker(session: CheatSession, onDone: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<String>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.isBlank()) { results = null; return@LaunchedEffect }
        delay(250)
        failed = false
        results = runCatching { session.search(query) }.onFailure { failed = true }.getOrDefault(emptyList())
    }
    val list = results ?: session.suggestions
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.cheats_pick_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDone) { Text(stringResource(R.string.common_cancel)) }
                }
                SearchField(query, { query = it }, stringResource(R.string.cheats_pick_search), onSearch = {})
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(if (results == null) R.string.cheats_pick_suggestions else R.string.cheats_pick_results),
                    style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
                )
            }
        }
        if (failed) item { Text(stringResource(R.string.cheats_pick_failed), style = MaterialTheme.typography.bodySmall, color = Palette.Coral) }
        if (list.isEmpty()) item {
            Text(stringResource(R.string.cheats_pick_nothing), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        items(list, key = { it }) { file ->
            Text(
                file.removeSuffix(".cht"),
                style = MaterialTheme.typography.bodyMedium,
                color = if (file == session.state.file) Palette.Neon else Palette.TextPrimary,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.SurfaceHigh)
                    .clickable { session.choose(file); onDone() }.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun AddCheatDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
    var description by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.cheats_add_code)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(description, { description = it }, label = { Text(stringResource(R.string.cheats_add_description)) }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.cheats_add_code_label)) })
                Text(stringResource(R.string.cheats_add_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(description, code) }, enabled = code.isNotBlank()) { Text(stringResource(R.string.cheats_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        containerColor = Palette.SurfaceHigh,
    )
}
