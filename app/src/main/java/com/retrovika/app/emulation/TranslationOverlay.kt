package com.retrovika.app.emulation

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrovika.app.R
import com.retrovika.app.core.translate.LiveTranslator
import com.retrovika.app.core.translate.TranslatedBlock
import com.retrovika.app.ui.theme.Palette

/** Tela traduzida: a captura do momento, com o andamento, o resultado ou o erro. */
sealed interface TranslationUi {
    val frame: Bitmap
    data class Working(override val frame: Bitmap, val stage: LiveTranslator.Stage) : TranslationUi
    /** [aiError]: a IA falhou e a tradução comum assumiu. [suggestPack]: vale baixar o OCR para jogos japoneses. */
    data class Ready(
        override val frame: Bitmap, val blocks: List<TranslatedBlock>,
        val aiError: String? = null, val suggestPack: Boolean = false,
        /** O texto veio da memória do jogo (sem posição na tela): a lista sai por cima da captura em vez de cada trecho no seu lugar. */
        val fromMemory: Boolean = false,
    ) : TranslationUi
    data class Failed(override val frame: Bitmap, val message: String) : TranslationUi
}

/**
 * Por cima da área do jogo: a captura congelada e, sobre cada trecho achado, a tradução no mesmo lugar
 * (o tamanho da letra se ajusta à caixa). Tocar num trecho mostra o original e a tradução inteira embaixo;
 * tocar fora volta ao jogo.
 */
@Composable
internal fun TranslationOverlay(state: TranslationUi, onClose: () -> Unit, modifier: Modifier) {
    var selected by remember(state) { mutableStateOf<TranslatedBlock?>(null) }
    BoxWithConstraints(
        modifier.background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        val density = LocalDensity.current
        // A captura tem o tamanho da view do jogo, que é exatamente esta área: basta a escala.
        val sx = constraints.maxWidth / state.frame.width.toFloat()
        val sy = constraints.maxHeight / state.frame.height.toFloat()
        Image(state.frame.asImageBitmap(), null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))

        if (state is TranslationUi.Ready && state.fromMemory) {
            MemoryTextCards(state.blocks, Modifier.align(Alignment.TopCenter))
        } else if (state is TranslationUi.Ready) {
            state.blocks.forEach { block ->
                val pad = 3f
                val x = with(density) { ((block.box.left - pad) * sx).toDp() }
                val y = with(density) { ((block.box.top - pad) * sy).toDp() }
                val w = with(density) { ((block.box.width + pad * 2) * sx).toDp() }
                val h = with(density) { ((block.box.height + pad * 2) * sy).toDp() }
                Box(
                    Modifier.offset(x, y).size(w.coerceAtLeast(24.dp), h.coerceAtLeast(18.dp))
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected === block) Palette.Ink.copy(alpha = 0.96f) else Color(0xF20B0714))
                        .border(1.dp, if (selected === block) Palette.Neon else Palette.Cyan.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                        .clickable { selected = block }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicText(
                        block.translated,
                        style = TextStyle(color = Color.White, fontWeight = FontWeight.Medium),
                        autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = 22.sp, stepSize = 0.5.sp),
                    )
                }
            }
        }

        // Faixa de status e detalhes, sempre legível, embaixo da área do jogo.
        Column(
            Modifier.align(Alignment.BottomCenter).padding(10.dp).widthIn(max = 560.dp).fillMaxWidth()
                .clip(RoundedCornerShape(16.dp)).background(Palette.Surface.copy(alpha = 0.95f))
                .border(1.dp, Palette.Outline, RoundedCornerShape(16.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { selected = null }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Translate, null, tint = Palette.Cyan, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (state) {
                        is TranslationUi.Working -> stringResource(
                            when (state.stage) {
                                LiveTranslator.Stage.READING -> R.string.translate_reading
                                LiveTranslator.Stage.ASKING_AI -> R.string.translate_asking_ai
                                LiveTranslator.Stage.DOWNLOADING_MODEL -> R.string.translate_downloading
                                LiveTranslator.Stage.TRANSLATING -> R.string.translate_translating
                            },
                        )
                        is TranslationUi.Ready -> when {
                            state.blocks.isEmpty() -> stringResource(R.string.translate_nothing)
                            state.fromMemory -> stringResource(R.string.translate_from_memory)
                            else -> stringResource(R.string.translate_hint)
                        }
                        is TranslationUi.Failed -> stringResource(R.string.translate_failed, state.message)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is TranslationUi.Failed) Palette.Coral else Palette.TextSecondary,
                    modifier = Modifier.weight(1f),
                )
                if (state is TranslationUi.Working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, stringResource(R.string.translate_close), tint = Palette.TextPrimary, modifier = Modifier.size(18.dp)) }
            }
            if (state is TranslationUi.Ready && selected == null) {
                state.aiError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.translate_ai_fallback, it), style = MaterialTheme.typography.labelSmall, color = Palette.Coral)
                }
                if (state.suggestPack) {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.translate_suggest_pack), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                }
            }
            selected?.let { block ->
                Spacer(Modifier.height(10.dp))
                Text(block.original, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
                Spacer(Modifier.height(4.dp))
                Text(block.translated, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary, textAlign = TextAlign.Start)
            }
        }
    }
}

/** O texto lido da memória do jogo: um cartão por trecho, o original pequeno e a tradução em cima dele. */
@Composable
private fun MemoryTextCards(blocks: List<TranslatedBlock>, modifier: Modifier) {
    Column(modifier.padding(10.dp).widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xF20B0714))
                    .border(1.dp, Palette.Cyan.copy(alpha = 0.55f), RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                Text(block.translated, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text(block.original, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
            }
        }
    }
}
