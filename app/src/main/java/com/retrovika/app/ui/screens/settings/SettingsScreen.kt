package com.retrovika.app.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.ui.res.pluralStringResource
import com.retrovika.app.R
import com.retrovika.app.core.settings.AppLanguage
import com.retrovika.app.core.settings.Languages
import androidx.compose.ui.res.stringResource
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import com.retrovika.app.container
import com.retrovika.app.core.settings.ShaderOption
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.storage.sizeRecursive
import com.retrovika.app.ui.components.BrandMark
import com.retrovika.app.ui.components.ChipStrip
import com.retrovika.app.ui.components.IconTile
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.Wordmark
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(onAddFolder: () -> Unit, onOpenCores: () -> Unit, onOpenBios: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val repo = app.settings
    val scope = rememberCoroutineScope()
    val s by repo.cached.collectAsStateWithLifecycle()
    val scan by app.library.scan.collectAsStateWithLifecycle()
    val counts by app.library.counts.collectAsStateWithLifecycle()
    var storageVersion by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    // Mostra na hora o último cálculo e atualiza em segundo plano: somar a pasta de ROMs demora.
    val storage by produceState(lastStorage, storageVersion) {
        value = withContext(Dispatchers.IO) {
            StorageUsage(
                games = app.paths.roms.sizeRecursive(),
                saves = app.paths.saves.sizeRecursive() + app.paths.states.sizeRecursive(),
                system = app.paths.system.sizeRecursive() + app.paths.cores.sizeRecursive(),
                cache = context.cacheDir.sizeRecursive(),
            )
        }.also { lastStorage = it }
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }

    LazyColumn(
        Modifier.fillMaxSize().ambientGlow(secondary = Palette.Cyan),
        contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp),
    ) {
        item { ScreenHeader(stringResource(R.string.settings_title), subtitle = stringResource(R.string.settings_subtitle)) }

        item { AboutCard(version, counts.sumOf { it.count }, counts.size) }

        group(R.string.settings_group_language) { LanguagePicker() }

        group(R.string.settings_group_library) {
            Text(
                stringResource(R.string.settings_folders_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
            )
            s.linkedFolders.forEach { uri ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.Folder, Palette.Sun, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        Uri.decode(uri).substringAfterLast("tree/").replace("primary:", stringResource(R.string.settings_folder_internal)),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        // Varreduras no escopo do app: trocar de aba no meio não as interrompe.
                        app.scope.launch {
                            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            repo.removeFolder(uri)
                            app.library.rescan()
                        }
                    }) { Icon(Icons.Rounded.Close, stringResource(R.string.settings_remove_folder), tint = Palette.TextMuted) }
                }
            }
            NavRow(Icons.Rounded.CreateNewFolder, Palette.Neon, stringResource(R.string.common_link_folder), stringResource(R.string.settings_link_folder_subtitle), onClick = onAddFolder)
            RowDivider()
            NavRow(
                Icons.Rounded.Refresh, Palette.Cyan, stringResource(R.string.settings_refresh_library),
                if (scan.running) stringResource(R.string.settings_refresh_running, scan.found) else stringResource(R.string.settings_refresh_subtitle),
                trailing = if (scan.running) {
                    { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Cyan) }
                } else null,
            ) { if (!scan.running) app.scope.launch { app.library.rescan() } }
            if (s.hiddenGames.isNotEmpty()) {
                RowDivider()
                NavRow(
                    Icons.Rounded.VisibilityOff, Palette.Violet,
                    stringResource(R.string.settings_hidden_games, s.hiddenGames.size),
                    stringResource(R.string.settings_hidden_games_subtitle),
                ) { app.scope.launch { app.library.unhideAll() } }
            }
        }

        group(R.string.settings_group_emulators) {
            NavRow(Icons.Rounded.Memory, Palette.Cyan, stringResource(R.string.settings_cores), stringResource(R.string.settings_cores_subtitle), onClick = onOpenCores)
            RowDivider()
            NavRow(Icons.Rounded.SdCard, Palette.Sun, stringResource(R.string.settings_bios), stringResource(R.string.settings_bios_subtitle), onClick = onOpenBios)
        }

        group(R.string.settings_group_pad) {
            // O valor arrastado fica na tela e só é gravado ao soltar: gravar no DataStore a cada quadro
            // do arraste enfileirava dezenas de escritas por segundo e o controle travava.
            var opacity by remember(s.padOpacity) { mutableFloatStateOf(s.padOpacity) }
            var padScale by remember(s.padScale) { mutableFloatStateOf(s.padScale) }
            PadPreview(opacity, padScale)
            SliderRow(stringResource(R.string.settings_pad_opacity), opacity, 0.2f..1f, onChange = { opacity = it }) {
                scope.launch { repo.setPadOpacity(opacity) }
            }
            SliderRow(stringResource(R.string.settings_pad_size), padScale, 0.7f..1.4f, onChange = { padScale = it }) {
                scope.launch { repo.setPadScale(padScale) }
            }
            RowDivider()
            SwitchRow(Icons.Rounded.Vibration, Palette.Neon, stringResource(R.string.settings_haptics), stringResource(R.string.settings_haptics_subtitle), s.haptics) { scope.launch { repo.setHaptics(it) } }
            RowDivider()
            SwitchRow(Icons.Rounded.Gamepad, Palette.Violet, stringResource(R.string.settings_hide_pad), stringResource(R.string.settings_hide_pad_subtitle), s.hidePadWithController) { scope.launch { repo.setHidePadWithController(it) } }
        }

        group(R.string.settings_group_emulation) {
            SwitchRow(Icons.Rounded.Save, Palette.Success, stringResource(R.string.settings_auto_save), stringResource(R.string.settings_auto_save_subtitle), s.autoSave) { scope.launch { repo.setAutoSave(it) } }
            RowDivider()
            SwitchRow(Icons.Rounded.AutoMode, Palette.Cyan, stringResource(R.string.settings_auto_load), stringResource(R.string.settings_auto_load_subtitle), s.autoLoad) { scope.launch { repo.setAutoLoad(it) } }
            RowDivider()
            SwitchRow(Icons.Rounded.GraphicEq, Palette.Sun, stringResource(R.string.settings_low_latency), stringResource(R.string.settings_low_latency_subtitle), s.lowLatencyAudio) { scope.launch { repo.setLowLatencyAudio(it) } }
            RowDivider()
            ChipRow(Icons.Rounded.FastForward, Palette.Orange, stringResource(R.string.settings_fast_forward), listOf(2, 3, 4, 6), s.fastForwardSpeed, { "${it}×" }) { scope.launch { repo.setFastForwardSpeed(it) } }
            RowDivider()
            ChipRow(Icons.Rounded.Tv, Palette.Neon, stringResource(R.string.settings_default_shader), ShaderOption.entries, s.shader, { stringResource(it.label) }) { scope.launch { repo.setShader(it) } }
        }

        group(R.string.settings_group_storage) {
            StorageBar(storage)
            Text(
                app.paths.root.absolutePath,
                style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            RowDivider()
            NavRow(
                Icons.Rounded.DeleteSweep, Palette.Coral, stringResource(R.string.settings_clear_cache),
                storage?.let { stringResource(R.string.settings_clear_cache_subtitle_size, it.cache.formatBytes()) }
                    ?: stringResource(R.string.settings_clear_cache_subtitle),
            ) {
                val cleared = context.getString(R.string.settings_cache_cleared)
                scope.launch {
                    val loader = SingletonImageLoader.get(context)
                    loader.memoryCache?.clear()
                    withContext(Dispatchers.IO) {
                        loader.diskCache?.clear()
                        // Só arquivos temporários de downloads que não estão em andamento.
                        if (app.downloads.tasks.value.none { it.status in com.retrovika.app.core.catalog.DownloadManager.ACTIVE }) {
                            app.paths.downloadsTmp.listFiles()?.forEach { it.deleteRecursively() }
                        }
                    }
                    storageVersion++
                    Toast.makeText(context, cleared, Toast.LENGTH_SHORT).show()
                }
            }
        }

        group(R.string.settings_group_advanced) {
            NavRow(Icons.Rounded.RestartAlt, Palette.Coral, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_subtitle)) { confirmReset = true }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Wordmark(fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.settings_footer_version, version), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                Text(stringResource(R.string.settings_footer_tagline), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Rounded.RestartAlt, null, tint = Palette.Coral) },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = {
                Text(stringResource(R.string.settings_reset_message))
            },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; scope.launch { repo.resetPreferences() } }) { Text(stringResource(R.string.settings_reset_confirm), color = Palette.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.common_cancel)) } },
            containerColor = Palette.SurfaceHigh,
        )
    }
}

