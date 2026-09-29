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
}
