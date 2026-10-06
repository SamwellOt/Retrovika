package com.retrovika.app.emulation

import androidx.annotation.StringRes
import com.retrovika.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.retrovika.app.ui.components.Badge
import com.retrovika.app.ui.components.ConfirmDialog
import com.retrovika.app.ui.components.focusRing
import com.retrovika.app.ui.components.pressScale
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.settings.AppSettings
import com.retrovika.app.core.settings.ShaderOption
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.emulation.input.PadListener
import com.retrovika.app.emulation.input.VirtualGamepad
import com.retrovika.app.emulation.input.PadProfile
import android.graphics.Bitmap
import com.retrovika.app.ui.components.BrandMark
import com.retrovika.app.ui.components.GhostButton
import com.retrovika.app.ui.components.GradientButton
import com.retrovika.app.ui.components.Kicker
import com.retrovika.app.ui.components.SelectChip
import com.retrovika.app.ui.components.ambientGlow
import com.retrovika.app.ui.theme.Palette
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
fun GameScreen(
    state: EmulationUi,
    game: Game?,
    system: GameSystem?,
    settings: AppSettings,
    menuOpen: Boolean,
    fastForward: Boolean,
    showPad: Boolean,
    padProfile: PadProfile,
    padEditing: Boolean,
    editorBackdrop: Bitmap?,
    toast: String?,
    padListener: PadListener,
    menu: MenuActions,
    onDismissToast: () -> Unit,
    /** Esperando o núcleo (o estado sai antes do menu abrir): um indicador mostra que o toque chegou. */
    busy: Boolean = false,
    /**
     * O menu já aparece, mas o estado do jogo ainda está sendo capturado na thread de emulação (que segue rodando
     * até ele sair): o que depende do estado (gravar num slot, traduzir, hospedar partida) fica desligado.
     */
    menuPending: Boolean = false,
    /** Controle físico em uso: o menu abre com o foco em "Continuar", mesmo se o último toque na tela foi um dedo. */
    controllerActive: Boolean = false,
    /** Carregando o jogo (do GLRetroView criado até o primeiro quadro): cobre a tela preta do núcleo. */
    loading: LoadingUi? = null,
    /** Contador de desempenho: lido só dentro dele, uma vez por segundo, sem recompor a tela do jogo; nulo = escondido. */
    perfStats: () -> PerfStats? = { null },
) {
    val menuShown = menuOpen || menuPending
    CompositionLocalProvider(LocalContentColor provides Palette.TextPrimary) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (state) {
            is EmulationUi.Preparing -> PreparingView(state, game, system)
            is EmulationUi.Failed -> FailedView(state, onExit = menu::exit)
            is EmulationUi.Benchmarking -> BenchmarkView(state, system, onSkip = menu::skipBenchmark)
            is EmulationUi.Running -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val portrait = maxHeight > maxWidth
                val padShown = showPad && system != null && padProfile.visible
                // Sem controle (ou com ele sobreposto) o jogo fica com a tela inteira: jogos só de toque
                // no DS/3DS usam a tela toda.
                val fullVideo = padProfile.fullScreenVideo(portrait, padShown)
                // A estrutura é sempre a mesma (vídeo + controle) para o GLRetroView nunca ser recriado ao girar a tela.
                val videoModifier = if (fullVideo) Modifier.fillMaxSize() else Modifier.fillMaxWidth().fillMaxHeight(VIDEO_SPLIT).align(Alignment.TopCenter)
                AndroidView(factory = { state.view }, modifier = videoModifier)
                if (padShown && system != null) {
                    VirtualGamepad(
                        layout = system.layout,
                        listener = padListener,
                        // Dividido, o controle não cobre o jogo: fica sempre opaco; a opacidade (do núcleo ou geral) vale sobreposto.
                        opacity = if (fullVideo) padProfile.opacity ?: settings.padOpacity else 1f,
                        scale = padProfile.scale ?: settings.padScale,
                        haptics = settings.haptics,
                        portrait = portrait,
                        overlay = fullVideo,
                        elements = padProfile.elements(portrait),
                        modifier = padModifier(fullVideo),
                    )
                }
                if (padProfile.showHud) {
                    Hud(
                        fastForward = fastForward,
                        onMenu = menu::open,
                        onFastForward = menu::toggleFastForward,
                        onTranslate = if (menu.canTranslate()) menu::translate else null,
                        modifier = if (!fullVideo) {
                            Modifier.align(Alignment.TopCenter).padding(top = maxHeight * VIDEO_SPLIT + 4.dp)
                        } else {
                            // Tela inteira: a câmera na tela (furo ou entalhe) ficaria por cima dos botões.
                            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top)).padding(top = 12.dp)
                        },
                        vertical = false,
                        // Sem controle na tela os botões ficam discretos para não tapar o jogo.
                        dimmed = !padShown,
                    )
                }
                (menu.netplay().ui as? NetplayUi.Playing)?.let {
                    NetplayBadge(it, Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(10.dp))
                }
                PerfHud(perfStats, Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.End)).padding(6.dp))
                // Sobre o vídeo, no mesmo lugar dele: as caixas traduzidas batem com o texto da captura.
                menu.translation()?.let { TranslationOverlay(it, onClose = menu::closeTranslation, modifier = videoModifier) }
                // Por cima de tudo (controle e botões incluídos): a vista do núcleo tem de existir já, é ela que cria a
                // superfície onde o jogo carrega, mas até o primeiro quadro ela é só um quadrado preto. Opaco e
                // consumindo os toques: nada chega ao controle nem ao jogo por baixo. Sai com um fade.
                var lastLoading by remember { mutableStateOf<LoadingUi?>(null) }
                if (loading != null) lastLoading = loading
                AnimatedVisibility(visible = loading != null, enter = EnterTransition.None, exit = fadeOut(tween(250))) {
                    lastLoading?.let {
                        PreparingView(
                            EmulationUi.Preparing(it.message, null), game, system, it.backdrop,
                            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                        )
                    }
                }
            }
        }

        // A aba fica aqui: o menu sai da composição enquanto o editor do controle está aberto e, ao voltar,
        // precisa continuar em Controle. Cada nova abertura do menu começa em Estados.
        var menuTab by remember { mutableStateOf(MenuTab.STATES) }
        // O foco inicial vai para "Continuar" uma vez por abertura: voltar do editor do controle (que recria o menu)
        // não o tira da aba Controle.
        var focusPlaced by remember { mutableStateOf(false) }
        LaunchedEffect(menuShown) { if (!menuShown) { menuTab = MenuTab.STATES; focusPlaced = false } }

        AnimatedVisibility(visible = menuShown && !padEditing, enter = fadeIn(), exit = fadeOut()) {
            PauseMenu(
                game, system, menu, fastForward, settings, padProfile, menuTab, ready = !menuPending, capturing = menuPending && busy,
                placeFocus = !focusPlaced, controllerActive = controllerActive, onFocusPlaced = { focusPlaced = true },
            ) { menuTab = it }
        }

        if (menuOpen) menu.sharing()?.let { ShareStateSheet(it, menu) }

        if (menuOpen && padEditing && system != null) {
            PadLayoutEditor(
                layout = system.layout,
                profile = padProfile,
                settings = settings,
                backdrop = editorBackdrop,
                onSave = { menu.setPadProfile(it); menu.stopPadEditor() },
                onCancel = menu::stopPadEditor,
            )
        }

        // Com o menu na tela quem avisa é o próprio menu ([PauseMenu], "capturing").
        if (busy && !menuShown) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Neon, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
            }
        }

        // Por cima do menu: o QR code do anfitrião ou o "conectando" do convidado.
        menu.netplay().ui?.takeIf { it !is NetplayUi.Playing }?.let { NetplaySheet(it, onCancel = { menu.netplay().end() }) }

        // Durante a animação de saída o aviso já é nulo: o texto que sai é o último mostrado, não um balão vazio.
        var lastToast by remember { mutableStateOf("") }
        if (toast != null) lastToast = toast
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(top = 20.dp, start = 16.dp, end = 16.dp),
        ) {
            Text(
                lastToast,
                color = Palette.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(Palette.SurfaceHighest.copy(alpha = 0.95f), RoundedCornerShape(50))
                    .border(1.dp, Palette.Outline, RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        // Mensagens longas (avisos) ficam mais tempo na tela.
        LaunchedEffect(toast) { if (toast != null) { delay((1500L + toast.length * 45L).coerceIn(2200L, 8000L)); onDismissToast() } }
    }
    }
}

