package com.retrovika.app.ui.screens.explore

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Business
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.VideogameAsset
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.retrovika.app.R
import com.retrovika.app.container
import com.retrovika.app.core.catalog.DownloadStatus
import com.retrovika.app.core.catalog.DownloadTask
import com.retrovika.app.core.catalog.Genre
import com.retrovika.app.core.catalog.RomVariant
import com.retrovika.app.core.catalog.downloadKey
import com.retrovika.app.core.catalog.regionOf
import com.retrovika.app.core.gameinfo.BackloggdInfo
import com.retrovika.app.core.gameinfo.HltbInfo
import com.retrovika.app.core.gameinfo.HltbTime
import com.retrovika.app.core.gameinfo.BackloggdReview
import com.retrovika.app.core.gameinfo.ExternalLink
import com.retrovika.app.core.gameinfo.ReviewScore
import com.retrovika.app.core.gameinfo.SiteRating
import com.retrovika.app.core.gameinfo.SourceDetails
import com.retrovika.app.core.gameinfo.WikiInfo
import com.retrovika.app.core.net.Urls
import com.retrovika.app.core.settings.localized
import com.retrovika.app.core.storage.formatBytes
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import com.retrovika.app.emulation.GameActivity
import com.retrovika.app.ui.components.busyWaitText
import com.retrovika.app.ui.components.DownloadProgressBar
import com.retrovika.app.ui.components.GameCover
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.HeaderIconButton
import com.retrovika.app.ui.components.IconTile
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.LocalBottomInset
import com.retrovika.app.ui.components.ReadableWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.semantics.Role
import com.retrovika.app.ui.components.Pill
import com.retrovika.app.ui.components.SectionHeader
import com.retrovika.app.ui.components.accentColor
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.components.readableAccent
import com.retrovika.app.ui.components.regionLabel
import com.retrovika.app.ui.components.shimmer
import com.retrovika.app.ui.theme.Palette
import java.util.Locale

private val Gutter = 20.dp
private val CardShape = RoundedCornerShape(20.dp)

/**
 * Página de um jogo do Explorar: capa e fundo, notas (Backloggd, crítica, usuários da fonte), o
 * botão de download, a descrição, capturas de tela, a ficha técnica, a comunidade do Backloggd, os
 * arquivos disponíveis e links para outras bases. Cada bloco aparece quando seus dados chegam.
 */
@Composable
fun CatalogGameScreen(entryKey: String, onBack: () -> Unit, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val entry = remember(entryKey) { app.catalog.opened(entryKey) }
    if (entry == null) {
        // Processo recriado: a entrada da busca não existe mais; volta ao Explorar. Espera a tela ficar
        // ativa: durante a transição de entrada o "voltar" é ignorado e ficaria uma tela vazia.
        val owner = LocalLifecycleOwner.current
        LaunchedEffect(Unit) { owner.lifecycle.withResumed { }; onBack() }
        return
    }
    val locale = remember { context.localized().resources.configuration.locales[0] ?: Locale.getDefault() }
    val lang = if (locale.language == "pt") "pt" else "en"
    val vm: CatalogGameViewModel = viewModel(key = "cgame-$entryKey-$lang") { CatalogGameViewModel(app, entry, lang) }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val tasks by app.downloads.tasks.collectAsStateWithLifecycle()
    val task = remember(tasks, entryKey) { tasks.firstOrNull { it.entryKey == entry.downloadKey } }
    val system = Systems.byId(entry.systemId)
    // A ficha (notas, descrição, downloads) é de uma fonte; os arquivos podem vir de todas as mescladas na entrada.
    val sourceName = remember(state.detailsSourceId) { app.catalog.source(state.detailsSourceId).name }
    val sourceNames = remember(entry) { entry.sourceIds.map { app.catalog.source(it).name } }
    val nameOf = remember { { id: String -> app.catalog.sources.firstOrNull { it.id == id }?.name } }
    var viewer by remember { mutableStateOf<Int?>(null) }

    val backloggd = state.backloggd.value
    val wiki = state.wiki.value
    val source = state.source
    val screenshots = remember(source.screenshots, backloggd?.backdropUrl) {
        (source.screenshots + listOfNotNull(backloggd?.backdropUrl)).distinct()
    }

    Box(Modifier.fillMaxSize().background(Palette.Ink)) {
        // Em telas largas a página fica numa coluna central (ReadableWidth); a rolagem segue a tela toda.
        ReadableWidth { side ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, bottom = 40.dp + LocalBottomInset.current)) {
            item(key = "hero", contentType = "hero") {
                Hero(entry.title, source, backloggd, wiki, system, sourceName)
            }
            item(key = "scores", contentType = "scores") {
                ScoresRow(state, sourceName, locale)
            }
            item(key = "download", contentType = "download") {
                DownloadPanel(state, task, sourceNames.joinToString(" & "), onDownload = vm::download, onPlay = { task?.gameId?.let { GameActivity.launch(context, it) } }, onOpenDownloads = onOpenDownloads)
            }
            item(key = "pills", contentType = "pills") { QuickFacts(source, state.sizes, locale) }
            item(key = "about", contentType = "about") { About(state, sourceName) }
            if (screenshots.isNotEmpty()) {
                item(key = "shots", contentType = "shots") { Screenshots(screenshots, onOpen = { viewer = it }) }
            }
            item(key = "facts", contentType = "facts") { Facts(state, system, sourceName, locale) }
            item(key = "community", contentType = "community") {
                Community(state.backloggd, entry.title, locale, onOpen = { CatalogGameFormat.openUrl(context, it) })
            }
            item(key = "playtime", contentType = "playtime") {
                PlayTime(state.hltb, locale, onOpen = { CatalogGameFormat.openUrl(context, it) })
            }
            item(key = "files", contentType = "files") { Files(state, nameOf, onDownload = vm::download) }
            item(key = "links", contentType = "links") { Links(state, sourceName, nameOf, onOpen = { CatalogGameFormat.openUrl(context, it) }) }
            item(key = "footer", contentType = "footer") {
                Text(
                    stringResource(R.string.cgame_attribution),
                    style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 24.dp),
                )
            }
        }
        }

        // Botões fixos sobre a página: voltar, abrir a página da fonte e compartilhar.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HeaderIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onBack)
            Spacer(Modifier.weight(1f))
            val site = source.website ?: entry.website
            if (site != null) {
                HeaderIconButton(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.cgame_open_site), { CatalogGameFormat.openUrl(context, site) })
                HeaderIconButton(Icons.Rounded.Share, stringResource(R.string.cgame_share), { CatalogGameFormat.share(context, entry.title, site) })
            }
        }
    }

    viewer?.let { start -> ScreenshotViewer(screenshots, start, onDismiss = { viewer = null }) }
    prompt?.let { VariantPickerSheet(it, sourceName = nameOf, onPick = { _, v -> vm.download(v) }, onDismiss = vm::dismissPrompt) }
}

