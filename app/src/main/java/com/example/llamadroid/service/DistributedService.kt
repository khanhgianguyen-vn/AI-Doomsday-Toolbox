package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.llamadroid.util.CpuFeatures
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.NetworkInterface

/**
 * Distributed inference modes
 */
enum class DistributedMode {
    NONE,   // Not in distributed mode
    MASTER, // Hosting model and coordinating workers
    WORKER  // Providing compute resources to master
}

/**
 * Information about a connected worker
 */
data class WorkerInfo(
    val ip: String,
    val port: Int,
    val deviceName: String,
    val availableRamMB: Int,
    val assignedLayers: IntRange? = null,
    val isConnected: Boolean = false,
    val isEnabled: Boolean = true,
    val savedWorkerId: Long? = null,  // Reference to persisted worker
    val assignedProportion: Float? = null  // User-set proportion override (0.0-1.0)
)

/**
 * Layer distribution across devices
 */
data class LayerDistribution(
    val totalLayers: Int,
    val modelSizeMB: Long,
    val assignments: Map<String, IntRange> // device identifier -> layer range
)

/**
 * Service for distributed LLM inference using llama.cpp RPC
 * 
 * In WORKER mode: Runs rpc-server binary to provide compute resources
 * In MASTER mode: Coordinates with LlamaService to connect to workers
 */
class DistributedService : Service() {

    companion object {
        private const val TAG = "DistributedService"
        const val RPC_DEFAULT_PORT = 50052
        
        const val ACTION_START_WORKER = "com.example.llamadroid.START_WORKER"
        const val ACTION_STOP_WORKER = "com.example.llamadroid.STOP_WORKER"
        const val EXTRA_PORT = "port"
        const val EXTRA_RAM_MB = "ram_mb"
        const val EXTRA_THREADS = "threads"
        const val EXTRA_CACHE = "cache"
        
        // State exposed to UI
        private val _mode = MutableStateFlow(DistributedMode.NONE)
        val mode: StateFlow<DistributedMode> = _mode.asStateFlow()
        
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        private val _workers = MutableStateFlow<List<WorkerInfo>>(emptyList())
        val workers: StateFlow<List<WorkerInfo>> = _workers.asStateFlow()
        
        private val _workerPort = MutableStateFlow(RPC_DEFAULT_PORT)
        val workerPort: StateFlow<Int> = _workerPort.asStateFlow()
        
        private val _workerRamMB = MutableStateFlow(4096)
        val workerRamMB: StateFlow<Int> = _workerRamMB.asStateFlow()
        
        private val _masterRamMB = MutableStateFlow(4096)
        val masterRamMB: StateFlow<Int> = _masterRamMB.asStateFlow()
        
        private val _localIp = MutableStateFlow<String?>(null)
        val localIp: StateFlow<String?> = _localIp.asStateFlow()
        
        // Connection count for worker mode - tracks how many masters have connected
        private val _connectionCount = MutableStateFlow(0)
        val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()
        
        // Increment connection count (called when rpc-server logs indicate connection)
        fun incrementConnectionCount() {
            _connectionCount.value++
            DebugLog.log("[DistributedService] Connection count: ${_connectionCount.value}")
        }
        
        // Reset connection count
        fun resetConnectionCount() {
            _connectionCount.value = 0
        }
        
        // ==================== Network Visualization States ====================
        
        // Current model layer count (read from GGUF)
        private val _modelLayerCount = MutableStateFlow(0)
        val modelLayerCount: StateFlow<Int> = _modelLayerCount.asStateFlow()
        
        // Layers assigned to RPC workers
        private val _rpcLayerCount = MutableStateFlow(0)
        val rpcLayerCount: StateFlow<Int> = _rpcLayerCount.asStateFlow()
        
        // Model size in MB
        private val _modelSizeMB = MutableStateFlow(0L)
        val modelSizeMB: StateFlow<Long> = _modelSizeMB.asStateFlow()
        
        // Inference running status
        private val _inferenceRunning = MutableStateFlow(false)
        val inferenceRunning: StateFlow<Boolean> = _inferenceRunning.asStateFlow()
        
        // Transfer progress (0-100) for layer transfer to workers
        private val _transferProgress = MutableStateFlow(0)
        val transferProgress: StateFlow<Int> = _transferProgress.asStateFlow()
        
        // RPC debug logs (captured from rpc-server stdout)
        private const val MAX_RPC_LOGS = 1000
        private val _rpcLogs = MutableStateFlow<List<String>>(emptyList())
        val rpcLogs: StateFlow<List<String>> = _rpcLogs.asStateFlow()
        
        fun addRpcLog(line: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())
            val newLine = "[$timestamp] $line"
            _rpcLogs.value = (_rpcLogs.value + newLine).takeLast(MAX_RPC_LOGS)
        }
        
