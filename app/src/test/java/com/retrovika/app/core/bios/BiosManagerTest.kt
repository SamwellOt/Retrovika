package com.retrovika.app.core.bios

import com.retrovika.app.core.systems.BiosFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosManagerTest {
    private val lo = BiosFile("000-lo.lo", 0)
    private val front = BiosFile("neocd_f.rom", 0, group = "neocd")
    private val top = BiosFile("neocd_t.rom", 0, group = "neocd")
    private val optional = BiosFile("extra.bin", 0, required = false)
    private val all = listOf(lo, front, top, optional)

    @Test
    fun `basta uma bios do grupo`() {
        assertTrue(BiosManager.unsatisfied(all) { it == lo || it == top }.isEmpty())
    }

    @Test
    fun `lista ausencias individuais e o grupo inteiro`() {
        val missing = BiosManager.unsatisfied(all) { false }
        assertEquals(listOf(listOf(lo), listOf(front, top)), missing)
    }
}
