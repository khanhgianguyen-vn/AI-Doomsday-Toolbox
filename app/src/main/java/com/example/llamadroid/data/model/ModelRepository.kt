package com.example.llamadroid.data.model

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile

import com.example.llamadroid.data.api.HfModelDto
import com.example.llamadroid.data.api.HuggingFaceService
import com.example.llamadroid.data.db.ModelDao
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File

class ModelRepository(
    private val context: Context,
    private val modelDao: ModelDao
) {
    private val settingsRepo = SettingsRepository(context)
    
    private val retrofit = Retrofit.Builder()
        .baseUrl("https://huggingface.co/api/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        
    private val hfService = retrofit.create(HuggingFaceService::class.java)

    // Use singleton progress to persist across navigation
    val downloadProgress = DownloadProgressHolder.progress

    fun getDownloadedModels(): Flow<List<ModelEntity>> = modelDao.getAllModels()
    
    fun getLLMModels(): Flow<List<ModelEntity>> = modelDao.getModelsByTypes(
        listOf(ModelType.LLM, ModelType.VISION_PROJECTOR, ModelType.EMBEDDING)
    )
    
    suspend fun searchModels(query: String, filter: String? = null): List<HfModelDto> = withContext(Dispatchers.IO) {
        try {
            // Pass filter to HuggingFace API ("gguf" for LLM, null for SD)
            hfService.searchModels(query, filter = filter, limit = 40)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    suspend fun getGgufFiles(repoId: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val info = hfService.getRepoInfo(repoId)
            info.siblings?.filter { it.rfilename.endsWith(".gguf") || it.rfilename.endsWith(".safetensors") }?.map { it.rfilename } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Get GGUF files with their sizes using the /tree/main endpoint
     */
    suspend fun getGgufFilesWithSize(repoId: String): List<FileInfo> = withContext(Dispatchers.IO) {
        try {
            // Use the /tree/main endpoint which returns actual file sizes
            val treeItems = hfService.getRepoTree(repoId)
            treeItems
                .filter { it.type == "file" && (it.path.endsWith(".gguf") || it.path.endsWith(".safetensors")) }
                .map { FileInfo(it.path, it.size) }
                .sortedByDescending { it.sizeBytes } // Show largest first
        } catch (e: Exception) {
            DebugLog.log("ModelRepository: Error fetching files: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Get all files with vision support detection
     * Returns RepoFiles with GGUF models and any associated mmproj (vision projection) files
     */
    suspend fun getFilesWithVisionSupport(repoId: String): RepoFiles = withContext(Dispatchers.IO) {
        try {
            val treeItems = hfService.getRepoTree(repoId)
            
            // Find model files (GGUF files only for LLM - llama.cpp doesn't support safetensors)
            val modelFiles = treeItems
                .filter { it.type == "file" && it.path.endsWith(".gguf") && !it.path.contains("mmproj") }
                .map { FileInfo(it.path, it.size, FileType.MODEL) }
                .sortedByDescending { it.sizeBytes }
            
            // Find vision projection files (mmproj files)
            val visionFiles = treeItems
                .filter { it.type == "file" && it.path.contains("mmproj") && it.path.endsWith(".gguf") }
                .map { FileInfo(it.path, it.size, FileType.VISION_PROJECTOR) }
                .sortedByDescending { it.sizeBytes }
            
            RepoFiles(
                modelFiles = modelFiles,
                visionFiles = visionFiles,
                hasVisionSupport = visionFiles.isNotEmpty()
            )
        } catch (e: Exception) {
            DebugLog.log("ModelRepository: Error fetching files with vision support: ${e.message}")
            RepoFiles(emptyList(), emptyList(), false)
        }
    }
    
    /**
     * Get the model directory based on storage settings and model type.
     * Uses app's external files directory if enabled, otherwise internal storage.
     * Note: Native binaries (llama-cli, sd) can only access app-specific directories.
     */
    fun getModelDir(type: ModelType): File {
        val useExternalStorage = settingsRepo.modelStorageUri.value != null
        
        // Get subfolder based on type
        val subfolder = when (type) {
            ModelType.LLM, ModelType.VISION_PROJECTOR, ModelType.EMBEDDING, ModelType.VISION, ModelType.MMPROJ -> "llm"
            ModelType.SD_CHECKPOINT, ModelType.SD_UPSCALER -> "sd/checkpoints"
            ModelType.SD_DIFFUSION -> "sd/flux"
            ModelType.SD_CLIP_L -> "sd/clip_l"
            ModelType.SD_T5XXL -> "sd/t5xxl"
            ModelType.SD_VAE -> "sd/vae"  
            ModelType.SD_LORA -> "sd/lora"
            ModelType.SD_CONTROLNET -> "sd/controlnet"
            ModelType.WHISPER -> "whisper"
        }
        
        if (useExternalStorage) {
            // Use app's external files directory - accessible to native binaries
            // Path: /storage/emulated/0/Android/data/com.example.llamadroid/files/models/...
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val folder = File(externalDir, "models/$subfolder")
                if (folder.exists() || folder.mkdirs()) {
                    DebugLog.log("ModelRepository: Using external: ${folder.absolutePath}")
                    return folder
                }
            }
        }
        
        // Fallback: internal storage
        val internalSubfolder = when (type) {
            ModelType.WHISPER -> "whisper_models"
            else -> "models"
        }
        return File(context.filesDir, internalSubfolder).apply { mkdirs() }
    }
    
    // We will assume a standard URL structure for GGUF files for the sake of the MVP
    // User would select a specific quantization
    suspend fun downloadModel(repoId: String, filename: String, type: ModelType) {
        val modelUrl = "https://huggingface.co/$repoId/resolve/main/$filename"
        val modelDir = getModelDir(type)
        val destFile = File(modelDir, filename)
        
        // Use unique progress key: repoId for models, repoId:mmproj for vision projectors
        val progressKey = if (type == ModelType.VISION_PROJECTOR) "$repoId:mmproj" else repoId
        
        // Track progress under unique key for UI display
        DownloadProgressHolder.updateProgress(progressKey, filename, 0f)
        
        // Start foreground service for background downloads with notification
        // Must be called on main thread for foreground service
        withContext(kotlinx.coroutines.Dispatchers.Main) {
            com.example.llamadroid.service.DownloadService.startDownload(
                context = context,
                url = modelUrl,
                destPath = destFile.absolutePath,
                filename = filename
            )
        }
        
        // Monitor progress from DownloadProgressHolder (updated by DownloadService)
        // Wait for completion (progress reaches 1.0 or -1.0 for error)
        var lastProgress = 0f
        while (true) {
            kotlinx.coroutines.delay(500) // Check every 500ms
            val progressMap = DownloadProgressHolder.progress.value
            // Check by progressKey (set by us)
            val progress = progressMap[progressKey] ?: 0f
            
            if (progress != lastProgress) {
                lastProgress = progress
                DownloadProgressHolder.updateProgress(progressKey, progress)
            }
            
            if (progress >= 1f) {
                // Download complete - save to DB
                val entity = ModelEntity(
                    filename = filename,
                    path = destFile.absolutePath,
                    sizeBytes = destFile.length(), 
                    type = type,
                    repoId = repoId,
                    isDownloaded = true
                )
                modelDao.insertModel(entity)
                DownloadProgressHolder.removeProgress(progressKey)
                DebugLog.log("ModelRepository: Saved $filename to DB as $type")
                break
            } else if (progress < 0f) {
                // Download failed
                DownloadProgressHolder.removeProgress(progressKey)
                DebugLog.log("ModelRepository: Download failed for $filename")
                break
            }
        }
    }
    
    /**
     * Start a download without waiting for completion.
     * Use this for SD models where the dialog closes immediately.
     * The download service will handle saving to DB via DownloadCompletionReceiver.
     */
    fun startDownloadAsync(repoId: String, filename: String, type: ModelType) {
        val modelUrl = "https://huggingface.co/$repoId/resolve/main/$filename"
        val modelDir = getModelDir(type)
        val destFile = File(modelDir, filename)
        
        // Use unique progress key
        val progressKey = repoId
        
        // Track progress under unique key for UI display
        DownloadProgressHolder.updateProgress(progressKey, filename, 0f)
        
        // Store pending download info so DownloadService can save to DB on completion
        PendingDownloadHolder.addPending(filename, repoId, type, destFile.absolutePath)
        
        // Start foreground service (this is called from main thread via onClick)
        com.example.llamadroid.service.DownloadService.startDownload(
            context = context,
            url = modelUrl,
            destPath = destFile.absolutePath,
            filename = filename
        )
        
        DebugLog.log("ModelRepository: Started async download for $filename")
    }
    
    suspend fun deleteModel(model: ModelEntity) {
        val file = File(model.path)
        if (file.exists()) file.delete()
        modelDao.deleteModel(model)
    }
    
    suspend fun insertModel(model: ModelEntity) {
        modelDao.insertModel(model)
    }
}

// Singleton to persist download progress across navigation
object DownloadProgressHolder {
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress = _progress.asStateFlow()
    
    // Track filename for each repoId for cancellation
    private val filenameMap = mutableMapOf<String, String>()
    
    fun updateProgress(repoId: String, filename: String, value: Float) {
        filenameMap[repoId] = filename
        _progress.value = _progress.value.toMutableMap().apply { put(repoId, value) }
    }
    
    /** Update by repoId only (when filename already tracked) */
    fun updateProgress(repoId: String, value: Float) {
        _progress.value = _progress.value.toMutableMap().apply { put(repoId, value) }
    }
    
    /** Find repoId by filename (for service callback) */
    fun findRepoIdByFilename(filename: String): String? {
        return filenameMap.entries.find { it.value == filename }?.key
    }
    
    fun removeProgress(repoId: String) {
        filenameMap.remove(repoId)
        _progress.value = _progress.value.toMutableMap().apply { remove(repoId) }
    }
    
    fun getFilename(repoId: String): String? = filenameMap[repoId]
}

/**
 * Type of file in the repository
 */
enum class FileType {
    MODEL,           // Main GGUF model file
    VISION_PROJECTOR // mmproj file for vision support
}

/**
 * Simple data class for file info with size
 */
data class FileInfo(
    val filename: String,
    val sizeBytes: Long,
    val type: FileType = FileType.MODEL
) {
    fun formattedSize(): String {
        return when {
            sizeBytes >= 1_000_000_000 -> String.format("%.2f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format("%.1f MB", sizeBytes / 1_000_000.0)
            sizeBytes >= 1_000 -> String.format("%.0f KB", sizeBytes / 1_000.0)
            else -> "$sizeBytes B"
        }
    }
    
    val isVisionProjector: Boolean get() = type == FileType.VISION_PROJECTOR
}

/**
 * Contains files from a repository with vision support information
 */
data class RepoFiles(
    val modelFiles: List<FileInfo>,
    val visionFiles: List<FileInfo>,
    val hasVisionSupport: Boolean
)

/**
 * Holds pending download info for async downloads
 */
data class PendingDownload(
    val filename: String,
    val repoId: String,
    val type: com.example.llamadroid.data.db.ModelType,
    val destPath: String
)

object PendingDownloadHolder {
    private val pendingDownloads = mutableMapOf<String, PendingDownload>()
    
    fun addPending(filename: String, repoId: String, type: com.example.llamadroid.data.db.ModelType, destPath: String) {
        pendingDownloads[filename] = PendingDownload(filename, repoId, type, destPath)
    }
    
    fun getPending(filename: String): PendingDownload? = pendingDownloads[filename]
    
    fun removePending(filename: String) {
        pendingDownloads.remove(filename)
    }
}

