package com.example.llamadroid.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity

@Dao
interface LlamaServerDao {
    @Query("SELECT * FROM llama_servers ORDER BY lastUsed DESC")
    fun getAllServers(): Flow<List<LlamaServerEntity>>

    @Query("SELECT * FROM llama_servers WHERE id = :id")
    suspend fun getServerById(id: Long): LlamaServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServer(server: LlamaServerEntity): Long

    @Delete
    suspend fun deleteServer(server: LlamaServerEntity)
    
    @Query("UPDATE llama_servers SET lastUsed = :timestamp WHERE id = :id")
    suspend fun updateLastUsed(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("SELECT * FROM llama_servers ORDER BY lastUsed DESC LIMIT 1")
    suspend fun getLastUsedServer(): LlamaServerEntity?

    @Query("UPDATE llama_servers SET supportsVision = :supportsVision WHERE id = :id")
    suspend fun updateSupportsVision(id: Long, supportsVision: Boolean)

    @Query("UPDATE llama_servers SET modelName = :modelName WHERE id = :id")
    suspend fun updateModelName(id: Long, modelName: String?)

    @Update
    suspend fun updateServer(server: LlamaServerEntity)
}

@Dao
interface LlamaChatDao {
    @Query("SELECT * FROM llama_chats ORDER BY lastModified DESC")
    fun getAllChats(): Flow<List<LlamaChatEntity>>

    @Query("SELECT * FROM llama_chats WHERE id = :id")
    suspend fun getChatById(id: Long): LlamaChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: LlamaChatEntity): Long

    @Delete
    suspend fun deleteChat(chat: LlamaChatEntity)

    @Query("UPDATE llama_chats SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE llama_chats SET contextSize = :contextSize WHERE id = :id")
    suspend fun updateContextSize(id: Long, contextSize: Int)
    
    @Query("UPDATE llama_chats SET lastModified = :timestamp WHERE id = :id")
    suspend fun updateLastModified(id: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE llama_chats SET systemPrompt = :systemPrompt WHERE id = :id")
    suspend fun updateSystemPrompt(id: Long, systemPrompt: String?)

    @Query("UPDATE llama_chats SET apiParams = :apiParams WHERE id = :id")
    suspend fun updateApiParams(id: Long, apiParams: String?)
}

@Dao
interface LlamaMessageDao {
    @Query("SELECT * FROM llama_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    fun getMessagesForChat(chatId: Long): Flow<List<LlamaMessageEntity>>

    @Query("SELECT * FROM llama_messages WHERE chatId = :chatId ORDER BY timestamp ASC")
    suspend fun getMessagesOnce(chatId: Long): List<LlamaMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: LlamaMessageEntity): Long

    @Delete
    suspend fun deleteMessage(message: LlamaMessageEntity)
    
    @Query("DELETE FROM llama_messages WHERE chatId = :chatId")
    suspend fun deleteAllMessagesForChat(chatId: Long)
    
    // Delete all messages after a specific message (for edit/regenerate flow)
    @Query("DELETE FROM llama_messages WHERE chatId = :chatId AND timestamp > :timestamp")
    suspend fun deleteMessagesAfter(chatId: Long, timestamp: Long)
    
    @Query("UPDATE llama_messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    @Query("UPDATE llama_messages SET content = :content, thinking = :thinking, promptTokens = :promptTokens, completionTokens = :completionTokens, tps = :tps, generationTimeMs = :generationTimeMs WHERE id = :id")
    suspend fun updateMessageThinkingAndContent(
        id: Long, 
        content: String, 
        thinking: String?,
        promptTokens: Int,
        completionTokens: Int,
        tps: Double,
        generationTimeMs: Long
    )

    @Query("UPDATE llama_messages SET isTruncated = :isTruncated WHERE id = :id")
    suspend fun updateMessageTruncatedStatus(id: Long, isTruncated: Boolean)
}