// ---------------------------------------------------------------- cabeçalho

@Composable
private fun Hero(title: String, source: SourceDetails, backloggd: BackloggdInfo?, wiki: WikiInfo?, system: GameSystem?, sourceName: String) {
    val accent = system?.accentColor() ?: Palette.Violet
    // Fundo: uma captura em alta (Backloggd/IGDB) ou da fonte; sem nenhuma, a capa desfocada.
    val backdrop = backloggd?.backdropUrl ?: source.screenshots.firstOrNull()
    val cover = source.coverUrl ?: backloggd?.coverUrl
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(300.dp).ambientGlow(primary = accent, secondary = Palette.Neon, height = 300.dp))
        when {
            backdrop != null -> AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(280.dp).graphicsLayer { alpha = 0.85f })
            cover != null -> AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(280.dp).blur(36.dp).graphicsLayer { alpha = 0.6f })
        }
        Box(
            Modifier.fillMaxWidth().height(282.dp)
                .background(Brush.verticalGradient(0f to Color(0x800B0714), 0.45f to Color(0x330B0714), 1f to Palette.Ink)),
        )
        Row(Modifier.padding(start = Gutter, end = Gutter, top = 176.dp), verticalAlignment = Alignment.Bottom) {
            GameCover(
                title, system, cover,
                Modifier.width(118.dp).height(160.dp).border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp)),
                corner = 16.dp,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f).padding(bottom = 2.dp)) {
                Kicker(system?.shortName ?: sourceName, color = system?.readableAccent() ?: Palette.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Text(source.title ?: title, style = MaterialTheme.typography.headlineSmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                val companies = (wiki?.developers.orEmpty().ifEmpty { source.developers }.ifEmpty { backloggd?.companies.orEmpty() }).take(2)
                val year = CatalogGameFormat.year(wiki?.releaseDate) ?: backloggd?.year ?: CatalogGameFormat.year(source.releaseDate)
                val subtitle = listOfNotNull(companies.joinToString(", ").ifBlank { null }, year).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    // Gêneros como chips: os da lista do Explorar com ícone e cor, os demais em texto.
    val genres = (backloggd?.genres.orEmpty().ifEmpty { wiki?.genres.orEmpty() }.ifEmpty { source.genres }).take(5)
    if (genres.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Gutter, end = Gutter, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            genres.forEach { g ->
                val known = Genre.of(listOf(g)).firstOrNull()
                Pill(g, color = known?.accent ?: Palette.TextSecondary, icon = known?.icon ?: Icons.Rounded.Category)
            }
        }
    }
}

// ---------------------------------------------------------------- notas

@Composable
private fun ScoresRow(state: CatalogGameState, sourceName: String, locale: Locale) {
    val backloggd = state.backloggd
    val critics = state.wiki.value?.scores.orEmpty().sortedByDescending { it.reviewer.contains("Metacritic", true) }.take(3)
    val siteRating = state.source.rating
    val loading = backloggd is Part.Loading || state.wiki is Part.Loading
    val hasBackloggd = backloggd.value?.rating != null
    if (!loading && !hasBackloggd && critics.isEmpty() && siteRating == null) return
    Column(Modifier.padding(top = 22.dp)) {
        SectionHeader(stringResource(R.string.cgame_scores))
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                backloggd is Part.Loading -> item { ScoreSkeleton(wide = true) }
                hasBackloggd -> item { BackloggdScoreCard(backloggd.value!!, locale) }
            }
            if (state.wiki is Part.Loading) item { ScoreSkeleton(wide = false) }
            critics.forEach { item { CriticCard(it) } }
            siteRating?.let { item { SiteRatingCard(it, sourceName, locale) } }
        }
    }
}

