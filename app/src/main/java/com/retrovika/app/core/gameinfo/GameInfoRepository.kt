package com.retrovika.app.core.gameinfo

import com.retrovika.app.core.net.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import java.io.File
import java.security.MessageDigest

/**
 * Busca e guarda as informações externas de um jogo (Backloggd, Wikipedia e HowLongToBeat): voltar para
 * uma página já aberta mostra tudo na hora, sem repetir os pedidos. Com [dir], o cache também fica no
 * disco por alguns dias: abrir de novo o app e o mesmo jogo não espera a rede (notas, fichas e tempos de
 * jogo mudam devagar).
 */
class GameInfoRepository(
    private val backloggd: BackloggdClient,
    private val wiki: WikiClient = WikiClient(),
    private val hltb: HowLongToBeatClient = HowLongToBeatClient(),
    private val dir: File? = null,
) {
    @Serializable
    private class Cached<T>(val value: T, val at: Long)

    private class Store<T>(val name: String, val serializer: KSerializer<T>) {
        val memory = object : LinkedHashMap<String, Cached<T>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached<T>>) = size > 40
        }
    }

    // "backloggd2": descarta o cache em disco da 0.5.5 ou anterior, que guardava a nota errada do JSON-LD por 3 dias.
    private val backloggdStore = Store("backloggd2", BackloggdInfo.serializer().nullable)
    private val wikiStore = Store("wiki", WikiInfo.serializer().nullable)
    private val hltbStore = Store("hltb", HltbInfo.serializer().nullable)

    /**
     * O jogo no Backloggd, ou nulo quando não há um jogo de mesmo nome no console. O título original
     * vem primeiro (há jogos que só existem com o nome japonês, no site de ROM e no Backloggd); só
     * quando ele não acha nada, tenta o nome em inglês e o slug do IGDB pelo Wikidata/Wikipedia.
     */
    suspend fun backloggd(title: String, systemId: String): BackloggdInfo? =
        cached(backloggdStore, "$systemId|${GameTitles.key(title)}", keep = { it?.reviewsFailed != true }) {
            backloggd.find(title, systemId) ?: otherTitle(title)?.let { backloggd.find(it.title, systemId, it.igdbSlug) }
        }

    /**
     * O nome em inglês pelo Wikidata/Wikipedia. Falha deles vale como "sem outro nome", mas o cancelamento (a
     * página fechou) segue adiante: senão viraria um "não encontrado" guardado no cache.
     */
    private suspend fun otherTitle(title: String): WikiClient.OtherTitle? = try {
        wiki.otherTitle(title)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * O jogo na Wikipedia/Wikidata; [igdbSlug] (do Backloggd) acha o item exato. [onHltbId] recebe o ID do
     * HowLongToBeat assim que o item chega (ou na hora, do cache), para os tempos não esperarem o resto.
     */
    suspend fun wiki(title: String, lang: String, igdbSlug: String?, onHltbId: (String?) -> Unit = {}): WikiInfo? {
        val key = "$lang|${igdbSlug ?: GameTitles.key(title)}"
        return cached(wikiStore, key, onCached = { onHltbId(it?.hltbId) }) { wiki.find(title, lang, igdbSlug, onHltbId) }
    }

    /** Prepara o que a próxima página de jogo vai precisar (o WebView do Backloggd, quando ele pede verificação). */
    suspend fun warmUp() = backloggd.warmUp()

    /** Tempos de jogo do HowLongToBeat pelo ID que o Wikidata registra ([WikiInfo.hltbId]). */
    suspend fun howLongToBeat(id: String): HltbInfo? = cached(hltbStore, id) { hltb.game(id) }

    /**
     * [keep] decide se o resultado vai para o cache (um resultado incompleto é pedido de novo na próxima vez).
     * "Não encontrado" (null) vale por menos tempo que um resultado: o jogo pode ser cadastrado depois.
     */
    private suspend fun <T> cached(
        store: Store<T>,
        key: String,
        keep: (T) -> Boolean = { true },
        onCached: (T) -> Unit = {},
        load: suspend () -> T,
    ): T {
        val now = System.currentTimeMillis()
        fun fresh(c: Cached<T>) = now - c.at < if (c.value == null) MISSING_TTL_MS else TTL_MS
        synchronized(store.memory) { store.memory[key] }?.takeIf(::fresh)?.let { onCached(it.value); return it.value }
        readDisk(store, key)?.takeIf(::fresh)?.let { c ->
            synchronized(store.memory) { store.memory[key] = c }
            onCached(c.value)
            return c.value
        }
        val value = load()
        // Cancelado no meio (a tela fechou), os clientes devolvem um resultado incompleto (sem resenhas, sem
        // resumo): esse não pode ficar no cache.
        currentCoroutineContext().ensureActive()
        if (keep(value)) {
            val entry = Cached(value, now)
            synchronized(store.memory) { store.memory[key] = entry }
            writeDisk(store, key, entry)
        }
        return value
    }

    private fun file(store: Store<*>, key: String): File? = dir?.let { d ->
        val hash = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        File(d, "${store.name}/$hash.json")
    }

    private suspend fun <T> readDisk(store: Store<T>, key: String): Cached<T>? {
        val f = file(store, key) ?: return null
        return withContext(Dispatchers.IO) {
            // Arquivo de uma versão antiga do formato (ou corrompido): vale como ausente.
            runCatching { Http.json.decodeFromString(Cached.serializer(store.serializer), f.readText()) }.getOrNull()
        }
    }

    private suspend fun <T> writeDisk(store: Store<T>, key: String, entry: Cached<T>) {
        val f = file(store, key) ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                f.parentFile?.mkdirs()
                val tmp = File(f.path + ".tmp")
                tmp.writeText(Http.json.encodeToString(Cached.serializer(store.serializer), entry))
                tmp.renameTo(f)
            }
        }
    }

    private companion object {
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L
        const val MISSING_TTL_MS = 12 * 60 * 60 * 1000L
    }
}
