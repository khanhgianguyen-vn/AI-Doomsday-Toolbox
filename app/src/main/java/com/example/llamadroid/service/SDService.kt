package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Service to run stable-diffusion.cpp image generation
 */
class SDService : Service() {
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Per-mode job and process tracking for independent cancellation
    private val modeJobs = mutableMapOf<SDMode, Job>()
    private val modeProcesses = mutableMapOf<SDMode, Process>()
    private var notificationTaskId: Int? = null
    
    private val _generationState = MutableStateFlow<SDGenerationState>(SDGenerationState.Idle)
    val generationState: StateFlow<SDGenerationState> = _generationState
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    
    inner class LocalBinder : Binder() {
        fun getService(): SDService = this@SDService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        // Acquire WakeLock to keep CPU running during image generation
        WakeLockManager.acquire(applicationContext, "SDService")
        DebugLog.log("[SDService] WakeLock acquired")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.IMAGE_GEN,
            "Image Generation"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        return START_NOT_STICKY
    }
    
    /**
     * Generate an image with the given configuration
     * @param useWorkflowStateHolder If true, uses isolated workflow state holders (doesn't affect main screens)
     */
    fun generate(config: SDConfig, useWorkflowStateHolder: Boolean = false, onComplete: (Result<String>) -> Unit) {
        // Get the mode-specific state holder (use workflow holders if requested)
        val modeStateHolder = if (useWorkflowStateHolder) {
            when (config.mode) {
                SDMode.TXT2IMG -> SDModeStateHolder.workflowTxt2img
                SDMode.UPSCALE -> SDModeStateHolder.workflowUpscale
                else -> SDModeStateHolder.getForMode(config.mode)
            }
        } else {
            SDModeStateHolder.getForMode(config.mode)
        }
        
        // Check if this specific mode is already generating
        if (modeStateHolder.state.value is SDGenerationState.Generating) {
            onComplete(Result.failure(IllegalStateException("${config.mode.name} generation already in progress")))
            return
        }
        
        modeJobs[config.mode] = serviceScope.launch {
            try {
                _generationState.value = SDGenerationState.Generating(0f)
                _progress.value = 0f
                modeStateHolder.updateState(SDGenerationState.Generating(0f))
                updateNotification("Generating ${config.mode.name.lowercase()}...")
                
                val result = runGeneration(config, modeStateHolder)
                
                _generationState.value = SDGenerationState.Complete(result)
                _progress.value = 1f
                modeStateHolder.updateState(SDGenerationState.Complete(result))
                updateNotification("${config.mode.name} complete")
                
                withContext(Dispatchers.Main) {
                    onComplete(Result.success(result))
                }
            } catch (e: Exception) {
                DebugLog.log("SDService: Generation failed - ${e.message}")
                _generationState.value = SDGenerationState.Error(e.message ?: "Unknown error")
                modeStateHolder.updateState(SDGenerationState.Error(e.message ?: "Unknown error"))
                withContext(Dispatchers.Main) {
                    onComplete(Result.failure(e))
                }
            }
        }
    }
    
