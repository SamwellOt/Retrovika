package com.retrovika.app.ui.components

import androidx.annotation.PluralsRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.retrovika.app.R
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import androidx.compose.material3.LinearProgressIndicator
import com.retrovika.app.core.catalog.DownloadStatus
import com.retrovika.app.core.catalog.DownloadTask
import com.retrovika.app.ui.theme.DisplayFamily
import com.retrovika.app.ui.theme.Palette
import com.retrovika.app.ui.theme.PixelFamily

fun GameSystem.accentColor(): Color = Color(accent)

/** Cor legível sobre o fundo escuro, mesmo para consoles com cor de marca muito escura. */
fun GameSystem.readableAccent(): Color {
    val c = accentColor()
    val lum = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
    return when {
        lum < 0.18f -> Color(0xFFC3BCDA)
        lum < 0.55f -> lerp(c, Color.White, 0.5f - lum * 0.5f)
        else -> c
    }
}

/** Altura da barra de abas flutuante: as telas de aba somam isso ao padding inferior da lista. */
val LocalBottomInset = compositionLocalOf { 0.dp }

/** Rota da aba tocada de novo quando já estava aberta: a tela daquela aba volta ao topo. */
val LocalTabReselect = staticCompositionLocalOf<SharedFlow<String>> { MutableSharedFlow() }

/** Tocar de novo na aba [route] rola a lista de volta ao início. */
@Composable
fun ScrollToTopOnReselect(route: String, state: LazyListState) {
    val events = LocalTabReselect.current
    LaunchedEffect(events, state) { events.collect { if (it == route) state.animateScrollToItem(0) } }
}

@Composable
fun ScrollToTopOnReselect(route: String, state: LazyGridState) {
    val events = LocalTabReselect.current
    LaunchedEffect(events, state) { events.collect { if (it == route) state.animateScrollToItem(0) } }
}

// ---------------------------------------------------------------- marca

/** O ícone do app (cenário + monograma), recortado como no launcher. */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier, corner: Dp = size * 0.28f) {
    Box(modifier.size(size).clip(RoundedCornerShape(corner))) {
        // O ícone adaptativo tem 108dp com 18dp de margem: ampliar 1,5× mostra só a área visível.
        val zoom = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
        Image(painterResource(R.drawable.ic_launcher_background), null, zoom, contentScale = ContentScale.Crop)
        Image(painterResource(R.drawable.ic_launcher_foreground), null, zoom, contentScale = ContentScale.Crop)
    }
}

/** Nome do app em fonte pixelada com o degradê do sol. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 14.sp) {
    Text(
        "RETROVIKA",
        modifier = modifier,
        style = TextStyle(fontFamily = PixelFamily, fontSize = fontSize, letterSpacing = 1.sp, brush = Palette.SunsetHorizontal),
    )
}

/** Rótulo curto em fonte pixelada ("CONTINUAR", "NOVO"...). */
@Composable
fun Kicker(text: String, modifier: Modifier = Modifier, color: Color = Palette.Neon) {
    Text(text.uppercase(), modifier = modifier, color = color, style = TextStyle(fontFamily = PixelFamily, fontSize = 8.sp, letterSpacing = 1.sp))
}

// ---------------------------------------------------------------- fundo e estrutura

/** Brilho ambiente no topo da tela: duas "nebulosas" suaves nas cores da marca. */
fun Modifier.ambientGlow(primary: Color = Palette.Neon, secondary: Color = Palette.Violet, height: Dp = 420.dp): Modifier = drawWithCache {
    // Os degradês só são recriados quando o tamanho muda, não a cada quadro desenhado.
    val h = height.toPx()
    val first = Brush.radialGradient(listOf(primary.copy(alpha = 0.20f), Color.Transparent), center = Offset(size.width * 0.15f, 0f), radius = h * 0.9f)
    val second = Brush.radialGradient(listOf(secondary.copy(alpha = 0.16f), Color.Transparent), center = Offset(size.width * 0.95f, h * 0.25f), radius = h * 0.8f)
    onDrawBehind {
        drawRect(first)
        drawRect(second)
    }
}

