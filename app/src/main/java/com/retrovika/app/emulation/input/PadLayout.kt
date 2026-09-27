package com.retrovika.app.emulation.input

import android.view.KeyEvent

enum class ButtonShape { CIRCLE, PILL, SHOULDER }

data class PadButton(
    val label: String,
    val keyCode: Int,
    val color: Long? = null,
    val shape: ButtonShape = ButtonShape.CIRCLE,
)

enum class FaceArrangement {
    /** Ordem: topo, direita, baixo, esquerda (SNES, PlayStation, Xbox...). */
    DIAMOND,
    /** Dois botões em diagonal (NES, Game Boy). */
    TWO_DIAGONAL,
    /** Três botões em arco (Mega Drive). */
    THREE_ARC,
    /** Duas fileiras de três (Saturn, arcade de 6 botões). */
    SIX_GRID,
    /** A e B grandes do N64. */
    N64,
    /** Um único botão de tiro (Atari). */
    SINGLE,
}

/**
 * Descrição declarativa do controle virtual de cada console.
 * Os códigos de tecla seguem o RetroPad do libretro: BUTTON_A = direita, B = baixo, X = cima, Y = esquerda.
 */
data class PadLayout(
    val face: List<PadButton>,
    val arrangement: FaceArrangement,
    val leftShoulders: List<PadButton> = emptyList(),
    val rightShoulders: List<PadButton> = emptyList(),
    val center: List<PadButton> = emptyList(),
    val dpad: Boolean = true,
    val leftStick: Boolean = false,
    val rightStick: Boolean = false,
    /** Os botões C do N64 são enviados como analógico direito. */
    val cButtons: Boolean = false,
)

private const val A = KeyEvent.KEYCODE_BUTTON_A
private const val B = KeyEvent.KEYCODE_BUTTON_B
private const val X = KeyEvent.KEYCODE_BUTTON_X
private const val Y = KeyEvent.KEYCODE_BUTTON_Y
private const val L1 = KeyEvent.KEYCODE_BUTTON_L1
private const val R1 = KeyEvent.KEYCODE_BUTTON_R1
private const val L2 = KeyEvent.KEYCODE_BUTTON_L2
private const val R2 = KeyEvent.KEYCODE_BUTTON_R2
private const val L3 = KeyEvent.KEYCODE_BUTTON_THUMBL
private const val R3 = KeyEvent.KEYCODE_BUTTON_THUMBR
private const val START = KeyEvent.KEYCODE_BUTTON_START
private const val SELECT = KeyEvent.KEYCODE_BUTTON_SELECT

object PadLayouts {
    private fun pill(label: String, key: Int) = PadButton(label, key, shape = ButtonShape.PILL)
    private fun shoulder(label: String, key: Int) = PadButton(label, key, shape = ButtonShape.SHOULDER)

