package com.retrovika.app.core.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class GameSource { LOCAL, IMPORTED, DOWNLOADED }

@Entity(
    tableName = "games",
    // addedAt e favorite: "Adicionados" e "Favoritos" do início são observados o tempo todo e refeitos a cada
    // escrita na tabela (varredura, sessão de jogo); sem índice, cada vez era a tabela inteira mais uma ordenação.
    indices = [Index("systemId"), Index(value = ["uri"], unique = true), Index("lastPlayed"), Index("addedAt"), Index("favorite")],
)
data class Game(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** Nome original do arquivo sem extensão, usado para achar capas no padrão No-Intro/Redump. */
    val rawName: String,
    val fileName: String,
    /** Caminho absoluto (arquivos internos) ou content:// (pastas vinculadas via SAF). */
    val uri: String,
    val systemId: String,
    val size: Long,
    val region: String? = null,
    val coverUrl: String? = null,
    val favorite: Boolean = false,
    val lastPlayed: Long? = null,
    val playTimeSeconds: Long = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val coreOverride: String? = null,
    val source: GameSource = GameSource.LOCAL,
    val developer: String? = null,
    val description: String? = null,
    /** Nome canônico No-Intro quando o arquivo foi identificado por hash. */
    val datName: String? = null,
    /** Verdadeiro quando o hash bateu com uma versão conhecida em um DAT. */
    @ColumnInfo(defaultValue = "0") val verified: Boolean = false,
) {
    val isContentUri: Boolean get() = uri.startsWith("content://")
}

data class SystemCount(val systemId: String, val count: Int)
