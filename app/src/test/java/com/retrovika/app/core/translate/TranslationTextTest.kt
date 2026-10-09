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

    @Test
    fun `reconhece o que ja esta em portugues e o que nao esta`() {
        assertTrue(TranslationText.probablyTranslated("Ola mundo, que bom ver você", "pt"))
        assertTrue(TranslationText.probablyTranslated("O gato sentou em uma esteira para dormir", "pt"))
        assertTrue(TranslationText.probablyTranslated("(Não) vamos \"agora\"!", "pt"))     // pontuação colada nas palavras
        assertFalse(TranslationText.probablyTranslated("The cat sat on a mat by the door", "pt"))
        assertFalse(TranslationText.probablyTranslated("Hello World", "pt"))
        assertFalse(TranslationText.probablyTranslated("Ola mundo com você", "en"))     // só para quem lê em português
        assertFalse(TranslationText.probablyTranslated("You are not ready, mundo", "pt"))   // inglês vence
    }

    @Test
    fun `uma palavra isolada do portugues dentro de ingles nao basta`() {
        assertFalse(TranslationText.probablyTranslated("Uma: Let's go!", "pt"))           // nome de personagem
        assertFalse(TranslationText.probablyTranslated("Ele: I am here, and you are late", "pt"))
        assertFalse(TranslationText.probablyTranslated("DOS 6.22 is ready. Press any key", "pt"))
        assertFalse(TranslationText.probablyTranslated("Welcome to Mundo Land with friends", "pt"))
    }

    @Test
    fun `quando o ingles vence o portugues nao conta como traduzido`() {
        // Duas palavras de português (mundo, com) contra três de inglês (what, are, you): o inglês vence
        assertFalse(TranslationText.probablyTranslated("mundo com what are you doing", "pt"))
        // Duas contra uma: o português vence
        assertTrue(TranslationText.probablyTranslated("mundo com you", "pt"))
    }

    @Test
    fun `apostrofos e aspas nao atrapalham a contagem`() {
        assertFalse(TranslationText.probablyTranslated("'Mundo' com don't you’re that's", "pt"))
        assertTrue(TranslationText.probablyTranslated("'Mundo' com amigos", "pt"))
    }
}