/** Fração da altura do jogo em retrato dividido; o controle fica com o resto. */
internal const val VIDEO_SPLIT = 0.55f

/** Área do controle: metade de baixo em retrato dividido, a tela toda quando sobreposto. */
internal fun BoxScope.padModifier(overlay: Boolean): Modifier =
    if (overlay) Modifier.fillMaxSize()
    else Modifier.fillMaxWidth().fillMaxHeight(1f - VIDEO_SPLIT).align(Alignment.BottomCenter).padding(top = 48.dp, bottom = 16.dp)

@Composable
private fun Hud(
    fastForward: Boolean, onMenu: () -> Unit, onFastForward: () -> Unit, onTranslate: (() -> Unit)?,
    modifier: Modifier, vertical: Boolean, dimmed: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        HudButton(Icons.Rounded.Menu, stringResource(R.string.game_menu), false, onMenu)
        HudButton(Icons.Rounded.FastForward, stringResource(R.string.game_fast_forward), fastForward, onFastForward)
        onTranslate?.let { HudButton(Icons.Rounded.Translate, stringResource(R.string.translate_button), false, it) }
    }
    val m = if (dimmed) modifier.alpha(0.45f) else modifier
    // Cada botão já ocupa 48 dp (área de toque mínima) com o círculo de 40 no meio: o espaço visível continua 10 dp.
    if (vertical) Column(m, verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    else Row(m, horizontalArrangement = Arrangement.spacedBy(2.dp)) { content() }
}

