package com.example.llamadroid.ui.ai.llama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.model.LlamaChatEntity
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.repository.LlamaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.llamadroid.service.LlamaClientService

class LlamaChatViewModel(
    private val repository: LlamaRepository
) : ViewModel() {

    private val _chats = MutableStateFlow<List<LlamaChatEntity>>(emptyList())
    val chats = _chats.asStateFlow()

    private val _messages = MutableStateFlow<List<LlamaMessageEntity>>(emptyList())
    val messages = _messages.asStateFlow()
    
    // For Chat Screen
    private var currentChatId: Long = -1L

    init {
        viewModelScope.launch {
            repository.allChats.collectLatest {
                _chats.value = it
            }
        }
    }

    fun createChat(title: String, contextSize: Int = 8192, systemPrompt: String? = null, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.createChat(title, contextSize, systemPrompt)
            onCreated(id)
        }
    }

    fun updateChat(chat: LlamaChatEntity, newTitle: String, newContextSize: Int, newSystemPrompt: String? = null) {
        viewModelScope.launch {
            repository.updateChatTitle(chat.id, newTitle)
            repository.updateChatContextSize(chat.id, newContextSize)
            repository.updateChatSystemPrompt(chat.id, newSystemPrompt)
        }
    }

    fun updateChatApiParams(chatId: Long, apiParams: String?) {
        viewModelScope.launch {
            repository.updateChatApiParams(chatId, apiParams)
        }
    }

    fun deleteChat(chat: LlamaChatEntity) {
        viewModelScope.launch {
            repository.deleteChat(chat)
        }
    }
    
    fun loadMessages(chatId: Long) {
        if (currentChatId != chatId) {
            LlamaClientService.resetStateIfIdle()
        }
        currentChatId = chatId
        viewModelScope.launch {
            repository.getMessages(chatId).collectLatest {
                _messages.value = it
            }
        }
    }
    
    fun sendMessage(content: String, serverId: Long) {
       // Logic handled in UI/Service via Intent for now, or we can move it here.
       // For better architecture, ViewModel should interact with Repository/Service. 
       // But the service logic is complex. 
       // Let's stick to the Service Intent pattern for generation to keep UI non-blocking 
       // and persistent, but we can save the USER message here immediately for instant feedback.
    }
    
    fun deleteMessage(message: LlamaMessageEntity) {
        viewModelScope.launch {
            repository.deleteMessage(message)
        }
    }
    
    fun updateMessage(id: Long, content: String) {
        viewModelScope.launch {
             // This is for editing user message. 
             // We also need to delete subsequent messages if we want "branching" or "retry" behavior 
             // from that point.
             repository.updateMessage(id, content)
        }
    }
    
    suspend fun getMessagesOnce(chatId: Long): List<LlamaMessageEntity> {
        return repository.getMessagesOnce(chatId)
    }
    
    fun importChat(title: String, systemPrompt: String?, messages: List<Pair<String, String>>, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val chatId = repository.createChat(title, 8192, systemPrompt)
            for ((role, content) in messages) {
                repository.addMessage(chatId, role, content)
            }
            onCreated(chatId)
        }
    }
}

class LlamaChatViewModelFactory(private val repository: LlamaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LlamaChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LlamaChatViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
