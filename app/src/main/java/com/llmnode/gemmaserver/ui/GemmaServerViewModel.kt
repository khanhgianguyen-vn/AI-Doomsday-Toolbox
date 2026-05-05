package com.llmnode.gemmaserver.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmnode.gemmaserver.network.TailscaleMonitor
import com.llmnode.gemmaserver.network.TailscaleStatus
import com.llmnode.gemmaserver.security.ApiKeyManager
import com.llmnode.gemmaserver.server.ApiServer
import com.llmnode.gemmaserver.server.IncomingTask
import com.llmnode.gemmaserver.server.StreamToken
import com.llmnode.gemmaserver.server.SimulationTask
import com.llmnode.gemmaserver.server.SimulationResult
import com.llmnode.gemmaserver.service.GemmaServerService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

data class GemmaUiState(
    val isModelLoading: Boolean = false,
    val isModelLoaded: Boolean = false,
    val isServerRunning: Boolean = false,
    val loadProgress: String = "",
    val error: String? = null,

    // Network
    val tailscale: TailscaleStatus = TailscaleStatus(),

    // Stats
    val totalRequests: Long = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val ramUsageMb: Long = 0,

    // Current task
    val currentTask: IncomingTask? = null,
    val streamingOutput: String = "",
    val tokensPerSec: Double = 0.0,
    val isTaskActive: Boolean = false,

    // Simulation
    val simulationTask: SimulationTask? = null,
    val simulationResult: SimulationResult? = null,
    val completedTasks: List<CompletedTask> = emptyList(),

    // API Key
    val apiKey: String = "",
    val showApiKey: Boolean = false,

    // Dialogs
    val showModelMissing: Boolean = false,
    val showBatteryDialog: Boolean = false,

    // Model file
    val modelPath: String = "",
    val modelFileName: String = "",
    val modelFileExists: Boolean = false
)