/** Velocidade (% da do console) e quadros novos por segundo, num canto: pequeno, sem tocar no jogo. */
@Composable
private fun PerfHud(stats: () -> PerfStats?, modifier: Modifier) {
    val s = stats() ?: return
    Text(
        stringResource(R.string.game_perf_hud, s.speedPercent, s.fps),
        color = Color.White.copy(alpha = 0.85f),
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun HudButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            // Círculo de 40 dp, mas o toque vale nos 48 dp recomendados ao redor dele.
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .background(if (active) Palette.Neon.copy(alpha = 0.85f) else Color(0x40FFFFFF))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = if (active) Color.Black else Color.White.copy(alpha = 0.85f), modifier = Modifier.size(22.dp)) }
}

@Composable
private fun PreparingView(
    state: EmulationUi.Preparing, game: Game?, system: GameSystem?,
    /** Última tela do jogo (miniatura do salvamento automático), esmaecida atrás do texto. */
    backdrop: Bitmap? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().background(Palette.Ink)) {
        backdrop?.let {
            val image = remember(it) { it.asImageBitmap() }
            Image(image, null, contentScale = ContentScale.Crop, alpha = 0.22f, modifier = Modifier.fillMaxSize())
        }
    Column(
        Modifier.fillMaxSize().ambientGlow(primary = system?.let { Color(it.accent) } ?: Palette.Neon).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // O logo "respira" enquanto o núcleo e o jogo carregam.
        val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
            0.92f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale",
        )
        BrandMark(72.dp, Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse })
        Spacer(Modifier.height(20.dp))
        system?.let {
            Kicker(it.shortName, color = Color(it.accent).copy(alpha = 0.9f).compositeOverWhite())
            Spacer(Modifier.height(10.dp))
        }
        Text(game?.title ?: "", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        val p = state.progress
        val bar = Modifier.widthIn(max = 320.dp).fillMaxWidth().clip(RoundedCornerShape(50))
        if (p != null && p >= 0f) LinearProgressIndicator(progress = { p }, modifier = bar, color = Palette.Neon, trackColor = Palette.SurfaceHighest)
        else LinearProgressIndicator(modifier = bar, color = Palette.Neon, trackColor = Palette.SurfaceHighest)
        Spacer(Modifier.height(12.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    }
    }
}

/** Garante contraste para cores de sistema muito escuras (ex.: Mega Drive preto). */
private fun Color.compositeOverWhite(): Color =
    if ((red + green + blue) / 3f < 0.35f) Color(0xFFB8BFD0) else this

@Composable
private fun FailedView(state: EmulationUi.Failed, onExit: () -> Unit) {
    // Rolável: a lista de BIOS que faltam (ou uma mensagem longa em paisagem) empurrava os botões para fora da tela.
    Box(Modifier.fillMaxSize().background(Palette.Ink), contentAlignment = Alignment.Center) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.title, style = MaterialTheme.typography.headlineSmall, color = Palette.Coral, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(state.message, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        Spacer(Modifier.height(24.dp))
        val action = state.action
        if (action != null) {
            GradientButton(action.label, action.run)
            Spacer(Modifier.height(12.dp))
            GhostButton(stringResource(R.string.game_back_to_library), onExit)
        } else {
            GradientButton(stringResource(R.string.game_back_to_library), onExit)
        }
    }
    }
}

