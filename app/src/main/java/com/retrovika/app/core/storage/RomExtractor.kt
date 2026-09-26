package com.retrovika.app.core.storage

import com.retrovika.app.R
import com.retrovika.app.core.net.LocalizedException
import com.retrovika.app.core.systems.GameSystem
import java.io.File

/**
 * Extrai a ROM de um .zip/.7z baixado ou importado. Jogos em disco com arquivo de índice
 * (.m3u/.cue/.gdi/.ccd) precisam de todas as faixas, então o conteúdo inteiro é extraído; nos
 * demais, só a ROM. Sem nenhuma entrada reconhecida, o arquivo compactado fica como está.
 *
 * Toda falha vira [LocalizedException] com o motivo (sem espaço, formato não suportado, memória…),
 * e o compactado é apagado: ele não serve para nada depois de falhar e ocuparia espaço.
 */
object RomExtractor {
    private val ARCHIVE_EXTS = setOf("zip", "7z")
    private val SHEET_PRIORITY = listOf("m3u", "cue", "gdi", "ccd")
    private const val SPACE_MARGIN = 64L * 1024 * 1024

    fun extract(archive: File, dir: File, system: GameSystem): File {
        // Com extensão de compactado mas conteúdo de outro tipo: fica como veio.
        if (Archives.formatOf(archive) == null) return archive
        try {
            val entries = Archives.entries(archive)
            val names = entries.map { it.name }
            val candidates = names.filter { it.substringAfterLast('.').lowercase().let { ext -> ext in system.extensions && ext !in ARCHIVE_EXTS } }
            val main = candidates.minByOrNull { SHEET_PRIORITY.indexOf(it.substringAfterLast('.').lowercase()).let { i -> if (i < 0) SHEET_PRIORITY.size else i } }
                ?: return archive
            val isSheet = main.substringAfterLast('.').lowercase() in SHEET_PRIORITY
            val wanted = if (isSheet) names.toSet() else setOf(main)
            // Jogos em disco somam centenas de MB: sem espaço, a extração falharia no meio.
            val needed = entries.filter { it.name in wanted }.sumOf { it.size.coerceAtLeast(0) }
            if (needed > 0 && dir.usableSpace < needed + SPACE_MARGIN) {
                throw LocalizedException(R.string.download_no_space, needed.formatBytes())
            }
            val extracted = Archives.extract(archive, dir, wanted)
            archive.delete()
            return extracted[main]
                ?: throw LocalizedException(R.string.download_extract_failed, main.substringAfterLast('/'))
        } catch (t: Throwable) {
            archive.delete()
            throw when (t) {
                is LocalizedException -> t
                is OutOfMemoryError -> LocalizedException(R.string.download_extract_memory, archive.name)
                else -> LocalizedException(R.string.download_extract_failed, t.message ?: t.javaClass.simpleName)
            }
        }
    }
}