    val NES = PadLayout(
        face = listOf(PadButton("B", B, 0xFFC62828), PadButton("A", A, 0xFFC62828)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val GAMEBOY = PadLayout(
        face = listOf(PadButton("B", B, 0xFF8E244D), PadButton("A", A, 0xFF8E244D)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val GBA = PadLayout(
        face = listOf(PadButton("B", B, 0xFF5C6BC0), PadButton("A", A, 0xFF5C6BC0)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        leftShoulders = listOf(shoulder("L", L1)),
        rightShoulders = listOf(shoulder("R", R1)),
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val SNES = PadLayout(
        face = listOf(
            PadButton("X", X, 0xFF3F51B5), PadButton("A", A, 0xFFD32F2F),
            PadButton("B", B, 0xFFFBC02D), PadButton("Y", Y, 0xFF388E3C),
        ),
        arrangement = FaceArrangement.DIAMOND,
        leftShoulders = listOf(shoulder("L", L1)),
        rightShoulders = listOf(shoulder("R", R1)),
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val NDS = SNES

    val N64 = PadLayout(
        face = listOf(PadButton("B", Y, 0xFF43A047), PadButton("A", B, 0xFF1E88E5)),
        arrangement = FaceArrangement.N64,
        leftShoulders = listOf(shoulder("L", L1), shoulder("Z", L2)),
        rightShoulders = listOf(shoulder("R", R1)),
        center = listOf(pill("START", START)),
        leftStick = true, cButtons = true,
    )

    val GAMECUBE = PadLayout(
        face = listOf(
            // O Dolphin liga cada botão do GameCube ao de mesmo nome do RetroPad (X→X, Y→Y), não à posição.
            PadButton("Y", Y, 0xFF9E9E9E), PadButton("A", A, 0xFF2E7D32),
            PadButton("B", B, 0xFFC62828), PadButton("X", X, 0xFF9E9E9E),
        ),
        arrangement = FaceArrangement.DIAMOND,
        leftShoulders = listOf(shoulder("L", L2)),
        rightShoulders = listOf(shoulder("R", R2), shoulder("Z", R1)),
        center = listOf(pill("START", START)),
        leftStick = true, rightStick = true,
    )

    val PSX = PadLayout(
        face = listOf(
            PadButton("△", X, 0xFF26A69A), PadButton("○", A, 0xFFEF5350),
            PadButton("✕", B, 0xFF5C6BC0), PadButton("□", Y, 0xFFEC407A),
        ),
        arrangement = FaceArrangement.DIAMOND,
        leftShoulders = listOf(shoulder("L1", L1), shoulder("L2", L2)),
        rightShoulders = listOf(shoulder("R1", R1), shoulder("R2", R2)),
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val PS2 = PSX.copy(
        leftStick = true, rightStick = true,
        center = PSX.center + listOf(pill("L3", L3), pill("R3", R3)),
    )

    val PSP = PSX.copy(
        leftShoulders = listOf(shoulder("L", L1)),
        rightShoulders = listOf(shoulder("R", R1)),
        leftStick = true,
    )

    val GENESIS = PadLayout(
        face = listOf(PadButton("A", Y, 0xFF424242), PadButton("B", B, 0xFF424242), PadButton("C", A, 0xFF424242)),
        arrangement = FaceArrangement.THREE_ARC,
        leftShoulders = listOf(shoulder("X", L1)),
        rightShoulders = listOf(shoulder("Y", X), shoulder("Z", R1)),
        center = listOf(pill("MODE", SELECT), pill("START", START)),
    )

    val MASTER_SYSTEM = PadLayout(
        face = listOf(PadButton("1", B, 0xFF424242), PadButton("2", A, 0xFF424242)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        center = listOf(pill("START", START)),
    )

    val SATURN = PadLayout(
        face = listOf(
            PadButton("X", Y, 0xFF616161), PadButton("Y", X, 0xFF616161), PadButton("Z", R2, 0xFF616161),
            PadButton("A", B, 0xFF616161), PadButton("B", A, 0xFF616161), PadButton("C", R1, 0xFF616161),
        ),
        arrangement = FaceArrangement.SIX_GRID,
        leftShoulders = listOf(shoulder("L", L1)),
        rightShoulders = listOf(shoulder("R", L2)),
        center = listOf(pill("START", START)),
    )

    val DREAMCAST = PadLayout(
        face = listOf(
            PadButton("Y", X, 0xFF43A047), PadButton("B", A, 0xFFE53935),
            PadButton("A", B, 0xFF1E88E5), PadButton("X", Y, 0xFFFDD835),
        ),
        arrangement = FaceArrangement.DIAMOND,
        leftShoulders = listOf(shoulder("L", L2)),
        rightShoulders = listOf(shoulder("R", R2)),
        center = listOf(pill("START", START)),
        leftStick = true,
    )

    val ARCADE = PadLayout(
        face = listOf(
            PadButton("1", Y, 0xFFE53935), PadButton("2", X, 0xFFFDD835), PadButton("3", L1, 0xFF1E88E5),
            PadButton("4", B, 0xFF43A047), PadButton("5", A, 0xFFFB8C00), PadButton("6", R1, 0xFF8E24AA),
        ),
        arrangement = FaceArrangement.SIX_GRID,
        center = listOf(pill("COIN", SELECT), pill("START", START)),
    )

    val PC_ENGINE = PadLayout(
        face = listOf(PadButton("II", B, 0xFF424242), PadButton("I", A, 0xFF424242)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        center = listOf(pill("SELECT", SELECT), pill("RUN", START)),
    )

    val ATARI = PadLayout(
        face = listOf(PadButton("FIRE", B, 0xFFD84315)),
        arrangement = FaceArrangement.SINGLE,
        center = listOf(pill("SELECT", SELECT), pill("RESET", START)),
    )

    val HANDHELD_2 = PadLayout(
        face = listOf(PadButton("B", B, 0xFF546E7A), PadButton("A", A, 0xFF546E7A)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        center = listOf(pill("OPTION", SELECT), pill("START", START)),
    )

    /** Só dois botões, sem START/SELECT (Arduboy, PICO-8). */
    val TWO_BUTTONS = PadLayout(
        face = listOf(PadButton("B", B, 0xFF546E7A), PadButton("A", A, 0xFF546E7A)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        center = listOf(pill("PAUSA", START)),
    )

    /** Quatro botões iguais (TIC-80, Vectrex). */
    val FOUR_BUTTONS = PadLayout(
        face = listOf(
            PadButton("X", X, 0xFF546E7A), PadButton("A", A, 0xFF546E7A),
            PadButton("B", B, 0xFF546E7A), PadButton("Y", Y, 0xFF546E7A),
        ),
        arrangement = FaceArrangement.DIAMOND,
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val VECTREX = PadLayout(
        face = listOf(
            PadButton("3", X, 0xFF37474F), PadButton("4", A, 0xFF37474F),
            PadButton("2", B, 0xFF37474F), PadButton("1", Y, 0xFF37474F),
        ),
        arrangement = FaceArrangement.DIAMOND,
    )

    /** Atari 400/800/XL: um botão de tiro e as teclas de console START/SELECT/OPTION. */
    val ATARI_8BIT = PadLayout(
        face = listOf(PadButton("FIRE", B, 0xFFD84315)),
        arrangement = FaceArrangement.SINGLE,
        leftShoulders = listOf(shoulder("OPTION", L1)),
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val ATARI_5200 = PadLayout(
        face = listOf(PadButton("1", B, 0xFFD84315), PadButton("2", A, 0xFFD84315)),
        arrangement = FaceArrangement.TWO_DIAGONAL,
        center = listOf(pill("PAUSE", SELECT), pill("START", START)),
    )

    val JAGUAR = PadLayout(
        face = listOf(PadButton("A", Y, 0xFF424242), PadButton("B", B, 0xFF424242), PadButton("C", A, 0xFF424242)),
        arrangement = FaceArrangement.THREE_ARC,
        center = listOf(pill("OPTION", SELECT), pill("PAUSE", START)),
    )

    /** FreeIntv: botões laterais + teclado numérico (SELECT mostra o teclado na tela). */
    val INTELLIVISION = PadLayout(
        face = listOf(
            PadButton("↑", X, 0xFF6D4C41), PadButton("R", A, 0xFF6D4C41),
            PadButton("L", B, 0xFF6D4C41), PadButton("K", Y, 0xFF6D4C41),
        ),
        arrangement = FaceArrangement.DIAMOND,
        leftShoulders = listOf(shoulder("0", L1), shoulder("CLR", L2)),
        rightShoulders = listOf(shoulder("5", R1), shoulder("ENT", R2)),
        center = listOf(pill("TECLADO", SELECT), pill("PAUSE", START)),
    )

    val NEO_GEO = PadLayout(
        face = listOf(
            PadButton("D", X, 0xFF1E88E5), PadButton("B", A, 0xFFFDD835),
            PadButton("A", B, 0xFFE53935), PadButton("C", Y, 0xFF43A047),
        ),
        arrangement = FaceArrangement.DIAMOND,
        center = listOf(pill("SELECT", SELECT), pill("START", START)),
    )

    val PC_FX = PadLayout(
        face = listOf(
            PadButton("III", X, 0xFF424242), PadButton("IV", Y, 0xFF424242), PadButton("V", L1, 0xFF424242),
            PadButton("II", B, 0xFF424242), PadButton("I", A, 0xFF424242), PadButton("VI", R1, 0xFF424242),
        ),
        arrangement = FaceArrangement.SIX_GRID,
        center = listOf(pill("SELECT", SELECT), pill("RUN", START)),
    )

    val THREE_DO = PadLayout(
        face = listOf(PadButton("A", Y, 0xFF424242), PadButton("B", B, 0xFF424242), PadButton("C", A, 0xFF424242)),
        arrangement = FaceArrangement.THREE_ARC,
        leftShoulders = listOf(shoulder("L", L1)),
        rightShoulders = listOf(shoulder("R", R1)),
        center = listOf(pill("X", SELECT), pill("P", START)),
    )

    /**
     * Computadores (MSX, C64, Amiga, ZX Spectrum, CPC, DOS): RetroPad completo, já que cada núcleo
     * usa botões diferentes para o teclado virtual e os atalhos.
     */
    val COMPUTER = PadLayout(
        face = listOf(
            PadButton("X", X, 0xFF546E7A), PadButton("A", A, 0xFF546E7A),
            PadButton("B", B, 0xFF546E7A), PadButton("Y", Y, 0xFF546E7A),
        ),
        arrangement = FaceArrangement.DIAMOND,
        leftShoulders = listOf(shoulder("L", L1), shoulder("L2", L2)),
        rightShoulders = listOf(shoulder("R", R1), shoulder("R2", R2)),
        center = listOf(pill("SELECT", SELECT), pill("START", START), pill("L3", L3), pill("R3", R3)),
    )

    val WII = GAMECUBE.copy(
        face = listOf(
            // Wiimote em pé no Dolphin: 1 = X e 2 = Y do RetroPad.
            PadButton("2", Y, 0xFF9E9E9E), PadButton("A", A, 0xFF1E88E5),
            PadButton("B", B, 0xFF9E9E9E), PadButton("1", X, 0xFF9E9E9E),
        ),
        center = listOf(pill("−", SELECT), pill("+", START)),
    )
}
