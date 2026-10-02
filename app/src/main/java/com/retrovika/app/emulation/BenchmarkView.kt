package com.retrovika.app.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.retrovika.app.R
import com.retrovika.app.core.cores.CoreBenchmark
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.theme.Palette

/**
 * Primeira vez do console no aparelho: cada núcleo (ou cada nível de qualidade do núcleo) roda o jogo por
 * alguns segundos, acelerado e sem som, numa prévia ao vivo; a lista mostra a velocidade de cada um.
 * Dá para pular (fica o padrão).
 */
@Composable
internal fun BenchmarkView(state: EmulationUi.Benchmarking, system: GameSystem?, onSkip: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Ink).ambientGlow(primary = system?.let { Color(it.accent) } ?: Palette.Neon)
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Kicker(
            if (state.kind == BenchKind.GAME) stringResource(R.string.tune_kicker_game)
            else stringResource(R.string.bench_kicker, system?.shortName.orEmpty()),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(if (state.kind == BenchKind.CORES) R.string.bench_title else R.string.tune_title),
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                when (state.kind) {
                    BenchKind.CORES -> R.string.bench_message
                    BenchKind.QUALITY -> R.string.tune_message
                    BenchKind.GAME -> R.string.tune_message_game
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(18.dp))
        // Prévia do núcleo em teste: a própria view do emulador, pequena.
        Box(
            Modifier.widthIn(max = 300.dp).fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp))
                .background(Color.Black).border(1.dp, Palette.Outline, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val view = state.view
            if (view != null) key(view) { AndroidView(factory = { view }, modifier = Modifier.fillMaxSize()) }
            else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Palette.Cyan)
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.items.forEachIndexed { i, item ->
                val result = state.results.firstOrNull { it.coreId == item.id }
                val done = result != null
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.SurfaceHigh)
                        .border(1.dp, if (i == state.current && !done) Palette.Cyan else Palette.Outline, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        done && result?.speed == null -> Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Coral, modifier = Modifier.size(18.dp))
                        done -> Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Success, modifier = Modifier.size(18.dp))
                        i == state.current -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                        else -> Icon(Icons.Rounded.HourglassEmpty, null, tint = Palette.TextMuted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, style = MaterialTheme.typography.titleSmall)
                        val speed = result?.speed
                        if (speed != null) {
                            Spacer(Modifier.height(4.dp))
                            // Barra até 4× a velocidade nativa; a marca da folga exigida fica visível pela cor.
                            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Palette.SurfaceHighest)) {
                                Box(
                                    Modifier.fillMaxHeight().fillMaxWidth((speed / 4f).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(50))
                                        .background(if (speed >= CoreBenchmark.HEADROOM) Palette.Success else if (speed >= 1f) Palette.Sun else Palette.Coral),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            result == null -> ""
                            result.speed == null -> stringResource(R.string.bench_failed)
                            else -> stringResource(R.string.bench_speed, (result.speed * 100).toInt())
                        },
                        style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        GhostButton(stringResource(R.string.bench_skip), onSkip, icon = Icons.Rounded.SkipNext)
    }
}
