package com.retrovika.app.ui.screens.library

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.container
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.ui.components.EmptyState
import com.retrovika.app.ui.components.GameCard
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ScreenHeader
import com.retrovika.app.ui.components.SearchField
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.SystemTile
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(onOpenSystem: (String) -> Unit, onOpenGame: (Long) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val library = context.container.library
    val scope = rememberCoroutineScope()
    val counts by library.counts.collectAsStateWithLifecycle()
    val scan by library.scan.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var onlyWithGames by rememberSaveable { mutableStateOf(false) }
    val results by remember(query) { if (query.isBlank()) flowOf(emptyList()) else library.search(query) }
        .collectAsStateWithLifecycle(emptyList())

    val countMap = remember(counts) { counts.associate { it.systemId to it.count } }
    val systems = remember(countMap, onlyWithGames) { Systems.all.filter { !onlyWithGames || (countMap[it.id] ?: 0) > 0 } }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().ambientGlow(primary = Palette.Violet, secondary = Palette.Neon),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = LocalBottomInset.current + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ScreenHeader(
                    stringResource(R.string.library_title),
                    inset = 4.dp,
                    subtitle = counts.sumOf { it.count }.let { n ->
                        stringResource(
                            R.string.library_summary,
                            pluralStringResource(R.plurals.games_count, n, n),
                            pluralStringResource(R.plurals.consoles_count, counts.size, counts.size),
                        )
                    },
                ) {
                    IconButton(
                        onClick = { scope.launch { library.rescan() } },
                        enabled = !scan.running,
                        modifier = Modifier.background(Palette.SurfaceHigh, CircleShape),
                    ) {
                        if (scan.running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                        else Icon(Icons.Rounded.Refresh, stringResource(R.string.library_refresh))
                    }
                }
                Spacer(Modifier.height(16.dp))
                SearchField(query, onChange = { query = it }, placeholder = stringResource(R.string.library_search), onSearch = { focus.clearFocus() })
                Spacer(Modifier.height(12.dp))
                if (query.isBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectChip(stringResource(R.string.library_filter_all), !onlyWithGames, onClick = { onlyWithGames = false })
                        SelectChip(stringResource(R.string.library_filter_with_games), onlyWithGames, onClick = { onlyWithGames = true })
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        if (query.isNotBlank()) {
            if (results.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(stringResource(R.string.library_no_results_title), stringResource(R.string.library_no_results_message, query), icon = Icons.Rounded.SearchOff)
            }
            items(results, key = { it.id }) { game -> GameCard(game, onClick = { onOpenGame(game.id) }) }
        } else {
            items(systems, key = { it.id }) { system ->
                SystemTile(system, countMap[system.id] ?: 0, onClick = { onOpenSystem(system.id) })
            }
        }
    }
}
