package com.retrovika.app.emulation

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.retrovika.app.R
import com.retrovika.app.core.settings.AppSettings
import com.retrovika.app.emulation.input.PadEditor
import com.retrovika.app.emulation.input.PadElement
import com.retrovika.app.emulation.input.PadElementConfig
import com.retrovika.app.emulation.input.PadLayout
import com.retrovika.app.emulation.input.PadListener
import com.retrovika.app.emulation.input.PadProfile
import com.retrovika.app.emulation.input.PortraitMode
import com.retrovika.app.emulation.input.VirtualGamepad
import com.retrovika.app.emulation.input.elements
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.theme.Palette

/**
 * Aba "Controle" do menu de pausa: o controle virtual deste console (vale para todos os jogos dele, em
 * qualquer núcleo). Mostrar/ocultar, tamanho, opacidade, retrato dividido ou sobreposto, botões de menu e o editor
 * de posições.
 */
@Composable
internal fun ControlsTab(menu: MenuActions, profile: PadProfile, settings: AppSettings, systemName: String, hasPad: Boolean) {
    fun update(p: PadProfile) = menu.setPadProfile(p)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text(
                stringResource(R.string.pad_profile_scope, systemName),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            )
        }
        item {
            ToggleRow(
                stringResource(R.string.pad_show), stringResource(R.string.pad_show_subtitle),
                checked = profile.visible, onChange = { update(profile.copy(visible = it)) },
            )
        }
        if (profile.visible && hasPad) {
            item {
                Column {
                    Text(stringResource(R.string.pad_portrait_mode), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.pad_portrait_mode_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PortraitMode.entries.forEach { mode ->
                            SelectChip(stringResource(mode.label), profile.portraitMode == mode, onClick = { update(profile.copy(portraitMode = mode)) })
                        }
                    }
                }
            }
            item {
                ProfileSlider(
                    stringResource(R.string.pad_size), profile.scale, settings.padScale, 0.6f..1.6f,
                    onDone = { update(profile.copy(scale = it)) },
                )
            }
            item {
                ProfileSlider(
                    stringResource(R.string.pad_opacity), profile.opacity, settings.padOpacity, 0.15f..1f,
                    onDone = { update(profile.copy(opacity = it)) },
                )
            }
            item {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.SurfaceHigh)
                        .border(1.dp, Palette.Outline, RoundedCornerShape(18.dp)).padding(14.dp),
                ) {
                    Text(stringResource(R.string.pad_layout), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.pad_layout_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    Spacer(Modifier.height(10.dp))
                    GradientButton(stringResource(R.string.pad_edit_layout), menu::startPadEditor, Modifier.fillMaxWidth(), icon = Icons.Rounded.Edit, height = 44.dp)
                }
            }
        }
        item {
            ToggleRow(
                stringResource(R.string.pad_hud), stringResource(R.string.pad_hud_subtitle),
                checked = profile.showHud, onChange = { update(profile.copy(showHud = it)) },
            )
        }
        if (!profile.isDefault) item {
            TextButton(onClick = { update(PadProfile()) }) {
                Icon(Icons.Rounded.RestartAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.pad_reset_console))
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Tamanho/opacidade do console. Nulo segue Ajustes (mostrado como "Geral"); mexer grava o valor próprio,
 * e "Usar o geral" volta a seguir Ajustes.
 */
@Composable
private fun ProfileSlider(title: String, own: Float?, general: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float?) -> Unit) {
    var value by remember(own, general) { mutableFloatStateOf(own ?: general) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (own != null) {
                Text(
                    stringResource(R.string.pad_use_general), style = MaterialTheme.typography.labelMedium, color = Palette.Cyan,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onDone(null) }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                if (own == null) stringResource(R.string.pad_general_value, (value * 100).toInt()) else "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium, color = Palette.Neon,
                modifier = Modifier.background(Palette.Neon.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = value, onValueChange = { value = it }, onValueChangeFinished = { onDone(value) }, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = Palette.Neon, activeTrackColor = Palette.Neon, inactiveTrackColor = Palette.SurfaceHighest),
        )
    }
}

/**
 * Editor do layout, sobre a última tela do jogo: arrastar move cada parte; tocar numa parte a seleciona
 * para mudar o tamanho ou ocultá-la. Vale para a orientação em uso (retrato e paisagem têm layouts
 * próprios). Nada é gravado até "Salvar"; voltar descarta.
 */
