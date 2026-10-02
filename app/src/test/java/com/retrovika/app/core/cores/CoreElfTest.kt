package com.retrovika.app.core.cores

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreElfTest {
    private fun header(elfClass: Int, machine: Int) = ByteArray(20).also {
        it[0] = 0x7F; it[1] = 'E'.code.toByte(); it[2] = 'L'.code.toByte(); it[3] = 'F'.code.toByte()
        it[4] = elfClass.toByte(); it[5] = 1
        it[18] = (machine and 0xFF).toByte(); it[19] = (machine shr 8).toByte()
    }

    @Test
    fun `aceita a biblioteca da arquitetura certa`() {
        assertTrue(CoreManager.elfMatches(header(2, 183), "arm64-v8a"))
        assertTrue(CoreManager.elfMatches(header(1, 40), "armeabi-v7a"))
        assertTrue(CoreManager.elfMatches(header(2, 62), "x86_64"))
    }

    @Test
    fun `recusa outra arquitetura, pagina de erro e arquivo curto`() {
        assertFalse(CoreManager.elfMatches(header(1, 40), "arm64-v8a"))
        assertFalse(CoreManager.elfMatches(header(2, 62), "arm64-v8a"))
        assertFalse(CoreManager.elfMatches("<!DOCTYPE html><html>".toByteArray(), "arm64-v8a"))
        assertFalse(CoreManager.elfMatches(ByteArray(4), "arm64-v8a"))
    }
}
