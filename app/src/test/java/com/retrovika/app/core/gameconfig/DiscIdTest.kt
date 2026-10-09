package com.retrovika.app.core.gameconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiscIdTest {

    @Test
    fun `iso traz o id no comeco`() {
        assertEquals("GBLPGL", DiscId.parse("GBLPGL\u0000\u0000rest".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("RSBE01", DiscId.parse("RSBE01".toByteArray()))
    }

    @Test
    fun `wbfs traz o id em 0x200`() {
        val head = ByteArray(0x206).also { "RSBP01".toByteArray().copyInto(it, 0x200) }
        assertEquals("RSBP01", DiscId.parse(head, wbfs = true))
        assertNull(DiscId.parse(head, wbfs = false))
    }

    @Test
    fun `cabecalho curto ou sem letras e numeros nao e id`() {
        assertNull(DiscId.parse(ByteArray(3)))
        assertNull(DiscId.parse(byteArrayOf(0x52, 0x56, 0x5A, 0x01, 0x01, 0x00)))   // "RVZ" + lixo
        assertNull(DiscId.parse("GB LPG".toByteArray()))
        assertNull(DiscId.parse(ByteArray(6)))
    }
}
