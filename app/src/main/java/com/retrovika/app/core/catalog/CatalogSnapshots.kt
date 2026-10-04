package com.retrovika.app.core.catalog

import com.retrovika.app.core.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/**
 * A última primeira página de cada filtro do Explorar, guardada no disco: abrir o app e o Explorar mostra os
 * cartões na hora, enquanto a busca de verdade atualiza a lista (os sites levam de meio segundo a vários
 * segundos, e o RomsFun ainda passa pela verificação do WebView).
 */
class CatalogSnapshots(private val dir: File) {

    @Serializable
    private class Snapshot(val at: Long, val entries: List<CatalogEntry>, val totalPages: Int, val totalResults: Int)

    class Shown(val entries: List<CatalogEntry>, val totalPages: Int, val totalResults: Int)

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.json")
    }

    suspend fun read(key: String): Shown? = withContext(Dispatchers.IO) {
        runCatching {
            val f = file(key)
            if (!f.exists()) return@runCatching null
            val snap = Http.json.decodeFromString(Snapshot.serializer(), f.readText())
            if (System.currentTimeMillis() - snap.at > TTL_MS || snap.entries.isEmpty()) null
            else Shown(snap.entries, snap.totalPages, snap.totalResults)
        }.getOrNull()
    }

    suspend fun write(key: String, page: CatalogPage) = withContext(Dispatchers.IO) {
        runCatching {
            dir.mkdirs()
            val f = file(key)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Http.json.encodeToString(Snapshot.serializer(), Snapshot(System.currentTimeMillis(), page.entries.take(MAX_ENTRIES), page.totalPages, page.totalResults)))
            tmp.renameTo(f)
        }
    }

    /** Apaga as listas vencidas: [read] já as ignorava, mas elas ficavam no disco para sempre. */
    fun prune(now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.forEach { f -> if (now - f.lastModified() > TTL_MS) f.delete() }
    }

    private companion object {
        const val TTL_MS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_ENTRIES = 60
    }
}
