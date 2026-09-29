package com.retrovika.app.core.share

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.retrovika.app.R
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.library.LibraryRepository
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.storage.FileNames
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.settings.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Estados compartilhados: "tente passar desta fase". Quem envia empacota um estado (arquivo pelo menu de
 * compartilhar do Android, ou QR code na mesma rede); quem recebe tem o jogo achado na biblioteca e abre
 * direto naquele momento.
 */
class SharedStates(
    private val context: Context,
    private val paths: StoragePaths,
    private val library: LibraryRepository,
) {
    sealed interface Received {
        val manifest: StateManifest
        /** O jogo está na biblioteca: o estado foi guardado em [stateFile], pronto para abrir. */
        data class Ready(override val manifest: StateManifest, val game: Game, val stateFile: File, val thumbnail: Bitmap?) : Received
        /** Não há na biblioteca uma versão do jogo em que o estado abra. */
        data class MissingGame(override val manifest: StateManifest, val thumbnail: Bitmap?) : Received
    }

    fun pack(game: Game, coreId: String, state: ByteArray, thumbnail: Bitmap?): ByteArray {
        val png = thumbnail?.let { bmp -> ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray() }
        return StatePackage.pack(StatePackage.manifestFor(game, coreId, System.currentTimeMillis()), state, png)
    }

    /** Grava o pacote no cache e devolve o Intent do menu de compartilhar do Android. */
    suspend fun shareIntent(game: Game, bytes: ByteArray): Intent = withContext(Dispatchers.IO) {
        val dir = context.cacheDir.resolve("shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = dir.resolve(FileNames.safe("${game.title}.${StatePackage.EXTENSION}"))
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val text = context.localized().getString(R.string.share_state_message, game.title)
        Intent(Intent.ACTION_SEND)
            .setType(StatePackage.MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun readUri(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > StatePackage.MAX_SIZE) throw LocalizedException(R.string.share_state_invalid)
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw LocalizedException(R.string.share_state_invalid)
    }

    /** Abre o pacote e guarda o estado junto aos do jogo correspondente (`received.state`). */
    suspend fun receive(bytes: ByteArray): Received = withContext(Dispatchers.IO) {
        val contents = StatePackage.unpack(bytes) ?: throw LocalizedException(R.string.share_state_invalid)
        val thumb = contents.thumbnail?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        val game = StatePackage.match(contents.manifest, library.all.first())
            ?: return@withContext Received.MissingGame(contents.manifest, thumb)
        val dir = paths.statesFor(game.systemId, game.id)
        val file = File(dir, RECEIVED_STATE)
        file.writeBytes(contents.state)
        contents.thumbnail?.let { File(dir, RECEIVED_THUMB).writeBytes(it) }
        Received.Ready(contents.manifest, game, file, thumb)
    }

    companion object {
        const val RECEIVED_STATE = "received.state"
        const val RECEIVED_THUMB = "received.png"
    }
}
