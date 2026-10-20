package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daigan.record.data.local.entity.ServerOptionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerOptionDao {

    @Query("SELECT * FROM game_server_options ORDER BY gameName ASC, sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<ServerOptionEntity>>

    @Query("SELECT * FROM game_server_options WHERE gameName = :gameName ORDER BY sortOrder ASC, id ASC")
    fun observeForGame(gameName: String): Flow<List<ServerOptionEntity>>

    @Query("SELECT * FROM game_server_options WHERE gameName = :gameName ORDER BY sortOrder ASC, id ASC")
    suspend fun findForGame(gameName: String): List<ServerOptionEntity>

    @Query("SELECT DISTINCT gameName FROM game_server_options ORDER BY gameName ASC")
    fun observeGameNames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(options: List<ServerOptionEntity>)

    @Query("DELETE FROM game_server_options WHERE gameName = :gameName")
    suspend fun deleteForGame(gameName: String)

    @Query("DELETE FROM game_server_options WHERE gameName = :gameName AND name = :name")
    suspend fun deleteOption(gameName: String, name: String)

    @Query("UPDATE game_server_options SET name = :newName WHERE gameName = :gameName AND name = :oldName")
    suspend fun renameOption(gameName: String, oldName: String, newName: String)
}
