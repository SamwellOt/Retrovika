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
 *   system/                BIOS e assets exigidos pelos núcleos
 * <interno>/cores/         núcleos libretro (.so) — precisam ficar no armazenamento interno para dlopen
 * ```
 */
class StoragePaths(private val context: Context) {

    val root: File by lazy { (context.getExternalFilesDir(null) ?: context.filesDir).resolve("Retrovika").ensureDir() }

    val roms: File get() = root.resolve("roms").ensureDir()
    val saves: File get() = root.resolve("saves").ensureDir()
    val states: File get() = root.resolve("states").ensureDir()
    val system: File get() = root.resolve("system").ensureDir()
    val cores: File get() = context.filesDir.resolve("cores").ensureDir()
    val downloadsTmp: File get() = context.cacheDir.resolve("downloads").ensureDir()

    fun romsFor(systemId: String): File = roms.resolve(systemId).ensureDir()
    fun savesFor(systemId: String): File = saves.resolve(systemId).ensureDir()
    fun statesFor(systemId: String, gameId: Long): File = states.resolve(systemId).resolve(gameId.toString()).ensureDir()

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
