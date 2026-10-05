package com.retrovika.app.emulation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.storage.RZip
import com.retrovika.app.core.storage.StoragePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

data class SaveSlot(val index: Int, val exists: Boolean, val timestamp: Long?, val thumbnail: File?)

/**
 * Save states por jogo: `states/<sistema>/<id>/slot<N>.state` + `slot<N>.png`.
 * O slot 0 é o salvamento automático ao sair do jogo.
 */
class SaveStates internal constructor(
    private val dirOf: () -> File,
    private val savesDirOf: () -> File,
    private val rawName: String,
) {
    constructor(paths: StoragePaths, game: Game) : this(
        { paths.statesFor(game.systemId, game.id) }, { paths.savesFor(game.systemId) }, game.rawName,
    )

    private val dir get() = dirOf()

    private fun stateFile(slot: Int) = File(dir, "slot$slot.state")
    private fun thumbFile(slot: Int) = File(dir, "slot$slot.png")

    fun slots(): List<SaveSlot> = (0..SLOT_COUNT).map { i ->
        val f = stateFile(i)
        SaveSlot(i, f.exists(), f.takeIf { it.exists() }?.lastModified(), thumbFile(i).takeIf { it.exists() })
    }

    /**
     * O estado do slot, já descompactado (os gravados antes da compactação são lidos como estão). Se uma gravação
     * deste slot ainda está em andamento ([writeAsync], da saída do jogo anterior), espera por ela: o arquivo antigo
     * continuaria lá, inteiro, e o jogo abriria num momento mais velho que o que o usuário deixou.
     */
    fun read(slot: Int): ByteArray? {
        awaitWrite(stateFile(slot))
        return stateFile(slot).takeIf { it.exists() }?.let(RZip::read)
    }

    /** Tamanho do estado do slot já descompactado (só o cabeçalho é lido); 0 sem arquivo, -1 se ilegível. */
    fun size(slot: Int): Long {
        awaitWrite(stateFile(slot))
        return stateFile(slot).takeIf { it.exists() }?.let(RZip::originalSize) ?: 0L
    }

    fun write(slot: Int, data: ByteArray, thumbnail: Bitmap?) {
        // Estado vazio = o núcleo falhou ao serializar: nunca troca um save bom por ele.
        if (data.isEmpty()) throw IOException("empty state")
        RZip.write(stateFile(slot), data)
        synchronized(thumbLock) {
            generations.merge(stateFile(slot).path, 1L, Long::plus)
            // Sem captura nova, a miniatura antiga mostraria um momento que não corresponde mais ao estado. Uma captura
            // feita junto com este estado ainda pode chegar depois, por [attachThumbnail].
            if (thumbnail == null) thumbFile(slot).delete()
            else writeThumbnail(thumbFile(slot), thumbnail)
        }
    }

    /** Quantas vezes o estado do slot foi gravado neste processo: identifica a gravação a que uma miniatura pertence. */
    fun generation(slot: Int): Long = generations[stateFile(slot).path] ?: 0L

    /**
     * Miniatura que chegou depois do estado (o PixelCopy responde só depois de o onPause gravar): só é gravada se o
     * estado ainda é o da gravação [generation]; trocado nesse meio-tempo, ela mostraria outro momento.
     */
    fun attachThumbnail(slot: Int, thumbnail: Bitmap, generation: Long) {
        attachIfCurrent(slot, generation) { writeThumbnail(it, thumbnail) }
    }

    /** O teste da regra de [attachThumbnail], sem Bitmap (que não existe na JVM): [write] recebe o arquivo da miniatura. */
    internal fun attachIfCurrent(slot: Int, generation: Long, write: (File) -> Unit): Boolean {
        synchronized(thumbLock) {
            if (generation(slot) != generation || !stateFile(slot).exists()) return false
            write(thumbFile(slot))
            return true
        }
    }

    /**
     * Como [write], mas compactar e gravar saem da thread de quem chamou: o estado (já serializado) vai para o [scope]
     * em Dispatchers.IO. A gravação continua atômica (temporário + renomear, ver [RZip.write]) e fica registrada em
     * [pending], de onde [read] e [awaitAll] a esperam: abrir o mesmo jogo logo depois nunca lê um arquivo pela metade
     * nem um mais velho que o prestes a ser trocado. Duas gravações do mesmo arquivo andam uma de cada vez, na ordem.
     */
    fun writeAsync(scope: CoroutineScope, slot: Int, data: ByteArray, thumbnail: Bitmap?) {
        val key = stateFile(slot).path
        val done = CompletableFuture<Unit>()
        val previous = pending.put(key, done)
        val job = scope.launch(Dispatchers.IO) {
            previous?.let { runCatching { it.get() } }
            runCatching { write(slot, data, thumbnail) }
        }
        // No fim de qualquer jeito, inclusive cancelado antes de começar: quem espera em [pending] nunca fica preso.
        job.invokeOnCompletion {
            done.complete(Unit)
            pending.remove(key, done)
        }
    }

    private fun awaitWrite(file: File) {
        pending[file.path]?.let { runCatching { it.get() } }
    }

    /** Miniatura por temporário + renomear: um PNG pela metade nunca aparece na lista de estados. */
    private fun writeThumbnail(file: File, thumbnail: Bitmap) {
        val tmp = File(file.path + ".tmp")
        try {
            tmp.outputStream().use { thumbnail.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (!tmp.renameTo(file)) throw IOException("rename ${tmp.name}")
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /**
     * Guarda o estado do slot à parte (`slotN.state.bak`). Usado quando o salvamento automático não
     * carrega (outro núcleo, versão nova do núcleo): o próximo salvamento automático não o destrói.
     */
    fun backup(slot: Int) {
        val file = stateFile(slot)
        awaitWrite(file)
        // Nome único: um segundo estado incompatível (troca de núcleo e volta) não apaga o primeiro backup.
        if (file.exists()) file.renameTo(File(dir, "slot$slot.${System.currentTimeMillis()}.state.bak"))
        thumbFile(slot).delete()
        // Só os mais recentes: cada troca de núcleo ou de versão deixava mais um estado inteiro para sempre.
        dir.listFiles { f -> f.name.startsWith("slot$slot.") && f.name.endsWith(".state.bak") }
            ?.sortedByDescending { it.name.removePrefix("slot$slot.").removeSuffix(".state.bak").toLongOrNull() ?: 0L }
            ?.drop(MAX_BACKUPS)
            ?.forEach { it.delete() }
    }

    fun thumbnail(slot: Int): Bitmap? {
        awaitWrite(stateFile(slot))
        return thumbFile(slot).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    /** Memória do cartucho, nomeada como no RetroArch para facilitar a migração de saves. */
    fun sramFile(): File = File(savesDirOf(), "$rawName.srm")

    companion object {
        /**
         * Gravações em andamento ([writeAsync]) por arquivo, do processo todo: cada jogo abre o seu [SaveStates], e o
         * próximo jogo (ou o mesmo, de novo) precisa ver o que o anterior ainda está gravando.
         */
        private val pending = ConcurrentHashMap<String, CompletableFuture<Unit>>()

        /** Gravações de cada estado ([generation]); com [thumbLock], a miniatura atrasada não passa por cima de outra. */
        private val generations = ConcurrentHashMap<String, Long>()
        private val thumbLock = Any()

        /** Espera todas as gravações em andamento (nunca chamar da thread principal). */
        fun awaitAll() {
            pending.values.toList().forEach { runCatching { it.get() } }
        }

        const val AUTO_SLOT = 0
        const val SLOT_COUNT = 4
        /** Backups ([backup]) guardados por slot. */
        const val MAX_BACKUPS = 2
    }
}
