package com.retrovika.app.emulation.input

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
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
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.runtime.mutableStateMapOf
import kotlin.math.abs
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
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
 * Modo de edição do layout: os toques movem as partes do controle em vez de apertar botões.
 * [onMove] recebe o deslocamento em fração da área do controle.
 */
class PadEditor(
    val selected: PadElement?,
    val onSelect: (PadElement) -> Unit,
    /** Arrasto de [dx]/[dy] (fração da área); [limits] é até onde a parte vai sem sair da tela (nulo antes do layout). */
    val onMove: (PadElement, dx: Float, dy: Float, limits: OffsetLimits?) -> Unit,
)

/** Listener vazio do modo de edição: nenhum botão chega ao jogo enquanto o layout é ajustado. */
private val NoInput = object : PadListener {
    override fun onKey(keyCode: Int, pressed: Boolean) = Unit
    override fun onMotion(source: Int, x: Float, y: Float) = Unit
}

/**
 * Controle virtual desenhado em Compose. Cada controle possui seu próprio pointerInput,
 * então vários dedos funcionam ao mesmo tempo, e toques fora dos botões chegam ao jogo
 * (necessário para a tela de toque do Nintendo DS).
 *
 * Em paisagem (ou em retrato sobreposto, [overlay]) o controle fica por cima do jogo; em retrato
 * dividido ocupa a metade inferior. A escala é reduzida automaticamente para caber no espaço
 * disponível. [elements] desloca, redimensiona ou oculta cada parte (ver [PadProfile]).
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
    overlay: Boolean = !portrait,
    elements: Map<PadElement, PadElementConfig> = emptyMap(),
    editor: PadEditor? = null,
) {
    val view = LocalView.current
    val feedback: () -> Unit = { if (haptics && editor == null) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    val input = if (editor != null) NoInput else listener

    // Canto da área do controle na tela: cada parte calcula a própria posição dentro dela para não passar das bordas.
    var areaOrigin by remember { mutableStateOf(Offset.Zero) }
    // ModulateAlpha: a opacidade vai em cada desenho, sem a camada fora da tela do tamanho do controle (a tela toda,
    // sobreposto) que o alpha comum cria e mistura de novo a cada redesenho — o analógico redesenha a cada movimento,
    // disputando a GPU com núcleos pesados.
    val alpha = if (editor != null) 1f else opacity
    BoxWithConstraints(
        modifier
            .graphicsLayer { this.alpha = alpha; compositingStrategy = CompositingStrategy.ModulateAlpha }
            .onPlaced { if (it.isAttached) areaOrigin = it.positionInRoot() },
    ) {
        val size = PadMetrics(layout)
        val fit = if (!overlay) {
            min(maxWidth.value / size.portraitWidth, maxHeight.value / size.portraitHeight)
        } else {
            min(maxHeight.value / size.landscapeHeight, maxWidth.value / size.overlayWidth)
        }
        val s = (scale * fit.coerceAtMost(1.15f)).coerceIn(0.5f, 1.5f)
        val area = IntSize(constraints.maxWidth, constraints.maxHeight)

        // Cada parte do controle passa por aqui: posição/tamanho do perfil e, no editor, arrastar para mover.
        // Oculta no jogo: sobreposta, sai do layout; dividida, continua ocupando o lugar (invisível e sem
        // toques), senão as outras partes andariam e o jogo não ficaria como no editor.
        @Composable
        fun Part(element: PadElement, modifier: Modifier, content: @Composable (PadListener, () -> Unit) -> Unit) {
            val config = elements[element] ?: PadElementConfig()
            val gone = config.hidden && editor == null
            if (gone && overlay) return
            PadPart(element, config, area, { areaOrigin }, editor, modifier, excludeGestures = !gone) { if (gone) content(NoInput, {}) else content(input, feedback) }
        }

        if (!overlay) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (size.hasShoulders) Row(Modifier.fillMaxWidth()) {
                    if (layout.leftShoulders.isNotEmpty()) Part(PadElement.LEFT_SHOULDERS, Modifier) { i, f ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { layout.leftShoulders.forEach { ShoulderButton(it, s, i, f) } }
                    }
                    Spacer(Modifier.weight(1f))
                    if (layout.rightShoulders.isNotEmpty()) Part(PadElement.RIGHT_SHOULDERS, Modifier) { i, f ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { layout.rightShoulders.asReversed().forEach { ShoulderButton(it, s, i, f) } }
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Part(PadElement.LEFT, Modifier.align(Alignment.CenterStart)) { i, f -> LeftCluster(layout, s, i, f, Modifier) }
                    Part(PadElement.FACE, Modifier.align(Alignment.CenterEnd)) { i, f -> RightCluster(layout, s, i, f, Modifier) }
                }
                if (layout.center.isNotEmpty()) Part(PadElement.CENTER, Modifier.align(Alignment.CenterHorizontally)) { i, f ->
                    CenterButtons(layout, s, i, f, Modifier)
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                if (layout.leftShoulders.isNotEmpty()) Part(PadElement.LEFT_SHOULDERS, Modifier.align(Alignment.TopStart).padding(16.dp)) { i, f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { layout.leftShoulders.forEach { ShoulderButton(it, s, i, f) } }
                }
                if (layout.rightShoulders.isNotEmpty()) Part(PadElement.RIGHT_SHOULDERS, Modifier.align(Alignment.TopEnd).padding(16.dp)) { i, f ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { layout.rightShoulders.asReversed().forEach { ShoulderButton(it, s, i, f) } }
                }
                Part(PadElement.LEFT, Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 20.dp)) { i, f -> LeftCluster(layout, s, i, f, Modifier) }
                Part(PadElement.FACE, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 20.dp)) { i, f -> RightCluster(layout, s, i, f, Modifier) }
                if (layout.center.isNotEmpty()) Part(PadElement.CENTER, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { i, f ->
                    CenterButtons(layout, s, i, f, Modifier)
                }
            }
        }
    }
}

/** Partes que o layout do console tem (um Atari não tem L/R, por exemplo). */
fun PadLayout.elements(): List<PadElement> = buildList {
    if (leftShoulders.isNotEmpty()) add(PadElement.LEFT_SHOULDERS)
    if (rightShoulders.isNotEmpty()) add(PadElement.RIGHT_SHOULDERS)
    add(PadElement.LEFT)
    add(PadElement.FACE)
    if (center.isNotEmpty()) add(PadElement.CENTER)
}

