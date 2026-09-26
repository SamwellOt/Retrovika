package com.retrovika.app.emulation.input

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Recebe os eventos do controle virtual; implementado pela tela de emulação. */
interface PadListener {
    fun onKey(keyCode: Int, pressed: Boolean)
    /** [source] é uma das constantes MOTION_SOURCE_* do GLRetroView. Y positivo = para baixo. */
    fun onMotion(source: Int, x: Float, y: Float)
}

object MotionSources {
    const val DPAD = 0
    const val ANALOG_LEFT = 1
    const val ANALOG_RIGHT = 2
}

private val PadSurface = Color(0x33FFFFFF)
private val PadStroke = Color(0x66FFFFFF)
private val PadPressed = Color(0x99FFFFFF)

/**
 * Controle virtual desenhado em Compose. Cada controle possui seu próprio pointerInput,
 * então vários dedos funcionam ao mesmo tempo, e toques fora dos botões chegam ao jogo
 * (necessário para a tela de toque do Nintendo DS).
 *
 * Em paisagem o controle fica sobreposto ao jogo; em retrato ocupa a metade inferior.
 * Nos dois casos a escala é reduzida automaticamente para caber no espaço disponível.
 */
@Composable
fun VirtualGamepad(
    layout: PadLayout,
    listener: PadListener,
    opacity: Float,
    scale: Float,
    haptics: Boolean,
    portrait: Boolean,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val feedback: () -> Unit = { if (haptics) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }

    BoxWithConstraints(modifier.alpha(opacity)) {
        val size = PadMetrics(layout)
        val fit = if (portrait) {
            min(maxWidth.value / size.portraitWidth, maxHeight.value / size.portraitHeight)
        } else {
            maxHeight.value / size.landscapeHeight
        }
        val s = (scale * fit.coerceAtMost(1.15f)).coerceIn(0.5f, 1.5f)

        if (portrait) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (size.hasShoulders) Row(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { layout.leftShoulders.forEach { ShoulderButton(it, s, listener, feedback) } }
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { layout.rightShoulders.asReversed().forEach { ShoulderButton(it, s, listener, feedback) } }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    LeftCluster(layout, s, listener, feedback, Modifier.align(Alignment.CenterStart))
                    RightCluster(layout, s, listener, feedback, Modifier.align(Alignment.CenterEnd))
                }
                CenterButtons(layout, s, listener, feedback, Modifier.fillMaxWidth())
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.align(Alignment.TopStart).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    layout.leftShoulders.forEach { ShoulderButton(it, s, listener, feedback) }
                }
                Row(Modifier.align(Alignment.TopEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    layout.rightShoulders.asReversed().forEach { ShoulderButton(it, s, listener, feedback) }
                }
                LeftCluster(layout, s, listener, feedback, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 20.dp))
                RightCluster(layout, s, listener, feedback, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 20.dp))
                CenterButtons(layout, s, listener, feedback, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            }
        }
    }
}

/** Tamanhos nominais (em dp, escala 1) usados para calcular quanto o controle precisa encolher. */
private class PadMetrics(layout: PadLayout) {
    val hasShoulders = layout.leftShoulders.isNotEmpty() || layout.rightShoulders.isNotEmpty()
    private val leftW = if (layout.leftStick && !layout.dpad) 150f else 160f
    private val leftH = when {
        layout.leftStick && layout.dpad -> 252f
        layout.leftStick -> 150f
        else -> 160f
    }
    private val faceW = when (layout.arrangement) {
        FaceArrangement.DIAMOND -> 180f; FaceArrangement.TWO_DIAGONAL -> 162f; FaceArrangement.THREE_ARC -> 224f
        FaceArrangement.SIX_GRID -> 205f; FaceArrangement.N64 -> 162f; FaceArrangement.SINGLE -> 87f
    }
    private val faceH = when (layout.arrangement) {
        FaceArrangement.DIAMOND -> 180f; FaceArrangement.TWO_DIAGONAL -> 124f; FaceArrangement.THREE_ARC -> 112f
        FaceArrangement.SIX_GRID -> 120f; FaceArrangement.N64 -> 137f; FaceArrangement.SINGLE -> 87f
    }
    private val rightH = faceH + (if (layout.cButtons) 132f else 0f) + (if (layout.rightStick) 132f else 0f)
    private val clusterH = maxOf(leftH, rightH)
    private val chromeH = (if (hasShoulders) 54f else 0f) + centerRows(layout) * 40f

