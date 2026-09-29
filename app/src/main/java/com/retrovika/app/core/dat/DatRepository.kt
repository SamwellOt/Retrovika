package com.retrovika.app.core.dat

import android.content.Context
import android.net.Uri
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.library.RomNaming
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.systems.Systems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Identifica os ROMs que o usuário já importou comparando os hashes do arquivo contra
 * os DATs No-Intro (só metadados). A partir da correspondência, mostra o nome canônico,
 * a região/revisão e todas as outras versões conhecidas do mesmo jogo.
 */
class DatRepository(
    private val context: Context,
    private val dao: DatDao,
) {
    sealed interface Identification {
        /** Sistema sem DAT de hashing por arquivo (ex.: discos ópticos). */
        data object Unsupported : Identification
        /** Nenhuma correspondência: o arquivo não bate com nenhuma versão conhecida. */
        data object NotFound : Identification
        data class Found(val match: DatEntry, val versions: List<DatEntry>) : Identification
    }

    fun supports(systemId: String): Boolean =
        datName(systemId) != null

    private fun datName(systemId: String): String? = DatCatalog.datName(systemId, Systems.byId(systemId)?.libretroDbName)

    suspend fun identify(game: Game): Identification {
        if (!supports(game.systemId)) return Identification.Unsupported
        ensureLoaded(game.systemId)
        val hashes = RomHasher.hash(context, game) ?: return Identification.NotFound
        val match = dao.findByCrc(game.systemId, hashes.crc32)
            ?: dao.findByMd5(game.systemId, hashes.md5)
            ?: return Identification.NotFound
        val versions = dao.versions(game.systemId, match.cleanTitle).filter { it.id != match.id }
        return Identification.Found(match, versions)
    }

    /**
     * Identifica de uma vez os [games] ainda não verificados, avisando o progresso (feitos, total).
     * Devolve os que bateram com o DAT; quem chama grava o resultado. Um arquivo ilegível só fica de fora.
     */
    suspend fun identifyAll(games: List<Game>, onProgress: (Int, Int) -> Unit): List<Pair<Game, DatEntry>> {
        val pending = games.filter { !it.verified && supports(it.systemId) }
        val found = mutableListOf<Pair<Game, DatEntry>>()
        pending.forEachIndexed { i, game ->
            onProgress(i, pending.size)
            val result = try {
                identify(game)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: java.io.IOException) {
                // Sem internet para baixar o DAT: nenhum outro jogo vai conseguir, então para aqui.
                if (dao.countHashed(game.systemId) == 0) throw t
                null
            }
            if (result is Identification.Found) found += game to result.match
        }
        onProgress(pending.size, pending.size)
        return found
    }

    /** Evita baixar e indexar o mesmo DAT duas vezes quando vários jogos são identificados juntos. */
    private val loadLock = Mutex()

    /**
     * Baixa e indexa o DAT do sistema na primeira vez que for necessário. Um índice sem nenhum
     * hash (gerado por versões antigas do parser) conta como ausente e é refeito.
     */
    private suspend fun ensureLoaded(systemId: String, force: Boolean = false) = loadLock.withLock {
        if (!force && dao.countHashed(systemId) > 0) return@withLock
        val name = datName(systemId) ?: return@withLock
        val text = Http.getString(DatCatalog.datUrl(name))
        indexInto(systemId, text)
    }

    /** Permite importar um DAT baixado pelo próprio usuário (No-Intro / Redump / Datomatic). */
    suspend fun importDat(systemId: String, uri: Uri) {
        val text = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        } ?: return
        // indexInto só substitui o índice atual se o arquivo tiver entradas válidas.
        loadLock.withLock { indexInto(systemId, text) }
    }

    private suspend fun indexInto(systemId: String, text: String) {
        val entries = withContext(Dispatchers.Default) {
            DatParser.parse(text).map { rom ->
                DatEntry(
                    systemId = systemId,
                    name = rom.gameName,
                    cleanTitle = RomNaming.cleanTitle(rom.gameName),
                    crc32 = rom.crc,
                    md5 = rom.md5,
                    size = rom.size,
                    region = RomNaming.region(rom.gameName),
                    revision = revision(rom.gameName),
                )
            }
        }
        if (entries.isNotEmpty()) dao.replace(systemId, entries)
    }

    private val revisionRegex = Regex("""\((Rev [\w.]+|v[\d.]+|Beta\s?\d*|Proto\w*|Demo|Sample)\)""", RegexOption.IGNORE_CASE)

    private fun revision(name: String): String? = revisionRegex.find(name)?.groupValues?.get(1)
}
