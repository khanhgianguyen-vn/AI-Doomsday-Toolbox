package com.example.llamadroid.data.db

import androidx.room.*
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.OllamaService

/**
 * Dataset generation database entities
 */

// Enums
enum class SourceType { PDF, TXT, NOTE }
enum class ChunkStatus { PENDING, CLEANED }
enum class QAStatus { QUESTIONED, ANSWERED, REVIEWED }
enum class PromptType { CLEAN, QUESTION, ANSWER, REVIEW }

/**
 * Dataset project - user can have multiple independent datasets
 */
@Entity(tableName = "dataset_projects")
data class DatasetProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val backend: String = SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
    // llama-server config - uses existing llama.cpp server (started from Dashboard)
    val serverUrl: String = "http://127.0.0.1:8080",  // URL for API calls (default llama.cpp port)
    // Ollama config
    val ollamaUrl: String = OllamaService.DEFAULT_URL,
    val ollamaModel: String? = null,
    val ollamaNumCtx: Int = 4096,
    val ollamaThreads: Int = 4,
    val ollamaMmap: Boolean = false,
    // Per-request API settings
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val useCoT: Boolean = false,
    val finalLanguage: String = "",
    val chunkSize: Int = 1000,  // Only used during source import
    val questionsPerChunk: Int = 5
)

/**
 * Source documents - multiple per project (PDF, TXT, Notes)
 */
@Entity(
    tableName = "dataset_sources",
    foreignKeys = [ForeignKey(
        entity = DatasetProjectEntity::class,
        parentColumns = ["id"],
        childColumns = ["projectId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("projectId")]
)
data class DatasetSourceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val type: SourceType,
    val uri: String,
    val name: String,
    val extractedText: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

/**
 * Text chunks - created from sources
 */
@Entity(
    tableName = "dataset_chunks",
    foreignKeys = [
        ForeignKey(
            entity = DatasetProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DatasetSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("sourceId")]
)
data class DatasetChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val sourceId: Long,
    val chunkIndex: Int,
    val originalText: String,
    val cleanedText: String? = null,
    val status: ChunkStatus = ChunkStatus.PENDING
)

/**
 * Q&A pairs - 5 per chunk
 */
@Entity(
    tableName = "dataset_qa",
    foreignKeys = [
        ForeignKey(
            entity = DatasetProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = DatasetChunkEntity::class,
            parentColumns = ["id"],
            childColumns = ["chunkId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("chunkId")]
)
data class DatasetQAEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val chunkId: Long,
    val question: String,
    val answer: String? = null,
    val score: Int? = null,  // 0-5
    val scoreJustification: String? = null,  // LLM explanation for the score
    val status: QAStatus = QAStatus.QUESTIONED
)

/**
 * Custom prompts - user can save/edit/delete, per type
 */
@Entity(tableName = "dataset_prompts")
data class DatasetPromptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PromptType,
    val content: String,
    val isDefault: Boolean = false
)
