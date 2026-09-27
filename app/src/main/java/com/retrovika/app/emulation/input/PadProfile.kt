package com.retrovika.app.emulation.input

import androidx.annotation.StringRes
import com.retrovika.app.R
import kotlinx.serialization.Serializable

/** Partes do controle virtual que podem ser movidas, redimensionadas ou ocultadas uma a uma. */
enum class PadElement(@StringRes val label: Int) {
    /** D-pad e/ou analógico esquerdo. */
    LEFT(R.string.pad_element_left),
    /** Botões de ação, botões C e analógico direito. */
    FACE(R.string.pad_element_face),
    LEFT_SHOULDERS(R.string.pad_element_left_shoulders),
    RIGHT_SHOULDERS(R.string.pad_element_right_shoulders),
    /** START, SELECT e afins. */
    CENTER(R.string.pad_element_center),
}

/**
 * Ajuste de uma parte do controle: deslocamento a partir da posição padrão, em fração da área do
 * controle (0,1 = 10% da largura/altura), tamanho relativo e se aparece.
 */
@Serializable
data class PadElementConfig(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val hidden: Boolean = false,
) {
    val isDefault: Boolean get() = this == PadElementConfig()
}

/** Como o jogo e o controle dividem a tela em retrato. */
enum class PortraitMode(@StringRes val label: Int) {
    /** Jogo em cima, controle embaixo. */
    SPLIT(R.string.pad_portrait_split),
    /** Jogo na tela inteira, controle por cima (como em paisagem). */
    OVERLAY(R.string.pad_portrait_overlay),
}

/**
 * Configuração do controle virtual de um console (vale em qualquer núcleo dele). [opacity] e [scale] nulos seguem os valores gerais de
 * Ajustes. As posições são guardadas separadas para retrato e paisagem, já que o espaço muda por completo.
 */
@Serializable
data class PadProfile(
    /** Falso: sem controle na tela, e o jogo ocupa a tela inteira (jogos só de toque no DS/3DS). */
    val visible: Boolean = true,
    val opacity: Float? = null,
    val scale: Float? = null,
    val portraitMode: PortraitMode = PortraitMode.SPLIT,
    /** Botões de menu e avanço rápido sobre o jogo; sem eles, o menu abre pelo gesto de voltar. */
    val showHud: Boolean = true,
    /** Retrato dividido: posições em fração da metade de baixo da tela. */
    val portrait: Map<PadElement, PadElementConfig> = emptyMap(),
    val landscape: Map<PadElement, PadElementConfig> = emptyMap(),
    /**
     * Retrato sobreposto: separado do dividido porque ali a área do controle é a tela inteira e as partes
     * partem dos cantos; o mesmo deslocamento levaria os botões para o meio do jogo.
     */
    val portraitOverlay: Map<PadElement, PadElementConfig> = emptyMap(),
) {
    fun elements(portrait: Boolean): Map<PadElement, PadElementConfig> = when {
        !portrait -> landscape
        portraitMode == PortraitMode.OVERLAY -> portraitOverlay
        else -> this.portrait
    }

    fun element(portrait: Boolean, element: PadElement): PadElementConfig = elements(portrait)[element] ?: PadElementConfig()

    fun withElement(portrait: Boolean, element: PadElement, config: PadElementConfig): PadProfile =
        withElements(portrait, (elements(portrait) + (element to config)).filterValues { !it.isDefault })

    fun resetLayout(portrait: Boolean): PadProfile = withElements(portrait, emptyMap())

    private fun withElements(portrait: Boolean, map: Map<PadElement, PadElementConfig>): PadProfile = when {
        !portrait -> copy(landscape = map)
        portraitMode == PortraitMode.OVERLAY -> copy(portraitOverlay = map)
        else -> copy(portrait = map)
    }

    /** O jogo fica com a tela inteira: sem controle, ou com ele sobreposto em retrato. */
    fun fullScreenVideo(portrait: Boolean, padShown: Boolean): Boolean =
        !padShown || !visible || !portrait || portraitMode == PortraitMode.OVERLAY

    val isDefault: Boolean get() = this == PadProfile()
}

/**
 * Até onde uma parte pode ser deslocada (em fração da área do controle, como [PadElementConfig.dx]/[dy])
 * sem passar das bordas. Vale para o editor (o arrasto para na borda) e para o jogo (um perfil salvo
 * noutro tamanho de tela, ou com a parte maior, também fica dentro).
 */
data class OffsetLimits(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float) {
    fun clampX(dx: Float): Float = dx.coerceIn(minX, maxX)
    fun clampY(dy: Float): Float = dy.coerceIn(minY, maxY)

    companion object {
        /**
         * [left]/[top]/[width]/[height]: a parte na posição padrão, em pixels dentro da área [areaWidth] x
         * [areaHeight]. [scale] cresce a partir do centro (como o graphicsLayer). Uma parte maior que a área
         * fica centralizada nela.
         */
        fun of(left: Float, top: Float, width: Float, height: Float, scale: Float, areaWidth: Float, areaHeight: Float): OffsetLimits? {
            if (areaWidth <= 0f || areaHeight <= 0f) return null
            fun axis(start: Float, length: Float, area: Float): Pair<Float, Float> {
                val center = start + length / 2f
                val half = length * scale / 2f
                val min = half - center
                val max = area - half - center
                return if (min <= max) min / area to max / area else ((area / 2f - center) / area).let { it to it }
            }
            val (minX, maxX) = axis(left, width, areaWidth)
            val (minY, maxY) = axis(top, height, areaHeight)
            return OffsetLimits(minX, maxX, minY, maxY)
        }
    }
}