    val portraitWidth = leftW + faceW + 56f
    val portraitHeight = clusterH + chromeH + 16f
    val landscapeHeight = clusterH + (if (hasShoulders) 76f else 0f) + 40f
}

/** Botões centrais em fileiras de dois: com quatro numa linha só (PS2, computadores) eles invadiriam o D-pad. */
private fun centerRows(layout: PadLayout) = (layout.center.size + 1) / 2

@Composable
private fun CenterButtons(layout: PadLayout, s: Float, listener: PadListener, feedback: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // A fileira principal (SELECT/START) fica embaixo, mais perto dos polegares.
        layout.center.chunked(2).asReversed().forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { row.forEach { PillButton(it, s, listener, feedback) } }
        }
    }
}

@Composable
private fun LeftCluster(layout: PadLayout, s: Float, listener: PadListener, feedback: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (layout.leftStick && layout.dpad) {
            DPad((110 * s).dp, listener, feedback)
            AnalogStick((130 * s).dp, MotionSources.ANALOG_LEFT, listener)
        } else if (layout.leftStick) {
            AnalogStick((150 * s).dp, MotionSources.ANALOG_LEFT, listener)
        } else {
            DPad((160 * s).dp, listener, feedback)
        }
    }
}

@Composable
private fun RightCluster(layout: PadLayout, s: Float, listener: PadListener, feedback: () -> Unit, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (layout.cButtons) CButtons((120 * s).dp, listener, feedback)
        FaceCluster(layout, s, listener, feedback)
        if (layout.rightStick) AnalogStick((120 * s).dp, MotionSources.ANALOG_RIGHT, listener)
    }
}

@Composable
private fun FaceCluster(layout: PadLayout, s: Float, listener: PadListener, feedback: () -> Unit) {
    val btn = (62 * s).dp
    when (layout.arrangement) {
        FaceArrangement.DIAMOND -> Box(Modifier.size(btn * 2.9f)) {
            val (top, right, bottom, left) = layout.face
            FaceButton(top, btn, listener, feedback, Modifier.align(Alignment.TopCenter))
            FaceButton(right, btn, listener, feedback, Modifier.align(Alignment.CenterEnd))
            FaceButton(bottom, btn, listener, feedback, Modifier.align(Alignment.BottomCenter))
            FaceButton(left, btn, listener, feedback, Modifier.align(Alignment.CenterStart))
        }
        FaceArrangement.TWO_DIAGONAL -> Box(Modifier.size(width = btn * 2.6f, height = btn * 2f)) {
            FaceButton(layout.face[0], btn * 1.12f, listener, feedback, Modifier.align(Alignment.BottomStart))
            FaceButton(layout.face[1], btn * 1.12f, listener, feedback, Modifier.align(Alignment.TopEnd))
        }
        FaceArrangement.THREE_ARC -> Box(Modifier.size(width = btn * 3.6f, height = btn * 1.8f)) {
            FaceButton(layout.face[0], btn, listener, feedback, Modifier.align(Alignment.BottomStart))
            FaceButton(layout.face[1], btn, listener, feedback, Modifier.align(Alignment.Center).offset(y = btn * 0.1f))
            FaceButton(layout.face[2], btn, listener, feedback, Modifier.align(Alignment.TopEnd))
        }
        FaceArrangement.SIX_GRID -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(layout.face.take(3), layout.face.drop(3)).forEachIndexed { row, buttons ->
                Row(
                    Modifier.offset(x = if (row == 0) btn * 0.3f else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) { buttons.forEach { FaceButton(it, btn * 0.9f, listener, feedback) } }
            }
        }
        FaceArrangement.N64 -> Box(Modifier.size(width = btn * 2.6f, height = btn * 2.2f)) {
            FaceButton(layout.face[0], btn, listener, feedback, Modifier.align(Alignment.TopStart))
            FaceButton(layout.face[1], btn * 1.2f, listener, feedback, Modifier.align(Alignment.BottomEnd))
        }
        FaceArrangement.SINGLE -> FaceButton(layout.face[0], btn * 1.4f, listener, feedback)
    }
}