/**
 * Uma parte do controle na posição e no tamanho do perfil. O deslocamento é aplicado depois do layout
 * (não empurra as outras partes) e o tamanho, na camada gráfica, que também leva os toques junto.
 * No editor, uma película por cima recebe o arrasto; partes ocultas aparecem apagadas para poderem voltar.
 */
@Composable
private fun PadPart(
    element: PadElement,
    config: PadElementConfig,
    area: IntSize,
    areaOrigin: () -> Offset,
    editor: PadEditor?,
    modifier: Modifier,
    excludeGestures: Boolean,
    content: @Composable () -> Unit,
) {
    val selected = editor?.selected == element
    // Os gestos são criados uma vez por parte: leem sempre o editor e o tamanho mais recentes.
    val currentEditor by rememberUpdatedState(editor)
    val currentScale by rememberUpdatedState(config.scale)
    // Retângulo da parte na posição padrão (sem o deslocamento, que vem depois na cadeia), na tela.
    var base by remember { mutableStateOf<Rect?>(null) }
    val limits: () -> OffsetLimits? = {
        base?.let { b ->
            val origin = areaOrigin()
            OffsetLimits.of(b.left - origin.x, b.top - origin.y, b.width, b.height, currentScale, area.width.toFloat(), area.height.toFloat())
        }
    }
    val currentLimits by rememberUpdatedState(limits)
    Box(
        modifier
            .onPlaced { if (it.isAttached) base = Rect(it.positionInRoot(), it.size.toSize()) }
            .offset {
                // Lido na fase de posicionamento: a parte nunca passa das bordas da área, nem no jogo.
                val l = limits()
                val dx = l?.clampX(config.dx) ?: config.dx
                val dy = l?.clampY(config.dy) ?: config.dy
                IntOffset((dx * area.width).roundToInt(), (dy * area.height).roundToInt())
            }
            .graphicsLayer {
                scaleX = config.scale
                scaleY = config.scale
                alpha = if (!config.hidden) 1f else if (editor != null) 0.28f else 0f
            }
            // D-pad e botões encostados na borda: sem isto, deslizar o polegar para fora abria o gesto de
            // voltar do sistema (Android 10+; o sistema aceita até 200 dp por borda). Depois da camada,
            // para o retângulo seguir o tamanho e a posição do perfil.
            .then(if (excludeGestures) Modifier.systemGestureExclusion() else Modifier),
    ) {
        content()
        if (editor != null) {
            val shape = RoundedCornerShape(16.dp)
            Box(
                Modifier
                    .matchParentSize()
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) EditSelected else EditOutline, shape)
                    .background(if (selected) EditSelected.copy(alpha = 0.12f) else Color.Transparent, shape)
                    .pointerInput(element, area) {
                        detectDragGestures(onDragStart = { currentEditor?.onSelect(element) }) { change, drag ->
                            change.consume()
                            // O arrasto chega em coordenadas da parte, já escalada: volta ao tamanho da tela.
                            currentEditor?.onMove(element, drag.x * currentScale / area.width, drag.y * currentScale / area.height, currentLimits())
                        }
                    }
                    .pointerInput(element) { detectTapGestures { currentEditor?.onSelect(element) } },
            )
        }
    }
}

