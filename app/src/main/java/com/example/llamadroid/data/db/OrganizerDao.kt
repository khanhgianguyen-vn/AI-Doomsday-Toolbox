package com.example.llamadroid.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OrganizerDao {
    @Query("SELECT * FROM organizer_events ORDER BY startAtMillis ASC, id ASC")
    fun getAllEvents(): Flow<List<OrganizerEventEntity>>

    @Query("SELECT * FROM organizer_events ORDER BY startAtMillis ASC, id ASC")
    suspend fun getAllEventsOnce(): List<OrganizerEventEntity>

    @Query(
        "SELECT * FROM organizer_events " +
            "WHERE startAtMillis <= :rangeEndMillis AND COALESCE(endAtMillis, startAtMillis) >= :rangeStartMillis " +
            "ORDER BY startAtMillis ASC, id ASC"
    )
    fun getEventsInRange(rangeStartMillis: Long, rangeEndMillis: Long): Flow<List<OrganizerEventEntity>>

    @Query(
        "SELECT * FROM organizer_events " +
            "WHERE startAtMillis <= :rangeEndMillis AND COALESCE(endAtMillis, startAtMillis) >= :rangeStartMillis " +
            "ORDER BY startAtMillis ASC, id ASC"
    )
    suspend fun getEventsInRangeOnce(rangeStartMillis: Long, rangeEndMillis: Long): List<OrganizerEventEntity>

    @Query("SELECT * FROM organizer_events WHERE id = :id")
    suspend fun getEventById(id: Long): OrganizerEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: OrganizerEventEntity): Long

    @Update
    suspend fun updateEvent(event: OrganizerEventEntity)

    @Delete
    suspend fun deleteEvent(event: OrganizerEventEntity)

    @Query("DELETE FROM organizer_events WHERE id = :id")
    suspend fun deleteEventById(id: Long)

    @Query("SELECT * FROM organizer_alarms ORDER BY triggerAtMillis ASC, id ASC")
    fun getAllAlarms(): Flow<List<OrganizerAlarmEntity>>

    @Query("SELECT * FROM organizer_alarms ORDER BY triggerAtMillis ASC, id ASC")
    suspend fun getAllAlarmsOnce(): List<OrganizerAlarmEntity>

    @Query("SELECT * FROM organizer_alarms WHERE eventId = :eventId ORDER BY triggerAtMillis ASC, id ASC")
    suspend fun getAlarmsForEventOnce(eventId: Long): List<OrganizerAlarmEntity>

    @Query("SELECT * FROM organizer_alarms WHERE id = :id")
    suspend fun getAlarmById(id: Long): OrganizerAlarmEntity?

    @Query("SELECT * FROM organizer_alarms WHERE enabled = 1 AND triggerAtMillis >= :nowMillis ORDER BY triggerAtMillis ASC")
    suspend fun getEnabledFutureAlarms(nowMillis: Long): List<OrganizerAlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlarm(alarm: OrganizerAlarmEntity): Long

    @Update
    suspend fun updateAlarm(alarm: OrganizerAlarmEntity)

    @Delete
    suspend fun deleteAlarm(alarm: OrganizerAlarmEntity)

    @Query("DELETE FROM organizer_alarms WHERE id = :id")
    suspend fun deleteAlarmById(id: Long)

    @Query("UPDATE organizer_alarms SET enabled = 0, deliveredAt = :deliveredAt, updatedAt = :deliveredAt WHERE id = :id")
    suspend fun markAlarmDelivered(id: Long, deliveredAt: Long)

    @Query("SELECT * FROM organizer_llm_settings WHERE id = 1")
    fun getLlmSettings(): Flow<OrganizerLlmSettingsEntity?>

    @Query("SELECT * FROM organizer_llm_settings WHERE id = 1")
    suspend fun getLlmSettingsOnce(): OrganizerLlmSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLlmSettings(settings: OrganizerLlmSettingsEntity)
}
