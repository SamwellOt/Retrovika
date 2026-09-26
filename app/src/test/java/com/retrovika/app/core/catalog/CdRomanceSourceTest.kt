package com.retrovika.app.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CdRomanceSourceTest {
    private val source = CdRomanceSource()

    // Cartão como vem nas páginas de categoria (ex.: /gba-roms/): sem o selo div.console.
    private fun categoryCard(url: String, title: String) = """
        <div class="game-container">
          <div class="top-section">
            <a class="cover-link" href="$url"><div class="game-thumb"><img src="https://cdromance.org/c.jpg" alt="$title"></div></a>
          </div>
          <div class="bottom-section"><a href="$url"><div class="game-title">$title</div></a></div>
        </div>
    """

    // Cartão da busca: traz o selo com a seção do console.
    private fun searchCard(url: String, title: String, section: String) = """
        <div class="game-container">
          <div class="top-section">
            <a class="cover-link" href="$url"><div class="game-thumb"><img src="https://cdromance.org/c.jpg" alt="$title"></div>
            <div class="console $section" title="Console">X</div></a>
          </div>
          <div class="bottom-section"><a href="$url"><div class="game-title">$title</div></a></div>
        </div>
    """

    @Test
    fun `pagina de categoria sem selo de console usa a secao da url`() {
        val html = "<div class=\"games-loop\">" +
            categoryCard("https://cdromance.org/gba-roms/antarctic-adventure-gba/", "Antarctic Adventure") +
            categoryCard("https://cdromance.org/gameboy-roms/shiren-gb/", "Shiren GB") +
            "</div>"
        val entries = source.parseCards(html, listingSystem = "gba")
        assertEquals(listOf("gba", "gb"), entries.map { it.systemId })
        assertEquals("Antarctic Adventure", entries.first().title)
    }

    @Test
    fun `sem selo nem secao conhecida cai no console da categoria`() {
        val html = categoryCard("https://cdromance.org/algum-jogo/", "Jogo")
        assertEquals("psp", source.parseCards(html, listingSystem = "psp").single().systemId)
        assertEquals(emptyList<CatalogEntry>(), source.parseCards(html, listingSystem = null))
    }

    @Test
    fun `busca classifica pelo selo`() {
        val html = searchCard("https://cdromance.org/snes-rom/super-mario/", "Super Mario", "snes-rom") +
            searchCard("https://cdromance.org/x/", "Sonic", "sega_genesis_roms")
        assertEquals(listOf("snes", "genesis"), source.parseCards(html, listingSystem = null).map { it.systemId })
    }

    @Test
    fun `classes com sublinhado e secoes novas`() {
        assertEquals("gb", source.classify("gb_roms"))
        assertEquals("gbc", source.classify("gbc_roms"))
        assertEquals("gba", source.classify("gba-roms"))
        assertEquals("segacd", source.classify("sega_cd_isos"))
        assertEquals("genesis", source.classify("sega_genesis_roms"))
        assertEquals("nes", source.classify("famicom_disk_system"))
        assertEquals("psx", source.classify("psx2psp"))
        assertEquals("snes", source.classify("snes-rom"))
        assertNull(source.classify("windows"))
    }
}
