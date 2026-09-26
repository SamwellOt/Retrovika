package com.retrovika.app.core.dat

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Uma entrada de um DAT No-Intro: apenas metadados (nome canônico, região, revisão e
 * hashes) de uma versão conhecida de um jogo. Não contém nenhum dado do jogo em si.
 *
 * Os DATs são publicados livremente (No-Intro / libretro-database) e servem para
 * identificar os dumps que o próprio usuário importou e listar as demais versões existentes.
 */
@Entity(
    tableName = "dat_entries",
    indices = [
        Index(value = ["systemId", "crc32"]),
        Index(value = ["systemId", "md5"]),
        Index(value = ["systemId", "cleanTitle"]),
    ],
)
data class DatEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val systemId: String,
    /** Nome completo no DAT, ex.: "Chrono Trigger (USA)". */
    val name: String,
    /** Título sem tags, para agrupar versões do mesmo jogo. */
    val cleanTitle: String,
    /** CRC32 minúsculo com 8 dígitos, ou nulo quando o DAT não fornece. */
    val crc32: String?,
    val md5: String?,
    val size: Long,
    val region: String?,
    /** "Rev 1", "v1.1", "Beta"… quando presente no nome. */
    val revision: String?,
)

/** Sistemas que possuem DAT No-Intro com hashing de arquivo inteiro (cartucho/portátil). */
object DatCatalog {
    /**
     * IDs de [com.retrovika.app.core.systems.Systems] cobertos pela identificação por hash → nome do
     * DAT na libretro-database. Nulo = mesmo nome de `Systems.libretroDbName` (o caso comum).
     */
    private val dats: Map<String, String?> = mapOf(
        "nes" to null, "snes" to null, "n64" to null, "gb" to null, "gbc" to null, "gba" to null,
        "genesis" to null, "32x" to null, "sms" to null, "gg" to null, "sg1000" to null,
        "pce" to null, "atari2600" to null, "atari7800" to null, "a5200" to null, "jaguar" to null,
        "a800" to "Atari - 8-bit Family", "lynx" to null, "ngp" to null, "wswan" to null, "vb" to null,
        "coleco" to null, "pokemini" to null, "vectrex" to null, "intv" to null, "odyssey2" to null,
        "channelf" to null, "supervision" to null, "arduboy" to null, "msx" to null,
    )

    val supportedSystems: Set<String> get() = dats.keys

    /** Nome do arquivo DAT para o sistema, ou nulo se ele não tiver DAT. */
    fun datName(systemId: String, libretroDbName: String?): String? =
        if (systemId !in dats) null else dats[systemId] ?: libretroDbName

    /** URL do DAT No-Intro correspondente na libretro-database (apenas metadados). */
    fun datUrl(datName: String): String {
        val encoded = java.net.URLEncoder.encode(datName, "UTF-8").replace("+", "%20")
        return "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro/$encoded.dat"
    }
}