/** Cabeçalho das telas: botão voltar opcional, título grande, subtítulo e ações à direita. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    kicker: String? = null,
    onBack: (() -> Unit)? = null,
    inset: Dp = 20.dp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(top = if (onBack != null) 4.dp else 16.dp)) {
        if (onBack != null) {
            HeaderIconButton(
                Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onBack,
                Modifier.padding(start = (inset - 12.dp).coerceAtLeast(0.dp), bottom = 4.dp),
            )
        }
        Row(Modifier.padding(horizontal = inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                kicker?.let { Kicker(it); Spacer(Modifier.height(8.dp)) }
                Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                }
            }
            actions()
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, inset: Dp = 20.dp, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = inset), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Palette.SunsetGradient))
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(8.dp),
            )
        }
    }
}

/** Cartão de superfície padrão: fundo elevado, borda sutil e cantos generosos. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    brush: Brush? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .pressScale(source, enabled = onClick != null)
            .clip(shape)
            .then(if (brush != null) Modifier.background(brush) else Modifier.background(Palette.SurfaceHigh))
            .border(1.dp, Palette.Outline.copy(alpha = 0.7f), shape)
            .then(if (onClick != null) Modifier.clickable(source, null, onClick = onClick) else Modifier),
        content = content,
    )
}

/** Encolhe levemente o elemento enquanto pressionado: resposta tátil visual. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, enabled: Boolean = true, pressed: Float = 0.96f): Modifier {
    if (!enabled) return this
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, spring(stiffness = 600f), label = "press")
    // Lido só na camada gráfica: a animação redesenha o elemento sem recompor o cartão a cada quadro.
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

// ---------------------------------------------------------------- botões

/** Botão principal com o degradê do sol. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 48.dp,
) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .pressScale(source, enabled)
            .defaultMinSize(minHeight = height)
            .clip(shape)
            .background(if (enabled) Palette.SunsetHorizontal else Brush.linearGradient(listOf(Palette.SurfaceHighest, Palette.SurfaceHighest)))
            .clickable(source, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val content = if (enabled) Color(0xFF1C0010) else Palette.TextMuted
        icon?.let { Icon(it, null, tint = content, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

/** Botão secundário: contorno fino e fundo translúcido. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Palette.TextPrimary) {
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .pressScale(source)
            .defaultMinSize(minHeight = 44.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.05f))
            .border(1.dp, Palette.Outline, shape)
            .clickable(source, null, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

/**
 * Botão redondo dos cabeçalhos (voltar, atualizar, navegador…): o mesmo visual em todas as telas.
 * [badge] maior que zero mostra um contador no canto; [busy] troca o ícone por um indicador de progresso.
 */
@Composable
fun HeaderIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Palette.TextPrimary,
    badge: Int = 0,
    busy: Boolean = false,
    enabled: Boolean = true,
) {
    BadgedBox(
        badge = { if (badge > 0) androidx.compose.material3.Badge(containerColor = Palette.Neon, contentColor = Color(0xFF1C0010)) { Text("$badge") } },
        modifier = modifier,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled && !busy,
            modifier = Modifier.background(Palette.SurfaceHigh.copy(alpha = 0.85f), CircleShape).border(1.dp, Palette.Outline, CircleShape),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Palette.Cyan)
            else Icon(icon, description, tint = tint)
        }
    }
}

/** Ícone dentro de um "squircle" colorido, usado em listas de ajustes e atalhos. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f)) }
}

// ---------------------------------------------------------------- jogos e consoles

/**
 * Capa do jogo com fallback gerado: um "cartucho" na cor do console com o título.
 * Se a imagem carregar, ela cobre o fallback; se falhar, o fallback continua visível.
 */
