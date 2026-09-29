package com.retrovika.app.core.cheats

import com.retrovika.app.R
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.library.RomNaming
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.systems.Systems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URLEncoder

/** O que o usuário escolheu para um jogo: o arquivo da libretro-database e os códigos (ligados ou próprios). */
@Serializable
data class GameCheats(val file: String? = null, val cheats: List<Cheat> = emptyList()) {
    val enabled: List<Cheat> get() = cheats.filter { it.enabled }
}

/**
 * Trapaças da libretro-database (`cht/<console>/<jogo>.cht`, o mesmo acervo do RetroArch).
 *
 * A lista de arquivos de cada console vem da API do GitHub: `contents/cht` dá o SHA de cada pasta, e a
 * árvore dela (`git/trees/<sha>`) lista todos os arquivos (a API de conteúdo para em 1000, e o SNES tem
 * mais). A lista fica guardada por uma semana; cada .cht baixado fica guardado de vez. Escolhas por jogo
 * ficam em `cheats/<console>/<id>.json`.
 */
class CheatRepository(private val paths: StoragePaths) {

    private val root: File get() = paths.root.resolve("cheats").apply { mkdirs() }
    private val lock = Mutex()

    /** Pasta do console na libretro-database (o mesmo nome dos thumbnails), ou nulo sem trapaças conhecidas. */
    fun folderFor(systemId: String): String? = Systems.byId(systemId)?.libretroDbName

    fun supports(systemId: String): Boolean = folderFor(systemId) != null

    suspend fun load(game: Game): GameCheats = withContext(Dispatchers.IO) {
        val f = stateFile(game)
        if (!f.exists()) return@withContext GameCheats()
        runCatching { Http.json.decodeFromString(GameCheats.serializer(), f.readText()) }.getOrDefault(GameCheats())
    }

    suspend fun save(game: Game, cheats: GameCheats) = withContext(Dispatchers.IO) {
        val f = stateFile(game)
        if (cheats.file == null && cheats.cheats.isEmpty()) { f.delete(); return@withContext }
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Http.json.encodeToString(GameCheats.serializer(), cheats))
        if (!tmp.renameTo(f)) tmp.delete()
    }

    /** Todos os .cht do console, em ordem alfabética. */
    suspend fun files(systemId: String): List<String> = lock.withLock {
        val folder = folderFor(systemId) ?: return@withLock emptyList()
        val cache = root.resolve("index").apply { mkdirs() }.resolve("$systemId.txt")
        withContext(Dispatchers.IO) {
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < INDEX_TTL_MS) {
                return@withContext cache.readLines().filter { it.isNotBlank() }
            }
            val fetched = runCatching { fetchIndex(folder) }
            fetched.getOrNull()?.let { list ->
                writeAtomically(cache, list.joinToString("\n"))
                return@withContext list
            }
            // Sem internet (ou sem cota na API): a lista velha ainda serve.
            if (cache.exists()) cache.readLines().filter { it.isNotBlank() } else throw fetched.exceptionOrNull()!!
        }
    }

    private suspend fun fetchIndex(folder: String): List<String> {
        val headers = mapOf("Accept" to "application/vnd.github+json")
        val dirs = Http.json.parseToJsonElement(Http.getString("$API/contents/cht", headers)).jsonArray
        val sha = dirs.map { it.jsonObject }.firstOrNull { it.str("name").equals(folder, ignoreCase = true) }?.str("sha")
            ?: throw LocalizedException(R.string.cheats_none_for_console)
        val tree = Http.json.parseToJsonElement(Http.getString("$API/git/trees/$sha", headers)).jsonObject
        return (tree["tree"] as? JsonArray).orEmpty()
            .map { it.jsonObject }
            .filter { it.str("type") == "blob" && it.str("path").endsWith(".cht", ignoreCase = true) }
            .map { it.str("path") }
            .sortedBy { it.lowercase() }
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()

    /** Arquivos que parecem ser deste jogo, do mais provável para o menos. */
    suspend fun suggestions(game: Game): List<String> {
        val name = game.datName ?: game.rawName
        return CheatFile.match(name, files(game.systemId), RomNaming::cleanTitle)
    }

    suspend fun search(systemId: String, query: String): List<String> = CheatFile.search(query, files(systemId))

    /** Códigos de um arquivo da pasta do console (baixado uma vez e guardado). */
    suspend fun read(systemId: String, file: String): List<Cheat> = withContext(Dispatchers.IO) {
        val folder = folderFor(systemId) ?: return@withContext emptyList()
        val local = root.resolve(systemId).apply { mkdirs() }.resolve(file.replace('/', '_'))
        val text = if (local.exists()) local.readText() else {
            val url = "$RAW/${encode(folder)}/${encode(file)}"
            Http.getString(url).also { writeAtomically(local, it) }
        }
        CheatFile.parse(text)
    }

    /** Temporário + renomear: um arquivo cortado no meio (processo morto, disco cheio) seria usado para sempre. */
    private fun writeAtomically(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) tmp.delete()
    }

    private fun stateFile(game: Game) = root.resolve(game.systemId).apply { mkdirs() }.resolve("${game.id}.json")

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private const val API = "https://api.github.com/repos/libretro/libretro-database"
        private const val RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/cht"
        private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
