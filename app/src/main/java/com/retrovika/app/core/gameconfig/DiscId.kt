package com.retrovika.app.core.gameconfig

import android.content.Context
import android.net.Uri
import com.retrovika.app.core.library.Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * O ID de 6 caracteres do disco de GameCube/Wii (`GALE01`, `RSBE01`…), que o Dolphin usa para achar o
 * `GameSettings/<ID>.ini` do jogo. Fica no começo da imagem (ISO, GCM, NKit) ou, no WBFS, em 0x200.
 * Formatos comprimidos (RVZ, GCZ, WIA, CISO) não têm o ID à vista: nulo.
 */
object DiscId {

    private const val WBFS_HEADER_OFFSET = 0x200

    /** O ID se [head] (os primeiros bytes da imagem) o traz; [wbfs] diz que o formato é WBFS. */
    fun parse(head: ByteArray, wbfs: Boolean = false): String? {
        val start = if (wbfs) WBFS_HEADER_OFFSET else 0
        if (head.size < start + 6) return null
        val id = String(head, start, 6, Charsets.US_ASCII)
        return id.takeIf { text -> text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } }
    }

    suspend fun read(context: Context, game: Game): String? = withContext(Dispatchers.IO) {
        val ext = game.fileName.substringAfterLast('.', "").lowercase()
        if (ext in UNREADABLE) return@withContext null
        val wbfs = ext == "wbfs"
        runCatching {
            open(context, game)?.use { input ->
                val head = ByteArray(if (wbfs) WBFS_HEADER_OFFSET + 6 else 6)
                var read = 0
                while (read < head.size) {
                    val n = input.read(head, read, head.size - read)
                    if (n < 0) break
                    read += n
                }
                parse(head.copyOf(read), wbfs)
            }
        }.getOrNull()
    }

    private fun open(context: Context, game: Game): InputStream? =
        if (game.isContentUri) context.contentResolver.openInputStream(Uri.parse(game.uri))
        else File(game.uri).takeIf { it.exists() }?.inputStream()

    private val UNREADABLE = setOf("rvz", "gcz", "wia", "ciso", "tgc", "dol", "elf", "wad", "json")
}