private val EditSelected = Color(0xFFFF3D8B)
private val EditOutline = Color(0x99FFFFFF)

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
    /** Sobreposto em retrato, os dois grupos dividem a largura estreita da tela. */
    val overlayWidth = leftW + faceW + 72f
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
    val group = remember { FaceGroup() }
    @Composable
    fun FaceButton(button: PadButton, size: Dp, listener: PadListener, feedback: () -> Unit, modifier: Modifier = Modifier) =
        FaceButton(button, size, group, listener, feedback, modifier)
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

/**
 * Modificador de botão: pressiona no toque, solta ao levantar o dedo ou cancelar. O gesto é criado uma vez,
 * mas usa sempre o [onChange] mais recente: a parte que é ocultada (ou volta) troca o listener sem recriar
 * o botão. A soltura vai para quem recebeu o aperto, para nada ficar preso no jogo.
 */
@Composable
private fun Modifier.pressable(onChange: (Boolean) -> Unit): Modifier {
    val current by rememberUpdatedState(onChange)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false).consume()
            val target = current
            target(true)
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach(PointerInputChange::consume)
                    if (event.changes.none { it.pressed }) break
                }
            } finally {
                target(false)
            }
        }
    }
}

/**
 * Os botões de ação como um grupo: o dedo que aperta um botão pode deslizar até o vizinho (solta um, aperta o
 * outro) ou parar no vão entre dois e apertar os dois (A+B com um polegar só, como num controle de verdade).
 * Cada botão continua com o próprio pointerInput — o toque só começa em cima de um botão, e o vão segue chegando
 * ao jogo (tela de toque do DS) —, mas o caminho do dedo é comparado, em coordenadas da tela, com o grupo todo.
 * Sair para uma área sem botão mantém o que estava apertado: o polegar que escorrega um pouco não solta o botão.
 */
private class FaceGroup {
    private val places = HashMap<PadButton, LayoutCoordinates>()
    private val byPointer = HashMap<PointerId, Set<PadButton>>()
    /** Quem apertou cada botão: a soltura vai para o mesmo listener, para nada ficar preso no jogo. */
    private val owners = HashMap<PadButton, PadListener>()
    /** Lido só no desenho dos botões: apertar redesenha, sem recompor. */
    val pressed = mutableStateMapOf<PadButton, Boolean>()

    fun place(button: PadButton, coordinates: LayoutCoordinates) { places[button] = coordinates }

    /** O dedo [pointer] está em [root]; [fallback] é o botão do toque inicial, se ele cair no canto da caixa. */
    fun track(pointer: PointerId, root: Offset?, listener: PadListener, feedback: () -> Unit, fallback: PadButton? = null) {
        val hit = root?.let(::hitTest) ?: fallback?.let(::setOf) ?: return
        if (hit == byPointer[pointer]) return
        byPointer[pointer] = hit
        sync(listener, feedback)
    }

