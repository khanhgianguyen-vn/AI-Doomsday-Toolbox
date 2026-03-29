package com.example.llamadroid.data.repository

import com.example.llamadroid.data.dao.LlamaServerDao
import com.example.llamadroid.data.dao.LlamaChatDao
import com.example.llamadroid.data.dao.LlamaMessageDao
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import kotlinx.coroutines.flow.Flow

class LlamaRepository(
    private val serverDao: LlamaServerDao,
    private val chatDao: LlamaChatDao,
    private val messageDao: LlamaMessageDao
) {
    // Servers
    val allServers: Flow<List<LlamaServerEntity>> = serverDao.getAllServers()
    
    suspend fun saveServer(server: LlamaServerEntity) = serverDao.insertServer(server)
    suspend fun deleteServer(server: LlamaServerEntity) = serverDao.deleteServer(server)
    suspend fun getServer(id: Long) = serverDao.getServerById(id)
    suspend fun updateServerUsage(id: Long) = serverDao.updateLastUsed(id)
    suspend fun updateServer(server: LlamaServerEntity) = serverDao.updateServer(server)
    suspend fun updateServerModelName(id: Long, modelName: String?) = serverDao.updateModelName(id, modelName)

    // Chats
    val allChats: Flow<List<LlamaChatEntity>> = chatDao.getAllChats()
    
    suspend fun createChat(title: String, contextSize: Int = 8192, systemPrompt: String? = null): Long {
        val chat = LlamaChatEntity(title = title, contextSize = contextSize, systemPrompt = systemPrompt)
        return chatDao.insertChat(chat)
    }
    
    suspend fun getChat(id: Long) = chatDao.getChatById(id)
    suspend fun deleteChat(chat: LlamaChatEntity) = chatDao.deleteChat(chat)
    suspend fun updateChatTitle(id: Long, title: String) = chatDao.updateTitle(id, title)
    suspend fun updateChatContextSize(id: Long, contextSize: Int) = chatDao.updateContextSize(id, contextSize)
    suspend fun updateChatModified(id: Long) = chatDao.updateLastModified(id)
    suspend fun updateChatSystemPrompt(id: Long, systemPrompt: String?) = chatDao.updateSystemPrompt(id, systemPrompt)
    suspend fun updateChatApiParams(id: Long, apiParams: String?) = chatDao.updateApiParams(id, apiParams)

    // Messages
    fun getMessages(chatId: Long): Flow<List<LlamaMessageEntity>> = messageDao.getMessagesForChat(chatId)
    suspend fun getMessagesOnce(chatId: Long): List<LlamaMessageEntity> = messageDao.getMessagesOnce(chatId)
    
    suspend fun addMessage(chatId: Long, role: String, content: String): Long {
        val msg = LlamaMessageEntity(
            chatId = chatId,
            role = role,
            content = content
        )
        val id = messageDao.insertMessage(msg)
        chatDao.updateLastModified(chatId)
        return id
    }
    
    suspend fun updateMessage(id: Long, content: String) = messageDao.updateMessageContent(id, content)
    suspend fun updateMessageThinkingAndContent(
        id: Long, 
        content: String, 
        thinking: String?,
        promptTokens: Int,
        completionTokens: Int,
        tps: Double,
        generationTimeMs: Long
    ) = messageDao.updateMessageThinkingAndContent(id, content, thinking, promptTokens, completionTokens, tps, generationTimeMs)
    
    suspend fun deleteMessage(message: LlamaMessageEntity) = messageDao.deleteMessage(message)
    
    // For "Edit and Regenerate": Delete all messages after the edited one
    suspend fun deleteMessagesAfter(chatId: Long, timestamp: Long) = messageDao.deleteMessagesAfter(chatId, timestamp)

    suspend fun updateMessageTruncatedStatus(id: Long, isTruncated: Boolean) = messageDao.updateMessageTruncatedStatus(id, isTruncated)
}