/** Último uso de armazenamento calculado, guardado entre as visitas à aba. */
private var lastStorage: StorageUsage? = null

private data class StorageUsage(val games: Long, val saves: Long, val system: Long, val cache: Long) {
    val total get() = games + saves + system + cache
}

/** Seção de ajustes: rótulo pixelado e um cartão agrupando as linhas. */
private fun LazyListScope.group(@StringRes title: Int, content: @Composable ColumnScope.() -> Unit) {
    item(key = "group-$title") {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 26.dp)) {
            Kicker(stringResource(title), color = Palette.TextSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Palette.SurfaceHigh.copy(alpha = 0.85f))
                    .border(1.dp, Palette.Outline.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                    .animateContentSize()
                    .padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun RowDivider() = HorizontalDivider(Modifier.padding(start = 64.dp), color = Palette.Outline.copy(alpha = 0.5f))

/** Cartão de apresentação: logo, versão e números da coleção. */
@Composable
private fun AboutCard(version: String, games: Int, systems: Int) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 18.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF3A1060), Color(0xFF1B152E), Color(0xFF10263A))))
            .border(1.dp, Brush.linearGradient(listOf(Palette.Neon.copy(alpha = 0.5f), Palette.Cyan.copy(alpha = 0.3f))), shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(60.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Wordmark(fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.settings_version, version), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            Spacer(Modifier.height(8.dp))
            Text(
                pluralStringResource(R.plurals.games_count, games, games) + " · " + pluralStringResource(R.plurals.consoles_count, systems, systems),
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun NavRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Spacer(Modifier.width(8.dp))
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextMuted)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, tint: Color, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Palette.Neon, checkedThumbColor = Color.White,
                uncheckedTrackColor = Palette.SurfaceHighest, uncheckedBorderColor = Palette.Outline, uncheckedThumbColor = Palette.TextMuted,
            ),
        )
    }
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit, onDone: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(
                "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium, color = Palette.Neon,
                modifier = Modifier.background(Palette.Neon.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Palette.Neon, activeTrackColor = Palette.Neon, inactiveTrackColor = Palette.SurfaceHighest),
        )
    }
}

