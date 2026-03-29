package com.example.llamadroid.ui.ai.llama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.api.LlamaApi
import com.example.llamadroid.data.api.LlamaModelsResponse
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class LlamaServerViewModel(
    private val repository: LlamaRepository
) : ViewModel() {

    private val _servers = MutableStateFlow<List<LlamaServerEntity>>(emptyList())
    val servers = _servers.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allServers.collectLatest {
                _servers.value = it
            }
        }
    }

    fun addServer(name: String, host: String, port: Int, supportsVision: Boolean = false) {
        viewModelScope.launch {
            val server = LlamaServerEntity(name = name, host = host, port = port, supportsVision = supportsVision)
            val id = repository.saveServer(server)
            // Auto-fetch model name after adding
            fetchModelName(server.copy(id = id))
        }
    }

    fun updateServer(server: LlamaServerEntity) {
        viewModelScope.launch {
            repository.updateServer(server)
        }
    }

    fun deleteServer(server: LlamaServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
        }
    }

    // Since we don't have a "global" selected server state in DB yet (only lastUsed),
    // we might just navigate to chat from here. 
    // For now, updating usage timestamp is enough.
    fun selectServer(server: LlamaServerEntity) {
        viewModelScope.launch {
            repository.updateServerUsage(server.id)
        }
    }

    fun fetchModelName(server: LlamaServerEntity) {
        viewModelScope.launch {
            try {
                val baseUrl = if (server.host.startsWith("http")) {
                    "${server.host}:${server.port}/"
                } else {
                    "http://${server.host}:${server.port}/"
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()

                val api = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(LlamaApi::class.java)

                val response = withContext(Dispatchers.IO) {
                    api.getModels().execute()
                }

                if (response.isSuccessful) {
                    val modelName = response.body()?.data?.firstOrNull()?.id
                    if (modelName != null) {
                        repository.updateServerModelName(server.id, modelName)
                        DebugLog.log("Fetched model name for ${server.name}: $modelName")
                    }
                } else {
                    DebugLog.log("Failed to fetch model name: ${response.code()}")
                    repository.updateServerModelName(server.id, null)
                }
            } catch (e: Exception) {
                DebugLog.log("Error fetching model name: ${e.message}")
                repository.updateServerModelName(server.id, null)
            }
        }
    }
}

class LlamaServerViewModelFactory(private val repository: LlamaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LlamaServerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LlamaServerViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