/** Modificador de botão: pressiona no toque, solta ao levantar o dedo ou cancelar. */
private fun Modifier.pressable(onChange: (Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        onChange(true)
        try {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach(PointerInputChange::consume)
                if (event.changes.none { it.pressed }) break
            }
        } finally {
            onChange(false)
        }
    }
}

@Composable
private fun FaceButton(button: PadButton, size: Dp, listener: PadListener, feedback: () -> Unit, modifier: Modifier = Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val base = button.color?.let { Color(it) } ?: Color(0xFF424242)
    Box(
        modifier
            .size(size)
            .background(if (pressed) base.copy(alpha = 0.95f) else base.copy(alpha = 0.55f), CircleShape)
            .border(2.dp, if (pressed) PadPressed else PadStroke, CircleShape)
            .pressable { down ->
                pressed = down
                if (down) feedback()
                listener.onKey(button.keyCode, down)
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(button.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
    }
}

@Composable
private fun ShoulderButton(button: PadButton, s: Float, listener: PadListener, feedback: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(width = (84 * s).dp, height = (44 * s).dp)
            .background(if (pressed) PadPressed else PadSurface, shape)
            .border(1.5.dp, PadStroke, shape)
            .pressable { down ->
                pressed = down
                if (down) feedback()
                listener.onKey(button.keyCode, down)
            },
        contentAlignment = Alignment.Center,
    ) { Text(button.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (15 * s).sp) }
}

@Composable
private fun PillButton(button: PadButton, s: Float, listener: PadListener, feedback: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(width = (74 * s).dp, height = (28 * s).dp)
            .background(if (pressed) PadPressed else PadSurface, shape)
            .border(1.dp, PadStroke, shape)
            .pressable { down ->
                pressed = down
                if (down) feedback()
                listener.onKey(button.keyCode, down)
            },
        contentAlignment = Alignment.Center,
    ) { Text(button.label, color = Color.White, fontSize = (10 * s).sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp) }
}