private enum class MenuTab(@StringRes val label: Int) {
    STATES(R.string.game_tab_states), OPTIONS(R.string.game_tab_game), CHEATS(R.string.game_tab_cheats), CONTROLS(R.string.game_tab_controls), CORE(R.string.game_tab_core),
    REMOTE(R.string.game_tab_remote)
}

@Composable
private fun PauseMenu(
    game: Game?,
    system: GameSystem?,
    menu: MenuActions,
    fastForward: Boolean,
    settings: AppSettings,
    padProfile: PadProfile,
    tab: MenuTab,
    /** Falso enquanto o estado do jogo ainda está sendo capturado: o que depende dele fica desligado. */
    ready: Boolean,
    /** Captura demorando (passou do instante em que só piscaria): um aviso discreto no pé do menu. */
    capturing: Boolean,
    /** Primeira composição do menu nesta abertura: o foco ainda pode ir para "Continuar" ([onFocusPlaced] avisa que foi). */
    placeFocus: Boolean,
    controllerActive: Boolean,
    onFocusPlaced: () -> Unit,
    onTab: (MenuTab) -> Unit,
) {
    var refresh by remember { mutableIntStateOf(0) }

    // Fundo opaco: o controle virtual e o HUD não aparecem por trás do menu.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.DeepViolet, Palette.Ink, Palette.Ink)))
            // Só segura os toques para não chegarem ao jogo: fora da navegação pelo D-pad.
            .focusProperties { canFocus = false }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
    ) {
        // Aberto pelo controle (fora do modo de toque): o foco já começa em "Continuar", e o D-pad navega pelo
        // menu sem um primeiro aperto "perdido". No toque nada fica destacado.
        val resume = remember { FocusRequester() }
        val inputMode = LocalInputModeManager.current.inputMode
        // Os botões e analógicos do controle não tiram o Android do modo de toque: o controle em uso conta também.
        val wantsFocus = placeFocus && (controllerActive || inputMode == InputMode.Keyboard)
        LaunchedEffect(Unit) {
            if (wantsFocus) runCatching { resume.requestFocus() }
            onFocusPlaced()
        }
        // Em retrato (ou telas estreitas) o cabeçalho empilha e os estados viram lista vertical.
        val compact = maxWidth < 600.dp || maxHeight > maxWidth
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = if (compact) 20.dp else 24.dp, vertical = if (compact) 16.dp else 20.dp),
        ) {
            val kicker = listOfNotNull(system?.shortName, menu.coreName().takeIf { it.isNotBlank() }).joinToString(" · ")
            if (compact) {
                Kicker(kicker)
                Spacer(Modifier.height(8.dp))
                Text(game?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    GradientButton(stringResource(R.string.common_continue), menu::close, Modifier.weight(1f).focusRequester(resume), icon = Icons.Rounded.PlayArrow, height = 48.dp)
                    GhostButton(stringResource(R.string.game_exit), menu::exit, icon = Icons.AutoMirrored.Rounded.ExitToApp)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Kicker(kicker)
                        Spacer(Modifier.height(8.dp))
                        Text(game?.title.orEmpty(), style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(12.dp))
                    GhostButton(stringResource(R.string.game_exit), menu::exit, icon = Icons.AutoMirrored.Rounded.ExitToApp)
                    Spacer(Modifier.width(10.dp))
                    GradientButton(stringResource(R.string.common_continue), menu::close, Modifier.focusRequester(resume), icon = Icons.Rounded.PlayArrow, height = 44.dp)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cheats = menu.cheats()
                MenuTab.entries.filter { it != MenuTab.CHEATS || cheats?.supported == true }
                    .forEach { t -> SelectChip(stringResource(t.label), tab == t, onClick = { onTab(t) }) }
            }
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    MenuTab.STATES -> if (compact) StatesList(menu, refresh, ready) { refresh++ } else StatesTab(menu, refresh, ready) { refresh++ }
                    MenuTab.OPTIONS -> OptionsTab(menu, fastForward, settings.shader, ready)
                    MenuTab.CONTROLS -> ControlsTab(menu, padProfile, settings, system?.name.orEmpty(), hasPad = system != null)
                    MenuTab.CORE -> CoreTab(menu)
                    MenuTab.CHEATS -> menu.cheats()?.let { CheatsTab(it) }
                    MenuTab.REMOTE -> RemoteTab()
                }
            }
            if (capturing) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Palette.Neon, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.game_menu_capturing), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                }
            }
        }
    }
}