@Composable
fun GameCover(
    title: String,
    system: GameSystem?,
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val accent = system?.accentColor() ?: Palette.Violet
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.6f), Palette.SurfaceHighest, Palette.Surface))),
    ) {
        // Fallback: etiqueta de cartucho com sulcos no topo, sigla e título.
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(4) { Box(Modifier.width(10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.18f))) }
            }
            Column {
                Text(system?.shortName.orEmpty(), style = TextStyle(fontFamily = PixelFamily, fontSize = 8.sp), color = Color.White.copy(alpha = 0.75f))
                Spacer(Modifier.height(6.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.95f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (url != null) {
            AsyncImage(model = url, contentDescription = title, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
        // Brilho de vidro na borda superior, dá volume à capa.
        Box(
            Modifier.matchParentSize().border(
                1.dp,
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))),
                RoundedCornerShape(corner),
            ),
        )
    }
}

@Composable
fun GameCard(game: Game, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = null) {
    val system = Systems.byId(game.systemId)
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .then(if (width != null) Modifier.width(width) else Modifier)
            .pressScale(source)
            .clickable(source, null, onClick = onClick),
    ) {
        Box {
            GameCover(game.title, system, game.coverUrl, Modifier.fillMaxWidth().aspectRatio(0.75f))
            if (game.favorite) {
                Icon(
                    Icons.Rounded.Favorite, stringResource(R.string.common_favorite), tint = Palette.Neon,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                        .background(Color(0xB30B0714), CircleShape).padding(4.dp),
                )
            }
            // Sem capa, o fallback já mostra a sigla do console.
            if (game.coverUrl != null) system?.let {
                Text(
                    it.shortName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                        .background(it.accentColor().copy(alpha = 0.85f).compositeOverInk(), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(game.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            game.region?.let { regionLabel(it) } ?: system?.name.orEmpty(),
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Esqueleto com as proporções do [GameCard], mostrado enquanto a lista ainda carrega. */
@Composable
fun GameCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.75f).shimmer(RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(13.dp).shimmer())
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth(0.45f).height(10.dp).shimmer())
    }
}

private fun Color.compositeOverInk(): Color {
    // Escurece cores de marca muito claras para o texto branco continuar legível.
    val lum = 0.299f * red + 0.587f * green + 0.114f * blue
    return if (lum > 0.6f) Color(red * 0.6f, green * 0.6f, blue * 0.6f, alpha) else this
}

/** Cartão de console na grade da biblioteca: sigla gigante em marca d'água e brilho na cor do sistema. */
@Composable
fun SystemTile(system: GameSystem, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = system.accentColor()
    val readable = system.readableAccent()
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .pressScale(source)
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .drawWithCache {
                val glow = Brush.radialGradient(listOf(accent.copy(alpha = 0.45f), Color.Transparent), center = Offset(size.width, 0f), radius = size.width * 0.95f)
                onDrawBehind { drawRect(glow) }
            }
            .border(1.dp, Brush.linearGradient(listOf(readable.copy(alpha = 0.55f), Palette.Outline.copy(alpha = 0.4f))), shape)
            .clickable(source, null, onClick = onClick),
    ) {
        Text(
            system.shortName,
            fontFamily = DisplayFamily, fontSize = 64.sp, fontWeight = FontWeight.Black,
            color = Color.White.copy(alpha = 0.05f), maxLines = 1, softWrap = false,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 4.dp).graphicsLayer { translationY = 18.dp.toPx() },
        )
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(system.shortName, fontFamily = DisplayFamily, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
                if (system.experimental) Badge(stringResource(R.string.common_beta), Palette.Sun)
            }
            Spacer(Modifier.height(20.dp))
            Text(system.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${system.manufacturer} · ${system.year}", style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (count == 0) Palette.TextMuted else readable))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (count == 0) stringResource(R.string.no_games) else pluralStringResource(R.plurals.games_count, count, count),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (count == 0) Palette.TextMuted else Palette.TextPrimary,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- pequenos elementos

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(84.dp).drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Palette.Neon.copy(alpha = 0.35f), Color.Transparent)), radius = size.minDimension)
                },
                contentAlignment = Alignment.Center,
            ) { IconTile(icon, Palette.Neon, size = 64.dp) }
            Spacer(Modifier.height(4.dp))
        }
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Palette.TextSecondary, icon: ImageVector? = null) {
    Row(
        modifier
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(50))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = color, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Campo de busca em cápsula, com ícone e botão de limpar. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = Palette.TextMuted) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Palette.TextSecondary) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.common_clear)) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(50),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = Palette.SurfaceHigh,
            focusedContainerColor = Palette.SurfaceHigh,
            unfocusedBorderColor = Palette.Outline,
            focusedBorderColor = Palette.Neon,
            cursorColor = Palette.Neon,
        ),
    )
}

