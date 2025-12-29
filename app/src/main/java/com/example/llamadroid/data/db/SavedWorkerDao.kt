package com.example.llamadroid.data.db

import androidx.room.*
import com.example.llamadroid.data.model.SavedWorker
import kotlinx.coroutines.flow.Flow

/**
 * DAO for saved worker configurations.
 */
@Dao
interface SavedWorkerDao {
    @Query("SELECT * FROM saved_workers ORDER BY deviceName ASC")
    fun getAllWorkers(): Flow<List<SavedWorker>>
    
    @Query("SELECT * FROM saved_workers WHERE isEnabled = 1 ORDER BY deviceName ASC")
    fun getEnabledWorkers(): Flow<List<SavedWorker>>
    
    @Query("SELECT * FROM saved_workers WHERE id = :id")
    suspend fun getWorkerById(id: Long): SavedWorker?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorker(worker: SavedWorker): Long
    
    @Update
    suspend fun updateWorker(worker: SavedWorker)
    
    @Delete
    suspend fun deleteWorker(worker: SavedWorker)
    
    @Query("DELETE FROM saved_workers WHERE id = :id")
    suspend fun deleteWorkerById(id: Long)
    
    @Query("UPDATE saved_workers SET isEnabled = :enabled WHERE id = :id")
    suspend fun setWorkerEnabled(id: Long, enabled: Boolean)
    
    @Query("DELETE FROM saved_workers")
    suspend fun deleteAll()
}
