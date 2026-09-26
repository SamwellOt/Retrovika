package com.retrovika.app.core.library

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {
    @Query("SELECT * FROM games ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE systemId = :systemId ORDER BY title COLLATE NOCASE")
    fun observeBySystem(systemId: String): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE lastPlayed IS NOT NULL ORDER BY lastPlayed DESC LIMIT :limit")
    fun observeRecent(limit: Int = 12): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE favorite = 1 ORDER BY title COLLATE NOCASE")
    fun observeFavorites(): Flow<List<Game>>

    @Query("SELECT * FROM games ORDER BY addedAt DESC LIMIT :limit")
    fun observeNewest(limit: Int = 12): Flow<List<Game>>

    @Query("SELECT * FROM games WHERE title LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE LIMIT 100")
    fun search(query: String): Flow<List<Game>>

    @Query("SELECT systemId, COUNT(*) AS count FROM games GROUP BY systemId")
    fun observeCounts(): Flow<List<SystemCount>>

    @Query("SELECT * FROM games WHERE id = :id")
    fun observe(id: Long): Flow<Game?>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun get(id: Long): Game?

    @Query("SELECT * FROM games WHERE uri = :uri")
    suspend fun getByUri(uri: String): Game?

    @Query("SELECT uri FROM games")
    suspend fun allUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(games: List<Game>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(game: Game): Long

    @Update
    suspend fun update(game: Game)

    @Delete
    suspend fun delete(game: Game)

    @Query("DELETE FROM games WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("UPDATE games SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("UPDATE games SET lastPlayed = :time, playTimeSeconds = playTimeSeconds + :addSeconds WHERE id = :id")
    suspend fun recordSession(id: Long, time: Long, addSeconds: Long)

    @Query("UPDATE games SET coreOverride = :coreId WHERE id = :id")
    suspend fun setCoreOverride(id: Long, coreId: String?)

    @Query("UPDATE games SET datName = :name, region = COALESCE(:region, region), verified = 1 WHERE id = :id")
    suspend fun setIdentified(id: Long, name: String, region: String?)
}
