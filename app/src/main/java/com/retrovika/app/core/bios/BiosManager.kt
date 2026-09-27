package com.retrovika.app.core.bios

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.retrovika.app.core.storage.StoragePaths
import com.retrovika.app.core.systems.BiosFile
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** INVALID: o conteúdo não é do formato esperado (BiosFile.format), mesmo com o nome certo. */
enum class BiosStatus { OK, WRONG_HASH, INVALID, MISSING }

data class BiosCheck(val bios: BiosFile, val status: BiosStatus)

/**
 * Verifica e importa arquivos de BIOS. O usuário seleciona os arquivos que já possui
 * e o Retrovika identifica cada um pelo nome ou pelo hash MD5, copiando-os para system/.
 */
class BiosManager(private val paths: StoragePaths, private val resolver: ContentResolver) {

    private val known: List<BiosFile> = Systems.all.flatMap { it.bios }.distinctBy { it.fileName }

    fun check(system: GameSystem): List<BiosCheck> = system.bios.map { bios ->
        val file = File(paths.system, bios.fileName)
        val status = when {
            !file.exists() -> BiosStatus.MISSING
            bios.md5 != null && !md5(file).equals(bios.md5, ignoreCase = true) -> BiosStatus.WRONG_HASH
            bios.format != null && !BiosFormats.isValid(bios.format, file) -> BiosStatus.INVALID
            else -> BiosStatus.OK
        }
        BiosCheck(bios, status)
    }

    /**
     * BIOS obrigatórias ausentes; cada item traz as alternativas aceitas (uma só, ou o grupo inteiro).
     * Só verifica a existência (sem MD5): roda na abertura do jogo, na thread principal.
     */
    fun missingRequired(system: GameSystem): List<List<BiosFile>> =
        unsatisfied(system.bios) { File(paths.system, it.fileName).exists() }

    /**
     * BIOS opcionais ausentes. Opcionais no sistema, mas às vezes obrigatórias para um dos núcleos
     * (o LRPS2 exige a do PS2, o Play! não): servem de pista quando o núcleo não abre o jogo.
     */
    fun missingOptional(system: GameSystem): List<BiosFile> =
        system.bios.filter { !it.required && !usable(it) }

    /** Presente e, quando há formato conhecido, com o conteúdo certo (só lê o diretório da ROM). */
    private fun usable(bios: BiosFile): Boolean {
        val file = File(paths.system, bios.fileName)
        return file.exists() && (bios.format == null || BiosFormats.isValid(bios.format, file))
    }

    /** [check] calcula MD5 de cada arquivo; use esta versão a partir da UI. */
    suspend fun checkAsync(system: GameSystem): List<BiosCheck> = withContext(Dispatchers.IO) { check(system) }

    /** Importa arquivos escolhidos pelo usuário. Retorna os nomes reconhecidos. */
    suspend fun import(uris: List<Uri>): List<String> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri ->
            // Cada arquivo à parte: um que falhe (provedor offline, sem espaço…) não perde os outros.
            val tmp = File(paths.downloadsTmp, "bios-import")
            try {
                val name = displayName(uri) ?: return@mapNotNull null
                tmp.parentFile?.mkdirs()
                resolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: return@mapNotNull null
                val hash = md5(tmp)
                // Pelo MD5, pelo nome ou, para BIOS com formato conhecido, pelo conteúdo: uma BIOS de PS2 com
                // outro nome (SCPH-70012.bin) vale tanto quanto a scph39001.bin. Nome certo com conteúdo
                // errado é recusado, senão apareceria como presente sem funcionar.
                val match = known.firstOrNull { it.md5 != null && it.md5.equals(hash, true) }
                    ?: known.firstOrNull { it.fileName.substringAfterLast('/').equals(name, true) && (it.format == null || BiosFormats.isValid(it.format, tmp)) }
                    ?: known.firstOrNull { it.format != null && BiosFormats.isValid(it.format, tmp) }
                    ?: return@mapNotNull null
                val dest = File(paths.system, match.fileName)
                dest.parentFile?.mkdirs()
                tmp.copyTo(dest, overwrite = true)
                match.fileName
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } finally {
                tmp.delete()
            }
        }
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    companion object {
        /** Exigências não atendidas: BIOS obrigatórias ausentes e grupos sem nenhuma alternativa presente. */
        fun unsatisfied(bios: List<BiosFile>, present: (BiosFile) -> Boolean): List<List<BiosFile>> {
            val required = bios.filter { it.required }
            val singles = required.filter { it.group == null && !present(it) }.map { listOf(it) }
            val groups = required.filter { it.group != null }.groupBy { it.group }.values.filter { g -> g.none(present) }
            return singles + groups
        }

        fun md5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().buffered().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
