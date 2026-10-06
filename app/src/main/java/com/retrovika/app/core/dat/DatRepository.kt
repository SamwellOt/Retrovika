package com.retrovika.app.core.dat

import android.content.Context
import android.net.Uri
import com.retrovika.app.core.library.Game
import com.retrovika.app.core.library.RomNaming
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.systems.Systems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
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
        return match(game, md5Fallback = dao.countMd5Only(game.systemId) > 0)
    }

    /**
     * Primeiro só o CRC32, numa passada barata; o MD5 (uma segunda leitura) só quando o CRC não achou nada e o DAT
     * tem entradas que só o MD5 identifica ([md5Fallback]).
     */
    private suspend fun match(game: Game, md5Fallback: Boolean): Identification {
        val hashes = RomHasher.hash(context, game, md5 = false) ?: return Identification.NotFound
        val match = dao.findByCrc(game.systemId, hashes.crc32)
            ?: (if (md5Fallback) RomHasher.hash(context, game, md5 = true)?.let { dao.findByMd5(game.systemId, it.md5) } else null)
            ?: return Identification.NotFound
        val versions = dao.versions(game.systemId, match.cleanTitle).filter { it.id != match.id }
        return Identification.Found(match, versions)
    }

    /**
     * Identifica de uma vez os [games] ainda não verificados, avisando o progresso (feitos, total).
     * Devolve os que bateram com o DAT; quem chama grava o resultado. Um arquivo ilegível só fica de fora.
     */
    suspend fun identifyAll(games: List<Game>, onProgress: (Int, Int) -> Unit): List<Pair<Game, DatEntry>> = coroutineScope {
        val pending = games.filter { !it.verified && supports(it.systemId) }
        val found = Collections.synchronizedList(mutableListOf<Pair<Game, DatEntry>>())
        val done = AtomicInteger(0)
        val total = pending.size
        onProgress(0, total)
        // Alguns arquivos ao mesmo tempo: o hash de um espera o disco enquanto o de outro usa a CPU.
        val gate = Semaphore(PARALLEL_HASHES)
        // Um sistema por vez: o DAT dele é baixado e indexado antes de qualquer hash. Um DAT que não rende nenhuma
        // entrada não é baixado de novo para cada jogo: os jogos daquele sistema ficam de fora.
        for ((systemId, list) in pending.groupBy { it.systemId }) {
            val ready = try {
                ensureLoaded(systemId)
                dao.countHashed(systemId) > 0
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: java.io.IOException) {
                // Sem internet para baixar o DAT: nenhum outro jogo vai conseguir, então para aqui.
                if (dao.countHashed(systemId) == 0) throw t
                true
            } catch (t: Exception) {
                runCatching { dao.countHashed(systemId) }.getOrDefault(0) > 0
            }
            if (!ready) {
                onProgress(done.addAndGet(list.size), total)
                continue
            }
            val md5Fallback = runCatching { dao.countMd5Only(systemId) > 0 }.getOrDefault(true)
            list.map { game ->
                async {
                    gate.withPermit {
                        // Arquivo ilegível ou entrada estranha: só este jogo fica de fora, o lote segue.
                        val result = try {
                            match(game, md5Fallback)
                        } catch (c: kotlinx.coroutines.CancellationException) {
                            throw c
                        } catch (t: Exception) {
                            null
                        }
                        if (result is Identification.Found) found += game to result.match
                    }
                    onProgress(done.incrementAndGet(), total)
                }
            }.awaitAll()
        }
        onProgress(total, total)
        found.toList()
    }

    private companion object {
        /** Arquivos lidos ao mesmo tempo na verificação em lote. */
        const val PARALLEL_HASHES = 3
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
