package com.retrovika.app.core.catalog

import android.content.ContextWrapper
import com.retrovika.app.core.gameinfo.SiteRating
import com.retrovika.app.core.net.WebFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomsFunSourceTest {
    // O WebView só nasce no primeiro pedido: aqui só o parsing é testado.
    private val source = RomsFunSource(WebFetcher(ContextWrapper(null)))

    // Cartão como vem na busca e nas listas por console (estrutura real do site, sem os SVGs).
    private fun card(game: String, title: String, console: String, consoleName: String) = """
        <div class="bg-white rounded-xl p-3 flex gap-4 shadow-md transition items-center">
          <div class="w-20 h-20 rounded-lg flex-shrink-0 flex items-center justify-center overflow-hidden">
            <a href="https://romsfun.com/roms/$game" class="block w-full h-full">
              <img src="https://romsfun.com/wp-content/uploads/2019/11/capa-300x274.jpg" alt="$title" class="w-full h-full object-cover">
            </a>
          </div>
          <div class="flex-1 min-w-0 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-1">
            <div class="flex-1">
              <h3 class="font-black mb-2 text-gray-900"><a href="https://romsfun.com/roms/$game" class="hover:text-romfun-pink"> $title </a></h3>
              <div class="flex flex-wrap items-center gap-2 mb-2">
                <a href="https://romsfun.com/roms/$console/" class="h-6 w-6 bg-gray-200 rounded">
                  <img src="https://romsfun.com/wp-content/uploads/2021/04/logo.png" alt="$consoleName" class="w-full h-full object-contain">
                </a>
              </div>
            </div>
            <div class="flex items-center gap-3 sm:items-end"><span class="badge badge-info text-xs">283,464</span></div>
          </div>
        </div>
    """

    @Test
    fun `cartoes trazem titulo capa e console pelo selo`() {
        val html = "<div class=\"space-y-4\">" +
            card("3ds/asphalt-3d.html", "Asphalt 3D", "nintendo-3ds", "Nintendo 3DS") +
            card("nintendo-wii/super-smash-bros-brawl-66108.html", "Super Smash Bros. Brawl", "nintendo-wii", "Nintendo Wii") +
            "</div>"
        val entries = source.parseCards(html)
        assertEquals(listOf("3ds", "wii"), entries.map { it.systemId })
        val first = entries.first()
        assertEquals("Asphalt 3D", first.title)
        assertEquals("https://romsfun.com/roms/3ds/asphalt-3d.html", first.id)
        assertEquals("https://romsfun.com/wp-content/uploads/2019/11/capa-300x274.jpg", first.coverUrl)
        assertEquals(listOf("Nintendo 3DS"), first.tags)
    }

    @Test
    fun `cartao do carrossel de lancamentos tambem tem capa`() {
        val html = """
            <div class="bg-white rounded-lg shadow-md overflow-hidden">
              <div class="aspect-[5/6] relative overflow-hidden">
                <a href="https://romsfun.com/roms/3ds/pokemon-x-and-y.html" class="absolute inset-0"><img src="https://romsfun.com/wp-content/uploads/2019/12/px.jpg" alt="Pokémon X &#038; Y"></a>
              </div>
              <div class="p-4"><h3 class="font-bold"><a href="https://romsfun.com/roms/3ds/pokemon-x-and-y.html"> Pokémon X &#038; Y </a></h3></div>
            </div>
        """
        val entry = source.parseCards(html).single()
        assertEquals("3ds", entry.systemId)
        assertEquals("Pokémon X & Y", entry.title)
        assertEquals("https://romsfun.com/wp-content/uploads/2019/12/px.jpg", entry.coverUrl)
    }

    @Test
    fun `consoles que o app nao roda ficam de fora`() {
        val html = card("xbox-360/halo-3.html", "Halo 3", "xbox-360", "Xbox 360") +
            card("mame/mario-bros-7.html", "Mario Bros.", "mame", "MAME")
        assertEquals(listOf("arcade"), source.parseCards(html).map { it.systemId })
    }

    @Test
    fun `variantes vem da tabela e o 3DS descarta CIA`() {
        fun row(n: Int, name: String, type: String, size: String) = """
            <tr class="odd:bg-gray-100">
              <td><a target="_blank" href="https://romsfun.com/download/asphalt-3d-43095/$n"> $name </a></td>
              <td> $type </td><td> $size </td>
            </tr>
        """
        val html = "<table><thead><tr><th>Filename</th><th>Type</th><th>Size</th></tr></thead><tbody>" +
            row(1, "Asphalt 3D (Europe) (EnFrDeEsIt)", "No-Intro (Decrypted)", "219.92 M") +
            row(3, "Asphalt 3D (USA) (EnFrEs)", "No-Intro (Decrypted)", "1.5 G") +
            row(5, "Asphalt 3D (Europe) (EnFrDeEsIt).cia", "CIA Format", "438.96 M") +
            row(6, "Asphalt 3D (Europe) (Theme)", "eShop", "3.5 M") +
            "</tbody></table>"
        val variants = source.parseVariants(html, "3ds")
        assertEquals(listOf("https://romsfun.com/download/asphalt-3d-43095/1", "https://romsfun.com/download/asphalt-3d-43095/3"), variants.map { it.downloadUrl })
        assertEquals("Europa", variants[0].region)
        assertEquals("No-Intro (Decrypted)", variants[0].note)
        assertEquals((219.92 * 1024 * 1024).toLong(), variants[0].sizeBytes)
        assertEquals((1.5 * 1024 * 1024 * 1024).toLong(), variants[1].sizeBytes)
        // Em outro console o .cia não é filtrado (não se aplica).
        assertEquals(4, source.parseVariants(html, "nds").size)
    }

    @Test
    fun `tamanhos no formato do site`() {
        assertEquals(512L * 1024, source.parseSize("512 K"))
        assertNull(source.parseSize(""))
    }

    @Test
    fun `todo console do mapa existe no app`() {
        val ids = com.retrovika.app.core.systems.Systems.all.map { it.id }.toSet()
        val missing = source.systems - ids
        assertTrue("Consoles sem sistema: $missing", missing.isEmpty())
    }

    @Test
    fun `chave de duplicata ignora regiao acentos e pontuacao`() {
        fun e(title: String, system: String) = CatalogEntry("x", "s", title, system, null, null, emptyList(), emptyList(), null, "", "", "game")
        assertEquals(dedupKey(e("Pokémon: Emerald (USA)", "gba")), dedupKey(e("pokemon emerald", "gba")))
        assertTrue(dedupKey(e("Pokemon Emerald", "gba")) != dedupKey(e("Pokemon Emerald", "nds")))
        // Títulos em japonês não podem virar todos a mesma chave vazia.
        assertTrue(dedupKey(e("ドラゴンクエスト", "nes")) != dedupKey(e("ファイナルファンタジー", "nes")))
        assertEquals(null, dedupKey(e("!!!", "nes")))
    }

    @Test
    fun `busca pela browse-all-roms com termo console e genero`() {
        val url = source.searchUrl("mario kart", listOf(91), Genre.RACING, 2)
        assertEquals("https://romsfun.com/browse-all-roms/page/2/?q=mario%20kart&consoles%5B%5D=91&genres%5B%5D=2357", url)
        // Sem termo: os mais populares.
        assertEquals("https://romsfun.com/browse-all-roms/?consoles%5B%5D=10&sort=popular", source.searchUrl(" ", listOf(10), null, 1))
        // Ordem escolhida vale com e sem termo; a que o site não tem (nota) cai no padrão.
        assertEquals("https://romsfun.com/browse-all-roms/?q=mario&consoles%5B%5D=10&sort=alphabetical", source.searchUrl("mario", listOf(10), null, 1, SortOrder.TITLE))
        assertEquals("https://romsfun.com/browse-all-roms/?consoles%5B%5D=10&sort=newest", source.searchUrl("", listOf(10), null, 1, SortOrder.RECENT))
        assertEquals("https://romsfun.com/browse-all-roms/?consoles%5B%5D=10&sort=popular", source.searchUrl("", listOf(10), null, 1, SortOrder.RATED))
    }

    @Test
    fun `todo genero tem ids no site`() {
        assertEquals(Genre.entries.toSet(), RomsFunSource.GENRE_TERMS.keys)
    }

    @Test
    fun `detalhes da pagina do jogo`() {
        val html = """
            <html><head><meta property="og:image" content="http://romsfun.com/wp-content/uploads/2019/11/asphalt-3d-3ds.jpg"></head><body>
            <div>
              <h1 class="text-xl">Asphalt 3D</h1>
              <div class="flex">
                <div class="rating" data-post_id="43095" data-rateyo-rating="4.4"></div>
                <span>4.4</span> <span>(6 reviews)</span> <span>•</span> <span> 19,708 downloads </span> <span> 219.92 M </span>
              </div>
              <div class="flex flex-wrap gap-2 mb-6">
                <a href="https://romsfun.com/roms/nintendo-3ds/"> Nintendo 3DS </a>
                <a href="https://romsfun.com/roms/nintendo-3ds/?genres[]=2357">Racing</a>
              </div>
              <table class="w-full"><tbody>
                <tr><td> Release Date </td><td><p>March 10, 2011</p></td></tr>
                <tr><td> Publisher </td><td><a href="https://romsfun.com/pub/ubisoft/">Ubisoft</a></td></tr>
                <tr><td> Region </td><td><a href="#">Europe</a>, <a href="#">Japan</a>, <a href="#">USA</a></td></tr>
              </tbody></table>
            </div>
            <div class="prose"><div class="revert page-content"><h3>INTRODUCTION</h3><p>Corrida em 3D.</p></div></div>
            <div id="romGallery"><a data-src="https://romsfun.com/wp-content/uploads/2022/03/shot1.jpeg"><img src="x"></a></div>
            </body></html>
        """
        fun e(title: String) = CatalogEntry("u", "romsfun", title, "3ds", null, null, emptyList(), listOf("Nintendo 3DS"), "u", "u", "a.7z", "game")
        val d = source.parseDetails(html, e("Asphalt 3D"))
        assertEquals("Asphalt 3D", d.title)
        assertEquals(SiteRating(4.4, 5.0, 6), d.rating)
        assertEquals(19_708L, d.downloads)
        assertEquals(listOf("Racing"), d.genres)
        assertEquals("March 10, 2011", d.releaseDate)
        assertEquals("Ubisoft", d.publisher)
        assertEquals("Europe, Japan, USA", d.region)
        assertEquals("https://romsfun.com/wp-content/uploads/2019/11/asphalt-3d-3ds.jpg", d.coverUrl)
        assertEquals(listOf("https://romsfun.com/wp-content/uploads/2022/03/shot1.jpeg"), d.screenshots)
        assertTrue(d.description!!.contains("Corrida em 3D."))
    }
}