@Composable
private fun <T> ChipRow(icon: ImageVector, tint: Color, title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        ChipStrip(contentPadding = PaddingValues(start = 64.dp, end = 16.dp)) {
            options.forEach { o -> SelectChip(label(o), o == selected, onClick = { onSelect(o) }) }
        }
    }
}

/** Miniatura de uma tela deitada com o controle virtual, refletindo opacidade e tamanho em tempo real. */
@Composable
private fun PadPreview(opacity: Float, scale: Float) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .height(130.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A0C52), Color(0xFF5A1466), Color(0xFF1C0736))))
            .border(1.dp, Palette.Outline, shape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val h = size.height
            // Cena de fundo (sol e horizonte), para dar noção da transparência.
            drawCircle(
                Brush.verticalGradient(listOf(Palette.Sun, Palette.Neon), startY = h * 0.35f, endY = h * 0.75f),
                radius = h * 0.32f, center = Offset(size.width / 2, h * 0.72f),
            )
            drawRect(Color(0xFF12071F), topLeft = Offset(0f, h * 0.72f), size = Size(size.width, h * 0.28f))

            val a = opacity.coerceIn(0f, 1f)
            val unit = h * 0.1f * scale
            val white = Color.White.copy(alpha = 0.85f * a)
            val body = Color(0xFF0B0714).copy(alpha = 0.55f * a)
            val dc = Offset(h * 0.55f, h * 0.55f)
            listOf(Offset(0f, 0f), Offset(-1f, 0f), Offset(1f, 0f), Offset(0f, -1f), Offset(0f, 1f)).forEach { d ->
                val topLeft = Offset(dc.x + d.x * unit - unit / 2, dc.y + d.y * unit - unit / 2)
                drawRoundRect(body, topLeft = topLeft, size = Size(unit, unit), cornerRadius = CornerRadius(unit * 0.25f))
                drawRoundRect(white, topLeft = topLeft, size = Size(unit, unit), cornerRadius = CornerRadius(unit * 0.25f), style = Stroke(1.5f))
            }
            val fc = Offset(size.width - h * 0.55f, h * 0.55f)
            val faceColors = listOf(Palette.Cyan, Palette.Neon, Palette.Sun, Palette.Success)
            listOf(Offset(0f, -1f), Offset(1f, 0f), Offset(0f, 1f), Offset(-1f, 0f)).forEachIndexed { i, d ->
                val c = Offset(fc.x + d.x * unit * 1.15f, fc.y + d.y * unit * 1.15f)
                drawCircle(body, radius = unit * 0.55f, center = c)
                drawCircle(faceColors[i].copy(alpha = a), radius = unit * 0.55f, center = c, style = Stroke(2f))
            }
        }
        Kicker(stringResource(R.string.settings_pad_preview), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp))
    }
}