    private suspend fun runGeneration(config: SDConfig, modeStateHolder: SDModeStateHolder): String = withContext(Dispatchers.IO) {
        // Use BinaryRepository to get the correct tier-based binary
        val binaryRepo = BinaryRepository(applicationContext)
        val sdBinary = binaryRepo.getSdBinary()
        
        if (sdBinary == null || !sdBinary.exists()) {
            throw IllegalStateException("SD binary not found")
        }
        
        // Build command arguments
        val args = mutableListOf(sdBinary.absolutePath)
        
        // Mode
        when (config.mode) {
            SDMode.TXT2IMG -> args.addAll(listOf("-M", "img_gen"))
            SDMode.IMG2IMG -> args.addAll(listOf("-M", "img_gen"))
            SDMode.UPSCALE -> args.addAll(listOf("-M", "upscale"))
        }
        
        // Model handling (different for FLUX vs regular SD)
        if (config.mode != SDMode.UPSCALE) {
            if (config.isFluxModel) {
                // FLUX uses --diffusion-model instead of -m
                args.addAll(listOf("--diffusion-model", config.modelPath))
                
                // FLUX component files (VAE, CLIP-L, T5-XXL)
                config.vaePath?.let { args.addAll(listOf("--vae", it)) }
                config.clipLPath?.let { args.addAll(listOf("--clip_l", it)) }
                config.t5xxlPath?.let { args.addAll(listOf("--t5xxl", it)) }
            } else {
                // Regular SD uses -m
                args.addAll(listOf("-m", config.modelPath))
                
                // Optional VAE for regular SD models
                config.vaePath?.let { args.addAll(listOf("--vae", it)) }
            }
        
            // Prompt (only for txt2img/img2img)
            args.addAll(listOf("-p", config.prompt))
            
            // Negative prompt
            if (config.negativePrompt.isNotBlank()) {
                args.addAll(listOf("-n", config.negativePrompt))
            }
            
            // Dimensions
            args.addAll(listOf("-W", config.width.toString()))
            args.addAll(listOf("-H", config.height.toString()))
            
            // Steps
            args.addAll(listOf("--steps", config.steps.toString()))
            
            // CFG Scale
            args.addAll(listOf("--cfg-scale", config.cfgScale.toString()))
            
            // Sampling method
            args.addAll(listOf("--sampling-method", config.samplingMethod.cliName))
            
            // Seed
            args.addAll(listOf("-s", config.seed.toString()))
            
            // ControlNet (optional)
            if (config.controlNetPath != null && config.controlImagePath != null) {
                args.addAll(listOf("--control-net", config.controlNetPath))
                args.addAll(listOf("--control-image", config.controlImagePath))
                args.addAll(listOf("--control-strength", config.controlStrength.toString()))
            }
            
            // LoRA (optional)
            if (config.loraPath != null) {
                args.addAll(listOf("--lora-model-dir", File(config.loraPath).parent ?: ""))
                val loraFilename = File(config.loraPath).name
                args.addAll(listOf("--lora", "${loraFilename}:${config.loraStrength}"))
            }
            
            // Quantization type (--type)
            if (config.quantizationType.isNotBlank()) {
                args.addAll(listOf("--type", config.quantizationType))
            }
        }
        
        // Output
        args.addAll(listOf("-o", config.outputPath))
        
        // img2img specific: input image and strength
        if (config.mode == SDMode.IMG2IMG && config.initImage != null) {
            args.addAll(listOf("-i", config.initImage))
            args.addAll(listOf("--strength", config.strength.toString()))
        }
        
        // Upscale specific: input image and upscale model
        if (config.mode == SDMode.UPSCALE) {
            if (config.initImage != null) {
                args.addAll(listOf("-i", config.initImage))
            }
            if (config.upscaleModel != null) {
                args.addAll(listOf("--upscale-model", config.upscaleModel))
            }
            args.addAll(listOf("--upscale-repeats", config.upscaleRepeats.toString()))
        }
        
        // Performance options
        if (config.threads > 0) {
            args.addAll(listOf("-t", config.threads.toString()))
        }
        
        // VAE tiling options for memory optimization
        if (config.vaeTiling) {
            args.add("--vae-tiling")
            args.addAll(listOf("--vae-tile-overlap", config.vaeTileOverlap.toString()))
            if (config.vaeTileSize.isNotBlank()) {
                args.addAll(listOf("--vae-tile-size", config.vaeTileSize))
            }
            if (config.vaeRelativeTileSize.isNotBlank()) {
                args.addAll(listOf("--vae-relative-tile-size", config.vaeRelativeTileSize))
            }
        }
        
        // Tensor type rules
        if (config.tensorTypeRules.isNotBlank()) {
            args.addAll(listOf("--tensor-type-rules", config.tensorTypeRules))
        }
        
        // Verbose for debugging
        args.add("-v")
        
        DebugLog.log("SDService: Running command: ${args.joinToString(" ")}")
        
        val pb = ProcessBuilder(args)
            .redirectErrorStream(true)
            .directory(sdBinary.parentFile)
        
        // Set library path for shared libraries
        pb.environment()["LD_LIBRARY_PATH"] = binaryRepo.getLibraryDir()
        
        modeProcesses[config.mode] = pb.start()
        val process = modeProcesses[config.mode]!!
        
        // Read output and parse progress in real-time
        val reader = process.inputStream.bufferedReader()
        var totalSteps = 20 // Default, will be updated from output
        var line: String? = reader.readLine()
        while (line != null) {
            DebugLog.log("SD: $line")
            
            // Parse progress - SD.cpp outputs: "| X/Y - Xs/it" for both steps and tiles
            // Example: "  |==>                                               | 1/25 - 7.17s/it"
            val progressMatch = Regex("""\|\s*(\d+)/(\d+)\s*-""").find(line)
            if (progressMatch != null) {
                val current = progressMatch.groupValues[1].toIntOrNull() ?: 0
                val total = progressMatch.groupValues[2].toIntOrNull() ?: 1
                totalSteps = total
                val progressValue = current.toFloat() / total.toFloat()
                _progress.value = progressValue
                _generationState.value = SDGenerationState.Generating(progressValue)
                modeStateHolder.updateState(SDGenerationState.Generating(progressValue))
                modeStateHolder.updateTotalSteps(total)
                updateNotification("${config.mode.name}: $current/$total", current, total)
            }
            
            // Also check for step X/Y format (for regular SD generation)
            val stepMatch = Regex("""step\s+(\d+)/(\d+)""", RegexOption.IGNORE_CASE).find(line)
            if (stepMatch != null) {
                val current = stepMatch.groupValues[1].toIntOrNull() ?: 0
                val total = stepMatch.groupValues[2].toIntOrNull() ?: 1
                totalSteps = total
                val progressValue = current.toFloat() / total.toFloat()
                _progress.value = progressValue
                _generationState.value = SDGenerationState.Generating(progressValue)
                modeStateHolder.updateState(SDGenerationState.Generating(progressValue))
                modeStateHolder.updateTotalSteps(total)
                updateNotification("${config.mode.name}: $current/$total", current, total)
            }
            
            line = reader.readLine()
        }
        reader.close()
        
        val exitCode = process.waitFor()
        DebugLog.log("SDService: Process exited with code $exitCode")
        
        if (exitCode != 0) {
            throw RuntimeException("SD generation failed with exit code $exitCode")
        }
        
        // Return output path
        config.outputPath
    }
    
