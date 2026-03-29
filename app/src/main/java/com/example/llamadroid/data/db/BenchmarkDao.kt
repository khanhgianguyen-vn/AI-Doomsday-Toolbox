package com.example.llamadroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BenchmarkDao {
    
    @Query("SELECT * FROM benchmark_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<BenchmarkResult>>
    
    @Query("SELECT * FROM benchmark_results WHERE modelPath = :modelPath ORDER BY threads ASC")
    fun getResultsForModel(modelPath: String): Flow<List<BenchmarkResult>>
    
    @Query("SELECT * FROM benchmark_results WHERE modelPath = :modelPath ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestResultForModel(modelPath: String): BenchmarkResult?
    
    @Query("SELECT DISTINCT modelPath, modelName FROM benchmark_results ORDER BY timestamp DESC")
    fun getTestedModels(): Flow<List<TestedModel>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: BenchmarkResult): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(results: List<BenchmarkResult>)
    
    @Query("DELETE FROM benchmark_results WHERE modelPath = :modelPath")
    suspend fun deleteResultsForModel(modelPath: String)
    
    @Query("DELETE FROM benchmark_results")
    suspend fun deleteAll()
}

data class TestedModel(
    val modelPath: String,
    val modelName: String
)
