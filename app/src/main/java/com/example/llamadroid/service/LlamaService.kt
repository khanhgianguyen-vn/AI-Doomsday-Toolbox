package com.example.llamadroid.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.GGUFParser

class LlamaService : Service() {
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val processController = ProcessController()
    private var notificationTaskId: Int? = null
    override fun onBind(intent: Intent?): IBinder? = null

    // Helper for updating global state
    // private fun updateState(newState: ServerState) {
    //    Companion.updateState(newState)
    // }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            DebugLog.log("LlamaService: onStartCommand action=${intent?.action}")
            when (intent?.action) {
                ACTION_START -> {
                    val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
                    val isEmbedding = intent.getBooleanExtra(EXTRA_IS_EMBEDDING, false)
                    val mmprojPath = intent.getStringExtra(EXTRA_MMPROJ_PATH)
                    
                    // Get optional settings overrides (used by distributed mode to avoid changing global settings)
                    val threadsOverride = if (intent.hasExtra(EXTRA_THREADS)) intent.getIntExtra(EXTRA_THREADS, -1) else null
                    val contextSizeOverride = if (intent.hasExtra(EXTRA_CONTEXT_SIZE)) intent.getIntExtra(EXTRA_CONTEXT_SIZE, -1) else null
                    val temperatureOverride = if (intent.hasExtra(EXTRA_TEMPERATURE)) intent.getFloatExtra(EXTRA_TEMPERATURE, -1f) else null
                    val hostOverride = intent.getStringExtra(EXTRA_HOST)
                    
                    DebugLog.log("LlamaService: MODEL_PATH=$modelPath")
                    if (mmprojPath != null) {
                        DebugLog.log("LlamaService: MMPROJ_PATH=$mmprojPath")
                    }
                    if (modelPath.isNullOrEmpty()) {
                        DebugLog.log("LlamaService: ERROR - No model path provided!")
                        Companion.updateState(ServerState.Error("No model selected"))
                        stopSelf()
                    } else {
                        startServer(modelPath, isEmbedding, mmprojPath, 
                            threadsOverride, contextSizeOverride, temperatureOverride, hostOverride)
                    }
                }
                ACTION_STOP -> stopServer()
            }
        } catch (e: Exception) {
            DebugLog.log("LlamaService: CRASH in onStartCommand: ${e.message}")
            e.printStackTrace()
        }
        return START_NOT_STICKY
    }
    
    private fun startServer(
        modelPath: String, 
        isEmbedding: Boolean, 
        mmprojPath: String? = null,
        threadsOverride: Int? = null,
        contextSizeOverride: Int? = null,
        temperatureOverride: Float? = null,
        hostOverride: String? = null
    ) {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_SERVER,
            "LLM Server"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        
        // Acquire WakeLock to keep server running
        WakeLockManager.acquire(applicationContext, "LlamaService")
        
        Companion.updateState(ServerState.Starting)
        DebugLog.log("LlamaService: Starting server for model: $modelPath")
        
        // Read settings from repository, but use overrides if provided (for distributed mode isolation)
        val settingsRepo = com.example.llamadroid.data.SettingsRepository(applicationContext)
        val threads = threadsOverride ?: settingsRepo.threads.value
        val contextSize = contextSizeOverride ?: settingsRepo.contextSize.value
        val temperature = temperatureOverride ?: settingsRepo.temperature.value
        val host = hostOverride ?: if (settingsRepo.remoteAccess.value) "0.0.0.0" else "127.0.0.1"
        val enableVision = settingsRepo.enableVision.value
        val selectedMmprojPath = settingsRepo.selectedMmprojPath.value
        
        // KV Cache settings for server
        val kvCacheEnabled = settingsRepo.serverKvCacheEnabled.value
        val kvCacheTypeK = settingsRepo.serverKvCacheTypeK.value
        val kvCacheTypeV = settingsRepo.serverKvCacheTypeV.value
        val kvCacheReuse = settingsRepo.serverKvCacheReuse.value
        
        // Use mmproj if vision is enabled AND we have a mmproj path (either from intent or settings)
        val effectiveMmprojPath = if (enableVision) {
            mmprojPath ?: selectedMmprojPath
        } else null
        
        DebugLog.log("LlamaService: Settings - threads=$threads, ctx=$contextSize, temp=$temperature, host=$host")
        DebugLog.log("LlamaService: Vision enabled=$enableVision, mmproj=$effectiveMmprojPath")
        if (kvCacheEnabled) {
            DebugLog.log("LlamaService: KV cache enabled - K=$kvCacheTypeK, V=$kvCacheTypeV, reuse=$kvCacheReuse")
        }
        
        // Get distributed inference workers from DistributedService (if master mode is active)
        val rpcWorkers = if (DistributedService.mode.value == DistributedMode.MASTER) {
            DistributedService.getWorkerAddresses().also {
                if (it.isNotEmpty()) {
                    DebugLog.log("LlamaService: Distributed mode - workers: ${it.joinToString(",")}")
                }
            }
        } else {
            emptyList()
        }
        
        serviceScope.launch {
            try {
                // Get binary from BinaryRepository
                val binaryRepo = BinaryRepository(applicationContext)
                val binaryFile = binaryRepo.getExecutable()
                
                if (binaryFile == null || !binaryFile.exists()) {
                    throw Exception("Binary not found. Please ensure binaries are extracted.")
                }
                val binary = binaryFile.absolutePath
                
                // Calculate layer distribution for RPC workers
                var nGpuLayers = 0
                var tensorSplit: String? = null
                
                if (rpcWorkers.isNotEmpty()) {
                    val connectedWorkers = DistributedService.workers.value.filter { it.isConnected }
                    
                    if (connectedWorkers.isNotEmpty()) {
                        // Get master RAM (from settings) and each worker's RAM
                        val masterRamMB = DistributedService.masterRamMB.value
                        
                        // Get model info
                        val modelFile = java.io.File(modelPath)
                        val modelSizeMB = (modelFile.length() / (1024 * 1024)).toInt()
                        val ggufMetadata = GGUFParser.parse(modelPath)
                        val totalLayers = ggufMetadata?.layerCount ?: 40
                        
                        // Check if any worker has assignedProportion set - if so, use proportions instead of RAM
                        val totalWorkerProportion = connectedWorkers.mapNotNull { it.assignedProportion }.sum()
                        
                        if (totalWorkerProportion > 0f) {
                            // Use proportion-based calculation
                            // Sum of all worker proportions = layers to RPC
                            // E.g., if worker has 80% assigned, give 80% of layers to RPC
                            val workerProportion = totalWorkerProportion.coerceIn(0.01f, 0.99f)
                            nGpuLayers = (totalLayers * workerProportion).toInt()
                                .coerceIn(1, totalLayers - 1) // Leave at least 1 layer on master
                            DebugLog.log("LlamaService: Using proportion-based split - workers get ${(workerProportion*100).toInt()}% = $nGpuLayers/$totalLayers layers")
                        } else {
                            // Fallback to RAM-based calculation
                            val workerRams = connectedWorkers.map { it.availableRamMB }
                            val totalWorkerRamMB = workerRams.sum()
                            val totalRamMB = masterRamMB + totalWorkerRamMB
                            
                            val workerProportion = totalWorkerRamMB.toFloat() / totalRamMB.toFloat()
                            nGpuLayers = (totalLayers * workerProportion).toInt()
                                .coerceIn(1, totalLayers - 1)
                            DebugLog.log("LlamaService: Using RAM-based split - workers get ${(workerProportion*100).toInt()}% = $nGpuLayers/$totalLayers layers")
                        }
                        
                        // If multiple workers, calculate tensor split among WORKERS ONLY
                        if (connectedWorkers.size > 1) {
                            val workerRams = connectedWorkers.map { it.availableRamMB }
                            val totalWorkerRamMB = workerRams.sum()
                            // Each worker's fraction of the total worker RAM
                            val workerFractions = workerRams.map { ram ->
                                ram.toFloat() / totalWorkerRamMB.toFloat()
                            }
                            tensorSplit = workerFractions.joinToString(",") { 
                                String.format(java.util.Locale.US, "%.2f", it) 
                            }
                        }
                        
                        val masterLayers = totalLayers - nGpuLayers
                        
                        DebugLog.log("LlamaService: RAM - master: ${masterRamMB}MB, workers: ${connectedWorkers.map { "${it.deviceName}:${it.availableRamMB}MB (${it.assignedProportion?.let { p -> "${(p*100).toInt()}%" } ?: "auto"})" }}")
                        DebugLog.log("LlamaService: Layers - master: $masterLayers, RPC: $nGpuLayers/$totalLayers (model: ${modelSizeMB}MB)")
                        if (tensorSplit != null) {
                            DebugLog.log("LlamaService: Tensor split among workers: $tensorSplit")
                        }
                        
                        // Update DistributedService for visualization
                        DistributedService.setModelInfo(
                            layers = totalLayers,
                            sizeMB = modelSizeMB.toLong(),
                            rpcLayers = nGpuLayers
                        )
                        DistributedService.setInferenceRunning(true)
                    }
                }
                
                val config = LlamaConfig(
                    modelPath = modelPath, 
                    isEmbedding = isEmbedding,
                    threads = threads,
                    contextSize = contextSize,
                    temperature = temperature,
                    host = host,
                    mmprojPath = effectiveMmprojPath,
                    kvCacheEnabled = kvCacheEnabled,
                    kvCacheTypeK = kvCacheTypeK,
                    kvCacheTypeV = kvCacheTypeV,
                    kvCacheReuse = kvCacheReuse,
                    rpcWorkers = rpcWorkers,
                    nGpuLayers = nGpuLayers,
                    tensorSplit = tensorSplit
                )
                
                DebugLog.log("LlamaService: Binary found at $binary")
                DebugLog.log("LlamaService: Starting on port ${config.port}")
                
                // Show loading state while model loads
                Companion.updateState(ServerState.Loading(0f, "Loading model..."))
                updateNotification("Loading model...")
                updateNotification("Llama Server Running on port ${config.port}")

                processController.start(binary, config, filesDir)
                
                // If process exits
                DebugLog.log("LlamaService: Process exited")
                if (!processController.stoppedIntentionally) {
                    // Process exited unexpectedly
                    DebugLog.log("LlamaService: Process terminated unexpectedly")
                }
                stopServer()
            } catch (e: Exception) {
                // Only show error if not intentionally stopped
                if (!processController.stoppedIntentionally) {
                    DebugLog.log("LlamaService ERROR: ${e.message}")
                    Companion.updateState(ServerState.Error(e.message ?: "Unknown error"))
                } else {
                    DebugLog.log("LlamaService: Stopped by user")
                    Companion.updateState(ServerState.Stopped)
                }
                stopSelf()
            }
        }
    }
    
    private fun stopServer() {
        processController.stop()
        DistributedService.setInferenceRunning(false)
        Companion.updateState(ServerState.Stopped)
        WakeLockManager.release("LlamaService")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun updateNotification(content: String) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, 1f, content)
        }
    }

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_MODEL_PATH = "MODEL_PATH"
        const val EXTRA_IS_EMBEDDING = "IS_EMBEDDING"
        const val EXTRA_MMPROJ_PATH = "MMPROJ_PATH"
        // Optional settings overrides for distributed mode (to avoid modifying global settings)
        const val EXTRA_THREADS = "THREADS"
        const val EXTRA_CONTEXT_SIZE = "CONTEXT_SIZE"
        const val EXTRA_TEMPERATURE = "TEMPERATURE"
        const val EXTRA_HOST = "HOST"
        
        // Global state for simple observation
        private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
        val state = _state.asStateFlow()
        
        fun updateState(newState: ServerState) {
            _state.value = newState
        }
    }
}