        fun clearRpcLogs() {
            _rpcLogs.value = emptyList()
        }
        
        fun setModelInfo(layers: Int, sizeMB: Long, rpcLayers: Int) {
            _modelLayerCount.value = layers
            _modelSizeMB.value = sizeMB
            _rpcLayerCount.value = rpcLayers
            DebugLog.log("[DistributedService] Model info set: $layers layers, ${sizeMB}MB, $rpcLayers to RPC")
        }
        
        fun setInferenceRunning(running: Boolean) {
            _inferenceRunning.value = running
        }
        
        fun setTransferProgress(progress: Int) {
            _transferProgress.value = progress.coerceIn(0, 100)
        }
        
        // Helper to get worker RPC addresses for master
        fun getWorkerAddresses(): List<String> {
            return _workers.value
                .filter { it.isConnected }
                .map { "${it.ip}:${it.port}" }
        }
        
        // Add worker manually with RAM
        fun addWorkerManually(ip: String, port: Int = RPC_DEFAULT_PORT, deviceName: String = "Manual", ramMB: Int = 4096, proportion: Float? = null) {
            val current = _workers.value.toMutableList()
            if (current.none { it.ip == ip && it.port == port }) {
                current.add(WorkerInfo(ip, port, deviceName, ramMB, null, true, true, null, proportion))
                _workers.value = current
                DebugLog.log("[$TAG] Manually added worker: $deviceName at $ip:$port with ${ramMB}MB RAM, proportion=${proportion?.let { "${(it*100).toInt()}%" } ?: "auto"}")
            }
        }
        
        // Remove worker
        fun removeWorker(ip: String, port: Int) {
            _workers.value = _workers.value.filterNot { it.ip == ip && it.port == port }
            DebugLog.log("[$TAG] Removed worker at $ip:$port")
        }
        
        // Clear all workers
        fun clearWorkers() {
            _workers.value = emptyList()
        }
        
        // Set master mode with worker addresses (for use by LlamaService)
        fun setMasterMode(workerAddresses: List<String>) {
            _mode.value = DistributedMode.MASTER
            DebugLog.log("[$TAG] Master mode set with ${workerAddresses.size} workers: $workerAddresses")
        }
        
        // Set master RAM allocation
        fun setMasterRam(ramMB: Int) {
            _masterRamMB.value = ramMB
        }
        
        // Set worker RAM allocation
        fun setWorkerRam(ramMB: Int) {
            _workerRamMB.value = ramMB
        }
        
        // Static helper methods to start/stop via intents
        fun startWorker(context: Context, port: Int = RPC_DEFAULT_PORT, ramMB: Int = 4096, threads: Int = 4, enableCache: Boolean = false) {
            val intent = Intent(context, DistributedService::class.java).apply {
                action = ACTION_START_WORKER
                putExtra(EXTRA_PORT, port)
                putExtra(EXTRA_RAM_MB, ramMB)
                putExtra(EXTRA_THREADS, threads)
                putExtra(EXTRA_CACHE, enableCache)
            }
            context.startService(intent)
        }
        