/**
 * Chip de seleção próprio: preenchido com o degradê quando ativo. Anuncia o estado ao leitor de
 * tela (`selectable`) e tem altura mínima de 36dp, para o toque não escapar em listas densas.
 */
@Composable
fun SelectChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(shape)
            .then(
                if (selected) Modifier.background(Palette.SunsetHorizontal)
                else Modifier.background(Palette.SurfaceHigh).border(1.dp, Palette.Outline, shape),
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color(0xFF1C0010) else Palette.TextSecondary, maxLines = 1)
    }
}

/**
 * Chip de filtro tingido por uma cor própria (a do console, a do gênero): ponto ou ícone na
 * cor [accent] e, quando ativo, fundo e borda nessa mesma cor.
 */
@Composable
fun AccentChip(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .defaultMinSize(minHeight = 36.dp)
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.18f) else Palette.SurfaceHigh)
            .border(1.dp, if (selected) accent.copy(alpha = 0.85f) else Palette.Outline, shape)
            .selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
            .padding(start = if (icon != null) 10.dp else 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = accent, modifier = Modifier.size(16.dp))
        else Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(if (icon != null) 6.dp else 7.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Palette.TextPrimary else Palette.TextSecondary, maxLines = 1)
    }
}

/** Rótulo curto em caixa alta acima de um grupo de filtros ("CONSOLE", "GÊNERO"…). */
@Composable
fun FilterLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, modifier = Modifier.weight(1f))
        trailing()
    }
}

/**
 * Placeholder de carregamento: bloco na cor de superfície com um brilho que atravessa em loop.
 * O progresso é lido só na fase de desenho, então a animação não recompõe quem usa.
 */
@Composable
fun Modifier.shimmer(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmer",
    )
    return clip(shape).background(Palette.SurfaceHighest.copy(alpha = 0.7f)).drawBehind {
        val w = size.width
        val x = -w + progress.value * 2f * w
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent),
                start = Offset(x, 0f), end = Offset(x + w, size.height),
            ),
        )
    }
}

/**
 * Estende o elemento [horizontal] para cada lado, além do padding do pai. Usado em faixas com
 * rolagem dentro de listas com margem: os chips correm até a borda da tela em vez de serem
 * cortados na margem, e o 1º continua alinhado ao conteúdo (com o mesmo padding interno).
 */
fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
    if (!constraints.hasBoundedWidth) {
        val placeable = measurable.measure(constraints)
        return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Faixa horizontal de chips com rolagem, alinhada às margens da tela. */
@Composable
fun ChipStrip(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp), content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Barra de um download: vazia enquanto espera na fila, com a fração quando o tamanho é conhecido e
 * indeterminada no resto (extração, servidor que não informa o tamanho).
 */
@Composable
fun DownloadProgressBar(task: DownloadTask, modifier: Modifier = Modifier) {
    val bar = modifier.fillMaxWidth().clip(RoundedCornerShape(50))
    when {
        task.status == DownloadStatus.QUEUED ->
            LinearProgressIndicator(progress = { 0f }, modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
        task.status == DownloadStatus.DOWNLOADING && task.progress > 0f ->
            LinearProgressIndicator(progress = { task.progress }, modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
        else -> LinearProgressIndicator(modifier = bar, color = Palette.Cyan, trackColor = Palette.SurfaceHighest)
    }
}

/**
 * Contagem no plural certo também para zero: no português a categoria "one" do Android cobre 0 e 1
 * ("0 jogo"). Com zero pede a forma "other" ("0 jogos"), igual ao inglês.
 */
@Composable
fun countString(@PluralsRes id: Int, count: Int): String = pluralStringResource(id, if (count == 0) 2 else count, count)
