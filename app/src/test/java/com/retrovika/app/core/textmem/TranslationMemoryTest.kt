package com.retrovika.app.core.textmem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationMemoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun file() = tmp.root.resolve("sub/1.pt.json")

    @Test
    fun `guarda e le de volta depois de gravar em outra instancia`() {
        val f = file()
        TranslationMemory(f).apply {
            put("Hello", "Olá")
            put("Bye", "Tchau")
            flush()
        }
        val again = TranslationMemory(f)
        assertEquals("Olá", again.get("Hello"))
        assertEquals("Tchau", again.get("Bye"))
        assertEquals(2, again.size)
    }

    @Test
    fun `lru descarta a entrada menos usada e a ordem sobrevive ao reload`() {
        val f = file()
        TranslationMemory(f, maxEntries = 3).apply {
            put("a", "A")
            put("b", "B")
            put("c", "C")
            assertEquals("A", get("a"))
            put("d", "D")
            assertNull(get("b"))
            assertEquals("A", get("a"))
            assertEquals("C", get("c"))
            assertEquals("D", get("d"))
            flush()
        }
        val reloaded = TranslationMemory(f, maxEntries = 3)
        assertEquals(3, reloaded.size)
        // A ordem gravada é a, c, d (a é a mais antiga): ao entrar e, sai a.
        reloaded.put("e", "E")
        assertNull(reloaded.get("a"))
        assertEquals("C", reloaded.get("c"))
        assertEquals("D", reloaded.get("d"))
        assertEquals("E", reloaded.get("e"))
    }

    @Test
    fun `arquivo corrompido vira memoria vazia e flush o reescreve`() {
        val f = file()
        f.parentFile.mkdirs()
        f.writeText("not json")
        val memory = TranslationMemory(f)
        assertEquals(0, memory.size)
        assertNull(memory.get("Hello"))
        memory.put("Hello", "Olá")
        memory.flush()
        val reloaded = TranslationMemory(f)
        assertEquals("Olá", reloaded.get("Hello"))
        assertEquals(1, reloaded.size)
    }

    @Test
    fun `versao desconhecida e tratada como vazia`() {
        val f = file()
        f.parentFile.mkdirs()
        f.writeText("""{"version":99,"entries":[["a","b"]]}""")
        assertEquals(0, TranslationMemory(f).size)
    }

    @Test
    fun `originais e traducoes em branco sao ignorados`() {
        val f = file()
        TranslationMemory(f).apply {
            put("", "vazio")
            put("   ", "espaços")
            put("Hello", "")
            put("Oi", "  ")
            assertEquals(0, size)
            // Igual ao original vale: significa que não precisa traduzir.
            put("OK", "OK")
            assertEquals("OK", get("OK"))
            flush()
        }
        assertEquals(1, TranslationMemory(f).size)
    }

    @Test
    fun `flush sem mudancas nao cria o arquivo`() {
        val f = file()
        val memory = TranslationMemory(f)
        assertNull(memory.get("Hello"))
        memory.flush()
        assertFalse(f.exists())
        assertTrue(f.parentFile.listFiles().isNullOrEmpty())
    }
}
