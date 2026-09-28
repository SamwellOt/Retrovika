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

    @Test
    fun `le a ficha do jogo a nota dos usuarios e as capturas`() {
        val url = "https://cdromance.org/psx-iso/digimon-world-2003-rb-select/"
        val html = """
            <article class="post-308571 psx-iso"><div class="entry-content">
            <div class="post-thumbnail"><img src="https://cdromance.org/wp-content/uploads/2026/09/cover.jpg"></div>
            <table class="rom-info"><thead><tr><th colspan="2">GAME INFORMATION</th></thead><tbody>
              <tr><th><span itemprop="applicationCategory">Game</span> Name</th><td><span itemprop="name">Digimon World 2003 (RB Select)</span></td></tr>
              <tr><th>Region</th><td><a href="#">Europe</a></td></tr>
              <tr><th>Console</th><td><a href="/psx-iso/"><span>PlayStation</span></a></td></tr>
              <tr><th>Game Release</th><td><span itemprop="datePublished">2002-11-29</span> (23 years ago)</td></tr>
              <tr><th>Genre</th><td><span itemprop="genre"><a href="#">Hack</a>, <a href="#">RPG</a></span></td></tr>
              <tr><th>Publisher</th><td>Bandai</td></tr>
              <tr><th>Languages</th><td><span><a href="#">English</a>, <a href="#">French</a>, <a href="#">German</a></span></td></tr>
              <tr><th>Image Format</th><td>BIN/CUE</td></tr>
              <tr><th>Game ID</th><td>SLES-03936</td></tr>
              <tr><th>Downloads</th><td>46,559</td></tr>
              <tr><th>Users Score</th><td><span itemprop="aggregateRating" itemscope>
                <meta itemprop="ratingValue" content="4.2"><meta itemprop="ratingCount" content="85">
                <meta itemprop="worstRating" content="1"><meta itemprop="bestRating" content="5"></span></td></tr>
            </tbody></table>
            <div class="game-description"><h2>Game Description:</h2><div id="custom-description">
              <p>A curated patch list.</p>
              <p>Flawe's Mod v2.0<br />
            Enables fast travel.</p>
            </div></div>
            <div class="games-loop" id="lightgallery">
              <div class="game-box-layout" data-src="https://cdromance.org/wp-content/uploads/2026/09/shot1.jpg"></div>
              <div class="game-box-layout" data-src="https://cdromance.org/wp-content/uploads/2026/09/shot2.jpg"></div>
            </div></div></article>
        """.trimIndent()
        val entry = CatalogEntry(url, "cdromance", "Digimon World 2003", "psx", null, null, emptyList(), emptyList(), url, url, "x.zip", "game")
        val d = source.parseDetails(html, entry)
        assertEquals("Digimon World 2003 (RB Select)", d.title)
        assertEquals("Europe", d.region)
        assertEquals("2002-11-29", d.releaseDate)
        assertEquals(listOf("Hack", "RPG"), d.genres)
        assertEquals("Bandai", d.publisher)
        assertEquals(listOf("English", "French", "German"), d.languages)
        assertEquals("BIN/CUE", d.format)
        assertEquals("SLES-03936", d.serial)
        assertEquals(46559L, d.downloads)
        assertEquals(4.2, d.rating!!.value, 0.001)
        assertEquals(85, d.rating!!.count)
        assertEquals("https://cdromance.org/wp-content/uploads/2026/09/cover.jpg", d.coverUrl)
        assertEquals(2, d.screenshots.size)
        assertEquals("A curated patch list.\n\nFlawe's Mod v2.0\nEnables fast travel.", d.description)
    }
}
