package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.BenchmarkResult
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlin.coroutines.coroutineContext

/**
 * Benchmark service that runs llama-bench to measure tokens/second with different thread counts.
 * Uses the actual llama-bench binary to get real performance measurements.
 * Results are saved to database for persistence.
 * 
 * Progress output format:
 * | llama-bench: benchmark 1/2: prompt run 1/5 |
 * | llama-bench: benchmark 2/2: generation run 3/5 |
 * 
 * Table format (8 columns):
 * | model | size | params | backend | threads | mmap | test | t/s |
 */
class BenchmarkService(private val context: Context) {
    
    companion object {
        // Global state for running status (persists across navigation)
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        
        private val _progressText = MutableStateFlow("")
        val progressText: StateFlow<String> = _progressText.asStateFlow()
        
        private val _progress = MutableStateFlow(0f)
        val progress: StateFlow<Float> = _progress.asStateFlow()
        
        // Service-level scope that survives navigation
        private val serviceScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )
        
        // Current benchmark job
        private var benchmarkJob: kotlinx.coroutines.Job? = null
        
        // Singleton instance for the current benchmark
        private var instance: BenchmarkService? = null
        
        fun cancel() {
            instance?.cancelInternal()
            benchmarkJob?.cancel()
            benchmarkJob = null
            _isRunning.value = false
            _progressText.value = "Cancelled"
        }
    }
    
    private val db = AppDatabase.getDatabase(context)
    private var notificationTaskId: Int? = null
    private var currentProcess: Process? = null
    
    @Volatile
    private var isCancelled = false
    
    data class SingleResult(
        val threads: Int,
        val promptTokensPerSecond: Float,
        val genTokensPerSecond: Float,
        val totalTimeMs: Long,
        val success: Boolean,
        val error: String? = null
    )
    
    /**
     * Internal cancel method.
     */
    private fun cancelInternal() {
        isCancelled = true
        currentProcess?.destroyForcibly()
        currentProcess = null
        DebugLog.log("Benchmark: Cancelled by user")
    }
    
    /**
     * Start a benchmark in the service scope (survives navigation).
     */
    fun startBenchmark(
        modelPath: String,
        minThreads: Int,
        maxThreads: Int,
        promptTokens: Int,
        genTokens: Int
    ) {
        // Cancel any existing job
        benchmarkJob?.cancel()
        instance = this
        
        benchmarkJob = serviceScope.launch {
            runFullBenchmark(
                modelPath = modelPath,
                minThreads = minThreads,
                maxThreads = maxThreads,
                promptTokens = promptTokens,
                genTokens = genTokens,
                onProgress = { _, _ -> }  // Progress handled by global state
            )
        }
    }
    
    /**
     * Run benchmark for a single thread count with progress updates.
     */
    suspend fun runBenchmark(
        modelPath: String,
        threads: Int,
        promptTokens: Int = 512,
        genTokens: Int = 128,
        repetitions: Int = 3,
        onProgress: (String, Float) -> Unit = { _, _ -> }
    ): SingleResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        
        if (isCancelled || !coroutineContext.isActive) {
            return@withContext SingleResult(
                threads = threads,
                promptTokensPerSecond = 0f,
                genTokensPerSecond = 0f,
                totalTimeMs = 0,
                success = false,
                error = "Cancelled"
            )
        }
        
        try {
            val binaryRepo = BinaryRepository(context)
            
            // Try llama-bench first
            var binaryFile = binaryRepo.getLlamaBenchBinary()
            val useLlamaBench = binaryFile != null && binaryFile.exists()
            
            if (!useLlamaBench) {
                binaryFile = binaryRepo.getLlamaCliBinary()
            }
            
            if (binaryFile == null || !binaryFile.exists()) {
                return@withContext SingleResult(
                    threads = threads,
                    promptTokensPerSecond = 0f,
                    genTokensPerSecond = 0f,
                    totalTimeMs = 0,
                    success = false,
                    error = "llama-bench binary not found"
                )
            }
            
            val args = if (useLlamaBench) {
                mutableListOf(
                    binaryFile.absolutePath,
                    "-m", modelPath,
                    "-t", threads.toString(),
                    "-p", promptTokens.toString(),
                    "-n", genTokens.toString(),
                    "-r", repetitions.toString(),
                    "-mmp", "0",         // disable mmap
                    "-ngl", "0",         // CPU only
                    "--progress",        // Enable progress output
                    "-o", "md"
                )
            } else {
                mutableListOf(
                    binaryFile.absolutePath,
                    "-m", modelPath,
                    "-c", promptTokens.toString(),
                    "-t", threads.toString(),
                    "-n", genTokens.toString(),
                    "-p", "Hello",
                    "--no-mmap",
                    "-ngl", "0"
                )
            }
            
            DebugLog.log("Benchmark: Running with $threads threads")
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            pb.directory(context.filesDir)
            
            val nativeLibDir = binaryFile.parentFile
            val libDir = File(context.filesDir, "lib")
            libDir.mkdirs()
            val ldPath = "${libDir.absolutePath}:${nativeLibDir?.absolutePath ?: ""}"
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["GGML_BACKEND_PATH"] = ""
            
            val process = pb.start()
            currentProcess = process
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            
            var promptTokPerSec = 0f
            var genTokPerSec = 0f
            var line: String?
            
            while (reader.readLine().also { line = it } != null && !isCancelled) {
                val currentLine = line ?: continue
                DebugLog.log("Benchmark: $currentLine")
                
                if (useLlamaBench) {
                    // Parse progress: "llama-bench: benchmark 1/2: prompt run 2/5"
                    val progressMatch = Regex(
                        """benchmark (\d+)/(\d+):\s*(warmup\s+)?(prompt|generation)\s+run\s*(\d+)?/?(\d+)?"""
                    ).find(currentLine)
                    
                    if (progressMatch != null) {
                        val benchNum = progressMatch.groupValues[1].toIntOrNull() ?: 1
                        val benchTotal = progressMatch.groupValues[2].toIntOrNull() ?: 2
                        val isWarmup = progressMatch.groupValues[3].isNotEmpty()
                        val phase = progressMatch.groupValues[4]
                        val runNum = progressMatch.groupValues[5].toIntOrNull() ?: 0
                        val runTotal = progressMatch.groupValues[6].toIntOrNull() ?: repetitions
                        
                        val overallProgress = if (isWarmup) {
                            ((benchNum - 1).toFloat() / benchTotal.toFloat())
                        } else {
                            ((benchNum - 1).toFloat() + (runNum.toFloat() / runTotal.toFloat())) / benchTotal.toFloat()
                        }
                        
                        val phaseText = if (isWarmup) "Warmup ($phase)" else "$phase $runNum/$runTotal"
                        onProgress("Thread $threads: $phaseText", overallProgress)
                    }
                    
                    // Parse result table row
                    // Format: | model | size | params | backend | threads | mmap | test | t/s |
                    // Example: | llama 1B IQ3_S mix - 3.66 bpw | 619.37 MiB | 1.24 B | CPU | 1 | 0 | pp512 | 7.20 ± 0.88 |
                    if (currentLine.startsWith("|") && 
                        !currentLine.contains("model") && 
                        !currentLine.contains("---") && 
                        !currentLine.contains("llama-bench:") &&
                        (currentLine.contains("pp") || currentLine.contains("tg"))) {
                        
                        val parts = currentLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        DebugLog.log("Benchmark: Parsed ${parts.size} columns: $parts")
                        
                        // Find test and t/s columns (last two meaningful columns)
                        if (parts.size >= 2) {
                            // test is second-to-last, t/s is last
                            val testCol = parts[parts.size - 2]
                            val speedStr = parts[parts.size - 1]
                            
                            // Parse speed (format: "7.20 ± 0.88" or just "7.20")
                            val speed = speedStr.split("±").firstOrNull()?.trim()?.toFloatOrNull()
                            
                            DebugLog.log("Benchmark: test=$testCol, speed=$speed")
                            
                            if (speed != null) {
                                when {
                                    testCol.contains("pp") -> {
                                        promptTokPerSec = speed
                                        DebugLog.log("Benchmark: Set pp=$speed")
                                    }
                                    testCol.contains("tg") -> {
                                        genTokPerSec = speed
                                        DebugLog.log("Benchmark: Set tg=$speed")
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // llama-cli fallback parsing
                    if (currentLine.contains("prompt eval time")) {
                        Regex("""([\d.]+)\s*tokens per second""").find(currentLine)
                            ?.groupValues?.get(1)?.toFloatOrNull()?.let { promptTokPerSec = it }
                    }
                    if (currentLine.contains("eval time") && !currentLine.contains("prompt")) {
                        Regex("""([\d.]+)\s*tokens per second""").find(currentLine)
                            ?.groupValues?.get(1)?.toFloatOrNull()?.let { genTokPerSec = it }
                    }
                }
            }
            
            if (isCancelled) {
                process.destroyForcibly()
                return@withContext SingleResult(
                    threads = threads,
                    promptTokensPerSecond = 0f,
                    genTokensPerSecond = 0f,
                    totalTimeMs = System.currentTimeMillis() - startTime,
                    success = false,
                    error = "Cancelled"
                )
            }
            
            process.waitFor()
            currentProcess = null
            val totalTime = System.currentTimeMillis() - startTime
            
            DebugLog.log("Benchmark: $threads threads - pp: $promptTokPerSec, tg: $genTokPerSec")
            
            SingleResult(
                threads = threads,
                promptTokensPerSecond = promptTokPerSec,
                genTokensPerSecond = genTokPerSec,
                totalTimeMs = totalTime,
                success = genTokPerSec > 0 || promptTokPerSec > 0
            )
            
        } catch (e: Exception) {
            DebugLog.log("Benchmark error: ${e.message}")
            SingleResult(
                threads = threads,
                promptTokensPerSecond = 0f,
                genTokensPerSecond = 0f,
                totalTimeMs = System.currentTimeMillis() - startTime,
                success = false,
                error = e.message
            )
        }
    }
    
    /**
     * Run benchmarks for a range of threads and save to database.
     */
    suspend fun runFullBenchmark(
        modelPath: String,
        minThreads: Int,
        maxThreads: Int,
        promptTokens: Int = 512,
        genTokens: Int = 128,
        onProgress: (String, Float) -> Unit
    ): List<BenchmarkResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BenchmarkResult>()
        val modelName = modelPath.substringAfterLast("/")
        val totalTests = maxThreads - minThreads + 1
        
        // Reset cancel flag and set global running state
        isCancelled = false
        _isRunning.value = true
        _progressText.value = "Starting..."
        _progress.value = 0f
        
        // Acquire wakelock to prevent sleep during benchmark
        WakeLockManager.acquire(context, "Benchmark")
        
        try {
            // Start notification
            val (taskId, _) = UnifiedNotificationManager.startTaskForForeground(
                UnifiedNotificationManager.TaskType.BENCHMARK,
                "Benchmarking $modelName"
            )
            notificationTaskId = taskId
            
            // Delete old results for this model
            db.benchmarkDao().deleteResultsForModel(modelPath)
        
            for (threads in minThreads..maxThreads) {
                if (isCancelled) {
                    DebugLog.log("Benchmark: Stopping at thread $threads (cancelled)")
                    break
                }
                
                val testNum = threads - minThreads + 1
                val overallProgress = (testNum - 1).toFloat() / totalTests.toFloat()
                
                // Update global and callback progress
                _progressText.value = "Thread $threads/$maxThreads"
                _progress.value = overallProgress
                onProgress("Thread $threads/$maxThreads", overallProgress)
                UnifiedNotificationManager.updateProgress(
                    taskId,
                    overallProgress,
                    "Testing $threads threads..."
                )
                
                val result = runBenchmark(
                    modelPath = modelPath,
                    threads = threads,
                    promptTokens = promptTokens,
                    genTokens = genTokens,
                    onProgress = { status, threadProgress ->
                        val combined = overallProgress + (threadProgress / totalTests)
                        _progressText.value = status
                        _progress.value = combined
                        onProgress(status, combined)
                        UnifiedNotificationManager.updateProgress(taskId, combined, status)
                    }
                )
                
                if (result.success) {
                    val dbResult = BenchmarkResult(
                        modelPath = modelPath,
                        modelName = modelName,
                        threads = threads,
                        promptTokensPerSecond = result.promptTokensPerSecond,
                        genTokensPerSecond = result.genTokensPerSecond,
                        promptTokens = promptTokens,
                        genTokens = genTokens
                    )
                    db.benchmarkDao().insert(dbResult)
                    results.add(dbResult)
                    DebugLog.log("Benchmark: Saved result for $threads threads: pp=${result.promptTokensPerSecond}, tg=${result.genTokensPerSecond}")
                }
            }
            
            // Complete notification
            val message = if (isCancelled) "Benchmark cancelled" else "Benchmark complete!"
            UnifiedNotificationManager.completeTask(taskId, message)
            
            results
        } finally {
            // Release wakelock and reset global state
            WakeLockManager.release("Benchmark")
            _isRunning.value = false
            currentProcess = null
        }
    }
}
