package com.retrovika.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrovika.app.R

/**
 * Paleta "Pôr do sol synthwave": base violeta quase preta, rosa neon como destaque principal,
 * ciano como secundário e o degradê do sol (rosa → laranja → amarelo) nos elementos de marca.
 */
object Palette {
    val Ink = Color(0xFF0B0714)
    val Surface = Color(0xFF130E21)
    val SurfaceHigh = Color(0xFF1B152E)
    val SurfaceHighest = Color(0xFF261F3F)
    val Outline = Color(0xFF342B55)
    val Neon = Color(0xFFFF3D8B)
    val Cyan = Color(0xFF36E2F5)
    val Sun = Color(0xFFFFC857)
    val Orange = Color(0xFFFF8A3D)
    val Violet = Color(0xFF8B6CFF)
    val Coral = Color(0xFFFF5A5F)
    val Success = Color(0xFF5BE49B)
    val TextPrimary = Color(0xFFF5F1FB)
    val TextSecondary = Color(0xFFABA3C4)
    // Contraste mínimo de 4,5:1 (WCAG AA) sobre Ink (5,9:1) e SurfaceHigh (5,2:1), e ainda 4,6:1 sobre
    // SurfaceHighest; continua abaixo do TextSecondary na hierarquia.
    val TextMuted = Color(0xFF8D86B2)
    /** Texto e ícones sobre o degradê do sol ou o rosa neon (botão principal, chip ativo, aba ativa). */
    val OnAccent = Color(0xFF1C0010)
    /** Ameixa do início dos cartões de destaque (boas-vindas, atualização, sobre). */
    val Plum = Color(0xFF3A1060)
    /** Violeta profundo do topo do menu de pausa e do cabeçalho dos ajustes. */
    val DeepViolet = Color(0xFF2A0C52)
    /** Azul-petróleo escuro do fim dos cartões de destaque. */
    val NightTeal = Color(0xFF10263A)

    /** Degradê do sol do logo, usado em botões principais e na marca. */
    val SunsetGradient = Brush.linearGradient(listOf(Neon, Orange, Sun))
    val SunsetHorizontal = Brush.horizontalGradient(listOf(Neon, Color(0xFFFF6A5C), Orange))
    val CoolGradient = Brush.linearGradient(listOf(Violet, Cyan))
    /** Fundo dos cartões de destaque: ameixa → superfície → azul-petróleo. */
    val HeroGradient = Brush.linearGradient(listOf(Plum, SurfaceHigh, NightTeal))
}

private val colors = darkColorScheme(
    primary = Palette.Neon,
    onPrimary = Palette.OnAccent,
    primaryContainer = Color(0xFF4A1233),
    onPrimaryContainer = Color(0xFFFFD6E6),
    secondary = Palette.Cyan,
    onSecondary = Color(0xFF00262B),
    secondaryContainer = Color(0xFF123A45),
    onSecondaryContainer = Color(0xFFCFF7FC),
    tertiary = Palette.Sun,
    onTertiary = Color(0xFF2A1A00),
    background = Palette.Ink,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.SurfaceHigh,
    surfaceContainerHigh = Palette.SurfaceHighest,
    surfaceContainerHighest = Color(0xFF2E2649),
    outline = Palette.Outline,
    outlineVariant = Color(0xFF241D3A),
    error = Palette.Coral,
)

@OptIn(ExperimentalTextApi::class)
private fun grotesk(weight: FontWeight) = Font(
    R.font.space_grotesk, weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight.coerceIn(300, 700))),
)

@OptIn(ExperimentalTextApi::class)
private fun mono(weight: FontWeight) = Font(
    R.font.jetbrains_mono, weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Space Grotesk: geométrica com personalidade, lembra os manuais de console dos anos 90. */
val DisplayFamily = FontFamily(
    grotesk(FontWeight.Normal), grotesk(FontWeight.Medium), grotesk(FontWeight.SemiBold), grotesk(FontWeight.Bold),
    grotesk(FontWeight.ExtraBold), grotesk(FontWeight.Black),
)
val MonoFamily = FontFamily(mono(FontWeight.Normal), mono(FontWeight.Medium), mono(FontWeight.Bold))

/** Fonte pixelada, reservada à marca e a pequenos rótulos de destaque. */
val PixelFamily = FontFamily(Font(R.font.press_start))

private val display = DisplayFamily

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 27.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = display, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = display, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = display, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.6.sp),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun RetrovikaTheme(@Suppress("UNUSED_PARAMETER") dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
}
