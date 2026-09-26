package com.retrovika.app.emulation

import androidx.annotation.StringRes
import com.retrovika.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.settings.AppSettings
import com.retrovika.app.core.settings.ShaderOption
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.emulation.input.PadListener
import com.retrovika.app.emulation.input.VirtualGamepad
import com.retrovika.app.ui.components.BrandMark
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.theme.Palette
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

@Composable
fun GameScreen(
    state: EmulationUi,
    game: Game?,
    system: GameSystem?,
    settings: AppSettings,
    menuOpen: Boolean,
    fastForward: Boolean,
    showPad: Boolean,
    toast: String?,
    padListener: PadListener,
    menu: MenuActions,
    onDismissToast: () -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides Palette.TextPrimary) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (state) {
            is EmulationUi.Preparing -> PreparingView(state, game, system)
            is EmulationUi.Failed -> FailedView(state, onExit = menu::exit)
            is EmulationUi.Running -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val portrait = maxHeight > maxWidth
                // A estrutura é sempre a mesma (vídeo + controle) para o GLRetroView nunca ser recriado ao girar a tela.
                AndroidView(
                    factory = { state.view },
                    modifier = if (portrait) Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.TopCenter) else Modifier.fillMaxSize(),
                )
                if (showPad && system != null) {
                    VirtualGamepad(
                        layout = system.layout,
                        listener = padListener,
                        opacity = if (portrait) 1f else settings.padOpacity,
                        scale = settings.padScale,
                        haptics = settings.haptics,
                        portrait = portrait,
                        modifier = if (portrait) {
                            Modifier.fillMaxWidth().fillMaxHeight(0.45f).align(Alignment.BottomCenter).padding(top = 48.dp, bottom = 16.dp)
                        } else Modifier.fillMaxSize(),
                    )
                }
                Hud(
                    fastForward = fastForward,
                    onMenu = menu::open,
                    onFastForward = menu::toggleFastForward,
                    modifier = if (portrait) {
                        Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.55f + 4.dp)
                    } else Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                    vertical = false,
                )
            }
        }

        AnimatedVisibility(visible = menuOpen, enter = fadeIn(), exit = fadeOut()) {
            PauseMenu(game, system, menu, fastForward, settings.shader)
        }

        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp),
        ) {
            Text(
                toast.orEmpty(),
                color = Palette.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Palette.SurfaceHighest.copy(alpha = 0.95f), RoundedCornerShape(50))
                    .border(1.dp, Palette.Outline, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        LaunchedEffect(toast) { if (toast != null) { delay(2200); onDismissToast() } }
    }
    }
}

@Composable
private fun Hud(fastForward: Boolean, onMenu: () -> Unit, onFastForward: () -> Unit, modifier: Modifier, vertical: Boolean) {
    val content: @Composable () -> Unit = {
        HudButton(Icons.Rounded.Menu, stringResource(R.string.game_menu), false, onMenu)
        HudButton(Icons.Rounded.FastForward, stringResource(R.string.game_fast_forward), fastForward, onFastForward)
    }
    if (vertical) Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    else Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun HudButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Palette.Neon.copy(alpha = 0.85f) else Color(0x40FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (active) Color.Black else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun PreparingView(state: EmulationUi.Preparing, game: Game?, system: GameSystem?) {
    Column(
        Modifier.fillMaxSize().background(Palette.Ink).ambientGlow(primary = system?.let { Color(it.accent) } ?: Palette.Neon).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // O logo "respira" enquanto o núcleo e o jogo carregam.
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            0.92f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale",
        )
        BrandMark(72.dp, Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse })
        Spacer(Modifier.height(20.dp))
        system?.let {
            Kicker(it.shortName, color = Color(it.accent).copy(alpha = 0.9f).compositeOverWhite())
            Spacer(Modifier.height(10.dp))
        }
        Text(game?.title ?: "", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        val p = state.progress
        val bar = Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(50))
        if (p != null && p >= 0f) LinearProgressIndicator(progress = { p }, modifier = bar, color = Palette.Neon, trackColor = Palette.SurfaceHighest)
        else LinearProgressIndicator(modifier = bar, color = Palette.Neon, trackColor = Palette.SurfaceHighest)
        Spacer(Modifier.height(12.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    }
}

/** Garante contraste para cores de sistema muito escuras (ex.: Mega Drive preto). */
private fun Color.compositeOverWhite(): Color =
    if ((red + green + blue) / 3f < 0.35f) Color(0xFFB8BFD0) else this

@Composable
private fun FailedView(state: EmulationUi.Failed, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Ink).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Coral, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        Spacer(Modifier.height(24.dp))
        GradientButton(stringResource(R.string.game_back_to_library), onExit)
    }
}

