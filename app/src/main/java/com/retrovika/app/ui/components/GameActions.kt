package com.retrovika.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.settings.localized
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Ações rápidas de um jogo, pelo toque longo no cartão: jogar direto, favoritar, abrir a página e remover, sem
 * passar pela página do jogo. O jogo é relido do banco: favoritar aqui muda o ícone na hora.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameQuickMenu(game: Game, onDismiss: () -> Unit, onDetails: (Long) -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val scope = rememberCoroutineScope()
    val current by remember(game.id) { app.library.observe(game.id) }.collectAsStateWithLifecycle(game)
    // Removido (aqui ou numa varredura) com a folha aberta: ela fecha sozinha.
    LaunchedEffect(current == null) { if (current == null) onDismiss() }
    val g = current ?: return
    var confirmRemove by remember { mutableStateOf(false) }
    val system = Systems.byId(g.systemId)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Palette.Surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                GameCover(g.title, system, g.coverUrl, Modifier.width(48.dp).height(64.dp), corner = 10.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(system?.name.orEmpty(), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                }
            }
            Spacer(Modifier.height(12.dp))
            QuickRow(Icons.Rounded.PlayArrow, Palette.Neon, stringResource(if (g.lastPlayed != null) R.string.common_continue else R.string.common_play)) {
                onDismiss()
                GameActivity.launch(context, g.id)
            }
            QuickRow(
                if (g.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, Palette.Sun,
                stringResource(if (g.favorite) R.string.details_favorite_remove else R.string.details_favorite_add),
            ) { scope.launch { app.library.toggleFavorite(g.id) } }
            QuickRow(Icons.Rounded.Info, Palette.Cyan, stringResource(R.string.game_quick_details), trailing = true) {
                onDismiss()
                onDetails(g.id)
            }
            QuickRow(Icons.Rounded.Delete, Palette.Coral, stringResource(R.string.common_remove)) { confirmRemove = true }
        }
    }

    if (confirmRemove) RemoveGameDialog(g, onDismiss = { confirmRemove = false }, onRemoved = onDismiss)
}

@Composable
private fun QuickRow(icon: ImageVector, tint: Color, label: String, trailing: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(PaddingValues(horizontal = 20.dp, vertical = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, tint, size = 36.dp)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        if (trailing) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Palette.TextMuted)
    }
}

/**
 * Confirmação de remover um jogo: jogo de pasta vinculada só sai da biblioteca, importado ou baixado tem o arquivo
 * apagado. [onRemoved] roda depois de removido; se o arquivo não pôde ser apagado, um aviso diz e o jogo fica.
 */
@Composable
fun RemoveGameDialog(game: Game, onDismiss: () -> Unit, onRemoved: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Delete, null, tint = Palette.Coral) },
        title = { Text(stringResource(R.string.details_remove_title, game.title)) },
        text = { Text(stringResource(if (game.isContentUri) R.string.details_remove_linked else R.string.details_remove_file)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                val failedText = context.localized().getString(R.string.details_remove_failed, game.title)
                // No escopo do app: o diálogo já saiu da composição (e com ele o escopo dele), e a folha ou a tela que
                // o abriu pode sair antes de o arquivo ser apagado.
                app.scope.launch(Dispatchers.Main) {
                    // Falso: o arquivo da ROM não pôde ser apagado e o jogo continua na biblioteca.
                    val removed = runCatching { app.library.delete(game, deleteFile = !game.isContentUri) }.getOrDefault(false)
                    if (removed) onRemoved() else Toast.makeText(context.applicationContext, failedText, Toast.LENGTH_LONG).show()
                }
            }) { Text(stringResource(R.string.common_remove), color = Palette.Coral) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        containerColor = Palette.SurfaceHigh,
    )
}
