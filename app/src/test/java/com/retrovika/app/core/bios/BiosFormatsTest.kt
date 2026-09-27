package com.retrovika.app.core.bios

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BiosFormatsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Diretório de ROM mínimo, no formato que o LRPS2 procura. */
    private fun ps2Bios(size: Int = 4 * 1024 * 1024, entries: List<String> = listOf("RESET", "ROMDIR", "EXTINFO", "ROMVER"), offset: Int = 0): ByteArray {
        val data = ByteArray(size)
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(offset)
        entries.forEach { name ->
            buf.put(name.toByteArray().copyOf(10)).putShort(0).putInt(0x10)
        }
        return data
    }

    private fun file(bytes: ByteArray) = tmp.newFile().apply { writeBytes(bytes) }

    @Test
    fun `aceita diretorio de ROM com ROMVER, mesmo depois do inicio`() {
        assertTrue(BiosFormats.isPs2Bios(file(ps2Bios())))
        assertTrue(BiosFormats.isPs2Bios(file(ps2Bios(offset = 0x2700))))
    }

    @Test
    fun `recusa tamanho fora de 4 a 8 MB, sem RESET ou sem ROMVER`() {
        assertFalse(BiosFormats.isPs2Bios(file(ps2Bios(size = 2 * 1024 * 1024))))
        assertFalse(BiosFormats.isPs2Bios(file(ByteArray(4 * 1024 * 1024))))
        assertFalse(BiosFormats.isPs2Bios(file(ps2Bios(entries = listOf("RESET", "ROMDIR", "EXTINFO")))))
    }
}