    /**
     * Cancel generation for a specific mode
     */
    fun cancelMode(mode: SDMode) {
        modeJobs[mode]?.cancel()
        modeJobs.remove(mode)
        modeProcesses[mode]?.destroy()
        modeProcesses.remove(mode)
        
        val modeStateHolder = SDModeStateHolder.getForMode(mode)
        modeStateHolder.updateState(SDGenerationState.Idle)
        
        _generationState.value = SDGenerationState.Idle
        _progress.value = 0f
        updateNotification("Cancelled")
    }
    
    /**
     * Cancel all ongoing generations (legacy method for compatibility)
     */
    fun cancel() {
        // Cancel all modes
        SDMode.entries.forEach { mode ->
            cancelMode(mode)
        }
    }
    
    private fun updateNotification(text: String, progress: Int = -1, maxProgress: Int = 100) {
        notificationTaskId?.let {
            val normalizedProgress = if (progress >= 0) progress.toFloat() / maxProgress else 0f
            UnifiedNotificationManager.updateProgress(it, normalizedProgress, text)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Destroy all mode processes
        modeProcesses.values.forEach { it.destroy() }
        modeProcesses.clear()
        modeJobs.values.forEach { it.cancel() }
        modeJobs.clear()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        // Release WakeLock
        WakeLockManager.release("SDService")
        DebugLog.log("[SDService] WakeLock released")
    }
    
    companion object {
        // Notification handled by UnifiedNotificationManager
    }
}

/**
 * State of image generation
 */
sealed class SDGenerationState {
    object Idle : SDGenerationState()
    data class Generating(val progress: Float) : SDGenerationState()
    data class Complete(val outputPath: String) : SDGenerationState()
    data class Error(val message: String) : SDGenerationState()
}

/**
 * Enum for SD generation modes
 */
enum class SDMode {
    TXT2IMG,
    IMG2IMG,
    UPSCALE
}

/**
 * State holder for a specific SD generation mode
 * Each mode has its own instance to allow concurrent operations
 */
class SDModeStateHolder(val mode: SDMode) {
    private val _state = MutableStateFlow<SDGenerationState>(SDGenerationState.Idle)
    val state: StateFlow<SDGenerationState> = _state
    
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress
    
