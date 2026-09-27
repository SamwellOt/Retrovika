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
 * Configuração do controle virtual de um núcleo. [opacity] e [scale] nulos seguem os valores gerais de
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
    val portrait: Map<PadElement, PadElementConfig> = emptyMap(),
    val landscape: Map<PadElement, PadElementConfig> = emptyMap(),
) {
    fun elements(portrait: Boolean): Map<PadElement, PadElementConfig> = if (portrait) this.portrait else landscape

    fun element(portrait: Boolean, element: PadElement): PadElementConfig = elements(portrait)[element] ?: PadElementConfig()

    fun withElement(portrait: Boolean, element: PadElement, config: PadElementConfig): PadProfile {
        val map = (elements(portrait) + (element to config)).filterValues { !it.isDefault }
        return if (portrait) copy(portrait = map) else copy(landscape = map)
    }

    fun resetLayout(portrait: Boolean): PadProfile = if (portrait) copy(portrait = emptyMap()) else copy(landscape = emptyMap())

    /** O jogo fica com a tela inteira: sem controle, ou com ele sobreposto em retrato. */
    fun fullScreenVideo(portrait: Boolean, padShown: Boolean): Boolean =
        !padShown || !visible || !portrait || portraitMode == PortraitMode.OVERLAY

    val isDefault: Boolean get() = this == PadProfile()
}