private enum class MenuTab(@StringRes val label: Int) { STATES(R.string.game_tab_states), OPTIONS(R.string.game_tab_game), CORE(R.string.game_tab_core) }

@Composable
private fun PauseMenu(game: Game?, system: GameSystem?, menu: MenuActions, fastForward: Boolean, shader: ShaderOption) {
    var tab by remember { mutableStateOf(MenuTab.STATES) }
    var refresh by remember { mutableIntStateOf(0) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xF02A0C52), Color(0xF00B0714), Color(0xF50B0714))))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker(listOfNotNull(system?.shortName, menu.coreName().takeIf { it.isNotBlank() }).joinToString(" · "))
                    Spacer(Modifier.height(8.dp))
                    Text(game?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(12.dp))
                GhostButton(stringResource(R.string.game_exit), menu::exit, icon = Icons.AutoMirrored.Rounded.ExitToApp)
                Spacer(Modifier.width(10.dp))
                GradientButton(stringResource(R.string.common_continue), menu::close, icon = Icons.Rounded.PlayArrow, height = 44.dp)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MenuTab.entries.forEach { t -> SelectChip(stringResource(t.label), tab == t, onClick = { tab = t }) }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    MenuTab.STATES -> StatesTab(menu, refresh) { refresh++ }
                    MenuTab.OPTIONS -> OptionsTab(menu, fastForward, shader)
                    MenuTab.CORE -> CoreTab(menu)
                }
            }
        }
    }
}

@Composable
private fun StatesTab(menu: MenuActions, refresh: Int, onChanged: () -> Unit) {
    val slots = remember(refresh) { menu.slots() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(slots, key = { it.index }) { slot ->
            val thumb = remember(refresh, slot.index) { menu.thumbnail(slot.index) }
            Column(
                Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(18.dp))
                    .padding(10.dp),
            ) {
                // Em paisagem a altura é curta: a miniatura encolhe para os botões continuarem visíveis.
                Box(
                    Modifier.weight(1f, fill = false).aspectRatio(4f / 3f).align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(12.dp)).background(Palette.Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    if (thumb != null) Image(thumb.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Text(stringResource(if (slot.exists) R.string.game_slot_no_image else R.string.game_slot_empty), color = Palette.TextMuted, style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(if (slot.index == SaveStates.AUTO_SLOT) stringResource(R.string.game_slot_auto) else stringResource(R.string.game_slot_n, slot.index), style = MaterialTheme.typography.titleSmall)
                Text(
                    slot.timestamp?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—",
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (slot.index != SaveStates.AUTO_SLOT) {
                        FilledTonalButton(onClick = { menu.save(slot.index); onChanged() }, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                            Icon(Icons.Rounded.Save, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.game_save))
                        }
                    }
                    OutlinedButton(onClick = { menu.load(slot.index) }, enabled = slot.exists, modifier = Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Icon(Icons.Rounded.Upload, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.game_load))
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionsTab(menu: MenuActions, fastForward: Boolean, shader: ShaderOption) {
    var currentShader by remember { mutableStateOf(shader) }
    val disks = remember { menu.disks() }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingRow(stringResource(R.string.game_fast_forward), stringResource(R.string.game_fast_forward_subtitle)) {
                Switch(checked = fastForward, onCheckedChange = { menu.toggleFastForward() })
            }
        }
        item {
            Column {
                Text(stringResource(R.string.game_video_filter), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShaderOption.entries.forEach { opt ->
                        SelectChip(stringResource(opt.label), currentShader == opt, onClick = { currentShader = opt; menu.setShader(opt) })
                    }
                }
            }
        }
        if (disks.first > 1) item {
            Column {
                Text(stringResource(R.string.game_change_disc), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(disks.first) { i ->
                        SelectChip(stringResource(R.string.game_disc_n, i + 1), disks.second == i, onClick = { menu.changeDisk(i) })
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = menu::reset) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.game_reset))
            }
        }
    }
}

@Composable
private fun CoreTab(menu: MenuActions) {
    var options by remember { mutableStateOf(menu.coreOptions()) }
    if (options.isEmpty()) {
        Text(stringResource(R.string.game_core_no_options), color = Palette.TextSecondary)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                stringResource(R.string.game_core_options_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            )
        }
        items(options, key = { it.key }) { opt ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Palette.SurfaceHigh)
                    .clickable {
                        val next = opt.next()
                        menu.setCoreOption(opt, next)
                        options = options.map { if (it.key == opt.key) it.copy(value = next) else it }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(opt.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(opt.value, style = MaterialTheme.typography.labelMedium, color = Palette.Neon, fontWeight = FontWeight.Bold)
            }
        }
        item { TextButton(onClick = { options = menu.coreOptions() }) { Text(stringResource(R.string.game_refresh_list)) } }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        trailing()
    }
}
