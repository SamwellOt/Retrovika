package com.retrovika.app.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R

/**
 * A região é gravada no banco em português na hora do scan ("EUA", "Japão"...), vinda de
 * RomNaming/regionOf, ou em inglês quando vem dos DATs. Traduzimos só na exibição, para que
 * trocar o idioma não exija reescanear a biblioteca. Valores desconhecidos aparecem como estão.
 */
@StringRes
private fun regionRes(stored: String): Int? = when (stored.trim().lowercase()) {
    "eua", "usa" -> R.string.region_usa
    "europa", "europe" -> R.string.region_europe
    "japão", "japan" -> R.string.region_japan
    "mundo", "mundial", "world" -> R.string.region_world
    "brasil", "brazil" -> R.string.region_brazil
    "coreia", "korea" -> R.string.region_korea
    "china" -> R.string.region_china
    "austrália", "australia" -> R.string.region_australia
    // Antes gravadas em inglês: as entradas antigas do banco continuam traduzidas.
    "ásia", "asia" -> R.string.region_asia
    "frança", "france" -> R.string.region_france
    "alemanha", "germany" -> R.string.region_germany
    "espanha", "spain" -> R.string.region_spain
    "itália", "italy" -> R.string.region_italy
    else -> null
}

@Composable
fun regionLabel(stored: String): String = regionRes(stored)?.let { stringResource(it) } ?: stored
