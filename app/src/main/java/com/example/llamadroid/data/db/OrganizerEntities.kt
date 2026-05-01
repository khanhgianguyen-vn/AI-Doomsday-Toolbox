package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "organizer_events",
    indices = [
        Index("startAtMillis"),
        Index("updatedAt")
    ]
)
data class OrganizerEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String = "",
    val location: String = "",
    val startAtMillis: Long,
    val endAtMillis: Long? = null,
    val allDay: Boolean = false,
    val timezoneId: String = java.time.ZoneId.systemDefault().id,
    val colorArgb: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "organizer_alarms",
    foreignKeys = [
        ForeignKey(
            entity = OrganizerEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("eventId"),
        Index("triggerAtMillis"),
        Index("enabled")
    ]
)
data class OrganizerAlarmEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val eventId: Long? = null,
    val title: String,
    val message: String = "",
    val triggerAtMillis: Long,
    val timezoneId: String = java.time.ZoneId.systemDefault().id,
    val soundEnabled: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deliveredAt: Long? = null
)

@Entity(tableName = "organizer_llm_settings")
data class OrganizerLlmSettingsEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ID,
    val calendarToolsAllowed: Boolean = false,
    val alarmToolsAllowed: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}
