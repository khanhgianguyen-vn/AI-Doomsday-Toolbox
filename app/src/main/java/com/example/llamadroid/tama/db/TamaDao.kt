package com.example.llamadroid.tama.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Room entity for persisting TamaPet data.
 */
@Entity(tableName = "tama_pets")
data class TamaPetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val species: String,
    val birthTimestamp: Long,
    val lastDecayTime: Long = System.currentTimeMillis(),
    val stage: String,
    
    // Stats
    val hunger: Float,
    val happiness: Float,
    val health: Float,
    val energy: Float,
    val hygiene: Float,
    
    val mood: String,
    val personality: String,
    
    // Genetics
    val eyeStyle: Int,
    val earStyle: Int,
    val mouthStyle: Int,
    val headShape: Int,
    val bodyStyle: Int,
    val armStyle: Int,
    val legStyle: Int,
    val colorTint: Int,
    
    val ownerBondLevel: Float,
    val educationLevel: Float,
    val currentLocationId: String,
    val money: Long,
    val inventoryJson: String,
    // Activity system
    val currentActivity: String = "NONE",  // ActivityType enum name
    val activityStartTime: Long? = null,
    val isSleeping: Boolean = false,
    val sleepStartTime: Long? = null,
    val lastSleepWarningTime: Long? = null,
    val miscareCount: Int = 0,
    val isMad: Boolean = false,
    val relationshipsJson: String,
    val discoveredLocationIdsJson: String = "[\"home\"]"
)

/**
 * Room entity for persisting chat messages.
 */
@Entity(tableName = "tama_chat_messages")
data class TamaChatMessageEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val thinking: String? = null
)

/**
 * Room entity for persisting events (for LLM context).
 */
@Entity(tableName = "tama_events")
data class TamaEventEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val petId: String,
    val eventType: String,
    val details: String,
    val locationId: String?,
    val npcId: String?,
    val statsChangeJson: String?
)

/**
 * Room entity for locations.
 */
@Entity(tableName = "tama_locations")
data class TamaLocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val description: String,
    val cityId: String,
    val x: Int,
    val y: Int,
    val isDiscovered: Boolean,
    val npcIdsJson: String,
    val shopInventoryJson: String?,
    val jobsJson: String?
)


/**
 * Room entity for NPCs.
 */
@Entity(tableName = "tama_npcs")
data class TamaNpcEntity(
    @PrimaryKey val id: String,
    val name: String,
    val species: String,
    val personality: String,
    val geneticsJson: String,
    val homeLocationId: String,
    val currentLocationId: String,
    val job: String?,
    val age: Int,
    val marriedToPetId: String?,
    val childrenIdsJson: String,
    val likesJson: String,
    val dislikesJson: String
)

/**
 * Room entity for memory summaries.
 */
@Entity(tableName = "tama_summaries")
data class TamaSummaryEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val date: String,  // YYYY-MM-DD
    val summary: String,
    val createdAt: Long,
    val lastEventTimestamp: Long = 0L, // To know from which event to start next summary
    val lastChatMessageTimestamp: Long = 0L // To know from which message to start next summary
)

/**
 * Room entity for adventure sessions.
 */
@Entity(tableName = "adventure_sessions")
data class AdventureSessionEntity(
    @PrimaryKey val id: String,
    val petId: String,
    val dungeonType: String,
    val schematicJson: String,
    val currentStage: Int,
    val isCompleted: Boolean,
    val cumulativeSummary: String,
    val createdAt: Long,
    val lastPlayedAt: Long
)

/**
 * Room entity for adventure stages.
 */
@Entity(tableName = "adventure_stages")
data class AdventureStageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val stageNumber: Int,
    val storyContent: String,
    val userResponse: String?,
    val stageSummary: String?,
    val timestamp: Long
)

/**
 * Room entity for dungeon unlock progress.
 */
@Entity(tableName = "dungeon_progress")
data class DungeonProgressEntity(
    @PrimaryKey val petId: String,
    val completedDungeonCount: Int = 0,
    val lastCompletedDungeonType: String? = null
)

/**
 * DAO for Tama database operations.
 */
@Dao
interface TamaDao {
    // Pet operations
    @Query("SELECT * FROM tama_pets WHERE id = :id")
    suspend fun getPet(id: String): TamaPetEntity?
    
    @Query("SELECT * FROM tama_pets LIMIT 1")
    suspend fun getActivePet(): TamaPetEntity?
    
    @Query("SELECT * FROM tama_pets LIMIT 1")
    fun observeActivePet(): Flow<TamaPetEntity?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePet(pet: TamaPetEntity)
    
    @Delete
    suspend fun deletePet(pet: TamaPetEntity)
    
