package com.retrovika.app.ui.screens.settings

import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.retrovika.app.R
import com.retrovika.app.core.settings.localized
import com.retrovika.app.ui.components.ScreenMessages
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.retrovika.app.container
import com.retrovika.app.core.bios.BiosCheck
import com.retrovika.app.core.bios.BiosStatus
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.core.bios.BiosManager
import com.retrovika.app.ui.components.Badge
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.ambientGlow
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ReadableWidth
import com.retrovika.app.ui.theme.Palette
import com.retrovika.app.core.net.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun BiosScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val bios = app.bios
    val status: ScreenMessages = viewModel { ScreenMessages() }
    val systems = remember { Systems.all.filter { it.bios.isNotEmpty() } }
    val checks by produceState(emptyMap<GameSystem, List<BiosCheck>>(), status.version) {
        value = systems.associateWith { bios.checkAsync(it) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        // Contexto da aplicação, pego fora da corrotina: a Activity pode ser recriada (rotação) antes da
        // importação terminar, e capturá-la aqui a manteria viva até o fim.
        val res = context.localized()
        app.scope.launch(Dispatchers.Main) {
            // O escopo do app não tem tratador: uma exceção solta aqui derrubaria o processo.
            try {
                status.errors = null
                val found = bios.import(uris)
                status.message = if (found.isEmpty()) res.getString(R.string.bios_ui_none_recognized)
                else res.getString(R.string.bios_ui_imported, found.joinToString())
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                status.message = null
                status.errors = t.userMessage(res)
            }
            status.version++
        }
    }

    ReadableWidth { side ->
    LazyColumn(
        Modifier.fillMaxSize().ambientGlow(primary = Palette.Sun, secondary = Palette.Neon),
        contentPadding = PaddingValues(start = side, end = side, bottom = 32.dp + LocalBottomInset.current),
    ) {
        item {
            val ready = systems.count { sys -> checks[sys].orEmpty().let { c -> c.isNotEmpty() && BiosManager.unsatisfied(sys.bios) { b -> c.any { it.bios == b && it.status == BiosStatus.OK } }.isEmpty() } }
            ScreenHeader(stringResource(R.string.bios_ui_title), subtitle = stringResource(R.string.bios_ui_subtitle, ready, systems.size), onBack = onBack)
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.bios_ui_intro),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            GradientButton(
                stringResource(R.string.bios_ui_import), { picker.launch(arrayOf("*/*")) },
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp), icon = Icons.Rounded.FileOpen,
            )
            status.message?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Success, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) }
            status.errors?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.Coral, modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp)) }
        }
        items(systems, key = { it.id }) { system ->
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.dp, Palette.Outline.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
                    .padding(14.dp),
            ) {
                val list = checks[system].orEmpty()
                val ok = list.isNotEmpty() && BiosManager.unsatisfied(system.bios) { b -> list.any { it.bios == b && it.status == BiosStatus.OK } }.isEmpty()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(system.accentColor()))
                    Spacer(Modifier.width(10.dp))
                    Text(system.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (list.isNotEmpty()) Badge(stringResource(if (ok) R.string.bios_ui_ready else R.string.bios_ui_pending), if (ok) Palette.Success else Palette.Coral)
                }
                checks[system].orEmpty().forEach { check ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (icon, tint) = when (check.status) {
                            BiosStatus.OK -> Icons.Rounded.CheckCircle to Palette.Success
                            BiosStatus.WRONG_HASH, BiosStatus.INVALID -> Icons.Rounded.ErrorOutline to Palette.Coral
                            BiosStatus.MISSING -> Icons.Rounded.RadioButtonUnchecked to (if (check.bios.required) Palette.Coral else Palette.TextMuted)
                        }
                        // O estado não pode depender só da cor e do desenho do ícone: o leitor de tela o anuncia.
                        val statusLabel = stringResource(
                            when (check.status) {
                                BiosStatus.OK -> R.string.bios_ui_status_ok
                                BiosStatus.WRONG_HASH, BiosStatus.INVALID -> R.string.bios_ui_status_wrong
                                BiosStatus.MISSING -> R.string.bios_ui_status_missing
                            },
                        )
                        Icon(icon, statusLabel, tint = tint, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(check.bios.fileName, style = MaterialTheme.typography.labelMedium)
                            Text(
                                stringResource(check.bios.description) + when (check.status) {
                                    BiosStatus.WRONG_HASH -> " · " + stringResource(R.string.bios_ui_wrong_hash)
                                    BiosStatus.INVALID -> " · " + stringResource(R.string.bios_ui_invalid)
                                    else -> ""
                                },
                                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                            )
                        }
                        if (check.bios.required) Text(stringResource(if (check.bios.group != null) R.string.bios_ui_one_of_group else R.string.bios_ui_required), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                    }
                }
            }
        }
    }
    }
}
