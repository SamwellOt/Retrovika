package com.retrovika.app.core.dat

import android.content.Context
import android.net.Uri
import com.retrovika.app.core.library.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import kotlin.coroutines.coroutineContext

/**
 * Calcula os hashes de um ROM para comparar com os DATs No-Intro.
 *
 * No-Intro faz o hash do ROM "puro". Alguns dumps trazem cabeçalhos de copiadora que
 * não entram no cálculo: o iNES (16 bytes, jogos ".nes") e o cabeçalho de 512 bytes do SNES.
 * Por isso calculamos, numa única passada, o hash com e sem esse cabeçalho e devolvemos
 * ambos como candidatos.
 */
object RomHasher {
    data class Hashes(val crc32: List<String>, val md5: List<String>, val size: Long)

    /**
     * Os hashes do arquivo. Sem [md5] só o CRC32 é calculado (várias vezes mais rápido que o MD5): os DATs No-Intro
     * trazem o CRC de quase todas as entradas, e o MD5 só é pedido quando o CRC não achou nada.
     */
    suspend fun hash(context: Context, game: Game, md5: Boolean = true): Hashes? = withContext(Dispatchers.IO) {
        openStream(context, game)?.use { input -> hashStream(input, headerLength(context, game), md5) }
    }

    private fun headerLength(context: Context, game: Game): Int {
        val ext = game.fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "nes", "unf", "unif" -> if (hasINesHeader(context, game)) 16 else 0
            "smc", "sfc", "swc", "fig" -> if (game.size % 1024L == 512L) 512 else 0
            else -> 0
        }
    }

    private fun hasINesHeader(context: Context, game: Game): Boolean =
        runCatching {
            openStream(context, game)?.use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 && head[0] == 'N'.code.toByte() && head[1] == 'E'.code.toByte() &&
                    head[2] == 'S'.code.toByte() && head[3] == 0x1A.toByte()
            } ?: false
        }.getOrDefault(false)

    private suspend fun hashStream(input: InputStream, headerLength: Int, withMd5: Boolean): Hashes {
        val crcFull = CRC32(); val crcTrim = CRC32()
        val md5Full = MessageDigest.getInstance("MD5"); val md5Trim = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(64 * 1024)
        var pos = 0L
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            crcFull.update(buffer, 0, n)
            if (withMd5) md5Full.update(buffer, 0, n)
            if (headerLength > 0) {
                val skip = (headerLength - pos).coerceIn(0L, n.toLong()).toInt()
                if (skip < n) {
                    crcTrim.update(buffer, skip, n - skip)
                    if (withMd5) md5Trim.update(buffer, skip, n - skip)
                }
            }
            pos += n
            total += n
        }
        val crcs = buildList {
            add(crcFull.value.toString(16).padStart(8, '0'))
            if (headerLength > 0) add(crcTrim.value.toString(16).padStart(8, '0'))
        }
        val md5s = if (!withMd5) emptyList() else buildList {
            add(md5Full.digest().toHex())
            if (headerLength > 0) add(md5Trim.digest().toHex())
        }
        return Hashes(crcs.distinct(), md5s.distinct(), total)
    }

    private fun openStream(context: Context, game: Game): InputStream? =
        if (game.isContentUri) context.contentResolver.openInputStream(Uri.parse(game.uri))
        else File(game.uri).takeIf { it.exists() }?.inputStream()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
