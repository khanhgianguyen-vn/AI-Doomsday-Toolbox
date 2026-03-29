package com.example.llamadroid.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "llama_servers")
data class LlamaServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int,
    val supportsVision: Boolean = false,
    val modelName: String? = null,
    val lastUsed: Long = System.currentTimeMillis()
)

@Entity(tableName = "llama_chats", indices = [Index("lastModified")])
data class LlamaChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val lastModified: Long = System.currentTimeMillis(),
    val contextSize: Int = 8192,
    val systemPrompt: String? = null,
    val apiParams: String? = null // JSON string for overrides like temperature, etc.
)

@Entity(
    tableName = "llama_messages",
    foreignKeys = [
        ForeignKey(
            entity = LlamaChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class LlamaMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isError: Boolean = false,
    val isTruncated: Boolean = false,
    val thinking: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val tps: Double = 0.0,
    val generationTimeMs: Long = 0L
)
