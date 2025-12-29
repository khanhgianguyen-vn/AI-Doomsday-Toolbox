package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Type of note for badge display
 */
enum class NoteType {
    TRANSCRIPTION,   // 🎤 From Whisper
    PDF_SUMMARY,     // 📄 From PDF AI Summary
    VIDEO_SUMMARY,   // 🎬 From Video Sumup
    WORKFLOW,        // ⚙️ From Workflow
    MANUAL           // 📝 User-created
}

/**
 * Entity for storing notes from transcriptions, PDF summaries, and user-created notes
 */
@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val title: String,
    val content: String,
    val type: NoteType,
    val sourceFile: String? = null,  // Original file name if from transcription/PDF
    val language: String? = null,     // Language for transcriptions
    val audioPath: String? = null,    // Path to associated recording for playback
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