/** Barra empilhada com o espaço ocupado por jogos, saves, sistema e cache. */
@Composable
private fun StorageBar(usage: StorageUsage?) {
    Column(Modifier.padding(16.dp)) {
        if (usage == null) {
            Text(stringResource(R.string.settings_storage_calculating), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(usage.total.formatBytes(), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_storage_in_use), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.height(12.dp))
        val parts = listOf(
            Triple(stringResource(R.string.settings_storage_games), usage.games, Palette.Neon),
            Triple(stringResource(R.string.settings_storage_saves), usage.saves, Palette.Cyan),
            Triple(stringResource(R.string.settings_storage_system), usage.system, Palette.Sun),
            Triple(stringResource(R.string.settings_storage_cache), usage.cache, Palette.Violet),
        )
        val total = usage.total.coerceAtLeast(1).toFloat()
        Row(
            Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Palette.SurfaceHighest),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            parts.forEach { (_, bytes, color) ->
                val f = bytes / total
                if (f > 0.004f) Box(Modifier.weight(f).fillMaxSize().background(color))
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            parts.forEach { (label, bytes, color) ->
                Row {
                    Box(Modifier.padding(top = 4.dp).size(8.dp).clip(RoundedCornerShape(50)).background(color))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                        Text(bytes.formatBytes(), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/** Seletor de idioma: automático (sistema), português ou inglês. Os nomes dos idiomas não se traduzem. */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val current = remember { Languages.current(context) }
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(Icons.Rounded.Translate, Palette.Cyan, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.settings_language_title), style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(10.dp))
        // FlowRow: as três opções quebram de linha em vez de sumir pela borda.
        FlowRow(
            Modifier.padding(start = 64.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                AppLanguage.SYSTEM to stringResource(R.string.settings_language_system),
                AppLanguage.PORTUGUESE to stringResource(R.string.settings_language_pt),
                AppLanguage.ENGLISH to stringResource(R.string.settings_language_en),
            ).forEach { (language, label) ->
                SelectChip(label, language == current, onClick = { context.findActivity()?.let { Languages.set(it, language) } })
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
