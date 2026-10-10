package com.retrovika.app.core.textmem

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** Traduções já feitas, por texto original. */
interface TranslationStore {
    fun get(original: String): String?
    fun put(original: String, translated: String)
}

/**
 * Memória de traduções de um jogo, gravada em disco para não pedir de novo à rede as mesmas falas.
 *
 * Carrega o arquivo na primeira leitura e só grava em [flush] quando algo mudou. Quando passa de
 * [maxEntries], descarta a tradução usada há mais tempo. Arquivo ausente, vazio ou corrompido vira
 * memória vazia: nada aqui lança exceção.
 */
class TranslationMemory(private val file: File, private val maxEntries: Int = 5000) : TranslationStore {

    /** Ordem de acesso: a entrada menos usada fica primeiro. */
    private val entries = LinkedHashMap<String, String>(16, 0.75f, true)
    private var loaded = false
    private var dirty = false

    @Synchronized
    override fun get(original: String): String? {
        ensureLoaded()
        val found = entries[original] ?: return null
        // Ler também conta como uso: a ordem mudou, então precisa ser gravada.
        dirty = true
        return found
    }

    @Synchronized
    override fun put(original: String, translated: String) {
        if (original.isBlank() || translated.isBlank()) return
        ensureLoaded()
        entries[original] = translated
        dirty = true
        trim()
    }

    /** Grava no disco se algo mudou desde a última gravação. Falha mantém as mudanças para a próxima tentativa. */
    @Synchronized
    fun flush() {
        if (!dirty) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = file.resolveSibling(file.name + ".tmp")
            tmp.writeText(encode())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            dirty = false
        }
    }

    @get:Synchronized
    val size: Int
        get() {
            ensureLoaded()
            return entries.size
        }

    /** Lê o arquivo uma vez. Corrompido, de outra versão ou ilegível: começa vazio. */
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val read = runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return@runCatching emptyList<Pair<String, String>>()
            (root["entries"] as? JsonArray).orEmpty().mapNotNull { item ->
                val pair = item as? JsonArray ?: return@mapNotNull null
                if (pair.size < 2) return@mapNotNull null
                val original = pair[0].asString() ?: return@mapNotNull null
                val translated = pair[1].asString() ?: return@mapNotNull null
                if (original.isBlank() || translated.isBlank()) null else original to translated
            }
        }.getOrDefault(emptyList())
        // Na ordem do arquivo (mais antiga primeiro): o put mantém a ordem de uso.
        for ((original, translated) in read) entries[original] = translated
        trim()
    }

    /** Descarta as entradas mais antigas até caber em [maxEntries]. */
    private fun trim() {
        val it = entries.keys.iterator()
        while (entries.size > maxEntries && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private fun encode(): String = buildJsonObject {
        put("version", VERSION)
        putJsonArray("entries") {
            for ((original, translated) in entries) {
                addJsonArray {
                    add(JsonPrimitive(original))
                    add(JsonPrimitive(translated))
                }
            }
        }
    }.toString()

    private fun JsonElement.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private companion object {
        const val VERSION = 1
    }
}
