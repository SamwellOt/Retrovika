package com.retrovika.app.core.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextTest {

    @Test
    fun `reconhece japones`() {
        assertTrue(TranslationText.hasJapanese("はじめる"))
        assertTrue(TranslationText.hasJapanese("ｽﾀｰﾄ"))
        assertTrue(TranslationText.hasJapanese("勇者"))
        assertFalse(TranslationText.hasJapanese("START GAME"))
    }

    @Test
    fun `escolhe a origem pelo idioma do texto e do leitor`() {
        assertEquals("ja", TranslationText.sourceFor("つづきから", "pt"))
        assertEquals("ja", TranslationText.sourceFor("つづきから", "en"))
        assertEquals("en", TranslationText.sourceFor("Continue your journey", "pt"))
        assertNull(TranslationText.sourceFor("Continue your journey", "en"))
        assertNull(TranslationText.sourceFor("HP 25", "pt"))
    }

    @Test
    fun `junta linhas sem espaco em japones`() {
        assertEquals("おはようございます勇者さま", TranslationText.joinLines(listOf("おはようございます", "勇者さま")))
        assertEquals("Hello there brave hero", TranslationText.joinLines(listOf("Hello there", " brave hero ", "")))
    }

    @Test
    fun `le a resposta do tradutor web`() {
        val body = """[[["Bom dia, ","おはよう、",null,null,10],["herói.","勇者。",null,null,10]],null,"ja"]"""
        assertEquals("Bom dia, herói.", TranslationText.parseWebResponse(body))
        assertNull(TranslationText.parseWebResponse("<html>"))
    }

    private fun line(l: Int, t: Int, r: Int, b: Int, text: String) = OcrLine(Box(l, t, r, b), text, 0.9f)

    @Test
    fun `junta as linhas de um balao e separa os itens de menu`() {
        val blocks = TranslationText.groupLines(
            listOf(
                line(20, 200, 300, 216, "おはようございます、ゆうしゃ"),
                line(20, 220, 120, 236, "さま。"),
                line(40, 20, 120, 36, "つづきから"),
                line(40, 44, 120, 60, "はじめから"),
            ),
        )
        assertEquals(listOf("つづきから", "はじめから", "おはようございます、ゆうしゃさま。"), blocks.map { it.text })
        assertEquals(Box(20, 200, 300, 236), blocks[2].box)
    }

    @Test
    fun `nao junta linhas distantes ou de tamanhos muito diferentes`() {
        val blocks = TranslationText.groupLines(
            listOf(
                line(20, 20, 400, 60, "たいとるがめんのもじです"),
                line(20, 64, 300, 76, "ちいさなもじのせつめいぶん"),
                line(20, 200, 300, 212, "とおくにあるべつのもじ"),
            ),
        )
        assertEquals(3, blocks.size)
    }

    @Test
    fun `descarta ruido`() {
        assertTrue(TranslationText.isNoise("…"))
        assertTrue(TranslationText.isNoise("12"))
        assertTrue(TranslationText.isNoise("x"))
        assertFalse(TranslationText.isNoise("は"))
        assertFalse(TranslationText.isNoise("OK"))
    }

    @Test
    fun `amplia quadros pequenos por um fator inteiro`() {
        assertEquals(4, TranslationText.upscaleFor(256, 224))
        assertEquals(5, TranslationText.upscaleFor(240, 160))
        assertEquals(3, TranslationText.upscaleFor(640, 240))
        assertEquals(1, TranslationText.upscaleFor(1920, 1080))
        assertEquals(1, TranslationText.upscaleFor(0, 0))
    }
}
