package com.retrovika.app.core.gameinfo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameInfoRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var fetches = 0
    private val page = """
        <script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"game":{"data":{"game":[
        {"game_name":"Super Mario 64","comp_main":43200,"comp_main_count":700}]}}}}}</script>
    """
    private val hltb = HowLongToBeatClient { url -> fetches++; if (url.endsWith("/404")) "<html></html>" else page }

    private fun repo() = GameInfoRepository(BackloggdClient(), hltb = hltb, dir = tmp.root)

    @Test
    fun `guarda no disco e reaproveita depois de reabrir o app`() = runBlocking {
        val first = repo().howLongToBeat("1")!!
        // Outra instância (o app foi reaberto): vem do disco, sem pedir de novo.
        val again = repo().howLongToBeat("1")!!
        assertEquals(1, fetches)
        assertEquals(first, again)
        assertEquals(HltbTime(HltbTime.Kind.MAIN, 43200, 700), again.times.single())
    }

    @Test
    fun `nao encontrado tambem fica guardado`() = runBlocking {
        assertNull(repo().howLongToBeat("404"))
        assertNull(repo().howLongToBeat("404"))
        assertEquals(1, fetches)
    }

    @Test
    fun `arquivo corrompido vale como ausente`() = runBlocking {
        repo().howLongToBeat("1")
        tmp.root.walkTopDown().filter { it.isFile }.forEach { it.writeText("{lixo") }
        repo().howLongToBeat("1")
        assertEquals(2, fetches)
    }
}