    private val _totalSteps = MutableStateFlow(20)
    val totalSteps: StateFlow<Int> = _totalSteps
    
    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep
    
    private val _currentPrompt = MutableStateFlow("")
    val currentPrompt: StateFlow<String> = _currentPrompt
    
    private val _generatedImages = MutableStateFlow<List<File>>(emptyList())
    val generatedImages: StateFlow<List<File>> = _generatedImages
    
    fun updateState(newState: SDGenerationState) {
        _state.value = newState
        when (newState) {
            is SDGenerationState.Generating -> {
                _progress.value = newState.progress
                _currentStep.value = (newState.progress * _totalSteps.value).toInt()
            }
            is SDGenerationState.Complete -> {
                _progress.value = 1f
                _currentStep.value = _totalSteps.value
                val outputFile = File(newState.outputPath)
                if (outputFile.exists()) {
                    _generatedImages.value = _generatedImages.value + outputFile
                }
            }
            is SDGenerationState.Idle, is SDGenerationState.Error -> {
                _progress.value = 0f
                _currentStep.value = 0
            }
        }
    }
    
    fun updateTotalSteps(total: Int) {
        _totalSteps.value = total
    }
    
    fun updatePrompt(prompt: String) {
        _currentPrompt.value = prompt
    }
    
    fun reset() {
        _state.value = SDGenerationState.Idle
        _progress.value = 0f
        _currentPrompt.value = ""
    }
    
    fun clearGallery() {
        _generatedImages.value = emptyList()
    }
    
    fun addImage(file: File) {
        if (file.exists()) {
            _generatedImages.value = _generatedImages.value + file
        }
    }
    
    fun removeImage(file: File) {
        _generatedImages.value = _generatedImages.value.filter { it.absolutePath != file.absolutePath }
    }
    
    companion object {
        // Separate instances for each mode (main screens)
        val txt2img = SDModeStateHolder(SDMode.TXT2IMG)
        val img2img = SDModeStateHolder(SDMode.IMG2IMG)
        val upscale = SDModeStateHolder(SDMode.UPSCALE)
        
        // Separate instances for workflow (isolated from main screens)
        val workflowTxt2img = SDModeStateHolder(SDMode.TXT2IMG)
        val workflowUpscale = SDModeStateHolder(SDMode.UPSCALE)
        
        fun getForMode(mode: SDMode): SDModeStateHolder = when(mode) {
            SDMode.TXT2IMG -> txt2img
            SDMode.IMG2IMG -> img2img
            SDMode.UPSCALE -> upscale
        }
        
        fun getForModeIndex(index: Int): SDModeStateHolder = when(index) {
            0 -> txt2img
            1 -> img2img
            2 -> upscale
            else -> txt2img
        }
    }
}

/**
 * Backward compatibility - delegate to txt2img by default
 */
object SDGenerationStateHolder {
    val state get() = SDModeStateHolder.txt2img.state
    val progress get() = SDModeStateHolder.txt2img.progress
    val totalSteps get() = SDModeStateHolder.txt2img.totalSteps
    val currentStep get() = SDModeStateHolder.txt2img.currentStep
    val currentPrompt get() = SDModeStateHolder.txt2img.currentPrompt
    val generatedImages get() = SDModeStateHolder.txt2img.generatedImages
    
    fun updateState(newState: SDGenerationState) = SDModeStateHolder.txt2img.updateState(newState)
    fun updateTotalSteps(total: Int) = SDModeStateHolder.txt2img.updateTotalSteps(total)
    fun updatePrompt(prompt: String) = SDModeStateHolder.txt2img.updatePrompt(prompt)
    fun reset() = SDModeStateHolder.txt2img.reset()
    fun clearGallery() = SDModeStateHolder.txt2img.clearGallery()
}

