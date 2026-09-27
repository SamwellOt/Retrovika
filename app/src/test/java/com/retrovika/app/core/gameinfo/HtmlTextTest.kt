package com.retrovika.app.core.gameinfo

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTextTest {
    @Test
    fun `paragrafos e quebras viram linhas e a formatacao do codigo some`() {
        val html = "<p>Linha um<br />\ncontinua.</p>\n<p>Outro   parágrafo\ncom quebra no código.</p><ul><li>Item</li></ul>"
        assertEquals("Linha um\ncontinua.\n\nOutro parágrafo com quebra no código.\n\n• Item", HtmlText.of(html))
    }

    @Test
    fun `mantem as quebras do proprio texto quando pedido`() {
        assertEquals("Resumo.\n• Item um\n• Item dois", HtmlText.of("<p>Resumo.\n• Item um\n• Item dois</p>", keepNewlines = true))
    }
}