    fun release(pointer: PointerId) {
        if (byPointer.remove(pointer) != null) sync(null) {}
    }

    private fun sync(listener: PadListener?, feedback: () -> Unit) {
        val now = byPointer.values.flatten().toSet()
        owners.keys.filter { it !in now }.forEach { b ->
            owners.remove(b)?.onKey(b.keyCode, false)
            pressed.remove(b)
        }
        if (listener == null) return
        var any = false
        now.filter { it !in owners }.forEach { b ->
            owners[b] = listener
            listener.onKey(b.keyCode, true)
            pressed[b] = true
            any = true
        }
        if (any) feedback()
    }

    private class Spot(val button: PadButton, val center: Offset, val radius: Float, val distance: Float) {
        val ratio get() = distance / radius
    }

    private fun hitTest(p: Offset): Set<PadButton>? {
        val spots = places.mapNotNull { (b, c) ->
            if (!c.isAttached) return@mapNotNull null
            val w = c.size.width.toFloat()
            val h = c.size.height.toFloat()
            // Pelas coordenadas da tela: o tamanho e a posição do perfil (camada gráfica) entram na conta.
            val center = c.localToRoot(Offset(w / 2f, h / 2f))
            val radius = (c.localToRoot(Offset(w, h / 2f)) - center).getDistance()
            if (radius <= 0f) null else Spot(b, center, radius, (p - center).getDistance())
        }.sortedBy { it.ratio }
        val first = spots.firstOrNull() ?: return null
        if (first.ratio <= 1f) return setOf(first.button)
        val second = spots.getOrNull(1)
        if (second != null && between(p, first, second)) return setOf(first.button, second.button)
        return if (first.ratio <= EDGE_SLACK) setOf(first.button) else null
    }

    /** No vão entre dois botões vizinhos: perto do segmento que liga os centros, longe das pontas. */
    private fun between(p: Offset, a: Spot, b: Spot): Boolean {
        val seg = b.center - a.center
        val len = seg.getDistance()
        if (len <= 0f || len > PAIR_REACH * (a.radius + b.radius) / 2f) return false
        val rel = p - a.center
        val t = (rel.x * seg.x + rel.y * seg.y) / (len * len)
        val perpendicular = abs(rel.x * seg.y - rel.y * seg.x) / len
        return t in 0.2f..0.8f && perpendicular <= 0.6f * min(a.radius, b.radius)
    }

    private companion object {
        /** Folga fora do círculo que ainda conta como o botão (em raios). */
        const val EDGE_SLACK = 1.3f
        /** Distância máxima entre centros (em raios) para o vão apertar os dois: vizinhos sim, opostos do losango não. */
        const val PAIR_REACH = 3.4f
    }
}

@Composable
private fun FaceButton(button: PadButton, size: Dp, group: FaceGroup, listener: PadListener, feedback: () -> Unit, modifier: Modifier = Modifier) {
    val base = button.color?.let { Color(it) } ?: Color(0xFF424242)
    // O gesto é criado uma vez: lê sempre o listener e a vibração atuais (parte ocultada ou de volta, editor).
    val currentListener by rememberUpdatedState(listener)
    val currentFeedback by rememberUpdatedState(feedback)
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    Box(
        modifier
            .size(size)
            .onPlaced { coordinates = it; group.place(button, it) }
            .drawBehind {
                val down = group.pressed[button] == true
                val stroke = 2.dp.toPx()
                drawCircle(if (down) base.copy(alpha = 0.95f) else base.copy(alpha = 0.55f))
                drawCircle(if (down) PadPressed else PadStroke, radius = this.size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            }
            .pointerInput(button) {
                fun root(local: Offset) = coordinates?.takeIf { it.isAttached }?.localToRoot(local)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    // O gesto inteiro (até a soltura) vai para quem recebeu o primeiro toque.
                    val target = currentListener
                    val haptic = currentFeedback
                    // Cada dedo que pousa neste botão durante o gesto é seguido à parte: o polegar que saiu deslizando
                    // para o vizinho não impede outro dedo de apertar este, e soltar um não solta o do outro.
                    val fingers = mutableSetOf(down.id)
                    group.track(down.id, root(down.position), target, haptic, fallback = button)
                    try {
                        while (fingers.isNotEmpty()) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                change.consume()
                                when {
                                    change.changedToDownIgnoreConsumed() -> {
                                        fingers += change.id
                                        group.track(change.id, root(change.position), target, haptic, fallback = button)
                                    }
                                    !change.pressed -> if (fingers.remove(change.id)) group.release(change.id)
                                    change.id in fingers -> group.track(change.id, root(change.position), target, haptic)
                                }
                            }
                        }
                    } finally {
                        fingers.forEach(group::release)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(button.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.34f).sp)
    }
}