@Composable
private fun BackloggdScoreCard(info: BackloggdInfo, locale: Locale) {
    val rating = info.rating ?: return
    ScoreSurface(Modifier.width(236.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandDot("B", Palette.Neon)
            Spacer(Modifier.width(8.dp))
            Text("Backloggd", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            val text = CatalogGameFormat.rating(rating, locale)
            Text(text, fontSize = 38.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
            Text(" /5", style = MaterialTheme.typography.titleMedium, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.width(12.dp))
            if (info.histogram.isNotEmpty()) Histogram(info.histogram, Modifier.weight(1f).height(40.dp).padding(bottom = 6.dp), compact = true)
        }
        StarRow(rating, 5.0, size = 16.dp)
        Spacer(Modifier.height(6.dp))
        info.ratingCount?.let {
            Text(stringResource(R.string.cgame_backloggd_ratings, CatalogGameFormat.compact(it, locale)), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun CriticCard(score: ReviewScore) {
    val normalized = score.normalized
    val color = when {
        normalized == null -> Palette.Cyan
        normalized >= 75 -> Palette.Success
        normalized >= 50 -> Palette.Sun
        else -> Palette.Coral
    }
    ScoreSurface(Modifier.widthIn(min = 132.dp, max = 180.dp)) {
        Text(stringResource(R.string.cgame_critics).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, letterSpacing = 1.sp)
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(normalized?.toString() ?: score.score, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = color)
        }
        Spacer(Modifier.height(10.dp))
        Text(score.reviewer, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (normalized != null) Text(score.score, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
    }
}

@Composable
private fun SiteRatingCard(rating: SiteRating, sourceName: String, locale: Locale) {
    ScoreSurface(Modifier.widthIn(min = 150.dp, max = 190.dp)) {
        Text(stringResource(R.string.cgame_site_users, sourceName).uppercase(), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, letterSpacing = 1.sp, maxLines = 1)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(CatalogGameFormat.rating(rating.value, locale), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text(" /${rating.best.toInt()}", style = MaterialTheme.typography.titleSmall, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 5.dp))
        }
        StarRow(rating.value, rating.best, size = 14.dp, color = Palette.Sun)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.cgame_site_votes, CatalogGameFormat.compact(rating.count, locale)), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
    }
}

@Composable
private fun ScoreSurface(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        // Altura mínima, não fixa: com fonte grande (1,3× ou mais) o conteúdo era cortado embaixo.
        modifier.heightIn(min = 150.dp).clip(CardShape)
            .background(Brush.verticalGradient(listOf(Palette.SurfaceHighest, Palette.SurfaceHigh)))
            .border(1.dp, Palette.Outline.copy(alpha = 0.8f), CardShape)
            .padding(14.dp),
    ) { content() }
}

@Composable
private fun ScoreSkeleton(wide: Boolean) {
    Box(Modifier.width(if (wide) 236.dp else 140.dp).height(150.dp).shimmer(CardShape))
}

/** Marca redonda com a inicial do site, no lugar do logotipo (que não podemos embutir). */
@Composable
private fun BrandDot(letter: String, color: Color, size: Dp = 22.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(letter, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Bold, color = Color(0xFF1C0010))
    }
}

/** Cinco estrelas preenchidas até [value] (de [best]); frações aparecem como estrela cortada. */
@Composable
private fun StarRow(value: Double, best: Double, size: Dp, color: Color = Palette.Neon) {
    val fraction = (value / best).coerceIn(0.0, 1.0).toFloat()
    val starSize = size
    val description = stringResource(R.string.cgame_rating_desc, String.format(Locale.getDefault(), "%.1f", value), best.toInt().toString())
    Box(Modifier.semantics { contentDescription = description }) {
        Row { repeat(5) { Icon(Icons.Rounded.StarOutline, null, tint = Palette.TextMuted, modifier = Modifier.size(starSize)) } }
        // As estrelas cheias são cortadas na fração da nota: 4,2 de 5 mostra 4 estrelas e um quinto.
        Row(Modifier.drawWithContent { clipRect(right = this.size.width * fraction) { this@drawWithContent.drawContent() } }) {
            repeat(5) { Icon(Icons.Rounded.Star, null, tint = color, modifier = Modifier.size(starSize)) }
        }
    }
}

/** Barras das 10 faixas de nota (0,5★ a 5★); a mais votada ganha o degradê cheio. */
@Composable
private fun Histogram(bars: List<Int>, modifier: Modifier, compact: Boolean = false) {
    val max = bars.maxOrNull()?.takeIf { it > 0 } ?: return
    val peak = bars.indexOf(max)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp), verticalAlignment = Alignment.Bottom) {
        bars.forEachIndexed { i, count ->
            val stars = String.format(Locale.getDefault(), "%.1f", (i + 1) / 2.0)
            val description = stringResource(R.string.cgame_histogram_desc, stars, count)
            Box(
                Modifier.weight(1f).fillMaxHeight((count.toFloat() / max).coerceAtLeast(0.04f))
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (i == peak) Palette.SunsetGradient else Brush.verticalGradient(listOf(Palette.Neon.copy(alpha = 0.55f), Palette.Violet.copy(alpha = 0.45f))))
                    .semantics { contentDescription = description },
            )
        }
    }
}