        fun stopWorker(context: Context) {
            val intent = Intent(context, DistributedService::class.java).apply {
                action = ACTION_STOP_WORKER
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var rpcProcess: Process? = null
    
    override fun onCreate() {
        super.onCreate()
        updateLocalIp()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        
        when (action) {
            ACTION_START_WORKER -> {
                val port = intent.getIntExtra(EXTRA_PORT, RPC_DEFAULT_PORT)
                val ramMB = intent.getIntExtra(EXTRA_RAM_MB, 4096)
                val threads = intent.getIntExtra(EXTRA_THREADS, 4)
                val enableCache = intent.getBooleanExtra(EXTRA_CACHE, false)
                startWorkerMode(port, ramMB, threads, enableCache)
            }
            ACTION_STOP_WORKER -> {
                stopWorkerMode()
            }
        }
        
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopWorkerMode()
        serviceScope.cancel()
    }
    
    /**
     * Start worker mode - run rpc-server binary
     */
    private fun startWorkerMode(port: Int, ramMB: Int, threads: Int = 4, enableCache: Boolean = false) {
        if (_isRunning.value) {
            DebugLog.log("[$TAG] Worker already running")
            return
        }
        
        serviceScope.launch {
            try {
                _mode.value = DistributedMode.WORKER
                _workerPort.value = port
                _workerRamMB.value = ramMB
                
                // Acquire WakeLock to keep CPU running while serving as RPC worker
                WakeLockManager.acquire(applicationContext, "DistributedService")
                DebugLog.log("[$TAG] WakeLock acquired for RPC worker")
                
                // Extract correct tier binary
                val tier = CpuFeatures.getTier()
                val binaryName = "librpc-server_$tier.so"
                val binaryFile = extractBinary(binaryName)
                
                if (binaryFile == null || !binaryFile.exists()) {
                    DebugLog.log("[$TAG] Failed to extract rpc-server binary")
                    _mode.value = DistributedMode.NONE
                    return@launch
                }
                
                // Build command: rpc-server -H 0.0.0.0 -p <port> -t <threads> [-c]
                val command = mutableListOf(
                    binaryFile.absolutePath,
                    "-H", "0.0.0.0",
                    "-p", port.toString(),
                    "-t", threads.toString()
                )
                
                // Add cache flag if enabled
                if (enableCache) {
                    command.add("-c")
                }
                
                DebugLog.log("[$TAG] Starting RPC server: ${command.joinToString(" ")}")
                
                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)
                processBuilder.environment()["LD_LIBRARY_PATH"] = binaryFile.parentFile?.absolutePath ?: ""
                
                // Set XDG_CACHE_HOME to app's cache folder for layer caching with -c flag
                val cacheDir = applicationContext.cacheDir.absolutePath
                val filesDir = applicationContext.filesDir.absolutePath
                processBuilder.environment()["XDG_CACHE_HOME"] = cacheDir
                
                // Workaround for some devices (e.g. RedMagic 9 Pro Android 15) where
                // rpc-server tries to stat /init and fails with Permission denied.
                // Setting HOME, TMPDIR, and PWD helps redirect filesystem operations.
                processBuilder.environment()["HOME"] = filesDir
                processBuilder.environment()["TMPDIR"] = cacheDir
                processBuilder.environment()["PWD"] = filesDir
                processBuilder.environment()["XDG_DATA_HOME"] = filesDir
                processBuilder.environment()["XDG_CONFIG_HOME"] = filesDir
                
                DebugLog.log("[$TAG] XDG_CACHE_HOME=$cacheDir, HOME=$filesDir")
                processBuilder.environment()["GGML_RPC_DEBUG"] = "1"  // Enable debug logging
                
                // Set working directory to app files dir - may help with /init access issue on some devices
                processBuilder.directory(java.io.File(filesDir))
                
                rpcProcess = processBuilder.start()
                _isRunning.value = true
                
                // Reset connection count and clear old logs
                resetConnectionCount()
                clearRpcLogs()
                addRpcLog("=== RPC Server Started ===")
                addRpcLog("Command: ${command.joinToString(" ")}")
                
                // Read output and detect connections
                val reader = BufferedReader(InputStreamReader(rpcProcess!!.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    // Only log to RPC logs StateFlow, NOT to DebugLog (app logs)
                    line?.let { output ->
                        addRpcLog(output)
                        
                        // Detect connection events
                        if (output.contains("Accepted client connection", ignoreCase = true) ||
                            output.contains("accepted connection", ignoreCase = true)) {
                            incrementConnectionCount()
                        }
                        if ((output.contains("Client connection closed", ignoreCase = true) ||
                             output.contains("connection closed", ignoreCase = true)) && 
                            _connectionCount.value > 0) {
                            _connectionCount.value--
                            DebugLog.log("[$TAG] Connection count decreased: ${_connectionCount.value}")
                        }
                    }
                }
                
                // Process ended
                _isRunning.value = false
                _mode.value = DistributedMode.NONE
                resetConnectionCount()
                
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Error starting worker: ${e.message}")
                _isRunning.value = false
                _mode.value = DistributedMode.NONE
            }
        }
    }
    
    /**
     * Stop worker mode
     */
    private fun stopWorkerMode() {
        rpcProcess?.destroyForcibly()
        rpcProcess = null
        _isRunning.value = false
        _mode.value = DistributedMode.NONE
        // Release WakeLock
        WakeLockManager.release("DistributedService")
        DebugLog.log("[$TAG] Worker stopped, WakeLock released")
    }
    
    /**
     * Extract binary from jniLibs
     */
    private fun extractBinary(binaryName: String): File? {
        return try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val file = File(nativeLibDir, binaryName)
            if (file.exists() && file.canExecute()) {
                file
            } else {
                DebugLog.log("[$TAG] Binary not found or not executable: ${file.absolutePath}")
                null
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Error getting binary: ${e.message}")
            null
        }
    }
    
    private fun updateLocalIp() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        _localIp.value = addr.hostAddress
                        return
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Error getting local IP: ${e.message}")
        }
    }
}
