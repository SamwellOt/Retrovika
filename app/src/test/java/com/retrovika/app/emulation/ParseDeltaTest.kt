package com.retrovika.app.emulation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseDeltaTest {

    @Test
    fun `aceita sinal opcional e decimal`() {
        assertEquals(-3L, parseDelta("-3"))
        assertEquals(5L, parseDelta("+5"))
        assertEquals(5L, parseDelta("5"))
    }

    @Test
    fun `aceita hexadecimal com e sem sinal`() {
        assertEquals(16L, parseDelta("0x10"))
        assertEquals(-16L, parseDelta("-0x10"))
    }

    @Test
    fun `recusa texto vazio e sinais repetidos`() {
        assertNull(parseDelta("abc"))
        assertNull(parseDelta(""))
        assertNull(parseDelta("-"))
        assertNull(parseDelta("+-3"))
        assertNull(parseDelta("0x-10"))
    }
}
