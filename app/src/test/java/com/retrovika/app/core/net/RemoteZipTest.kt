package com.retrovika.app.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RemoteZipTest {

    private val content = ByteArray(200_000) { (it * 31 % 251).toByte() }

    private fun zip(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("jni/x86/libonnxruntime.so")); zip.write(ByteArray(5000) { 1 }); zip.closeEntry()
            zip.putNextEntry(ZipEntry("jni/arm64-v8a/libonnxruntime.so")); zip.write(content); zip.closeEntry()
        }
    }.toByteArray()

    private fun extract(data: ByteArray, tailSize: Int): ByteArray? = runBlocking {
        val tailStart = (data.size - tailSize).coerceAtLeast(0)
        val tail = data.copyOfRange(tailStart, data.size)
        val entry = RemoteZip.findEntry(tail, tailStart.toLong(), "jni/arm64-v8a/libonnxruntime.so") { offset, length ->
            data.copyOfRange(offset.toInt(), offset.toInt() + length)
        } ?: return@runBlocking null
        val start = entry.localHeaderOffset.toInt() + RemoteZip.localHeaderLength(data.copyOfRange(entry.localHeaderOffset.toInt(), entry.localHeaderOffset.toInt() + 30))
        val compressed = data.copyOfRange(start, start + entry.compressedSize.toInt())
        val out = ByteArray(entry.size.toInt())
        Inflater(true).apply { setInput(compressed); inflate(out); end() }
        assertEquals(CRC32().apply { update(out) }.value, entry.crc)
        out
    }

    @Test
    fun `acha a entrada com o diretorio central no fim lido`() {
        assertArrayEquals(content, extract(zip(), 64 * 1024))
    }

    @Test
    fun `busca o diretorio central quando ele nao cabe no fim lido`() {
        assertArrayEquals(content, extract(zip(), 60))
    }

    @Test
    fun `entrada ausente`() = runBlocking {
        val data = zip()
        assertNull(RemoteZip.findEntry(data, 0, "nada.so") { _, _ -> ByteArray(0) })
    }
}
