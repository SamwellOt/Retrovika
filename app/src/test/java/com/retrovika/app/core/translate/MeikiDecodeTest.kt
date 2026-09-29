package com.retrovika.app.core.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeikiDecodeTest {

    private fun c(char: String, start: Int, end: Int, conf: Float) =
        MeikiDecode.Candidate(char, Box(start, 0, end, 10), conf, start, end)

    @Test
    fun `fica com o caractere mais confiavel quando dois se sobrepoem`() {
        val (text, conf) = MeikiDecode.decode(listOf(c("は", 0, 10, 0.9f), c("ほ", 1, 11, 0.4f), c("い", 12, 22, 0.8f)), punctFactor = 1f)
        assertEquals("はい", text)
        assertEquals(0.85f, conf, 0.001f)
    }

    @Test
    fun `pontuacao perde para letra sobreposta`() {
        val (text, _) = MeikiDecode.decode(listOf(c("、", 0, 10, 0.9f), c("へ", 0, 10, 0.5f)))
        assertEquals("へ", text)
    }

    @Test
    fun `ordena pela posicao e corrige pares trocados`() {
        val (text, _) = MeikiDecode.decode(listOf(c("談", 0, 10, 0.9f), c("冗", 10, 20, 0.9f), c("だ", 20, 30, 0.9f)).reversed(), punctFactor = 1f)
        assertEquals("冗談だ", text)
    }

    @Test
    fun `leva a caixa do reconhecedor para a imagem`() {
        // Linha de 200 x 20 px redimensionada para 320 x 32 dentro dos 960 x 32 do modelo.
        val crop = Box(100, 50, 300, 70)
        val cand = MeikiDecode.toImage('あ'.code, 32f, 0f, 64f, 32f, 0.7f, crop, 320, 32, false, 960, 32)!!
        assertEquals(Box(120, 50, 140, 70), cand.box)
        // Detecção no enchimento à direita do conteúdo não conta.
        assertNull(MeikiDecode.toImage('あ'.code, 400f, 0f, 420f, 32f, 0.7f, crop, 320, 32, false, 960, 32))
        // Nota abaixo do limite também não.
        assertNull(MeikiDecode.toImage('あ'.code, 32f, 0f, 64f, 32f, 0.05f, crop, 320, 32, false, 960, 32))
    }

    @Test
    fun `corta linhas verticais altas em trechos sobrepostos`() {
        val (short, h) = MeikiDecode.verticalSegments(0, 400, 1f)
        assertEquals(listOf(0f), short)
        assertEquals(400f, h, 0.001f)
        val (starts, segment) = MeikiDecode.verticalSegments(0, 1000, 1f)
        assertEquals(420f, segment, 0.001f)
        assertEquals(0f, starts.first(), 0.001f)
        assertEquals(580f, starts.last(), 0.001f)
        assertTrue(starts.zipWithNext().all { (a, b) -> b - a <= 356f + 0.001f })
    }

    @Test
    fun `reconhece pontuacao`() {
        assertTrue(MeikiDecode.isPunctuation("。"))
        assertTrue(MeikiDecode.isPunctuation("「"))
        assertTrue(!MeikiDecode.isPunctuation("あ"))
    }
}
