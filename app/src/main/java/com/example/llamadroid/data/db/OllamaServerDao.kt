package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OllamaServerDao {
    @Query("SELECT * FROM ollama_servers ORDER BY lastConnected DESC")
    fun getAllServers(): Flow<List<OllamaServerEntity>>

    @Query("SELECT * FROM ollama_servers WHERE id = :id")
    suspend fun getServerById(id: Long): OllamaServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: OllamaServerEntity): Long

    @Update
    suspend fun updateServer(server: OllamaServerEntity)

    @Delete
    suspend fun deleteServer(server: OllamaServerEntity)

    @Query("UPDATE ollama_servers SET lastConnected = :timestamp WHERE id = :id")
    suspend fun updateLastConnected(id: Long, timestamp: Long)
}