@Composable
internal fun PadLayoutEditor(
    layout: PadLayout,
    profile: PadProfile,
    settings: AppSettings,
    backdrop: Bitmap?,
    onSave: (PadProfile) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(profile) }
    var selected by remember { mutableStateOf<PadElement?>(null) }
    // A barra pode ir para cima ou para baixo, para não cobrir a parte que está sendo ajustada.
    var toolbarOnTop by remember { mutableStateOf(true) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            // Toques no fundo não chegam ao jogo pausado por baixo; tocar fora das partes tira a seleção.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { selected = null },
    ) {
        val portrait = maxHeight > maxWidth
        val overlay = draft.fullScreenVideo(portrait, padShown = true)
        backdrop?.let {
            Image(
                it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = if (overlay) Modifier.fillMaxSize() else Modifier.fillMaxWidth().fillMaxHeight(VIDEO_SPLIT).align(Alignment.TopCenter),
            )
        }
        VirtualGamepad(
            layout = layout,
            listener = NoPad,
            opacity = 1f,
            scale = draft.scale ?: settings.padScale,
            haptics = false,
            portrait = portrait,
            overlay = overlay,
            elements = draft.elements(portrait),
            editor = PadEditor(
                selected = selected,
                onSelect = { selected = it },
                onMove = { element, dx, dy, limits ->
                    val c = draft.element(portrait, element)
                    // O arrasto para nas bordas da tela: a parte inteira fica sempre visível. Partindo do valor já
                    // limitado, arrastar de volta responde na hora (sem "zona morta" do que passou da borda).
                    val x = limits?.clampX(c.dx) ?: c.dx
                    val y = limits?.clampY(c.dy) ?: c.dy
                    draft = draft.withElement(
                        portrait, element,
                        c.copy(dx = limits?.clampX(x + dx) ?: (x + dx), dy = limits?.clampY(y + dy) ?: (y + dy)),
                    )
                },
            ),
            modifier = padModifier(overlay),
        )
        EditorToolbar(
            layout = layout,
            draft = draft,
            portrait = portrait,
            selected = selected,
            onSelect = { selected = it },
            onChange = { draft = it },
            onSave = { onSave(draft) },
            onCancel = onCancel,
            onMoveToolbar = { toolbarOnTop = !toolbarOnTop },
            modifier = Modifier.align(if (toolbarOnTop) Alignment.TopCenter else Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)
                .widthIn(max = if (portrait) 520.dp else 440.dp),
        )
    }
}

private val NoPad = object : PadListener {
    override fun onKey(keyCode: Int, pressed: Boolean) = Unit
    override fun onMotion(source: Int, x: Float, y: Float) = Unit
}

@Composable
private fun EditorToolbar(
    layout: PadLayout,
    draft: PadProfile,
    portrait: Boolean,
    selected: PadElement?,
    onSelect: (PadElement) -> Unit,
    onChange: (PadProfile) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onMoveToolbar: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(Palette.Surface.copy(alpha = 0.94f))
            .border(1.dp, Palette.Outline, shape)
            // A barra não repassa toques ao fundo (que tiraria a seleção).
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Gamepad, null, tint = Palette.Neon, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.pad_editor_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (portrait) R.string.pad_editor_portrait else R.string.pad_editor_landscape),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary,
                )
            }
            IconButton(onClick = onMoveToolbar) {
                Icon(Icons.Rounded.SwapVert, stringResource(R.string.pad_move_toolbar), tint = Palette.TextSecondary)
            }
            TextButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.common_cancel))
            }
        }
        Spacer(Modifier.height(8.dp))
        // Todas as partes do layout, inclusive as ocultas (que não dá para tocar na tela).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            layout.elements().forEach { element ->
                val hidden = draft.element(portrait, element).hidden
                SelectChip(
                    stringResource(element.label) + if (hidden) " · " + stringResource(R.string.pad_hidden_tag) else "",
                    selected == element, onClick = { onSelect(element) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (selected == null) {
            Text(stringResource(R.string.pad_editor_hint), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        } else {
            val config = draft.element(portrait, selected)
            fun set(c: PadElementConfig) = onChange(draft.withElement(portrait, selected, c))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pad_element_size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
                Slider(
                    value = config.scale, onValueChange = { set(config.copy(scale = it)) }, valueRange = 0.5f..2f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Neon, activeTrackColor = Palette.Neon, inactiveTrackColor = Palette.SurfaceHighest),
                )
                Text("${(config.scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = Palette.Neon, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton(
                    stringResource(if (config.hidden) R.string.pad_element_show else R.string.pad_element_hide),
                    { set(config.copy(hidden = !config.hidden)) },
                    icon = if (config.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                )
                if (!config.isDefault) {
                    GhostButton(stringResource(R.string.pad_element_reset), { set(PadElementConfig()) }, icon = Icons.Rounded.RestartAlt)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (draft.elements(portrait).isNotEmpty()) {
                GhostButton(stringResource(R.string.pad_reset_layout), { onChange(draft.resetLayout(portrait)) }, icon = Icons.Rounded.RestartAlt)
            }
            Spacer(Modifier.weight(1f))
            GradientButton(stringResource(R.string.pad_save), onSave, icon = Icons.Rounded.Check, height = 44.dp)
        }
    }
}
