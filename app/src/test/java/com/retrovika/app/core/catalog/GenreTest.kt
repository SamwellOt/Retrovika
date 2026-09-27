package com.retrovika.app.core.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenreTest {
    @Test
    fun `casa etiquetas e sinonimos sem diferenciar maiusculas`() {
        assertTrue(Genre.RPG.matches(listOf("Open Source", "RPG")))
        assertTrue(Genre.RPG.matches(listOf("Role-Playing")))
        assertTrue(Genre.SHOOTER.matches(listOf("Shoot 'em up")))
        assertTrue(Genre.PLATFORM.matches(listOf("Platformer")))
        assertTrue(Genre.PLATFORM.matches(listOf("Metroidvania")))
        assertTrue(Genre.PUZZLE.matches(listOf("puzzle game")))
    }

    @Test
    fun `so casa palavras inteiras`() {
        // "sport" dentro de "transport", "rts" dentro de "parts", "race" não é sinônimo.
        assertFalse(Genre.SPORTS.matches(listOf("Transport")))
        assertFalse(Genre.STRATEGY.matches(listOf("Spare parts")))
        assertFalse(Genre.RACING.matches(listOf("Trace")))
        assertFalse(Genre.ACTION.matches(listOf("Interaction")))
    }

    @Test
    fun `sem etiquetas nao casa nada`() {
        assertTrue(Genre.of(emptyList()).isEmpty())
        assertFalse(Genre.ACTION.matches(emptyList()))
    }

    @Test
    fun `of lista os generos na ordem do enum`() {
        assertEquals(listOf(Genre.ACTION, Genre.PUZZLE), Genre.of(listOf("Puzzle", "Action")))
    }

    @Test
    fun `todo genero tem termo de busca entre as palavras-chave`() {
        Genre.entries.forEach { g ->
            assertTrue("${g.name}: ${g.searchTerm}", g.matches(listOf(g.searchTerm)))
            g.keywords.forEach { assertEquals("${g.name}: $it", it.lowercase(), it) }
        }
    }
}
