package com.example.llamadroid.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import com.example.llamadroid.util.DebugLog

class ProcessController {
    
    private var process: Process? = null
    private val _logs = MutableStateFlow<String>("")
    val logs = _logs.asStateFlow()
    
    // Flag to distinguish user-initiated stop from error
    @Volatile
    var stoppedIntentionally = false
        private set
    
    suspend fun start(binaryPath: String, config: LlamaConfig, filesDir: File) = withContext(Dispatchers.IO) {
        stoppedIntentionally = false
        if (process?.isAlive == true) stop()
        
        val args = mutableListOf(
            binaryPath,
            "-m", config.modelPath,
            "-c", config.contextSize.toString(),
            "-t", config.threads.toString(),
            "--port", config.port.toString(),
            "--host", config.host
        )
        
        // Add vision model projector if available
        if (config.mmprojPath != null) {
            args.add("--mmproj")
            args.add(config.mmprojPath)
            DebugLog.log("ProcessController: Vision model with mmproj: ${config.mmprojPath}")
        }
        
        if (config.isEmbedding) {
            args.add("--embedding")
        } else {
             // Chat specific params
             args.add("--temp")
             args.add(config.temperature.toString())
        }
        
        // Add KV cache quantization flags if enabled
        if (config.kvCacheEnabled) {
            args.add("--cache-type-k")
            args.add(config.kvCacheTypeK)
            args.add("--cache-type-v")
            args.add(config.kvCacheTypeV)
            if (config.kvCacheReuse > 0) {
                args.add("--cache-reuse")
                args.add(config.kvCacheReuse.toString())
            }
            DebugLog.log("ProcessController: KV cache enabled - K=${config.kvCacheTypeK}, V=${config.kvCacheTypeV}, reuse=${config.kvCacheReuse}")
        }
        
        // Add RPC workers for distributed inference
        if (config.rpcWorkers.isNotEmpty()) {
            val rpcArg = config.rpcWorkers.joinToString(",")
            args.add("--rpc")
            args.add(rpcArg)
            // Disable automatic memory fitting for distributed inference - it can cause SIGSEGV
            args.add("--fit")
            args.add("off")
            
            // Use -ngl to specify how many layers to offload to RPC workers
            if (config.nGpuLayers > 0) {
                args.add("-ngl")
                args.add(config.nGpuLayers.toString())
            }
            
            // Use -ts to split the offloaded layers among multiple workers
            // Only needed when there are 2+ workers
            if (!config.tensorSplit.isNullOrEmpty() && config.rpcWorkers.size > 1) {
                args.add("-ts")
                args.add(config.tensorSplit)
            }
            
            DebugLog.log("ProcessController: Distributed mode - workers: $rpcArg, ngl: ${config.nGpuLayers}" +
                if (config.rpcWorkers.size > 1 && !config.tensorSplit.isNullOrEmpty()) 
                    ", tensor-split: ${config.tensorSplit}" else "")
        }
        
        try {
            DebugLog.log("ProcessController: Starting binary: $binaryPath")
            DebugLog.log("ProcessController: Args: ${args.joinToString(" ")}")
            
            // Create a lib directory with symlinks for versioned libraries
            val libDir = File(filesDir, "lib")
            libDir.mkdirs()
            
            val nativeLibDir = File(binaryPath).parentFile
            setupLibrarySymlinks(nativeLibDir, libDir)
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            
            // Set working directory to app's files dir (like Termux does)
            pb.directory(filesDir)
            
            // Set LD_LIBRARY_PATH to include both native lib dir and our symlink dir
            val ldPath = "${libDir.absolutePath}:${nativeLibDir?.absolutePath ?: ""}"
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            DebugLog.log("ProcessController: LD_LIBRARY_PATH=$ldPath")
            
            // Set environment variables like Termux does
            pb.environment()["HOME"] = filesDir.absolutePath
            pb.environment()["PWD"] = filesDir.absolutePath
            pb.environment()["TMPDIR"] = filesDir.absolutePath
            pb.environment()["PREFIX"] = filesDir.absolutePath
            // Try to prevent backend loading by setting empty path
            pb.environment()["GGML_BACKEND_PATH"] = ""
            DebugLog.log("ProcessController: Working dir=${filesDir.absolutePath}")
            
            process = pb.start()
            
            // Start log consumer
            val reader = BufferedReader(InputStreamReader(process!!.inputStream))
            var line: String?
            var modelLoaded = false
            while (reader.readLine().also { line = it } != null) {
                _logs.value = line ?: ""
                Log.d("LlamaServer", line ?: "")
                DebugLog.log("Server: ${line ?: ""}")
                
                // Parse loading progress from server output
                val currentLine = line ?: ""
                
                // Detect model loading (llama.cpp outputs loading progress)
                if (currentLine.contains("loading model")) {
                    LlamaService.Companion.updateState(ServerState.Loading(-1f, "Loading model..."))
                }
                
                // Detect tensor loading progress (e.g., "llm_load_tensors: tensor")
                if (currentLine.contains("llm_load_tensors") && !modelLoaded) {
                    LlamaService.Companion.updateState(ServerState.Loading(-1f, "Loading tensors..."))
                }
                
                // Detect warming up
                if (currentLine.contains("warming up")) {
                    LlamaService.Companion.updateState(ServerState.Loading(-1f, "Warming up model..."))
                }
                
                // Detect server ready (listening)
                if (currentLine.contains("listening on") || currentLine.contains("HTTP server") || currentLine.contains("server listening")) {
                    modelLoaded = true
                    LlamaService.Companion.updateState(ServerState.Running(config.port))
                    DebugLog.log("ProcessController: Server is ready and listening on port ${config.port}")
                }
            }
            
            // Process exited
            val exitCode = process?.waitFor() ?: -1
            DebugLog.log("ProcessController: Process exited with code $exitCode")
        } catch (e: Exception) {
            DebugLog.log("ProcessController: FAILED - ${e.message}")
            Log.e("ProcessController", "Failed to start", e)
            throw e
        }
    }
    
