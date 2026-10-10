package com.retrovika.app.core.storage

import android.content.Context
import java.io.File

/**
 * Estrutura de pastas do Retrovika:
 *
 * ```
 * <externo>/Retrovika/
 *   roms/<sistema>/        jogos importados ou baixados, organizados por console
 *   saves/<sistema>/       memória interna do cartucho (.srm)
 *   states/<sistema>/<id>/ save states por slot + miniatura
 *   cheats/<sistema>/      trapaças baixadas e as escolhas de cada jogo (<id>.json)
 *   translations/<sistema>/ traduções dos diálogos já feitas por jogo (<id>.<idioma>.json)
 *   system/                BIOS e assets exigidos pelos núcleos
 * <interno>/cores/         núcleos libretro (.so) — precisam ficar no armazenamento interno para dlopen
 * ```
 */
class StoragePaths(private val context: Context) {

    /** Pasta do app no armazenamento externo, lida uma vez: nula quando ele não estava montado. */
    private val external: File? by lazy { context.getExternalFilesDir(null) }

    val root: File by lazy { (external ?: context.filesDir).resolve("Retrovika").ensureDir() }

    /**
     * Falso quando o armazenamento externo não estava disponível e [root] caiu no interno: os jogos de
     * `roms/` cadastrados antes continuam lá fora e não podem ser tomados por apagados.
     */
    val onExternal: Boolean get() = external != null && root.canRead()

    val roms: File get() = root.resolve("roms").ensureDir()
    val saves: File get() = root.resolve("saves").ensureDir()
    val states: File get() = root.resolve("states").ensureDir()
    val cheats: File get() = root.resolve("cheats").ensureDir()
    val system: File get() = root.resolve("system").ensureDir()
    val translations: File get() = root.resolve("translations").ensureDir()
    val cores: File get() = context.filesDir.resolve("cores").ensureDir()
    val downloadsTmp: File get() = context.cacheDir.resolve("downloads").ensureDir()

    fun romsFor(systemId: String): File = roms.resolve(systemId).ensureDir()
    fun savesFor(systemId: String): File = saves.resolve(systemId).ensureDir()
    fun statesFor(systemId: String, gameId: Long): File = statesDir(systemId, gameId).ensureDir()

    /** A pasta dos estados de um jogo, sem criá-la (para apagar). */
    fun statesDir(systemId: String, gameId: Long): File = states.resolve(systemId).resolve(gameId.toString())

    /** As trapaças escolhidas para um jogo. */
    fun cheatsFor(systemId: String, gameId: Long): File = cheats.resolve(systemId).resolve("$gameId.json")

    /** A memória de traduções de um jogo num idioma (sem criar o arquivo). */
    fun translationsFor(systemId: String, gameId: Long, language: String): File =
        translations.resolve(systemId).resolve("$gameId.$language.json")

    private fun File.ensureDir(): File = apply { if (!exists()) mkdirs() }
}

fun File.sizeRecursive(): Long = if (isDirectory) listFiles()?.sumOf { it.sizeRecursive() } ?: 0L else length()

fun Long.formatBytes(): String {
    if (this < 1024) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = this / 1024.0
    var i = 0
    while (value >= 1024 && i < units.lastIndex) { value /= 1024; i++ }
    return if (value >= 100) "%.0f %s".format(value, units[i]) else "%.1f %s".format(value, units[i])
}