@Composable
private fun ShoulderButton(button: PadButton, s: Float, listener: PadListener, feedback: () -> Unit) {
    // Lido só no desenho: apertar redesenha o botão sem recompô-lo.
    val pressed = remember { mutableStateOf(false) }
    var pressedValue by pressed
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(width = (84 * s).dp, height = (44 * s).dp)
            .drawBehind { drawRoundRect(if (pressed.value) PadPressed else PadSurface, cornerRadius = CornerRadius(14.dp.toPx())) }
            .border(1.5.dp, PadStroke, shape)
            .pressable { down ->
                pressedValue = down
                if (down) feedback()
                listener.onKey(button.keyCode, down)
            },
        contentAlignment = Alignment.Center,
    ) { Text(button.label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (15 * s).sp) }
}

@Composable
private fun PillButton(button: PadButton, s: Float, listener: PadListener, feedback: () -> Unit) {
    val pressed = remember { mutableStateOf(false) }
    var pressedValue by pressed
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .size(width = (74 * s).dp, height = (28 * s).dp)
            .drawBehind { drawRoundRect(if (pressed.value) PadPressed else PadSurface, cornerRadius = CornerRadius(this.size.height / 2f)) }
            .border(1.dp, PadStroke, shape)
            .pressable { down ->
                pressedValue = down
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
    // O gesto é criado uma vez: lê sempre o listener e a vibração atuais (parte ocultada ou de volta, editor).
    val currentListener by rememberUpdatedState(listener)
    val currentFeedback by rememberUpdatedState(feedback)

    fun update(position: Offset, sizePx: Float, listener: PadListener) {
        val cx = sizePx / 2
        val dx = position.x - cx
        val dy = position.y - cx
        val dist = hypot(dx, dy)
        val next = if (dist < sizePx * 0.12f) 0 to 0 else {
            val sector = ((atan2(dy, dx) / (PI / 4)).roundToInt() + 8) % 8
            DIRS[sector]
        }
        if (next != direction) {
            if (next != (0 to 0)) currentFeedback()
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
                    // O gesto inteiro (até a soltura) vai para quem recebeu o primeiro toque.
                    val target = currentListener
                    update(down.position, this.size.width.toFloat(), target)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            update(change.position, this.size.width.toFloat(), target)
                        }
                    } finally {
                        direction = 0 to 0
                        target.onMotion(MotionSources.DPAD, 0f, 0f)
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
    val currentListener by rememberUpdatedState(listener)

    Canvas(
        Modifier
            .size(size)
            .pointerInput(Unit) {
                fun emit(p: Offset, listener: PadListener) {
                    // Lido a cada movimento: o tamanho muda sem reiniciar o bloco (tela dividida, dobráveis).
                    val radius = min(this.size.width, this.size.height) / 2f
                    val v = Offset(p.x - radius, p.y - radius)
                    val len = v.getDistance()
                    val clamped = if (len > radius) v * (radius / len) else v
                    knob = clamped / radius
                    listener.onMotion(source, knob.x, knob.y)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val target = currentListener
                    emit(down.position, target)
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            emit(change.position, target)
                        }
                    } finally {
                        knob = Offset.Zero
                        target.onMotion(source, 0f, 0f)
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
    val pressedState = remember { mutableStateOf(setOf<Int>()) }
    var pressed by pressedState
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
                // Lido no desenho: apertar um C não recompõe os quatro.
                .drawBehind { drawCircle(if (index in pressedState.value) Color(0xFFFFD600) else Color(0x99FFD600)) }
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
