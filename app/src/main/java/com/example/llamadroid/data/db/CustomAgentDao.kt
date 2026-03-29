package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomAgentDao {
    
    @Query("SELECT * FROM custom_agents WHERE isEnabled = 1 ORDER BY name ASC")
    fun getEnabledAgents(): Flow<List<CustomAgentEntity>>
    
    @Query("SELECT * FROM custom_agents ORDER BY name ASC")
    fun getAllAgents(): Flow<List<CustomAgentEntity>>
    
    @Query("SELECT * FROM custom_agents WHERE name = :name")
    suspend fun getAgentByName(name: String): CustomAgentEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: CustomAgentEntity)
    
    @Update
    suspend fun updateAgent(agent: CustomAgentEntity)
    
    @Delete
    suspend fun deleteAgent(agent: CustomAgentEntity)
    
    @Query("DELETE FROM custom_agents WHERE name = :name")
    suspend fun deleteAgentByName(name: String)
    
    @Query("UPDATE custom_agents SET isEnabled = :enabled WHERE name = :name")
    suspend fun setAgentEnabled(name: String, enabled: Boolean)
    
    @Query("SELECT COUNT(*) FROM custom_agents")
    suspend fun getAgentCount(): Int
}