/** D-pad de 8 direções com zona morta central; deslizar o dedo muda a direção sem soltar. */
@Composable
private fun DPad(size: Dp, listener: PadListener, feedback: () -> Unit) {
    var direction by remember { mutableStateOf(0 to 0) }

    fun update(position: Offset, sizePx: Float) {
        val cx = sizePx / 2
        val dx = position.x - cx
        val dy = position.y - cx
        val dist = hypot(dx, dy)
        val next = if (dist < sizePx * 0.12f) 0 to 0 else {
            val sector = ((atan2(dy, dx) / (PI / 4)).roundToInt() + 8) % 8
            DIRS[sector]
        }
        if (next != direction) {
            if (next != (0 to 0)) feedback()
            direction = next
            listener.onMotion(MotionSources.DPAD, next.first.toFloat(), next.second.toFloat())
        }
    }

    Canvas(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    update(down.position, this.size.width.toFloat())
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            update(change.position, this.size.width.toFloat())
                        }
                    } finally {
                        direction = 0 to 0
                        listener.onMotion(MotionSources.DPAD, 0f, 0f)
                    }
                }
            },
    ) {
        val w = this.size.width
        val arm = w / 3
        val r = CornerRadius(arm * 0.18f)
        drawRoundRect(PadSurface, Offset(arm, 0f), Size(arm, w), r)
        drawRoundRect(PadSurface, Offset(0f, arm), Size(w, arm), r)
        drawRoundRect(PadStroke, Offset(arm, 0f), Size(arm, w), r, style = Stroke(3f))
        drawRoundRect(PadStroke, Offset(0f, arm), Size(w, arm), r, style = Stroke(3f))
        val (dx, dy) = direction
        if (dx != 0 || dy != 0) {
            val cx = w / 2 + dx * arm
            val cy = w / 2 + dy * arm
            drawCircle(PadPressed, radius = arm * 0.42f, center = Offset(cx, cy))
        }
        // setas
        val c = w / 2
        val t = arm * 0.22f
        listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).forEach { (ax, ay) ->
            val tip = Offset(c + ax * arm * 1.3f, c + ay * arm * 1.3f)
            val path = Path().apply {
                moveTo(tip.x, tip.y)
                lineTo(tip.x - ax * t - ay * t, tip.y - ay * t - ax * t)
                lineTo(tip.x - ax * t + ay * t, tip.y - ay * t + ax * t)
                close()
            }
            drawPath(path, PadStroke)
        }
    }
}

private val DIRS = listOf(1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1)

/** Analógico flutuante: a base é fixa e o manche segue o dedo, limitado ao raio. */
@Composable
private fun AnalogStick(size: Dp, source: Int, listener: PadListener) {
    var knob by remember { mutableStateOf(Offset.Zero) }

    Canvas(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                val radius = min(this.size.width, this.size.height) / 2f
                fun emit(p: Offset) {
                    val v = Offset(p.x - radius, p.y - radius)
                    val len = v.getDistance()
                    val clamped = if (len > radius) v * (radius / len) else v
                    knob = clamped / radius
                    listener.onMotion(source, knob.x, knob.y)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    emit(down.position)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            emit(change.position)
                        }
                    } finally {
                        knob = Offset.Zero
                        listener.onMotion(source, 0f, 0f)
                    }
                }
            },
    ) {
        val r = this.size.minDimension / 2
        drawCircle(PadSurface, r)
        drawCircle(PadStroke, r, style = Stroke(3f))
        drawCircle(PadPressed, r * 0.42f, center = center + knob * (r * 0.58f))
    }
}

/** Os quatro botões C do N64, enviados como analógico direito (padrão do Mupen64Plus-Next). */
@Composable
private fun CButtons(size: Dp, listener: PadListener, feedback: () -> Unit) {
    var pressed by remember { mutableStateOf(setOf<Int>()) }
    val btn = size / 3

    fun send(next: Set<Int>) {
        pressed = next
        var x = 0f; var y = 0f
        if (0 in next) y -= 1f
        if (1 in next) x += 1f
        if (2 in next) y += 1f
        if (3 in next) x -= 1f
        listener.onMotion(MotionSources.ANALOG_RIGHT, x, y)
    }

    @Composable
    fun C(index: Int, label: String, modifier: Modifier) {
        Box(
            modifier
                .size(btn)
                .background(if (index in pressed) Color(0xFFFFD600) else Color(0x99FFD600), CircleShape)
                .pressable { down ->
                    if (down) feedback()
                    send(if (down) pressed + index else pressed - index)
                },
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = (btn.value * 0.4f).sp) }
    }

    Box(Modifier.size(size)) {
        C(0, "▲", Modifier.align(Alignment.TopCenter))
        C(1, "▶", Modifier.align(Alignment.CenterEnd))
        C(2, "▼", Modifier.align(Alignment.BottomCenter))
        C(3, "◀", Modifier.align(Alignment.CenterStart))
        Text("C", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.Center), fontWeight = FontWeight.Black)
    }
}
