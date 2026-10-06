package com.retrovika.app.ui.screens.library

import androidx.compose.ui.res.stringResource
import com.retrovika.app.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import com.retrovika.app.ui.components.countString
import com.retrovika.app.ui.components.HeaderIconButton
import com.retrovika.app.ui.components.ScrollToTopOnReselect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.container
import com.retrovika.app.core.library.Game
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import com.retrovika.app.ui.components.GameQuickMenu

@Composable
fun LibraryScreen(onOpenSystem: (String) -> Unit, onOpenGame: (Long) -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val app = context.container
    val library = app.library
    val counts by library.counts.collectAsStateWithLifecycle()
    val countsLoaded by library.countsLoaded.collectAsStateWithLifecycle()
    // Só o "em andamento": o progresso da varredura muda várias vezes por segundo e recompunha a tela toda.
    val scanning by remember { library.scan.map { it.running }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    var query by rememberSaveable { mutableStateOf("") }
    var onlyWithGames by rememberSaveable { mutableStateOf(false) }
    // Cada resposta vem junto do termo que a pediu. O collectAsState mantém o último valor quando a busca
    // muda, e "sem resultados" piscava com a resposta do termo anterior; agora só aparece com a resposta
    // do termo atual. Enquanto ela não chega, a lista anterior continua na tela (sem piscar vazia).
    val answer by remember(query) {
        (if (query.isBlank()) flowOf(emptyList<Game>()) else library.search(query)).map { query to it }
    }.collectAsStateWithLifecycle(null)
    val results = answer?.second
    val currentResults = answer?.takeIf { it.first == query }?.second

    val settings by app.settings.cached.collectAsStateWithLifecycle()
    val countMap = remember(counts) { counts.associate { it.systemId to it.count } }
    val systems = remember(countMap, onlyWithGames) { Systems.all.filter { !onlyWithGames || (countMap[it.id] ?: 0) > 0 } }
    val gridState = rememberLazyGridState()
    ScrollToTopOnReselect("library", gridState)
    var quick by remember { mutableStateOf<Game?>(null) }
    quick?.let { GameQuickMenu(it, onDismiss = { quick = null }, onDetails = onOpenGame) }
    // Resultados de busca são capas: seguem o tamanho escolhido nos ajustes. Consoles mantêm a grade própria.
    val columns = if (query.isBlank()) 150.dp else settings.coverSize.minWidth.dp

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(columns),
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
                            countString(R.plurals.games_count, n),
                            countString(R.plurals.consoles_count, counts.size),
                        )
                    },
                ) {
                    HeaderIconButton(
                        Icons.Rounded.Refresh, stringResource(R.string.library_refresh),
                        onClick = { app.scope.launch { library.rescan() } },
                        busy = scanning,
                    )
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
            if (currentResults?.isEmpty() == true) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(stringResource(R.string.library_no_results_title), stringResource(R.string.library_no_results_message, query), icon = Icons.Rounded.SearchOff)
            }
            items(results.orEmpty(), key = { it.id }, contentType = { "game" }) { game -> GameCard(game, onClick = { onOpenGame(game.id) }, onLongClick = { quick = game }) }
        } else {
            // "Com jogos" sem nenhum jogo na biblioteca: explica em vez de deixar a grade vazia.
            if (onlyWithGames && countsLoaded && systems.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(
                    stringResource(R.string.library_with_games_empty_title),
                    stringResource(R.string.library_with_games_empty_message),
                    icon = Icons.Rounded.VideogameAsset,
                )
            }
            items(systems, key = { it.id }, contentType = { "system" }) { system ->
                SystemTile(system, countMap[system.id] ?: 0, onClick = { onOpenSystem(system.id) })
            }
        }
    }
}
