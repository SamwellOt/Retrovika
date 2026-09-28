package com.retrovika.app.core.gameinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trechos reais da página do Backloggd (setembro de 2026), reduzidos ao que o leitor usa. */
class BackloggdClientTest {
    private val client = BackloggdClient()

    private val page = """
        <html><head>
        <meta property="og:description" content="Resumo curto">
        <script type="application/ld+json">
        { "@context": "https://schema.org/", "@type": "AggregateRating",
          "itemReviewed": { "@type": "Game", "name": "Super Mario 64" },
          "ratingValue": "4.159190937943853", "ratingCount": "57057", "bestRating": "5", "worstRating": "0.5" }
        </script></head><body>
        <img class="lazy" src="//images.igdb.com/igdb/image/upload/t_screenshot_med/sc8e2k.webp" data-src="//images.igdb.com/igdb/image/upload/t_1080p_2x/sc8e2k.webp">
        <div class="row" id="game-profile">
          <div class="card mx-auto game-cover overlay-hide" game_id="1074"><div class="overflow-wrapper">
            <img class="lazy card-img height" src="https://images.igdb.com/igdb/image/upload/t_cover_big/co721v.jpg" data-src="https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co721v.jpg">
          </div></div>
          <h1 class="mb-0">Super Mario 64</h1>
          <div class="col-auto my-auto game-subtitle">
            <a href="/games/lib/popular/release_year:1996/" class="game-year mb-0">1996</a>
            <a href="/company/nintendo-entertainment-analysis-and-development/">Nintendo Entertainment Analysis &amp; Development</a>
            <a href="/company/nintendo/">Nintendo</a>
          </div>
          <a href="/games/lib/popular/release_year:1996/">Jun 23, 1996</a>
          <div id="collapseSummary" class="collapse"><p class="mb-0">Mario is super in a whole new way!
        • Run freely in a grassy meadow</p></div>
          <a class="game-details-value" href="/games/lib/popular/genre:adventure/">Adventure</a>
          <a class="game-details-value" href="/games/lib/popular/genre:platform/">Platform</a>
          <div class="row mt-2" id="game-page-platforms">
            <a class="game-details-value game-page-platform" href=/games/lib/popular/release_platform:wiiu/><i></i> Wii U</a>
            <a class="game-details-value game-page-platform" href=/games/lib/popular/release_platform:n64/><i></i> Nintendo 64</a>
          </div>
          <p class="mb-0 subtitle-text">More info on <a href="https://www.igdb.com/games/super-mario-64" target="_blank">IGDB</a></p>
          <div class="row mx-0" id="ratings-bars-height">
            <div class="col px-0 top-tooltip" data-tippy-content="94 | 0.5 ★ Ratings (0.2%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="121 | 1.0 ★ Ratings (0.2%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="199 | 1.5 ★ Ratings (0.3%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="645 | 2.0 ★ Ratings (1.1%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="1216 | 2.5 ★ Ratings (2.1%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="4175 | 3.0 ★ Ratings (7.3%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="7664 | 3.5 ★ Ratings (13.4%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="15802 | 4.0 ★ Ratings (27.7%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="11448 | 4.5 ★ Ratings (20.1%)"></div>
            <div class="col px-0 top-tooltip" data-tippy-content="15665 | 5.0 ★ Ratings (27.5%)"></div>
          </div>
          <div class="backloggd-container center-container log-counters h-100">
            <div class="row container-row w-100"><a class="d-flex w-100" href="/logs/super-mario-64/plays/">
              <div class="col-auto pr-0"><p class="log-counter-label"><i class="fa-kit fa-gamepad-classic"></i> Plays</p></div>
              <div class="col-auto ml-auto pl-0"><p class="mb-0 log-counter-stat">99K</p></div></a></div>
            <div class="row container-row w-100"><a class="d-flex w-100" href="/logs/super-mario-64/playing/">
              <div class="col-auto pr-0"><p class="log-counter-label"><i class="fa-solid fa-play"></i> Playing</p></div>
              <div class="col-auto ml-auto pl-0"><p class="mb-0 log-counter-stat">1.9K</p></div></a></div>
          </div>
          <div class="row time-section">
            <div class="col pr-2"><a href="/lists/super-mario-64/"><div class="backloggd-container">
              <div class="row"><div class="col text-center"><h3 class="mb-0">11K</h3></div></div>
              <div class="row mt-1"><div class="col text-center"><p class="mb-0"><i class="fa-light fa-layer-group"></i> Lists</p></div></div>
            </div></a></div>
          </div>
          <div class="row time-played-overview time-section d-none d-md-flex">
            <div class="row time-played"><div class="col-auto mr-auto pl-1 pr-2">
              <p class="mb-0 stat-value element-revealed">20<small>h</small></p>
              <p class="mb-0 stat-value element-hidden">Hidden</p>
              <p class="mb-0 label">average</p></div></div>
            <div class="row time-played"><div class="col-auto mr-auto pl-1 pr-2">
              <p class="mb-0 stat-value element-revealed">11<small>h</small></p>
              <p class="mb-0 label">to finish</p></div></div>
          </div>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `le nota distribuicao contadores e ficha da pagina do jogo`() {
        val info = client.parseGame(page, "super-mario-64")
        assertEquals("Super Mario 64", info.title)
        assertEquals(4.159, info.rating!!, 0.001)
        assertEquals(57057, info.ratingCount)
        assertEquals(listOf(94, 121, 199, 645, 1216, 4175, 7664, 15802, 11448, 15665), info.histogram)
        assertEquals("1996", info.year)
        assertEquals("Jun 23, 1996", info.releaseDate)
        assertEquals(listOf("Nintendo Entertainment Analysis & Development", "Nintendo"), info.companies)
        assertEquals(listOf("Adventure", "Platform"), info.genres)
        assertEquals(listOf("Wii U", "Nintendo 64"), info.platforms)
        assertEquals("https://images.igdb.com/igdb/image/upload/t_1080p_2x/sc8e2k.webp", info.backdropUrl)
        assertEquals("https://images.igdb.com/igdb/image/upload/t_cover_big_2x/co721v.jpg", info.coverUrl)
        assertEquals("https://www.igdb.com/games/super-mario-64", info.igdbUrl)
        assertTrue(info.description!!.startsWith("Mario is super in a whole new way!\n• Run freely"))
    }

    @Test
    fun `le as reviews com nota status plataforma e curtidas`() {
        val html = """
            <turbo-stream action="replace"><template><turbo-frame id="game-reviews-section">
            <div class="row pt-2 pb-1 review-card"><div class="col"><div class="row">
              <div class="col-auto mb-auto pr-0" id="avatar"><a href="/u/ggxxbb/"><img src="https://backloggd-avatars.b-cdn.net/abc?q=25" /></a></div>
              <div class="col">
                <div class="col-auto my-auto username-link pr-0 mr-n2"><p class="mb-0">ggxxbb</p></div>
                <div class="row star-ratings-static"><div class="stars-top" style="width:90%"></div></div>
                <p class="mb-0 play-type completed">Completed</p>
                <a class="my-0 ml-auto review-platform" href="/u/ggxxbb/games/added/played_platform:n64/"><p class="mb-0">Nintendo 64</p></a>
                <time datetime="2026-09-23T00:58:55Z">Sep 23, 2026</time>
                <div class="collapse mb-0 card-text" id="collapseReview5316552game">
                  Primeiro parágrafo da review.<br><br>Segundo
                  parágrafo.
                </div>
                <p class="mb-0 d-inline-block like-counter"><a href="/u/ggxxbb/review/5316552/likes/">38 Likes</a></p>
                <a class="open-review-link" href="/u/ggxxbb/review/5316552/">Open review</a>
              </div>
            </div></div></div>
            </turbo-frame></template></turbo-stream>
        """.trimIndent()
        val review = client.parseReviews(html).single()
        assertEquals("ggxxbb", review.user)
        assertEquals(4.5, review.rating!!, 0.001)
        assertEquals("Completed", review.status)
        assertEquals("Nintendo 64", review.platform)
        assertEquals("2026-09-23T00:58:55Z", review.date)
        assertEquals("Primeiro parágrafo da review.\n\nSegundo parágrafo.", review.text)
        assertEquals(38, review.likes)
        assertEquals("https://backloggd.com/u/ggxxbb/review/5316552/", review.url)
    }
}
