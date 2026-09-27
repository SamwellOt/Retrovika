package com.retrovika.app.core.bios

import com.retrovika.app.core.systems.BiosFormat
import java.io.File
import java.io.RandomAccessFile

/**
 * Confere o conteúdo de BIOS que não têm um MD5 único (há dezenas de versões da do PS2), com as
 * mesmas regras do núcleo: um arquivo com o nome certo mas outro conteúdo aparecia como presente
 * em Ajustes e o LRPS2 dizia que faltava BIOS.
 */
object BiosFormats {
    private const val PS2_MIN_SIZE = 4L * 1024 * 1024
    private const val PS2_MAX_SIZE = 8L * 1024 * 1024
    private const val ENTRY = 16

    fun isValid(format: BiosFormat, file: File): Boolean = when (format) {
        BiosFormat.PS2 -> isPs2Bios(file)
    }

    /**
     * BiosTools.cpp do LRPS2 (IsBIOS/LoadBiosVersion) e o filtro de tamanho da varredura: de 4 a 8 MB,
     * com o diretório da ROM (entradas de 16 bytes a partir da "RESET") contendo uma "ROMVER".
     */
    fun isPs2Bios(file: File): Boolean {
        val size = file.length()
        if (size < PS2_MIN_SIZE || size > PS2_MAX_SIZE) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val entry = ByteArray(ENTRY)
                // O núcleo lê entradas seguidas desde o início até achar a RESET.
                var found = false
                while (raf.filePointer + ENTRY <= size) {
                    raf.readFully(entry)
                    if (name(entry) == "RESET") { found = true; break }
                }
                if (!found) return@use false
                while (entry[0].toInt() != 0 && entry.take(10).any { it.toInt() == 0 }) {
                    if (name(entry) == "ROMVER") return@use true
                    if (raf.filePointer + ENTRY > size) break
                    raf.readFully(entry)
                }
                false
            }
        }.getOrDefault(false)
    }

    /** Nome da entrada: até 10 bytes, terminado em zero (sem zero, a entrada é inválida). */
    private fun name(entry: ByteArray): String {
        val end = (0 until 10).firstOrNull { entry[it].toInt() == 0 } ?: 10
        return String(entry, 0, end, Charsets.US_ASCII)
    }
}