@Composable
private fun slotTitle(slot: SaveSlot) =
    if (slot.index == SaveStates.AUTO_SLOT) stringResource(R.string.game_slot_auto) else stringResource(R.string.game_slot_n, slot.index)

private fun slotTime(slot: SaveSlot) =
    slot.timestamp?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: "—"

/**
 * Miniatura do slot, decodificada fora da thread principal (um PNG por slot, cinco slots, na primeira composição
 * do menu). Até chegar a caixa fica vazia, sem o "Vazio" que piscaria; numa atualização ([refresh]) a imagem antiga
 * segue na tela até a nova ficar pronta.
 */
@Composable
private fun SlotThumb(menu: MenuActions, slot: SaveSlot, refresh: Int, modifier: Modifier) {
    val loaded by produceState<Pair<Boolean, Bitmap?>>(false to null, refresh, slot.index) {
        value = true to withContext(Dispatchers.IO) { menu.thumbnail(slot.index) }
    }
    val thumb = loaded.second
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Ink), contentAlignment = Alignment.Center) {
        if (thumb != null) {
            val image = remember(thumb) { thumb.asImageBitmap() }
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else if (loaded.first) {
            Text(stringResource(if (slot.exists) R.string.game_slot_no_image else R.string.game_slot_empty), color = Palette.TextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Os slots (um stat por arquivo) lidos fora da thread principal; nulo até a primeira leitura. */
@Composable
private fun slotsState(menu: MenuActions, refresh: Int): List<SaveSlot>? {
    val slots by produceState<List<SaveSlot>?>(null, refresh) { value = withContext(Dispatchers.IO) { menu.slots() } }
    return slots
}

/**
 * "Desfazer": volta ao ponto de antes do último carregar ou reiniciar pelo menu. Um estado carregado no slot errado
 * (o botão fica logo abaixo do de gravar) ou um reinício sem querer perdiam o progresso da sessão.
 */
@Composable
private fun UndoRow(menu: MenuActions, modifier: Modifier = Modifier) {
    val kind = menu.undoKind() ?: return
    val shape = RoundedCornerShape(18.dp)
    val source = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .pressScale(source)
            .focusRing(source, shape)
            .clip(shape)
            .background(Palette.Sun.copy(alpha = 0.10f))
            .border(1.dp, Palette.Sun.copy(alpha = 0.45f), shape)
            .clickable(source, null, role = Role.Button, onClick = menu::undo)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Rounded.Undo, null, tint = Palette.Sun, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (kind == UndoKind.RESET) R.string.game_undo_reset else R.string.game_undo_load), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (kind == UndoKind.RESET) R.string.game_undo_reset_subtitle else R.string.game_undo_load_subtitle),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            )
        }
    }
}

/** Retrato: um estado por linha, miniatura à esquerda e botões empilhados à direita. */
@Composable
private fun StatesList(menu: MenuActions, refresh: Int, ready: Boolean, onChanged: () -> Unit) {
    val slots = slotsState(menu, refresh) ?: return
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
        if (menu.undoKind() != null) item(key = "undo") { UndoRow(menu) }
        items(slots, key = { it.index }) { slot ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(18.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SlotThumb(menu, slot, refresh, Modifier.width(132.dp).aspectRatio(4f / 3f))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(slotTitle(slot), style = MaterialTheme.typography.titleSmall)
                    Text(slotTime(slot), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, maxLines = 1)
                    Spacer(Modifier.height(8.dp))
                    SlotButtons(menu, slot, ready, onChanged, stacked = true)
                }
            }
        }
    }
}

/**
 * Botão dos slots no estilo do app: degradê do sol ([primary], gravar) ou contorno (carregar). O desenho fica
 * compacto (40 dp) para caber três por cartão na horizontal, mas o toque vale nos 48 dp recomendados.
 */
