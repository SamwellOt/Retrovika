package com.retrovika.app.core.gameinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HowLongToBeatClientTest {
    private val client = HowLongToBeatClient { error("sem rede no teste") }

    // Recorte do __NEXT_DATA__ real de /game/3978 (God of War III), só com os campos que o app lê.
    private fun page(game: String) = """
        <html><head><title>How long is God of War III? | HowLongToBeat</title></head><body>
        <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"game":{"data":{"game":[$game],"individuality":[]}}}},"page":"/game/[gameId]"}</script>
        </body></html>
    """

    @Test
    fun `le os cinco tempos com o numero de jogadores`() {
        val info = client.parse("3978", page(
            """{"game_id":3978,"game_name":"God of War III","comp_main":35999,"comp_main_count":1460,"comp_plus":39857,"comp_plus_count":792,
               "comp_100":67252,"comp_100_count":285,"comp_all":38689,"comp_all_count":2537,"comp_speed":24164,"comp_speed_count":5,
               "invested_co":19,"invested_co_count":1}""",
        ))!!
        assertEquals("God of War III", info.title)
        assertEquals("https://howlongtobeat.com/game/3978", info.url)
        assertEquals(
            listOf(
                HltbTime(HltbTime.Kind.MAIN, 35999, 1460),
                HltbTime(HltbTime.Kind.EXTRAS, 39857, 792),
                HltbTime(HltbTime.Kind.COMPLETIONIST, 67252, 285),
                HltbTime(HltbTime.Kind.ALL_STYLES, 38689, 2537),
                HltbTime(HltbTime.Kind.SPEEDRUN, 24164, 5),
            ),
            info.times,
        )
    }

    @Test
    fun `tempo sem jogadores fica de fora e jogo sem nenhum da null`() {
        val info = client.parse("1", page("""{"game_name":"X","comp_main":3600,"comp_main_count":3,"comp_speed":0,"comp_speed_count":0}"""))!!
        assertEquals(listOf(HltbTime.Kind.MAIN), info.times.map { it.kind })
        assertNull(client.parse("2", page("""{"game_name":"Y","comp_main":0,"comp_main_count":0}""")))
        assertNull(client.parse("3", "<html>sem dados</html>"))
    }
}
