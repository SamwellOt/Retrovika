package com.retrovika.app.core.textmem

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPatcherTest {

    /** A memória do jogo em um array; as gravações são aplicadas na hora (o jogo de verdade as aplica no próximo quadro). */
    private class FakeMemory(size: Int) : MemoryAccess {
        val bytes = ByteArray(size)
        val writes = mutableListOf<Triple<Int, Int, Boolean>>()
        override fun read(offset: Int, length: Int) = if (offset < 0 || offset + length > bytes.size) null else bytes.copyOfRange(offset, offset + length)
        var accept = true
        override fun write(offset: Int, data: ByteArray, wordSwap: Boolean): Boolean {
            if (!accept) return false
            writes += Triple(offset, data.size, wordSwap)
            for (i in data.indices) {
                val logical = offset + i
                bytes[if (wordSwap) logical xor 3 else logical] = data[i]
            }
            return true
        }
        fun put(at: Int, text: String) = text.toByteArray(Charsets.ISO_8859_1).copyInto(bytes, at)
        fun text(at: Int, length: Int) = String(bytes, at, length, Charsets.ISO_8859_1).trimEnd('\u0000')
    }

    private val hook = TextHook("dialogo", address = 0x100, length = 128, encoding = TextEncoding.ASCII)

    private val pt = mapOf(
        "Hello World!" to "Ola mundo!",
        "Welcome to the village of Lakeside" to "Bem-vindo a vila de Lakeside",
    )

    private val translate: suspend (List<String>) -> Map<String, String> = { texts -> texts.mapNotNull { t -> pt[t]?.let { t to it } }.toMap() }

    @Test
    fun `o botao traduz e escreve no lugar do original`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val report = TextPatcher(mem).step(listOf(hook), force = true, translate)
        assertEquals(1, report.written)
        assertEquals("Ola mundo!", mem.text(0x100, 20))
        // O resto do texto original não sobra atrás da tradução.
        assertEquals(0, mem.bytes[0x100 + "Ola mundo!".length].toInt())
        assertEquals(0, mem.bytes[0x100 + "Hello World!".length - 1].toInt())
    }

    @Test
    fun `texto mais longo cabe no espaco que o jogo deixou depois do original`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Welcome to the village of Lakeside") }
        TextPatcher(mem).step(listOf(hook), force = true, translate)
        assertEquals("Bem-vindo a vila de Lakeside", mem.text(0x100, 60))
    }

    @Test
    fun `nao escreve alem do espaco do proprio texto quando o vizinho esta colado`() = runBlocking {
        // "Hello World!" e, colado (um terminador só), outro texto que o tradutor não conhece.
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!"); it.put(0x100 + 13, "Next dialog line") }
        val neighbor = mem.bytes.copyOfRange(0x100 + 13, 0x100 + 13 + 17)
        val report = TextPatcher(mem).step(listOf(hook), true) { texts ->
            texts.filter { it == "Hello World!" }.associateWith { "Uma traducao bem mais comprida que o original" }
        }
        assertEquals(1, report.written)
        assertEquals(1, report.truncated)
        // O vizinho continua byte a byte igual, e nada foi gravado depois do espaço do primeiro texto (13 bytes).
        assertTrue(neighbor.contentEquals(mem.bytes.copyOfRange(0x100 + 13, 0x100 + 13 + 17)))
        assertTrue(mem.writes.all { (offset, size, _) -> offset + size <= 0x100 + 13 })
        assertTrue(mem.text(0x100, 12).endsWith("..."))
    }

    @Test
    fun `um texto ja traduzido nao e traduzido de novo`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val patcher = TextPatcher(mem)
        var calls = 0
        val counted: suspend (List<String>) -> Map<String, String> = { calls++; translate(it) }
        patcher.step(listOf(hook), true, counted)
        patcher.step(listOf(hook), true, counted)
        assertEquals(1, calls)
        assertEquals("Ola mundo!", mem.text(0x100, 20))
    }

    @Test
    fun `se o jogo recarrega o original a traducao guardada volta sem rede`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val patcher = TextPatcher(mem)
        patcher.step(listOf(hook), true, translate)
        mem.bytes.fill(0, 0x100, 0x140); mem.put(0x100, "Hello World!")     // o jogo reescreveu o original
        var calls = 0
        val report = patcher.step(listOf(hook), false) { calls++; emptyMap() }
        assertEquals(0, calls)
        assertEquals(1, report.written)
        assertEquals("Ola mundo!", mem.text(0x100, 20))
    }

    @Test
    fun `no modo automatico o texto so e traduzido depois de ficar igual em duas leituras`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello Wor") }       // diálogo ainda sendo montado
        val patcher = TextPatcher(mem)
        var asked = emptyList<String>()
        val spy: suspend (List<String>) -> Map<String, String> = { asked = it; translate(it) }
        patcher.step(listOf(hook), false, spy)
        assertTrue(asked.isEmpty())
        mem.put(0x100, "Hello World!")                                         // mudou: ainda não é estável
        val second = patcher.step(listOf(hook), false, spy)
        assertTrue(asked.isEmpty())
        assertEquals(1, second.waiting)
        val third = patcher.step(listOf(hook), false, spy)                    // igual à leitura anterior: traduz
        assertEquals(listOf("Hello World!"), asked)
        assertEquals(1, third.written)
    }

    @Test
    fun `se o jogo muda o texto durante a traducao nada e escrito por cima do novo`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val report = TextPatcher(mem).step(listOf(hook), true) { texts ->
            mem.bytes.fill(0, 0x100, 0x140); mem.put(0x100, "Something else entirely")    // novo diálogo entra
            texts.associateWith { "Ola mundo!" }
        }
        assertEquals(0, report.written)
        assertEquals("Something else entirely", mem.text(0x100, 40))
    }

    @Test
    fun `quebras de linha do jogo sao mantidas na traducao`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Welcome to the\nvillage of Lakeside") }
        TextPatcher(mem).step(listOf(hook), true) { texts -> texts.associateWith { "Bem-vindo a vila de Lakeside, viajante" } }
        val written = mem.text(0x100, 100)
        assertTrue("Tem quebra de linha: '$written'", written.contains('\n'))
        // As linhas nao passam da largura da maior linha original ("village of Lakeside": 19 letras).
        assertTrue(written.split('\n').all { it.length <= 19 })
    }

    @Test
    fun `n64 escreve em palavras invertidas`() = runBlocking {
        val mem = FakeMemory(512)
        // "Hello World!" em palavras invertidas, como o RDRAM do N64 o guarda
        "lleHoW o!dlr".toByteArray().copyInto(mem.bytes, 0x100)
        val h = hook.copy(wordSwap = true, length = 64)
        val report = TextPatcher(mem).step(listOf(h), true, translate)
        assertEquals(1, report.written)
        assertTrue(mem.writes.single().third)
        // Lido de volta na mesma ordem lógica, é a tradução.
        val back = TextDecoder.read(mem.bytes, h)
        assertEquals(listOf("Ola mundo!"), back)
    }

    @Test
    fun `tabela propria e utf16 voltam para o formato do jogo`() = runBlocking {
        val table = LinearTable(upper = 0xBB, lower = 0xD5, digit = 0xA1, space = 0x00, terminator = 0xFF, extra = mapOf("!" to 0xAB))
        val th = TextHook("t", 0x40, 64, TextEncoding.TABLE, table = table)
        val mem = FakeMemory(256)
        fun enc(s: String) = s.map { c -> when (c) { in 'A'..'Z' -> 0xBB + (c - 'A'); in 'a'..'z' -> 0xD5 + (c - 'a'); ' ' -> 0x00; '!' -> 0xAB; else -> 0 }.toByte() }.toByteArray()
        enc("Hello World!").copyInto(mem.bytes, 0x40); mem.bytes[0x40 + 12] = 0xFF.toByte()
        TextPatcher(mem).step(listOf(th), true) { t -> t.associateWith { "Ola mundo!" } }
        assertEquals(listOf("Ola mundo!"), TextDecoder.read(mem.bytes, th))

        val uh = TextHook("u", 0x80, 64, TextEncoding.UTF16LE)
        val mem2 = FakeMemory(256)
        "Hello World!".toByteArray(Charsets.UTF_16LE).copyInto(mem2.bytes, 0x80)
        TextPatcher(mem2).step(listOf(uh), true) { t -> t.associateWith { "Olá, mundo!" } }
        assertEquals(listOf("Olá, mundo!"), TextDecoder.read(mem2.bytes, uh))   // UTF-16 guarda os acentos
    }

    @Test
    fun `gancho fora da memoria nao quebra e avisa`() = runBlocking {
        val report = TextPatcher(FakeMemory(64)).step(listOf(hook.copy(address = 0x100)), true, translate)
        assertTrue(report.unreadable)
        assertEquals(0, report.written)
    }

    // ---- telas de texto em grade (C64): códigos de tela A=1..Z=26, espaço=32, dígitos 48..57, "." = 46
    private val c64 = LinearTable(upper = 1, lower = null, digit = 48, space = 32, terminator = 0xFF, extra = mapOf("." to 46, "*" to 42))

    private fun screen(mem: FakeMemory, row: Int, text: String, width: Int = 40) {
        val bytes = ByteArray(width) { 32 }
        text.forEachIndexed { i, c ->
            bytes[i] = when (c) { in 'A'..'Z' -> (c - 'A' + 1).toByte(); in '0'..'9' -> (48 + (c - '0')).toByte(); '.' -> 46; '*' -> 42; else -> 32 }
        }
        bytes.copyInto(mem.bytes, 0x100 + row * width)
    }

    private fun row(mem: FakeMemory, row: Int, width: Int = 40) = TextDecoder.decodeTable(mem.bytes.copyOfRange(0x100 + row * width, 0x100 + (row + 1) * width), c64).trim()

    private val screenHook = TextHook("tela", 0x100, 40 * 10, TextEncoding.TABLE, table = c64, gridWidth = 40)

    @Test
    fun `traduz cada linha da tela de texto no lugar e completa com espacos`() = runBlocking {
        val mem = FakeMemory(1024)
        screen(mem, 0, "64K RAM SYSTEM  38911 BASIC BYTES FREE")
        screen(mem, 2, "READY.")
        val dict = mapOf("64K RAM SYSTEM 38911 BASIC BYTES FREE" to "SISTEMA 64K 38911 BYTES LIVRES", "READY." to "PRONTO.")
        val report = TextPatcher(mem).step(listOf(screenHook), true) { t -> t.mapNotNull { x -> dict[x]?.let { x to it } }.toMap() }
        assertEquals(2, report.written)
        assertEquals("SISTEMA 64K 38911 BYTES LIVRES", row(mem, 0))
        assertEquals("PRONTO.", row(mem, 2))
        // A linha continua com 40 bytes: o resto virou espaço (32), sem sobras da linha antiga.
        assertTrue(mem.bytes.copyOfRange(0x100 + 80 + 7, 0x100 + 120).all { it.toInt() == 32 })
        assertTrue(mem.bytes.copyOfRange(0x100 + 30, 0x100 + 40).all { it.toInt() == 32 })
    }

    @Test
    fun `linhas de graficos e memoria qualquer nao sao tocadas`() = runBlocking {
        val mem = FakeMemory(1024)
        // Labirinto do 10 PRINT: códigos 77 e 78 (não fazem parte da tabela)
        for (i in 0 until 40) mem.bytes[0x100 + 40 + i] = (77 + i % 2).toByte()
        // Memória qualquer
        val random = java.util.Random(1)
        for (i in 0 until 40) mem.bytes[0x100 + 80 + i] = random.nextInt(256).toByte()
        screen(mem, 0, "READY.")
        val before = mem.bytes.copyOf()
        TextPatcher(mem).step(listOf(screenHook), true) { t -> t.associateWith { "PRONTO." } }
        assertEquals("PRONTO.", row(mem, 0))
        // Só a linha 0 mudou
        assertTrue(mem.bytes.copyOfRange(0x100 + 40, 0x100 + 400).contentEquals(before.copyOfRange(0x100 + 40, 0x100 + 400)))
    }

    @Test
    fun `linha traduzida maior que a tela e encurtada na largura`() = runBlocking {
        val mem = FakeMemory(1024)
        screen(mem, 0, "READY.")
        val long = "UMA TRADUCAO MUITO MAIS COMPRIDA QUE UMA LINHA DE QUARENTA COLUNAS"
        val report = TextPatcher(mem).step(listOf(screenHook), true) { t -> t.associateWith { long } }
        assertEquals(1, report.truncated)
        assertTrue(row(mem, 0).length <= 40)
        // A linha seguinte não foi invadida
        assertTrue(mem.bytes.copyOfRange(0x100 + 40, 0x100 + 80).all { it.toInt() == 0 })
    }

    @Test
    fun `texto cortado pela janela nao e traduzido nem gravado`() = runBlocking {
        val mem = FakeMemory(512)
        mem.put(0x100, "Hello World! and the dialog goes on past the window")
        val narrow = hook.copy(length = 20)      // a janela acaba no meio do texto
        val report = TextPatcher(mem).step(listOf(narrow), true) { t -> t.associateWith { "Ola" } }
        assertEquals(0, report.written)
        assertEquals("Hello World! and the dialog goes on past the window", mem.text(0x100, 60))
    }

    @Test
    fun `nunca escreve alem da capacidade do texto`() = runBlocking {
        val mem = FakeMemory(512)
        // O texto e um terminador, e logo depois outro dado: capacidade = 13 bytes.
        mem.put(0x100, "Hello World!"); mem.bytes[0x100 + 13] = 0x7F
        TextPatcher(mem).step(listOf(hook), true) { t -> t.associateWith { "Uma traducao bem longa" } }
        assertEquals(0x7F, mem.bytes[0x100 + 13].toInt())
        assertTrue(mem.writes.all { (offset, size, _) -> offset + size <= 0x100 + 13 })
    }

    @Test
    fun `reset pedido de fora vale no proximo passo`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val patcher = TextPatcher(mem)
        var calls = 0
        val counted: suspend (List<String>) -> Map<String, String> = { calls++; translate(it) }
        patcher.step(listOf(hook), true, counted)
        mem.bytes.fill(0, 0x100, 0x140); mem.put(0x100, "Hello World!")
        patcher.reset()
        patcher.step(listOf(hook), true, counted)
        assertEquals(2, calls)    // esqueceu a tradução e pediu de novo
    }

    @Test
    fun `lixo binario perto do dialogo nao e tratado como texto`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        // Depois do diálogo: uma estrutura qualquer com bytes que por acaso são "An" seguido de valores altos
        byteArrayOf(0x41, 0x6E, 0x12, 0x80.toByte(), 0x99.toByte(), 0xF3.toByte(), 0x07, 0xAA.toByte()).copyInto(mem.bytes, 0x100 + 40)
        var asked = emptyList<String>()
        TextPatcher(mem).step(listOf(hook), true) { t -> asked = t; t.associateWith { "x" } }
        assertEquals(listOf("Hello World!"), asked)
    }

    @Test
    fun `fila de gravacao cheia nao conta como escrito e o texto volta no proximo passo`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val patcher = TextPatcher(mem)
        mem.accept = false
        val first = patcher.step(listOf(hook), true, translate)
        assertEquals(0, first.written)
        assertEquals("Hello World!", mem.text(0x100, 20))
        mem.accept = true
        var calls = 0
        val second = patcher.step(listOf(hook), false) { calls++; emptyMap() }
        assertEquals(0, calls)                       // a tradução já estava guardada
        assertEquals(1, second.written)
        assertEquals("Ola mundo!", mem.text(0x100, 20))
    }

    @Test
    fun `no maximo oito pedidos por passo`() = runBlocking {
        val mem = FakeMemory(2048)
        for (i in 0 until 20) mem.put(0x100 + i * 40, "Dialog line number ${'a' + i}")
        var asked = 0
        val big = hook.copy(length = 1024)
        TextPatcher(mem).step(listOf(big), true) { t -> asked = t.size; t.associateWith { "ok" } }
        assertEquals(8, asked)
    }

    @Test
    fun `traducao vazia nao apaga o dialogo`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val report = TextPatcher(mem).step(listOf(hook), true) { t -> t.associateWith { "   " } }
        assertEquals(0, report.written)
        assertEquals("Hello World!", mem.text(0x100, 20))
    }

    @Test
    fun `texto que so tem simbolos fora da tabela nao vira linha em branco`() = runBlocking {
        val table = LinearTable(upper = 1, space = null, terminator = 0xFF)   // sem espaço e sem pontuação
        val grid = TextHook("g", 0x100, 80, TextEncoding.TABLE, table = table, gridWidth = 40)
        val mem = FakeMemory(512)
        val row = ByteArray(40) { (1 + it % 26).toByte() }       // 40 letras A..Z
        row.copyInto(mem.bytes, 0x100)
        TextPatcher(mem).step(listOf(grid), true) { t -> t.associateWith { "!!! ???" } }
        // nenhum caractere da tradução existe na tabela: nada é gravado, a linha continua como estava
        assertTrue(row.contentEquals(mem.bytes.copyOfRange(0x100, 0x100 + 40)))
    }

    @Test
    fun `um campo zero logo depois da string nao vira espaco para a traducao crescer`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }          // 12 letras
        // uma estrutura: depois da string vêm muitos zeros e, bem depois, um campo de verdade
        mem.bytes[0x100 + 100] = 0x55
        TextPatcher(mem).step(listOf(hook), true) { t -> t.associateWith { "x".repeat(150) } }
        assertEquals(0x55, mem.bytes[0x100 + 100].toInt())
        assertTrue(mem.writes.all { (offset, size, _) -> offset + size <= 0x100 + 12 * 2 + 8 })
    }

    @Test
    fun `utf16 com janela de tamanho impar nao trata o texto cortado como inteiro`() = runBlocking {
        val mem = FakeMemory(512)
        "Hello World!".toByteArray(Charsets.UTF_16LE).copyInto(mem.bytes, 0x100)    // 24 bytes e nada depois dentro da janela
        val odd = TextHook("u", 0x100, 25, TextEncoding.UTF16LE)
        val report = TextPatcher(mem).step(listOf(odd), true) { t -> t.associateWith { "Ola" } }
        assertEquals(0, report.written)
    }

    @Test
    fun `o que ja escrevemos continua nosso depois de um reset`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val patcher = TextPatcher(mem)
        patcher.step(listOf(hook), true, translate)
        patcher.reset()                                   // as fontes mudaram
        var asked = emptyList<String>()
        patcher.step(listOf(hook), true) { t -> asked = t; t.associateWith { "x" } }
        assertTrue("O texto traduzido não pode voltar ao tradutor: $asked", asked.isEmpty())
        assertEquals("Ola mundo!", mem.text(0x100, 20))
    }

    @Test
    fun `texto que ja esta no idioma de quem le nao e traduzido de novo`() = runBlocking {
        // Um save carregado com a tradução já escrita: a memória tem português e o patcher não sabe que é dele.
        val mem = FakeMemory(512).also { it.put(0x100, "Ola mundo, que bom ver voce") }
        var asked = emptyList<String>()
        val patcher = TextPatcher(mem) { com.retrovika.app.core.translate.TranslationText.probablyTranslated(it, "pt") }
        patcher.step(listOf(hook), true) { t -> asked = t; t.associateWith { "x" } }
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `texto sem palavras traduziveis nao e pedido de novo`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "HP 25 / 40 ok") }
        val patcher = TextPatcher(mem)
        var calls = 0
        val none: suspend (List<String>) -> Map<String, String> = { calls++; emptyMap() }
        patcher.step(listOf(hook), true, none)
        patcher.step(listOf(hook), true, none)
        assertEquals(1, calls)
    }

    @Test
    fun `dialogo novo em ingles continua sendo traduzido mesmo com o filtro de ja traduzido`() = runBlocking {
        val mem = FakeMemory(512).also { it.put(0x100, "Hello World!") }
        val patcher = TextPatcher(mem) { com.retrovika.app.core.translate.TranslationText.probablyTranslated(it, "pt") }
        val report = patcher.step(listOf(hook), true, translate)
        assertEquals(1, report.written)
        assertEquals("Ola mundo!", mem.text(0x100, 20))
    }

    // ---- Shift-JIS: letras de largura total quando a fonte não tem as meias-larguras
    private val sjis = charset("Shift_JIS")
    private val sjHook = TextHook("sj", 0x100, 64, TextEncoding.SHIFT_JIS)

    private fun sjText(mem: FakeMemory, text: String) = text.toByteArray(sjis).copyInto(mem.bytes, 0x100)

    @Test
    fun `shift-jis sem letras meia-largura recebe a traducao em largura total`() = runBlocking {
        val mem = FakeMemory(512).also { sjText(it, "こんにちは、ゆうしゃよ") }
        val report = TextPatcher(mem).step(listOf(sjHook), true) { t -> t.associateWith { "Ola!" } }
        assertEquals(1, report.written)
        assertArrayEquals("Ｏｌａ！".toByteArray(sjis), mem.bytes.copyOfRange(0x100, 0x100 + 8))
    }

    @Test
    fun `shift-jis com letras meia-largura recebe a traducao em ascii`() = runBlocking {
        val mem = FakeMemory(512).also { sjText(it, "Lv5のゆうしゃ") }
        val report = TextPatcher(mem).step(listOf(sjHook), true) { t -> t.associateWith { "Hero" } }
        assertEquals(1, report.written)
        assertArrayEquals("Hero".toByteArray(Charsets.US_ASCII), mem.bytes.copyOfRange(0x100, 0x100 + 4))
    }

    @Test
    fun `traducao em largura total gravada nao e traduzida de novo`() = runBlocking {
        val mem = FakeMemory(512).also { sjText(it, "こんにちは、ゆうしゃよ") }
        val patcher = TextPatcher(mem)
        patcher.step(listOf(sjHook), true) { t -> t.associateWith { "Ola!" } }
        var calls = 0
        val report = patcher.step(listOf(sjHook), true) { calls++; emptyMap() }
        assertEquals(0, calls)
        assertEquals(0, report.written)
    }

    @Test
    fun `largura total ja traduzida e reconhecida pela forma normalizada`() = runBlocking {
        val mem = FakeMemory(512).also { sjText(it, "Ｏｌａ！") }
        var seen = emptyList<String>()
        TextPatcher(mem) { text -> seen += text; text == "Ola!" }.step(listOf(sjHook), true) { t -> t.associateWith { "x" } }
        assertTrue(seen.contains("Ola!"))
        assertEquals(0, mem.writes.size)
    }
}
