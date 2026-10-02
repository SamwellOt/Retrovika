package com.retrovika.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import com.retrovika.app.core.tuning.DeviceTier
import com.retrovika.app.core.tuning.EffectivePreset
import com.retrovika.app.core.tuning.TuneSource

/** Que nível de qualidade está valendo e por quê (medido, estimado, escolhido pelo usuário ou ajustado ao jogo). */
@Composable
fun tuneSourceText(e: EffectivePreset): String {
    val label = stringResource(e.preset.label)
    val percent = e.speed?.let { (it * 100).toInt() }
    return when (e.source) {
        TuneSource.USER -> stringResource(R.string.tune_source_user, label)
        TuneSource.ESTIMATED -> stringResource(R.string.tune_source_estimated, label)
        TuneSource.MEASURED -> if (percent != null) stringResource(R.string.tune_source_measured, label, percent) else stringResource(R.string.tune_source_estimated, label)
        TuneSource.GAME -> when {
            percent != null -> stringResource(R.string.tune_source_game_measured, label, percent)
            e.slowdown -> stringResource(R.string.tune_source_game_slowdown, label)
            else -> stringResource(R.string.tune_source_game, label)
        }
    }
}

@Composable
fun tierLabel(tier: DeviceTier): String = stringResource(
    when (tier) {
        DeviceTier.ENTRY -> R.string.tune_tier_entry
        DeviceTier.MID -> R.string.tune_tier_mid
        DeviceTier.HIGH -> R.string.tune_tier_high
        DeviceTier.TOP -> R.string.tune_tier_top
    },
)
