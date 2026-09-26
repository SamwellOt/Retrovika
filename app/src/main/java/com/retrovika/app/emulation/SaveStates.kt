package com.retrovika.app.emulation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.storage.StoragePaths
import java.io.File

data class SaveSlot(val index: Int, val exists: Boolean, val timestamp: Long?, val thumbnail: File?)

/**
 * Save states por jogo: `states/<sistema>/<id>/slot<N>.state` + `slot<N>.png`.
 * O slot 0 é o salvamento automático ao sair do jogo.
 */
class SaveStates(private val paths: StoragePaths, private val game: Game) {

    private val dir get() = paths.statesFor(game.systemId, game.id)

    private fun stateFile(slot: Int) = File(dir, "slot$slot.state")
    private fun thumbFile(slot: Int) = File(dir, "slot$slot.png")

    fun slots(): List<SaveSlot> = (0..SLOT_COUNT).map { i ->
        val f = stateFile(i)
        SaveSlot(i, f.exists(), f.takeIf { it.exists() }?.lastModified(), thumbFile(i).takeIf { it.exists() })
    }

    fun read(slot: Int): ByteArray? = stateFile(slot).takeIf { it.exists() }?.readBytes()

    fun write(slot: Int, data: ByteArray, thumbnail: Bitmap?) {
        val tmp = File(dir, "slot$slot.tmp")
        tmp.writeBytes(data)
        tmp.renameTo(stateFile(slot))
        // Sem captura nova, a miniatura antiga mostraria um momento que não corresponde mais ao estado.
        if (thumbnail == null) thumbFile(slot).delete()
        else thumbFile(slot).outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 90, it) }
    }

    fun thumbnail(slot: Int): Bitmap? = thumbFile(slot).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }

    /** Memória do cartucho, nomeada como no RetroArch para facilitar a migração de saves. */
    fun sramFile(): File = File(paths.savesFor(game.systemId), "${game.rawName}.srm")

    companion object {
        const val AUTO_SLOT = 0
        const val SLOT_COUNT = 4
    }
}