// ---------------------------------------------------------------- download

@Composable
private fun DownloadPanel(
    state: CatalogGameState,
    task: DownloadTask?,
    sourceName: String,
    onDownload: () -> Unit,
    onPlay: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val sizes = state.sizes
    val sizeText = when {
        sizes.isEmpty() -> null
        sizes.size == 1 || sizes.min() == sizes.max() -> sizes.first().formatBytes()
        else -> "${sizes.min().formatBytes()} – ${sizes.max().formatBytes()}"
    }
    Column(
        Modifier.padding(start = Gutter, end = Gutter, top = 22.dp).fillMaxWidth().clip(CardShape)
            .background(Palette.SurfaceHigh).border(1.dp, Palette.Outline.copy(alpha = 0.8f), CardShape).padding(16.dp),
    ) {
        when (task?.status) {
            DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING, DownloadStatus.EXTRACTING -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val busy = busyWaitText(task)
                    val label = when {
                        busy != null -> busy
                        task.status == DownloadStatus.QUEUED -> stringResource(R.string.cgame_queued)
                        task.status == DownloadStatus.EXTRACTING -> stringResource(R.string.cgame_extracting)
                        task.progress >= 0f -> stringResource(R.string.cgame_downloading, (task.progress * 100).toInt())
                        else -> stringResource(R.string.cgame_downloading_indeterminate)
                    }
                    Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        stringResource(R.string.cgame_see_downloads), style = MaterialTheme.typography.labelLarge, color = Palette.Cyan,
                        modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button, onClick = onOpenDownloads).padding(6.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                DownloadProgressBar(task)
                if (task.bytesTotal > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("${task.bytesDone.formatBytes()} / ${task.bytesTotal.formatBytes()}", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                }
            }
            DownloadStatus.DONE -> GradientButton(stringResource(R.string.common_play), onPlay, Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow, height = 54.dp)
            DownloadStatus.FAILED -> {
                task.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Coral)
                    Spacer(Modifier.height(10.dp))
                }
                GradientButton(stringResource(R.string.common_retry), onDownload, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, height = 54.dp)
            }
            else -> GradientButton(
                sizeText?.let { stringResource(R.string.cgame_download_size, it) } ?: stringResource(R.string.common_download),
                onDownload, Modifier.fillMaxWidth(), icon = Icons.Rounded.Download, height = 54.dp,
            )
        }
        // Resumo do que será baixado: quantos arquivos, tamanho e de onde vem.
        val variants = state.variants.value
        val summary = when {
            variants == null || variants.isEmpty() -> null
            variants.size == 1 -> sizeText?.let { stringResource(R.string.cgame_one_file, it) }
            sizeText != null -> stringResource(R.string.cgame_files_count, variants.size, sizeText)
            else -> stringResource(R.string.cgame_files_count_nosize, variants.size)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudDownload, null, tint = Palette.TextMuted, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                listOfNotNull(summary, stringResource(R.string.cgame_via, sourceName)).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary,
            )
            if (state.variants is Part.Loading) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Palette.TextMuted)
            }
        }
    }
}

