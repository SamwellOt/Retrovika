package com.retrovika.app.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlsTest {
    @Test
    fun `codifica como o android Uri encode`() {
        assertEquals("action%20rpg", Urls.encode("action rpg"))
        assertEquals("beat%20'em%20up", Urls.encode("beat 'em up"))
        assertEquals("Pok%C3%A9mon", Urls.encode("Pokémon"))
        assertEquals("a%2Fb", Urls.encode("a/b"))
        assertEquals("a/b%20c", Urls.encode("a/b c", allow = "/"))
        assertEquals("_-!.~'()*", Urls.encode("_-!.~'()*"))
    }

    @Test
    fun `monta a query mantendo a ordem e repetindo chaves`() {
        assertEquals(
            "https://x.org/s?fl%5B%5D=id&fl%5B%5D=title&q=a%26b",
            Urls.withQuery("https://x.org/s", listOf("fl[]" to "id", "fl[]" to "title", "q" to "a&b")),
        )
        assertEquals("https://x.org/s", Urls.withQuery("https://x.org/s", emptyList()))
    }
}