    @Query("UPDATE tama_pets SET hunger = :hunger, happiness = :happiness, health = :health, energy = :energy, hygiene = :hygiene WHERE id = :id")
    suspend fun updateStats(id: String, hunger: Float, happiness: Float, health: Float, energy: Float, hygiene: Float)
    
    @Query("UPDATE tama_pets SET money = :money WHERE id = :id")
    suspend fun updateMoney(id: String, money: Long)
    
    @Query("UPDATE tama_pets SET currentLocationId = :locationId WHERE id = :id")
    suspend fun updateLocation(id: String, locationId: String)
    
    @Query("UPDATE tama_pets SET currentActivity = :activity, activityStartTime = :startTime WHERE id = :id")
    suspend fun updateActivityStatus(id: String, activity: String, startTime: Long?)
    
    // Event operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveEvent(event: TamaEventEntity)
    
    @Query("SELECT * FROM tama_events WHERE petId = :petId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentEvents(petId: String, limit: Int = 50): List<TamaEventEntity>
    
    @Query("SELECT * FROM tama_events WHERE petId = :petId AND timestamp >= :since ORDER BY timestamp ASC")
    suspend fun getEventsSince(petId: String, since: Long): List<TamaEventEntity>
    
    // Location operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLocation(location: TamaLocationEntity)
    
    @Query("SELECT * FROM tama_locations WHERE id = :id")
    suspend fun getLocation(id: String): TamaLocationEntity?
    
    @Query("SELECT * FROM tama_locations WHERE cityId = :cityId")
    suspend fun getLocationsInCity(cityId: String): List<TamaLocationEntity>
    
    
    // NPC operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveNpc(npc: TamaNpcEntity)
    
    @Query("SELECT * FROM tama_npcs WHERE id = :id")
    suspend fun getNpc(id: String): TamaNpcEntity?
    
    @Query("SELECT * FROM tama_npcs WHERE currentLocationId = :locationId")
    suspend fun getNpcsAtLocation(locationId: String): List<TamaNpcEntity>
    
    // Summary operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSummary(summary: TamaSummaryEntity)
    
    @Query("SELECT * FROM tama_summaries WHERE petId = :petId ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentSummaries(petId: String, limit: Int = 7): List<TamaSummaryEntity>
    
    @Query("SELECT * FROM tama_summaries WHERE petId = :petId ORDER BY createdAt DESC LIMIT 1")
    fun getSummaryForPet(petId: String): Flow<List<TamaSummaryEntity>>

    // Chat operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveChatMessage(message: TamaChatMessageEntity)
    
    @Query("SELECT * FROM tama_chat_messages WHERE petId = :petId ORDER BY timestamp ASC")
    suspend fun getChatHistory(petId: String): List<TamaChatMessageEntity>
    
    @Query("DELETE FROM tama_chat_messages WHERE petId = :petId")
    suspend fun clearChatHistory(petId: String)
    
    @Query("DELETE FROM tama_chat_messages WHERE id = :id")
    suspend fun deleteChatMessage(id: String)
    
    // Adventure operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureSession(session: AdventureSessionEntity)
    
    @Query("SELECT * FROM adventure_sessions WHERE id = :id")
    suspend fun getAdventureSession(id: String): AdventureSessionEntity?
    
    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId AND isCompleted = 0 ORDER BY lastPlayedAt DESC LIMIT 1")
    suspend fun getActiveAdventureSession(petId: String): AdventureSessionEntity?
    
    @Query("SELECT * FROM adventure_sessions WHERE petId = :petId ORDER BY lastPlayedAt DESC")
    suspend fun getAdventureHistory(petId: String): List<AdventureSessionEntity>
    
    @Query("DELETE FROM adventure_sessions WHERE id = :id")
    suspend fun deleteAdventureSession(id: String)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAdventureStage(stage: AdventureStageEntity)
    
    @Query("SELECT * FROM adventure_stages WHERE sessionId = :sessionId ORDER BY stageNumber ASC")
    suspend fun getAdventureStages(sessionId: String): List<AdventureStageEntity>
    
    @Query("DELETE FROM adventure_stages WHERE sessionId = :sessionId")
    suspend fun deleteAdventureStages(sessionId: String)
    
    // Dungeon progress operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDungeonProgress(progress: DungeonProgressEntity)
    
    @Query("SELECT * FROM dungeon_progress WHERE petId = :petId")
    suspend fun getDungeonProgress(petId: String): DungeonProgressEntity?
    
    @Query("UPDATE dungeon_progress SET completedDungeonCount = :count, lastCompletedDungeonType = :dungeonType WHERE petId = :petId")
    suspend fun updateDungeonProgress(petId: String, count: Int, dungeonType: String)
}
