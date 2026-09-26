package com.retrovika.app.core.dat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DatDao {
    @Query("SELECT COUNT(*) FROM dat_entries WHERE systemId = :systemId")
    suspend fun count(systemId: String): Int

    @Query("SELECT COUNT(*) FROM dat_entries WHERE systemId = :systemId AND (crc32 IS NOT NULL OR md5 IS NOT NULL)")
    suspend fun countHashed(systemId: String): Int

    @Query("SELECT * FROM dat_entries WHERE systemId = :systemId AND crc32 IN (:crcs) LIMIT 1")
    suspend fun findByCrc(systemId: String, crcs: List<String>): DatEntry?

    @Query("SELECT * FROM dat_entries WHERE systemId = :systemId AND md5 IN (:md5s) LIMIT 1")
    suspend fun findByMd5(systemId: String, md5s: List<String>): DatEntry?

    /** Todas as versões conhecidas de um jogo (mesmo título limpo). */
    @Query("SELECT * FROM dat_entries WHERE systemId = :systemId AND cleanTitle = :cleanTitle ORDER BY region, revision")
    suspend fun versions(systemId: String, cleanTitle: String): List<DatEntry>

    @Insert
    suspend fun insertAll(entries: List<DatEntry>)

    @Query("DELETE FROM dat_entries WHERE systemId = :systemId")
    suspend fun clear(systemId: String)

    /** Troca o DAT inteiro de um sistema de forma atômica (nunca deixa um índice pela metade). */
    @Transaction
    suspend fun replace(systemId: String, entries: List<DatEntry>) {
        clear(systemId)
        entries.chunked(2000).forEach { insertAll(it) }
    }
}