data class CompletedTask(
    val taskId: String = "",
    val personaName: String = "",
    val personaTitle: String = "",
    val eventName: String = "",
    val resultJson: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

class GemmaServerViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "gemma_server_prefs"
        private const val KEY_MODEL_PATH = "model_path"
        private const val KEY_MODEL_NAME = "model_file_name"
        private const val KEY_COMPLETED_TASKS = "completed_tasks"
    }

    private val _uiState = MutableStateFlow(GemmaUiState())
    val uiState: StateFlow<GemmaUiState> = _uiState.asStateFlow()

    private val tailscaleMonitor = TailscaleMonitor()

    init {
        // Load API key
        val apiKey = ApiKeyManager.getOrCreateApiKey(application)

        // Load saved model path
        val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedModelPath = prefs.getString(KEY_MODEL_PATH, "") ?: ""
        val savedModelName = prefs.getString(KEY_MODEL_NAME, "") ?: ""

        _uiState.value = _uiState.value.copy(
            apiKey = apiKey,
            modelPath = savedModelPath,
            modelFileName = savedModelName,
            modelFileExists = savedModelPath.isNotEmpty() && File(savedModelPath).exists(),
            completedTasks = loadCompletedTasks(prefs)
        )

        // Start Tailscale polling
        tailscaleMonitor.startPolling(viewModelScope)
        viewModelScope.launch {
            tailscaleMonitor.status.collect { status ->
                _uiState.value = _uiState.value.copy(tailscale = status)
            }
        }

        // Auto-load model if one is already selected
        if (savedModelPath.isNotEmpty() && File(savedModelPath).exists()) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(500) // Let UI render first
                loadModel()
            }
        }

        // Observe server state
        viewModelScope.launch {
            GemmaServerService.serverState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    isModelLoading = state.isModelLoading,
                    isModelLoaded = state.isModelLoaded,
                    isServerRunning = state.isRunning,
                    loadProgress = state.loadProgress,
                    error = state.error
                )
            }
        }

        // Observe API server flows (when available)
        viewModelScope.launch {
            while (true) {
                val server = GemmaServerService.apiServer
                if (server != null) {
                    launch { collectTasks(server) }
                    launch { collectTokens(server) }
                    launch { collectStats(server) }
                    launch { collectSimulation(server) }
                    launch { collectSimulationResult(server) }
                    break
                }
                kotlinx.coroutines.delay(500)
            }
        }

        // Check battery optimization on first launch
        checkBatteryOptimization()
        
        // Poll RAM
        viewModelScope.launch {
            while (true) {
                val runtime = Runtime.getRuntime()
                val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                _uiState.value = _uiState.value.copy(ramUsageMb = usedMb)
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private suspend fun collectTasks(server: ApiServer) {
        server.taskFlow.collect { task ->
            _uiState.value = _uiState.value.copy(
                currentTask = task,
                streamingOutput = "", // Clear output for new task
                isTaskActive = true
            )
        }
    }

    private suspend fun collectTokens(server: ApiServer) {
        server.tokenFlow.collect { token ->
            if (token.done) {
                _uiState.value = _uiState.value.copy(isTaskActive = false)
            } else {
                _uiState.value = _uiState.value.copy(
                    streamingOutput = _uiState.value.streamingOutput + token.token,
                    tokensPerSec = token.tokensPerSec
                )
            }
        }
    }

    private suspend fun collectStats(server: ApiServer) {
        while (true) {
            _uiState.value = _uiState.value.copy(
                totalRequests = server.totalRequests.get(),
                bytesIn = server.totalBytesIn.get(),
                bytesOut = server.totalBytesOut.get()
            )
            kotlinx.coroutines.delay(1000)
        }
    }

    private suspend fun collectSimulation(server: ApiServer) {
        server.simulationFlow.collect { simTask ->
            _uiState.value = _uiState.value.copy(
                simulationTask = simTask,
                simulationResult = null // Clear previous result
            )
        }
    }

    private suspend fun collectSimulationResult(server: ApiServer) {
        server.simulationResultFlow.collect { result ->
            val simTask = _uiState.value.simulationTask
            val completed = CompletedTask(
                taskId = result.taskId,
                personaName = simTask?.personaName ?: "Unknown",
                personaTitle = simTask?.personaTitle ?: "",
                eventName = simTask?.eventName ?: "",
                resultJson = result.rawJson,
                timestamp = System.currentTimeMillis()
            )
            val updatedTasks = listOf(completed) + _uiState.value.completedTasks.take(49) // Keep last 50
            _uiState.value = _uiState.value.copy(
                simulationResult = result,
                completedTasks = updatedTasks
            )
            saveCompletedTasks(updatedTasks)
        }
    }

    /**
     * Called when user picks a model file from SAF file picker.
     * Copies the file to app-private storage so we have persistent access.
     */
    fun onModelFileSelected(uri: Uri) {
        val context = getApplication<Application>()

        viewModelScope.launch {
            try {
                // Take persistable permission so we can re-read later
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) { }

                // Get original filename
                val fileName = getFileName(context, uri) ?: "model.litertlm"

                // Copy to app's private models directory for reliable access
                val modelsDir = context.getExternalFilesDir("models")
                modelsDir?.mkdirs()
                val destFile = File(modelsDir, fileName)

                _uiState.value = _uiState.value.copy(
                    loadProgress = "Copying model file..."
                )

                // Copy the file
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 8192)
                    }
                }

                val modelPath = destFile.absolutePath

                // Save to SharedPreferences
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_MODEL_PATH, modelPath)
                    .putString(KEY_MODEL_NAME, fileName)
                    .apply()

                _uiState.value = _uiState.value.copy(
                    modelPath = modelPath,
                    modelFileName = fileName,
                    modelFileExists = true,
                    loadProgress = ""
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = "Failed to import model: ${e.message}",
                    loadProgress = ""
                )
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    fun loadModel() {
        val context = getApplication<Application>()
        val modelPath = _uiState.value.modelPath

        if (modelPath.isEmpty() || !File(modelPath).exists()) {
            _uiState.value = _uiState.value.copy(showModelMissing = true)
            return
        }

        // Start foreground service with model path
        val intent = Intent(context, GemmaServerService::class.java).apply {
            action = GemmaServerService.ACTION_START
            putExtra(GemmaServerService.EXTRA_MODEL_PATH, modelPath)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopServer() {
        GemmaServerService.stopServer(getApplication())
    }

    fun toggleApiKeyVisibility() {
        _uiState.value = _uiState.value.copy(showApiKey = !_uiState.value.showApiKey)
    }

    fun dismissModelMissing() {
        _uiState.value = _uiState.value.copy(showModelMissing = false)
    }

    fun dismissBatteryDialog() {
        _uiState.value = _uiState.value.copy(showBatteryDialog = false)
    }

    fun refreshModelStatus() {
        val path = _uiState.value.modelPath
        _uiState.value = _uiState.value.copy(
            modelFileExists = path.isNotEmpty() && File(path).exists()
        )
    }

    private fun checkBatteryOptimization() {
        val context = getApplication<Application>()
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            _uiState.value = _uiState.value.copy(showBatteryDialog = true)
        }
    }

    private fun saveCompletedTasks(tasks: List<CompletedTask>) {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonArray = org.json.JSONArray()
        tasks.forEach { task ->
            val obj = org.json.JSONObject()
            obj.put("taskId", task.taskId)
            obj.put("personaName", task.personaName)
            obj.put("personaTitle", task.personaTitle)
            obj.put("eventName", task.eventName)
            obj.put("resultJson", task.resultJson)
            obj.put("timestamp", task.timestamp)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_COMPLETED_TASKS, jsonArray.toString()).apply()
    }

    private fun loadCompletedTasks(prefs: android.content.SharedPreferences): List<CompletedTask> {
        val json = prefs.getString(KEY_COMPLETED_TASKS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CompletedTask(
                    taskId = obj.optString("taskId", ""),
                    personaName = obj.optString("personaName", ""),
                    personaTitle = obj.optString("personaTitle", ""),
                    eventName = obj.optString("eventName", ""),
                    resultJson = obj.optString("resultJson", ""),
                    timestamp = obj.optLong("timestamp", 0)
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    override fun onCleared() {
        tailscaleMonitor.stopPolling()
        super.onCleared()
    }
}