@Composable
private fun SlotButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, primary: Boolean, enabled: Boolean, showIcon: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val source = remember { MutableInteractionSource() }
    val content = when {
        !enabled -> Palette.TextMuted
        primary -> Palette.OnAccent
        else -> Palette.TextPrimary
    }
    Box(modifier.minimumInteractiveComponentSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .pressScale(source, enabled)
                .focusRing(source, shape)
                .clip(shape)
                .then(
                    when {
                        primary && enabled -> Modifier.background(Palette.SunsetHorizontal)
                        primary -> Modifier.background(Palette.SurfaceHighest)
                        else -> Modifier.background(Color.White.copy(alpha = 0.05f)).border(1.dp, if (enabled) Palette.Outline else Palette.Outline.copy(alpha = 0.5f), shape)
                    },
                )
                .clickable(source, null, enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showIcon) { Icon(icon, null, tint = content, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
            Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SlotButtons(menu: MenuActions, slot: SaveSlot, ready: Boolean, onChanged: () -> Unit, stacked: Boolean) {
    val save: @Composable (Modifier) -> Unit = { m ->
        // Gravar usa o estado capturado ao abrir o menu: só depois que ele ficou pronto.
        SlotButton(stringResource(R.string.game_save), Icons.Rounded.Save, primary = true, enabled = ready, showIcon = stacked, modifier = m) { menu.save(slot.index, onChanged) }
    }
    val load: @Composable (Modifier) -> Unit = { m ->
        SlotButton(stringResource(R.string.game_load), Icons.Rounded.Upload, primary = false, enabled = slot.exists, showIcon = stacked, modifier = m) { menu.load(slot.index) }
    }
    val canSave = slot.index != SaveStates.AUTO_SLOT
    val share: @Composable () -> Unit = {
        if (slot.exists) {
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .minimumInteractiveComponentSize()
                    .size(40.dp)
                    .focusRing(source, CircleShape)
                    .clip(CircleShape)
                    .background(Palette.Cyan.copy(alpha = 0.14f))
                    .clickable(source, null, role = Role.Button) { menu.share(slot.index) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Share, stringResource(R.string.share_state_title), tint = Palette.Cyan, modifier = Modifier.size(18.dp)) }
        }
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canSave) save(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                load(Modifier.weight(1f))
                share()
            }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (canSave) save(Modifier.weight(1f))
            load(Modifier.weight(1f))
            share()
        }
    }
}

/** Paisagem: cartões lado a lado; a miniatura encolhe para os botões continuarem visíveis. */
@Composable
private fun StatesTab(menu: MenuActions, refresh: Int, ready: Boolean, onChanged: () -> Unit) {
    val slots = slotsState(menu, refresh) ?: return
    Column {
    UndoRow(menu, Modifier.padding(bottom = 10.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(slots, key = { it.index }) { slot ->
            Column(
                Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(18.dp))
                    .padding(10.dp),
            ) {
                SlotThumb(menu, slot, refresh, Modifier.weight(1f, fill = false).aspectRatio(4f / 3f).align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(8.dp))
                Text(slotTitle(slot), style = MaterialTheme.typography.titleSmall)
                Text(slotTime(slot), style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary)
                Spacer(Modifier.height(8.dp))
                SlotButtons(menu, slot, ready, onChanged, stacked = false)
            }
        }
    }
    }
}

@Composable
private fun OptionsTab(menu: MenuActions, fastForward: Boolean, shader: ShaderOption, ready: Boolean) {
    var currentShader by remember { mutableStateOf(shader) }
    var disks by remember { mutableStateOf(menu.disks()) }
    var confirmReset by remember { mutableStateOf(false) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SettingRow(stringResource(R.string.game_fast_forward), stringResource(R.string.game_fast_forward_subtitle)) {
                Switch(checked = fastForward, onCheckedChange = { menu.toggleFastForward() })
            }
        }
        item {
            Column {
                Text(stringResource(R.string.game_video_filter), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShaderOption.entries.forEach { opt ->
                        SelectChip(stringResource(opt.label), currentShader == opt, onClick = { currentShader = opt; menu.setShader(opt) })
                    }
                }
            }
        }
        if (disks.first > 1) item {
            Column {
                Text(stringResource(R.string.game_change_disc), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(disks.first) { i ->
                        SelectChip(stringResource(R.string.game_disc_n, i + 1), disks.second == i, onClick = { menu.changeDisk(i); disks = disks.first to i })
                    }
                }
            }
        }
        item {
            val net = menu.netplay()
            Column {
                Text(stringResource(R.string.netplay_menu_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(if (net.playing) R.string.netplay_menu_playing else R.string.netplay_menu_subtitle),
                    style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!net.playing) {
                        GhostButton(stringResource(R.string.netplay_host), menu::hostNetplay, icon = Icons.Rounded.Wifi, enabled = ready)
                    } else {
                        if (net.isHost) GhostButton(stringResource(R.string.netplay_resync), { net.resync() })
                        GhostButton(stringResource(R.string.netplay_leave), { net.end() })
                    }
                }
            }
        }
        item {
            GhostButton(stringResource(R.string.translate_screen), { menu.close(); menu.translate() }, icon = Icons.Rounded.Translate, enabled = ready)
        }
        item {
            // Só com o estado de agora capturado: é ele que o "Desfazer" do reinício devolve.
            GhostButton(stringResource(R.string.game_reset), { confirmReset = true }, icon = Icons.Rounded.RestartAlt, tint = Palette.Coral, enabled = ready)
        }
    }
    // Reiniciar apaga o progresso desde o último save: um toque sem querer não pode bastar.
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.game_reset_confirm_title),
            // Núcleo sem save states (Play!) ou captura que falhou: não há estado de antes para o "Desfazer" devolver.
            message = stringResource(if (menu.canUndoReset()) R.string.game_reset_confirm_message else R.string.game_reset_confirm_message_no_undo),
            confirmLabel = stringResource(R.string.game_reset),
            onConfirm = menu::reset,
            onDismiss = { confirmReset = false },
            icon = Icons.Rounded.RestartAlt,
        )
    }
}

