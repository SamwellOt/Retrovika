package com.retrovika.app.core.share

import com.retrovika.app.core.library.Game
import com.retrovika.app.core.library.RomNaming
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** O que acompanha um estado compartilhado: de que jogo, em que núcleo e quando. */
@Serializable
data class StateManifest(
    val format: Int = StatePackage.FORMAT,
    val systemId: String,
    val coreId: String,
    val title: String,
    val rawName: String,
    val datName: String? = null,
    val fileName: String,
    val createdAt: Long,
)

/**
 * Pacote `.rvstate`: um zip com `manifest.json`, `state.bin` (o estado do núcleo) e, quando há,
 * `thumb.png` (a tela daquele momento). O amigo abre o arquivo (ou lê o QR code) e o jogo começa ali.
 */
object StatePackage {
    const val FORMAT = 1
    const val EXTENSION = "rvstate"
    const val MIME = "application/vnd.retrovika.state"
    /** Estados de PS2/GameCube passam de 100 MB; acima disto o pacote é recusado ao receber. */
    const val MAX_SIZE = 512L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    class Contents(val manifest: StateManifest, val state: ByteArray, val thumbnail: ByteArray?)

    fun pack(manifest: StateManifest, state: ByteArray, thumbnail: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream(state.size / 2 + 4096)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST)); zip.write(json.encodeToString(StateManifest.serializer(), manifest).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(STATE)); zip.write(state); zip.closeEntry()
            if (thumbnail != null) { zip.putNextEntry(ZipEntry(THUMB)); zip.write(thumbnail); zip.closeEntry() }
        }
        return out.toByteArray()
    }

    /** Nulo quando o arquivo não é um pacote de estado (outro zip, arquivo corrompido). */
    fun unpack(bytes: ByteArray): Contents? = runCatching {
        var manifest: StateManifest? = null
        var state: ByteArray? = null
        var thumb: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when (e.name) {
                    MANIFEST -> manifest = json.decodeFromString(StateManifest.serializer(), zip.readBytes().decodeToString())
                    STATE -> state = zip.readBytes()
                    THUMB -> thumb = zip.readBytes()
                }
            }
        }
        val m = manifest ?: return null
        val s = state?.takeIf { it.isNotEmpty() } ?: return null
        if (m.format > FORMAT) return null
        Contents(m, s, thumb)
    }.getOrNull()

    fun manifestFor(game: Game, coreId: String, now: Long) = StateManifest(
        systemId = game.systemId, coreId = coreId, title = game.title, rawName = game.rawName,
        datName = game.datName, fileName = game.fileName, createdAt = now,
    )

    /**
     * O jogo da biblioteca que corresponde ao do pacote, do mais para o menos seguro: o mesmo nome do DAT,
     * o mesmo nome de arquivo, o mesmo título limpo com a mesma região. Só o título (outra região) não
     * basta: o estado de uma versão costuma não abrir na outra.
     */
    fun match(manifest: StateManifest, library: List<Game>): Game? {
        val same = library.filter { it.systemId == manifest.systemId }
        manifest.datName?.let { dat -> same.firstOrNull { it.datName == dat }?.let { return it } }
        same.firstOrNull { it.rawName == manifest.rawName }?.let { return it }
        same.firstOrNull { it.datName != null && it.datName == manifest.rawName }?.let { return it }
        same.firstOrNull { it.fileName.equals(manifest.fileName, ignoreCase = true) }?.let { return it }
        val title = RomNaming.cleanTitle(manifest.datName ?: manifest.rawName).lowercase()
        val region = RomNaming.region(manifest.datName ?: manifest.rawName)
        return same.firstOrNull {
            val name = it.datName ?: it.rawName
            RomNaming.cleanTitle(name).lowercase() == title && RomNaming.region(name) == region
        }
    }

    private const val MANIFEST = "manifest.json"
    private const val STATE = "state.bin"
    private const val THUMB = "thumb.png"
}