    /**
     * Create symlinks for versioned library names (.so.0 -> .so)
     */
    private fun setupLibrarySymlinks(sourceDir: File?, targetDir: File) {
        if (sourceDir == null) return
        
        val librariesToLink = listOf(
            "libmtmd.so" to "libmtmd.so.0",
            "libllama.so" to "libllama.so.0",
            "libggml.so" to "libggml.so.0",
            "libggml-cpu.so" to "libggml-cpu.so.0",
            "libggml-base.so" to "libggml-base.so.0"
        )
        
        for ((sourceName, linkName) in librariesToLink) {
            val sourceFile = File(sourceDir, sourceName)
            val linkFile = File(targetDir, linkName)
            
            if (sourceFile.exists()) {
                try {
                    // Delete existing link/file
                    if (linkFile.exists()) {
                        linkFile.delete()
                    }
                    
                    // Create symlink using Runtime.exec (Android doesn't have Files.createSymbolicLink in older APIs)
                    val result = Runtime.getRuntime().exec(arrayOf("ln", "-sf", sourceFile.absolutePath, linkFile.absolutePath)).waitFor()
                    
                    if (result == 0 && linkFile.exists()) {
                        DebugLog.log("ProcessController: Created symlink ${linkFile.name} -> ${sourceFile.name}")
                    } else {
                        // Fallback: copy the file if symlink fails
                        DebugLog.log("ProcessController: Symlink failed, copying ${sourceName} to ${linkName}")
                        sourceFile.copyTo(linkFile, overwrite = true)
                    }
                } catch (e: Exception) {
                    DebugLog.log("ProcessController: Error creating link for $sourceName: ${e.message}")
                    // Try copying as fallback
                    try {
                        sourceFile.copyTo(linkFile, overwrite = true)
                    } catch (copyError: Exception) {
                        DebugLog.log("ProcessController: Copy also failed: ${copyError.message}")
                    }
                }
            } else {
                DebugLog.log("ProcessController: Source library not found: ${sourceFile.absolutePath}")
            }
        }
    }
    
    fun stop() {
        stoppedIntentionally = true
        process?.destroy()
        process = null
    }
    
    fun isAlive(): Boolean = process?.isAlive == true
}