/**
 * Opções do núcleo: tocar abre a lista de valores (o atual e o padrão do núcleo marcados); com controle, esquerda e
 * direita passam pelo anterior e pelo próximo direto na linha. Antes o toque só avançava, e voltar um valor exigia
 * dar a volta na lista inteira.
 */
@Composable
private fun CoreTab(menu: MenuActions) {
    var options by remember { mutableStateOf(menu.coreOptions()) }
    var picking by remember { mutableStateOf<CoreOption?>(null) }
    if (options.isEmpty()) {
        Text(stringResource(R.string.game_core_no_options), color = Palette.TextSecondary)
        return
    }
    val set: (CoreOption, String) -> Unit = { opt, value ->
        // Recusado (partida em rede): a linha continua com o valor que o núcleo tem.
        if (value != opt.value && menu.setCoreOption(opt, value)) {
            options = options.map { if (it.key == opt.key) it.copy(value = value) else it }
        }
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Text(
                stringResource(R.string.game_core_options_hint),
                style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary,
            )
        }
        items(options, key = { it.key }) { opt ->
            val shape = RoundedCornerShape(12.dp)
            val source = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRing(source, shape)
                    .clip(shape)
                    .background(Palette.SurfaceHigh)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // Segurar a seta repetiria a troca a cada ~50 ms, reaplicando a opção no núcleo a cada passo
                        // (resolução interna, por exemplo): um passo por aperto.
                        val repeat = e.nativeKeyEvent.repeatCount > 0
                        when (e.key) {
                            Key.DirectionLeft -> { if (!repeat) set(opt, opt.previous()); true }
                            Key.DirectionRight -> { if (!repeat) set(opt, opt.next()); true }
                            else -> false
                        }
                    }
                    .semantics { stateDescription = opt.value }
                    .clickable(source, null, role = Role.DropdownList) { picking = opt }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(opt.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(10.dp))
                Text(opt.value, style = MaterialTheme.typography.labelMedium, color = Palette.Neon, fontWeight = FontWeight.Bold)
            }
        }
        item { TextButton(onClick = { options = menu.coreOptions() }) { Text(stringResource(R.string.game_refresh_list)) } }
    }
    picking?.let { opt ->
        CoreOptionSheet(opt, onPick = { set(opt, it); picking = null }, onDismiss = { picking = null })
    }
}

/** Todos os valores de uma opção do núcleo; o primeiro da lista é o padrão do núcleo (formato libretro v0). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoreOptionSheet(option: CoreOption, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(), containerColor = Palette.Surface) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(option.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
            }
            items(option.values) { value ->
                val selected = value == option.value
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton) { onPick(value) }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            value, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) Palette.Neon else Palette.TextPrimary,
                            fontWeight = if (selected) FontWeight.Bold else null,
                        )
                        if (value == option.default) {
                            Spacer(Modifier.width(8.dp))
                            Badge(stringResource(R.string.game_core_option_default), Palette.Cyan)
                        }
                    }
                    if (selected) Icon(Icons.Rounded.Check, null, tint = Palette.Neon)
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        trailing()
    }
}