@Composable
private fun QuickFacts(source: SourceDetails, sizes: List<Long>, locale: Locale) {
    val pills = buildList<Pair<String, ImageVector>> {
        source.region?.let { add(regionText(it) to Icons.Rounded.Public) }
        if (source.languages.isNotEmpty()) {
            val more = if (source.languages.size > 3) " +${source.languages.size - 3}" else ""
            add((source.languages.take(3).joinToString(", ") + more) to Icons.Rounded.Translate)
        }
        source.format?.let { add(it to Icons.AutoMirrored.Rounded.InsertDriveFile) }
        sizes.maxOrNull()?.let { add(it.formatBytes() to Icons.Rounded.Storage) }
        source.downloads?.let { add(CatalogGameFormat.compact(it, locale) to Icons.Rounded.Download) }
    }
    if (pills.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = Gutter, end = Gutter, top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { pills.forEach { (text, icon) -> Pill(text, icon = icon) } }
}

/** Região como a fonte escreve ("Europe", "USA") no idioma do app, quando conhecida. */
@Composable
private fun regionText(raw: String): String = regionOf("($raw)")?.let { regionLabel(it) } ?: raw

// ---------------------------------------------------------------- sobre

@Composable
private fun About(state: CatalogGameState, sourceName: String) {
    val wiki = state.wiki.value
    val backloggd = state.backloggd.value
    val source = state.source
    // Texto principal: o artigo da Wikipedia (no idioma do app, quando existe), senão o resumo do IGDB
    // no Backloggd, senão a descrição da fonte. Notas de quem enviou (patches, hacks) ficam à parte.
    val (text, credit) = when {
        wiki?.extract != null -> wiki.extract to "Wikipedia"
        backloggd?.description != null -> backloggd.description to "Backloggd / IGDB"
        source.description != null -> source.description to sourceName
        else -> null to null
    }
    val loading = text == null && (state.wiki is Part.Loading || state.backloggd is Part.Loading || state.details is Part.Loading)
    if (text == null && !loading) return
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader(stringResource(R.string.cgame_about))
        Spacer(Modifier.height(10.dp))
        if (text == null) {
            Column(Modifier.padding(horizontal = Gutter)) {
                repeat(4) { i -> Box(Modifier.fillMaxWidth(if (i == 3) 0.6f else 1f).height(12.dp).shimmer()); Spacer(Modifier.height(8.dp)) }
            }
        } else {
            ExpandableText(text, Modifier.padding(horizontal = Gutter))
            credit?.let {
                Text(
                    stringResource(R.string.cgame_text_from, it), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted,
                    modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
                )
            }
        }
        val notes = source.description?.takeIf { credit != sourceName }
        if (notes != null) {
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Palette.Surface).border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(14.dp),
            ) {
                Text(stringResource(R.string.cgame_source_notes, sourceName), style = MaterialTheme.typography.labelLarge, color = Palette.Cyan)
                Spacer(Modifier.height(6.dp))
                ExpandableText(notes, collapsedLines = 4, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 6,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(modifier.animateContentSize()) {
        Text(
            text, style = style, color = Palette.TextSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
        if (overflows || expanded) {
            Text(
                stringResource(if (expanded) R.string.cgame_read_less else R.string.cgame_read_more),
                style = MaterialTheme.typography.labelLarge, color = Palette.Neon,
                modifier = Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button) { expanded = !expanded }.padding(vertical = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- capturas

@Composable
private fun Screenshots(urls: List<String>, onOpen: (Int) -> Unit) {
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader(stringResource(R.string.cgame_screenshots))
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(urls, key = { _, u -> u }) { i, url ->
                val description = stringResource(R.string.cgame_screenshot_n, i + 1, urls.size)
                AsyncImage(
                    url, description, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(236.dp).aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp))
                        .background(Palette.SurfaceHigh).border(1.dp, Palette.Outline.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .clickable { onOpen(i) },
                )
            }
        }
    }
}

/** Capturas em tela cheia, deslizando entre elas. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenshotViewer(urls: List<String>, start: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, urls.lastIndex)) { urls.size }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.96f))) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                AsyncImage(urls[page], stringResource(R.string.cgame_screenshot_n, page + 1, urls.size), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${urls.size}", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.padding(start = 8.dp))
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color.White.copy(alpha = 0.12f), CircleShape)) {
                    Icon(Icons.Rounded.Close, stringResource(R.string.common_back), tint = Color.White)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- ficha técnica

private data class Fact(val icon: ImageVector, val label: String, val value: String)

@Composable
private fun Facts(state: CatalogGameState, system: GameSystem?, sourceName: String, locale: Locale) {
    val wiki = state.wiki.value
    val backloggd = state.backloggd.value
    val source = state.source
    fun List<String>.text() = distinct().joinToString(", ").ifBlank { null }
    val release = CatalogGameFormat.date(wiki?.releaseDate ?: source.releaseDate?.takeIf { it.length >= 7 } ?: backloggd?.releaseDate ?: source.releaseDate, locale)
    val sizes = state.sizes
    val facts = buildList {
        system?.let { add(Fact(Icons.Rounded.VideogameAsset, stringResource(R.string.cgame_fact_console), it.name)) }
        release?.let { add(Fact(Icons.Rounded.CalendarMonth, stringResource(R.string.cgame_fact_release), it)) }
        (wiki?.developers.orEmpty().ifEmpty { source.developers }.ifEmpty { backloggd?.companies.orEmpty() }).text()
            ?.let { add(Fact(Icons.Rounded.Business, stringResource(R.string.cgame_fact_developer), it)) }
        (wiki?.publishers.orEmpty().ifEmpty { listOfNotNull(source.publisher) }).text()
            ?.let { add(Fact(Icons.Rounded.Flag, stringResource(R.string.cgame_fact_publisher), it)) }
        (wiki?.genres.orEmpty().ifEmpty { backloggd?.genres.orEmpty() }.ifEmpty { source.genres }).text()
            ?.let { add(Fact(Icons.Rounded.Category, stringResource(R.string.cgame_fact_genres), it)) }
        wiki?.modes?.text()?.let { add(Fact(Icons.Rounded.Groups, stringResource(R.string.cgame_fact_modes), it)) }
        wiki?.series?.text()?.let { add(Fact(Icons.Rounded.Collections, stringResource(R.string.cgame_fact_series), it)) }
        wiki?.directors?.text()?.let { add(Fact(Icons.Rounded.Movie, stringResource(R.string.cgame_fact_director), it)) }
        wiki?.composers?.text()?.let { add(Fact(Icons.Rounded.LibraryMusic, stringResource(R.string.cgame_fact_composer), it)) }
        (backloggd?.platforms.orEmpty().ifEmpty { wiki?.platforms.orEmpty() }).text()
            ?.let { add(Fact(Icons.Rounded.SportsEsports, stringResource(R.string.cgame_fact_platforms), it)) }
        wiki?.ageRatings?.text()?.let { add(Fact(Icons.Rounded.Gavel, stringResource(R.string.cgame_fact_age), it)) }
        source.region?.let { add(Fact(Icons.Rounded.Public, stringResource(R.string.cgame_fact_region), regionText(it))) }
        source.languages.text()?.let { add(Fact(Icons.Rounded.Language, stringResource(R.string.cgame_fact_languages), it)) }
        source.format?.let { add(Fact(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.cgame_fact_format), it)) }
        source.serial?.let { add(Fact(Icons.Rounded.Numbers, stringResource(R.string.cgame_fact_serial), it)) }
        if (sizes.isNotEmpty()) {
            val text = if (sizes.min() == sizes.max()) sizes.first().formatBytes() else "${sizes.min().formatBytes()} – ${sizes.max().formatBytes()}"
            add(Fact(Icons.Rounded.Storage, stringResource(R.string.cgame_fact_size), text))
        }
        source.downloads?.let { add(Fact(Icons.Rounded.Download, stringResource(R.string.cgame_fact_downloads, sourceName), CatalogGameFormat.integer(it, locale))) }
        source.addedDate?.let { d -> CatalogGameFormat.date(d, locale)?.let { add(Fact(Icons.Rounded.Today, stringResource(R.string.cgame_fact_added), it)) } }
        source.license?.let { add(Fact(Icons.Rounded.Gavel, stringResource(R.string.cgame_fact_license), it)) }
    }
    val loading = state.details is Part.Loading || state.wiki is Part.Loading
    Column(Modifier.padding(top = 26.dp)) {
        SectionHeader(stringResource(R.string.cgame_facts))
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(CardShape).background(Palette.SurfaceHigh)
                .border(1.dp, Palette.Outline.copy(alpha = 0.8f), CardShape).padding(vertical = 6.dp),
        ) {
            facts.forEachIndexed { i, fact ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 58.dp), color = Palette.Outline.copy(alpha = 0.5f))
                FactRow(fact)
            }
            if (loading) {
                repeat(if (facts.isEmpty()) 5 else 2) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(30.dp).shimmer(RoundedCornerShape(10.dp)))
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Box(Modifier.width(80.dp).height(10.dp).shimmer())
                            Spacer(Modifier.height(6.dp))
                            Box(Modifier.width(160.dp).height(12.dp).shimmer())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FactRow(fact: Fact) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(fact.icon, Palette.Cyan, size = 30.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(fact.label, style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
            Text(fact.value, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary)
        }
    }
}

// ---------------------------------------------------------------- comunidade do Backloggd

@Composable
private fun Community(part: Part<BackloggdInfo?>, title: String, locale: Locale, onOpen: (String) -> Unit) {
    Column(Modifier.padding(top = 30.dp)) {
        SectionHeader(stringResource(R.string.cgame_community))
        Text(
            stringResource(R.string.cgame_community_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        when (part) {
            Part.Loading -> Box(Modifier.padding(horizontal = Gutter).fillMaxWidth().height(260.dp).shimmer(CardShape))
            Part.Failed -> CommunityMissing(stringResource(R.string.cgame_backloggd_failed), title, onOpen)
            is Part.Ready -> part.value?.let { CommunityContent(it, locale, onOpen) }
                ?: CommunityMissing(stringResource(R.string.cgame_backloggd_missing), title, onOpen)
        }
    }
}

@Composable
private fun CommunityMissing(message: String, title: String, onOpen: (String) -> Unit) {
    Row(
        Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(CardShape).background(Palette.SurfaceHigh)
            .border(1.dp, Palette.Outline.copy(alpha = 0.8f), CardShape).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandDot("B", Palette.Neon, 30.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.cgame_backloggd_search), style = MaterialTheme.typography.labelLarge, color = Palette.Neon,
            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                .clickable { onOpen("https://backloggd.com/search/games/${Urls.encode(com.retrovika.app.core.gameinfo.GameTitles.clean(title))}/") }
                .padding(8.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommunityContent(info: BackloggdInfo, locale: Locale, onOpen: (String) -> Unit) {
    Column(Modifier.padding(horizontal = Gutter)) {
        // Nota e distribuição
        Column(
            Modifier.fillMaxWidth().clip(CardShape).background(Brush.linearGradient(listOf(Color(0xFF2A0C3F), Palette.SurfaceHigh)))
                .border(1.dp, Palette.Neon.copy(alpha = 0.35f), CardShape).padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(R.string.cgame_avg_rating), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(info.rating?.let { CatalogGameFormat.rating(it, locale) } ?: "–", fontSize = 44.sp, fontWeight = FontWeight.Bold)
                        Text(" /5", style = MaterialTheme.typography.titleMedium, color = Palette.TextMuted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    info.rating?.let { StarRow(it, 5.0, size = 18.dp) }
                    info.ratingCount?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.cgame_backloggd_ratings, CatalogGameFormat.integer(it.toLong(), locale)), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                    }
                }
            }
            if (info.histogram.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.cgame_distribution), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                Spacer(Modifier.height(8.dp))
                Histogram(info.histogram, Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("½★", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                    Spacer(Modifier.weight(1f))
                    Text("5★", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                }
            }
        }

        // Reviews
        if (info.reviews.isNotEmpty()) {
            var showAll by rememberSaveable { mutableStateOf(false) }
            Spacer(Modifier.height(22.dp))
            Text(stringResource(R.string.cgame_reviews), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                info.reviews.take(if (showAll) info.reviews.size else 3).forEach { ReviewCard(it, locale, onOpen) }
            }
            if (!showAll && info.reviews.size > 3) {
                Spacer(Modifier.height(10.dp))
                GhostButton(stringResource(R.string.cgame_more_reviews), { showAll = true }, Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(14.dp))
        GhostButton(stringResource(R.string.cgame_open_backloggd), { onOpen(info.url) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew, tint = Palette.Neon)
    }
}

@Composable
private fun TimeTile(value: String, label: String, players: String, icon: ImageVector, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Palette.Sun.copy(alpha = 0.14f), Palette.SurfaceHigh)))
            .border(1.dp, Palette.Sun.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    ) {
        Icon(icon, null, tint = Palette.Sun, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(players, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Tempos do HowLongToBeat: história principal, com extras, 100%, todos os estilos e speedrun, cada um com
 * quantos jogadores registraram. Só aparece quando o Wikidata liga o jogo a uma página de lá.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayTime(part: Part<HltbInfo?>, locale: Locale, onOpen: (String) -> Unit) {
    val info = part.value ?: return
    Column(Modifier.padding(top = 30.dp)) {
        SectionHeader(stringResource(R.string.cgame_time))
        Text(
            stringResource(R.string.cgame_time_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = Gutter)) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                info.times.forEach { time ->
                    val (label, icon) = when (time.kind) {
                        HltbTime.Kind.MAIN -> R.string.cgame_hltb_main to Icons.Rounded.Flag
                        HltbTime.Kind.EXTRAS -> R.string.cgame_hltb_extras to Icons.Rounded.Explore
                        HltbTime.Kind.COMPLETIONIST -> R.string.cgame_hltb_completionist to Icons.Rounded.EmojiEvents
                        HltbTime.Kind.ALL_STYLES -> R.string.cgame_hltb_all to Icons.Rounded.Timer
                        HltbTime.Kind.SPEEDRUN -> R.string.cgame_hltb_speedrun to Icons.Rounded.Bolt
                    }
                    val players = pluralStringResource(R.plurals.cgame_hltb_players, time.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), CatalogGameFormat.compact(time.count, locale))
                    TimeTile(hltbDuration(time.seconds, locale), stringResource(label), players, icon, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            GhostButton(stringResource(R.string.cgame_open_hltb), { onOpen(info.url) }, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew, tint = Palette.Sun)
        }
    }
}

/** Como o HowLongToBeat mostra: minutos abaixo de 1 h, depois horas arredondadas para a meia hora ("10½ h"). */
@Composable
private fun hltbDuration(seconds: Long, locale: Locale): String {
    if (seconds < 3600) return stringResource(R.string.cgame_hltb_minutes, ((seconds + 30) / 60).toInt().coerceAtLeast(1))
    val halves = (seconds + 900) / 1800
    val whole = CatalogGameFormat.integer(halves / 2, locale)
    return stringResource(R.string.cgame_hltb_hours, if (halves % 2 == 1L) "$whole½" else whole)
}

@Composable
private fun ReviewCard(review: BackloggdReview, locale: Locale, onOpen: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Surface)
            .border(1.dp, Palette.Outline.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .then(if (review.url != null) Modifier.clickable { onOpen(review.url) } else Modifier)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(Palette.SurfaceHighest), contentAlignment = Alignment.Center) {
                Text(review.user.take(1).uppercase(), style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
                review.avatarUrl?.let { AsyncImage(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(review.user, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    review.rating?.let { StarRow(it, 5.0, size = 12.dp); Spacer(Modifier.width(8.dp)) }
                    val meta = listOfNotNull(
                        review.status?.let { s -> CatalogGameFormat.status(s)?.let { stringResource(it) } ?: s },
                        review.platform,
                        review.date?.take(10)?.let { CatalogGameFormat.date(it, locale) },
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        ExpandableText(review.text, collapsedLines = 5, style = MaterialTheme.typography.bodySmall)
        review.likes?.takeIf { it > 0 }?.let {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = Palette.Neon, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.cgame_review_likes, it), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------- arquivos e links

@Composable
private fun Files(state: CatalogGameState, sourceName: (String) -> String?, onDownload: (RomVariant) -> Unit) {
    val part = state.variants
    // Um arquivo só já está no botão principal; a lista serve para escolher entre versões.
    if (part is Part.Ready && part.value.size <= 1) return
    Column(Modifier.padding(top = 30.dp)) {
        SectionHeader(stringResource(R.string.cgame_files))
        Text(
            stringResource(R.string.cgame_files_subtitle), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            modifier = Modifier.padding(horizontal = Gutter, vertical = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.padding(horizontal = Gutter).fillMaxWidth().clip(CardShape).background(Palette.SurfaceHigh)
                .border(1.dp, Palette.Outline.copy(alpha = 0.8f), CardShape),
        ) {
            when (part) {
                Part.Loading -> repeat(3) { Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp, vertical = 8.dp).shimmer(RoundedCornerShape(12.dp))) }
                Part.Failed -> Text(stringResource(R.string.cgame_files_failed), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, modifier = Modifier.padding(14.dp))
                is Part.Ready -> {
                val sources = part.value.variantSources()
                part.value.forEachIndexed { i, v ->
                    if (i > 0) HorizontalDivider(color = Palette.Outline.copy(alpha = 0.5f))
                    Row(
                        Modifier.fillMaxWidth().clickable { onDownload(v) }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(v.label, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val meta = listOfNotNull(
                                v.region?.let { regionLabel(it) }, v.sizeBytes?.takeIf { it > 0 }?.formatBytes(), v.note, v.sourceLabel(sources, sourceName),
                            )
                            if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                        }
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Rounded.Download, stringResource(R.string.common_download), tint = Palette.Cyan, modifier = Modifier.size(20.dp))
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Links(state: CatalogGameState, sourceName: String, nameOf: (String) -> String?, onOpen: (String) -> Unit) {
    val backloggd = state.backloggd.value
    val wiki = state.wiki.value
    val links = buildList {
        backloggd?.let { add(ExternalLink("Backloggd", it.url)) }
        wiki?.articleUrl?.let { add(ExternalLink("Wikipedia", it)) }
        addAll(wiki?.links.orEmpty())
        if (wiki?.links.orEmpty().none { it.name == "IGDB" }) backloggd?.igdbUrl?.let { add(ExternalLink("IGDB", it)) }
        (state.source.website ?: state.entry.website)?.let { add(ExternalLink(sourceName, it)) }
        // As outras páginas do mesmo jogo (outra fonte, ou outra página na mesma).
        state.entry.members.forEach { m -> m.website?.let { url -> nameOf(m.sourceId)?.let { add(ExternalLink(it, url)) } } }
    }.distinctBy { it.url }
    if (links.isEmpty()) return
    Column(Modifier.padding(top = 30.dp)) {
        SectionHeader(stringResource(R.string.cgame_links))
        Spacer(Modifier.height(12.dp))
        FlowRow(Modifier.padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { link ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).border(1.dp, Palette.Outline, RoundedCornerShape(50))
                        .clickable { onOpen(link.url) }.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(link.name, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, tint = Palette.TextMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
