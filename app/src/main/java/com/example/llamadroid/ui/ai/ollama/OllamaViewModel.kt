package com.example.llamadroid.ui.ai.ollama

import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.llamadroid.data.api.OllamaModel
import com.example.llamadroid.data.db.OllamaServerEntity
import com.example.llamadroid.data.repository.OllamaRepository
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.SSHService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

data class OllamaUiState(
    val servers: List<OllamaServerEntity> = emptyList(),
    val selectedServer: OllamaServerEntity? = null,
    val models: List<OllamaModel> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val downloadProgress: Map<String, Float> = emptyMap(), // ModelName -> Progress (0.0 - 1.0)
    val downloadStatus: Map<String, String> = emptyMap() // ModelName -> Status Text
)

class OllamaViewModel(
    private val repository: OllamaRepository
) : ViewModel() {
    private val appContext
        get() = LlamaApplication.instance
    private val runtimeManager by lazy { AgentForegroundService.getOllamaRuntimeManager(appContext) }
    private var lastCompletedModels: Set<String> = emptySet()

    private val _uiState = MutableStateFlow(OllamaUiState())
    val uiState: StateFlow<OllamaUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.savedServers.collect { servers ->
                _uiState.value = _uiState.value.copy(servers = servers)
                // Select first server if none selected and servers exist
                if (_uiState.value.selectedServer == null && servers.isNotEmpty()) {
                    selectServer(servers.first())
                }
            }
        }
        viewModelScope.launch {
            runtimeManager.runtimeState.collect { runtimeState ->
                _uiState.value = _uiState.value.copy(
                    downloadProgress = runtimeState.progress,
                    downloadStatus = runtimeState.status
                )
                val completedModels = runtimeState.status
                    .filterValues { it.equals(appContext.getString(R.string.ollama_runtime_success), ignoreCase = true) }
                    .keys
                val hasNewCompletion = completedModels.any { it !in lastCompletedModels }
                lastCompletedModels = completedModels
                if (hasNewCompletion) {
                    _uiState.value.selectedServer?.let { fetchModels(it) }
                }
            }
        }
    }

    fun selectServer(server: OllamaServerEntity) {
        _uiState.value = _uiState.value.copy(selectedServer = server)
        fetchModels(server)
    }

    fun fetchModels(server: OllamaServerEntity) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val models = repository.getModels(server.url)
                _uiState.value = _uiState.value.copy(models = models, isLoading = false)
                repository.updateLastConnected(server.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = appContext.getString(R.string.ollama_error_connect, e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    fun addServer(name: String, url: String) {
        viewModelScope.launch {
            try {
                repository.addServer(name, url)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = appContext.getString(R.string.ollama_error_add_server, e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }

    fun deleteServer(server: OllamaServerEntity) {
        viewModelScope.launch {
            repository.deleteServer(server)
            if (_uiState.value.selectedServer == server) {
                _uiState.value = _uiState.value.copy(
                    selectedServer = null,
                    models = emptyList()
                )
            }
        }
    }
    
    fun updateServer(server: OllamaServerEntity) {
        viewModelScope.launch {
            repository.updateServer(server)
        }
    }

    fun pullModel(modelName: String) {
        val server = uiState.value.selectedServer ?: return
        runtimeManager.pullModel(server.url, modelName)
    }

    private fun updateDownloadStatus(modelName: String, status: String, progress: Float) {
        val newProgress = _uiState.value.downloadProgress.toMutableMap()
        val newStatus = _uiState.value.downloadStatus.toMutableMap()
        
        if (status == "success") {
            newProgress.remove(modelName)
            newStatus.remove(modelName)
        } else {
            newProgress[modelName] = progress
            newStatus[modelName] = status
        }
        
        _uiState.value = _uiState.value.copy(
            downloadProgress = newProgress,
            downloadStatus = newStatus
        )
    }
    
    fun clearDownloadStatus(modelName: String) {
        runtimeManager.clearStatus(modelName)
    }

    fun cancelOperation(modelName: String) {
        runtimeManager.cancelOperation(modelName)
    }

    fun deleteModel(modelName: String) {
        val server = uiState.value.selectedServer ?: return
        viewModelScope.launch {
            try {
                repository.deleteModel(server.url, modelName)
                fetchModels(server)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = appContext.getString(R.string.ollama_error_delete_model, e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }
    
    fun createModel(name: String, fromModel: String, modelfile: String) {
        val server = uiState.value.selectedServer ?: return
        val normalizedName = repository.normalizeCreateModelName(name)
        if (!repository.isValidCreateModelName(normalizedName)) {
            _uiState.value = _uiState.value.copy(
                error = appContext.getString(R.string.ollama_invalid_model_name)
            )
            return
        }
        runtimeManager.createModel(server.url, normalizedName, fromModel, modelfile)
    }

    fun createModelLocally(sshService: SSHService, name: String, fromModel: String, modelfile: String) {
        val normalizedName = repository.normalizeCreateModelName(name)
        if (!repository.isValidCreateModelName(normalizedName)) {
            _uiState.value = _uiState.value.copy(
                error = appContext.getString(R.string.ollama_invalid_model_name)
            )
            return
        }
        runtimeManager.createModelLocally(normalizedName, fromModel, modelfile)
    }
    
    fun getModelInfo(modelName: String, onResult: (String, String) -> Unit) {
        val server = uiState.value.selectedServer ?: return
        viewModelScope.launch {
            try {
                val info = repository.getModelInfo(server.url, modelName)
                onResult(repository.normalizeModelfile(info.modelfile, modelName), info.template)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = appContext.getString(R.string.ollama_error_model_info, e.message ?: e.javaClass.simpleName)
                )
            }
        }
    }
}

class OllamaViewModelFactory(private val repository: OllamaRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OllamaViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OllamaViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
