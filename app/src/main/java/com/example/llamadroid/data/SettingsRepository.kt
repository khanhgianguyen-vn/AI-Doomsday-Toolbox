package com.example.llamadroid.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("llamadroid_settings", Context.MODE_PRIVATE)
    
    // Selected LLM Model Path
    private val _selectedModelPath = MutableStateFlow(prefs.getString("selected_model_path", null))
    val selectedModelPath = _selectedModelPath.asStateFlow()
    
    fun setSelectedModelPath(path: String?) {
        prefs.edit().putString("selected_model_path", path).apply()
        _selectedModelPath.value = path
    }
    
    // Selected Embedding Model Path
    private val _selectedEmbeddingModelPath = MutableStateFlow(prefs.getString("selected_embedding_model_path", null))
    val selectedEmbeddingModelPath = _selectedEmbeddingModelPath.asStateFlow()
    
    fun setSelectedEmbeddingModelPath(path: String?) {
        prefs.edit().putString("selected_embedding_model_path", path).apply()
        _selectedEmbeddingModelPath.value = path
    }
    
    // Context Size
    private val _contextSize = MutableStateFlow(prefs.getInt("context_size", 8192))
    val contextSize = _contextSize.asStateFlow()
    
    fun setContextSize(size: Int) {
        prefs.edit().putInt("context_size", size).apply()
        _contextSize.value = size
    }
    
    // Threads
    private val _threads = MutableStateFlow(prefs.getInt("threads", 4))
    val threads = _threads.asStateFlow()
    
    fun setThreads(count: Int) {
        prefs.edit().putInt("threads", count).apply()
        _threads.value = count
    }
    
    // Temperature
    private val _temperature = MutableStateFlow(prefs.getFloat("temperature", 0.7f))
    val temperature = _temperature.asStateFlow()
    
    fun setTemperature(temp: Float) {
        prefs.edit().putFloat("temperature", temp).apply()
        _temperature.value = temp
    }
    
    // Remote Access (allow connections from other devices)
    private val _remoteAccess = MutableStateFlow(prefs.getBoolean("remote_access", false))
    val remoteAccess = _remoteAccess.asStateFlow()
    
    fun setRemoteAccess(enabled: Boolean) {
        prefs.edit().putBoolean("remote_access", enabled).apply()
        _remoteAccess.value = enabled
    }
    
    // Enable Vision (load mmproj for vision models)
    private val _enableVision = MutableStateFlow(prefs.getBoolean("enable_vision", true))
    val enableVision = _enableVision.asStateFlow()
    
    fun setEnableVision(enabled: Boolean) {
        prefs.edit().putBoolean("enable_vision", enabled).apply()
        _enableVision.value = enabled
    }
    
    // Selected mmproj (vision projector) path
    private val _selectedMmprojPath = MutableStateFlow(prefs.getString("selected_mmproj_path", null))
    val selectedMmprojPath = _selectedMmprojPath.asStateFlow()
    
    fun setSelectedMmprojPath(path: String?) {
        prefs.edit().putString("selected_mmproj_path", path).apply()
        _selectedMmprojPath.value = path
    }
    
    // Selected system prompt ID (for saved prompts feature)
    private val _selectedPromptId = MutableStateFlow(prefs.getLong("selected_prompt_id", -1L))
    val selectedPromptId = _selectedPromptId.asStateFlow()
    
    fun setSelectedPromptId(id: Long) {
        prefs.edit().putLong("selected_prompt_id", id).apply()
        _selectedPromptId.value = id
    }
    
    // Output folder for generated images (null = default app folder)
    private val _outputFolderUri = MutableStateFlow(prefs.getString("output_folder_uri", null))
    val outputFolderUri = _outputFolderUri.asStateFlow()
    
    fun setOutputFolderUri(uri: String?) {
        prefs.edit().putString("output_folder_uri", uri).apply()
        _outputFolderUri.value = uri
    }
    
    // ========== Stable Diffusion Thread Settings ==========
    
    // SD txt2img threads
    private val _sdTxt2imgThreads = MutableStateFlow(prefs.getInt("sd_txt2img_threads", 4))
    val sdTxt2imgThreads = _sdTxt2imgThreads.asStateFlow()
    
    fun setSdTxt2imgThreads(count: Int) {
        prefs.edit().putInt("sd_txt2img_threads", count).apply()
        _sdTxt2imgThreads.value = count
    }
    
    // SD img2img threads
    private val _sdImg2imgThreads = MutableStateFlow(prefs.getInt("sd_img2img_threads", 4))
    val sdImg2imgThreads = _sdImg2imgThreads.asStateFlow()
    
    fun setSdImg2imgThreads(count: Int) {
        prefs.edit().putInt("sd_img2img_threads", count).apply()
        _sdImg2imgThreads.value = count
    }
    
    // SD upscale threads  
    private val _sdUpscaleThreads = MutableStateFlow(prefs.getInt("sd_upscale_threads", 4))
    val sdUpscaleThreads = _sdUpscaleThreads.asStateFlow()
    
    fun setSdUpscaleThreads(count: Int) {
        prefs.edit().putInt("sd_upscale_threads", count).apply()
        _sdUpscaleThreads.value = count
    }
    
    // ========== WhisperCPP Settings ==========
    
    private val _whisperThreads = MutableStateFlow(prefs.getInt("whisper_threads", 4))
    val whisperThreads = _whisperThreads.asStateFlow()
    
    fun setWhisperThreads(count: Int) {
        prefs.edit().putInt("whisper_threads", count).apply()
        _whisperThreads.value = count
    }
    
    private val _whisperOutputFolder = MutableStateFlow(prefs.getString("whisper_output_folder", null))
    val whisperOutputFolder = _whisperOutputFolder.asStateFlow()
    
    fun setWhisperOutputFolder(uri: String?) {
        prefs.edit().putString("whisper_output_folder", uri).apply()
        _whisperOutputFolder.value = uri
    }
    
    // ========== Video Upscaler Settings ==========
    
    // Load threads for loading frames (default 1 for less lag)
    private val _upscalerLoadThreads = MutableStateFlow(prefs.getInt("upscaler_load_threads", 1))
    val upscalerLoadThreads = _upscalerLoadThreads.asStateFlow()
    fun setUpscalerLoadThreads(count: Int) {
        prefs.edit().putInt("upscaler_load_threads", count).apply()
        _upscalerLoadThreads.value = count
    }
    
    // Process threads for upscaling (default 1 for less lag)
    private val _upscalerProcThreads = MutableStateFlow(prefs.getInt("upscaler_proc_threads", 1))
    val upscalerProcThreads = _upscalerProcThreads.asStateFlow()
    fun setUpscalerProcThreads(count: Int) {
        prefs.edit().putInt("upscaler_proc_threads", count).apply()
        _upscalerProcThreads.value = count
    }
    
    // Save threads for saving frames (default 1 for less lag)
    private val _upscalerSaveThreads = MutableStateFlow(prefs.getInt("upscaler_save_threads", 1))
    val upscalerSaveThreads = _upscalerSaveThreads.asStateFlow()
    fun setUpscalerSaveThreads(count: Int) {
        prefs.edit().putInt("upscaler_save_threads", count).apply()
        _upscalerSaveThreads.value = count
    }
    
    private val _upscalerOutputFolder = MutableStateFlow(prefs.getString("upscaler_output_folder", null))
    val upscalerOutputFolder = _upscalerOutputFolder.asStateFlow()
    
    fun setUpscalerOutputFolder(uri: String?) {
        prefs.edit().putString("upscaler_output_folder", uri).apply()
        _upscalerOutputFolder.value = uri
    }
    
    // ========== PDF Summary Settings ==========
    
    // PDF Summary model path (separate from chat model)
    private val _pdfModelPath = MutableStateFlow(prefs.getString("pdf_model_path", null))
    val pdfModelPath = _pdfModelPath.asStateFlow()
    fun setPdfModelPath(path: String?) {
        prefs.edit().putString("pdf_model_path", path).apply()
        _pdfModelPath.value = path
    }
    
    // PDF Summary context size
    private val _pdfContextSize = MutableStateFlow(prefs.getInt("pdf_context_size", 4096))
    val pdfContextSize = _pdfContextSize.asStateFlow()
    fun setPdfContextSize(size: Int) {
        prefs.edit().putInt("pdf_context_size", size).apply()
        _pdfContextSize.value = size
    }
    
    // PDF Summary threads
    private val _pdfThreads = MutableStateFlow(prefs.getInt("pdf_threads", 4))
    val pdfThreads = _pdfThreads.asStateFlow()
    fun setPdfThreads(count: Int) {
        prefs.edit().putInt("pdf_threads", count).apply()
        _pdfThreads.value = count
    }
    
    // PDF Summary temperature
    private val _pdfTemperature = MutableStateFlow(prefs.getFloat("pdf_temperature", 0.3f))
    val pdfTemperature = _pdfTemperature.asStateFlow()
    fun setPdfTemperature(temp: Float) {
        prefs.edit().putFloat("pdf_temperature", temp).apply()
        _pdfTemperature.value = temp
    }
    
    // PDF Summary max tokens
    private val _pdfMaxTokens = MutableStateFlow(prefs.getInt("pdf_max_tokens", 1024))
    val pdfMaxTokens = _pdfMaxTokens.asStateFlow()
    fun setPdfMaxTokens(tokens: Int) {
        prefs.edit().putInt("pdf_max_tokens", tokens).apply()
        _pdfMaxTokens.value = tokens
    }
    
    // PDF Summary system prompt (for summarizing each chunk)
    private val _pdfSummaryPrompt = MutableStateFlow(prefs.getString("pdf_summary_prompt", null))
    val pdfSummaryPrompt = _pdfSummaryPrompt.asStateFlow()
    fun setPdfSummaryPrompt(prompt: String?) {
        prefs.edit().putString("pdf_summary_prompt", prompt).apply()
        _pdfSummaryPrompt.value = prompt
    }
    
    // PDF Unification system prompt (for combining summaries)
    private val _pdfUnificationPrompt = MutableStateFlow(prefs.getString("pdf_unification_prompt", null))
    val pdfUnificationPrompt = _pdfUnificationPrompt.asStateFlow()
    fun setPdfUnificationPrompt(prompt: String?) {
        prefs.edit().putString("pdf_unification_prompt", prompt).apply()
        _pdfUnificationPrompt.value = prompt
    }
    
    // Welcome screen completion flag
    private val _hasCompletedWelcome = MutableStateFlow(prefs.getBoolean("has_completed_welcome", false))
    val hasCompletedWelcome = _hasCompletedWelcome.asStateFlow()
    
    fun setHasCompletedWelcome(completed: Boolean) {
        prefs.edit().putBoolean("has_completed_welcome", completed).apply()
        _hasCompletedWelcome.value = completed
    }
    
    // Selected language (system, en, es)
    private val _selectedLanguage = MutableStateFlow(prefs.getString("selected_language", "system") ?: "system")
    val selectedLanguage = _selectedLanguage.asStateFlow()
    
    fun setSelectedLanguage(lang: String) {
        prefs.edit().putString("selected_language", lang).apply()
        _selectedLanguage.value = lang
    }
    
    // External Model Storage URI (SAF)
    private val _modelStorageUri = MutableStateFlow(prefs.getString("model_storage_uri", null))
    val modelStorageUri = _modelStorageUri.asStateFlow()
    
    fun setModelStorageUri(uri: String?) {
        prefs.edit().putString("model_storage_uri", uri).apply()
        _modelStorageUri.value = uri
    }
    
    // ZIM Files Storage URI (SAF) for Kiwix
    private val _zimFolderUri = MutableStateFlow(prefs.getString("zim_folder_uri", null))
    val zimFolderUri = _zimFolderUri.asStateFlow()
    
    fun setZimFolderUri(uri: String?) {
        prefs.edit().putString("zim_folder_uri", uri).apply()
        _zimFolderUri.value = uri
    }
    
    // Kiwix Server LAN Visibility (like LLM remote access)
    private val _kiwixRemoteAccess = MutableStateFlow(prefs.getBoolean("kiwix_remote_access", false))
    val kiwixRemoteAccess = _kiwixRemoteAccess.asStateFlow()
    
    fun setKiwixRemoteAccess(enabled: Boolean) {
        prefs.edit().putBoolean("kiwix_remote_access", enabled).apply()
        _kiwixRemoteAccess.value = enabled
    }
    
    // ========== KV Cache Quantization Settings ==========
    
    // LLM Server (llama-server) KV Cache settings
    private val _serverKvCacheEnabled = MutableStateFlow(prefs.getBoolean("server_kv_cache_enabled", false))
    val serverKvCacheEnabled = _serverKvCacheEnabled.asStateFlow()
    fun setServerKvCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("server_kv_cache_enabled", enabled).apply()
        _serverKvCacheEnabled.value = enabled
    }
    
    private val _serverKvCacheTypeK = MutableStateFlow(prefs.getString("server_kv_cache_type_k", "f16") ?: "f16")
    val serverKvCacheTypeK = _serverKvCacheTypeK.asStateFlow()
    fun setServerKvCacheTypeK(type: String) {
        prefs.edit().putString("server_kv_cache_type_k", type).apply()
        _serverKvCacheTypeK.value = type
    }
    
    private val _serverKvCacheTypeV = MutableStateFlow(prefs.getString("server_kv_cache_type_v", "f16") ?: "f16")
    val serverKvCacheTypeV = _serverKvCacheTypeV.asStateFlow()
    fun setServerKvCacheTypeV(type: String) {
        prefs.edit().putString("server_kv_cache_type_v", type).apply()
        _serverKvCacheTypeV.value = type
    }
    
    private val _serverKvCacheReuse = MutableStateFlow(prefs.getInt("server_kv_cache_reuse", 0))
    val serverKvCacheReuse = _serverKvCacheReuse.asStateFlow()
    fun setServerKvCacheReuse(tokens: Int) {
        prefs.edit().putInt("server_kv_cache_reuse", tokens).apply()
        _serverKvCacheReuse.value = tokens
    }
    
    // PDF AI (llama-cli) KV Cache settings
    private val _pdfKvCacheEnabled = MutableStateFlow(prefs.getBoolean("pdf_kv_cache_enabled", false))
    val pdfKvCacheEnabled = _pdfKvCacheEnabled.asStateFlow()
    fun setPdfKvCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("pdf_kv_cache_enabled", enabled).apply()
        _pdfKvCacheEnabled.value = enabled
    }
    
    private val _pdfKvCacheTypeK = MutableStateFlow(prefs.getString("pdf_kv_cache_type_k", "f16") ?: "f16")
    val pdfKvCacheTypeK = _pdfKvCacheTypeK.asStateFlow()
    fun setPdfKvCacheTypeK(type: String) {
        prefs.edit().putString("pdf_kv_cache_type_k", type).apply()
        _pdfKvCacheTypeK.value = type
    }
    
    private val _pdfKvCacheTypeV = MutableStateFlow(prefs.getString("pdf_kv_cache_type_v", "f16") ?: "f16")
    val pdfKvCacheTypeV = _pdfKvCacheTypeV.asStateFlow()
    fun setPdfKvCacheTypeV(type: String) {
        prefs.edit().putString("pdf_kv_cache_type_v", type).apply()
        _pdfKvCacheTypeV.value = type
    }
    
    // Video Sumup (llama-cli) KV Cache settings
    private val _videoKvCacheEnabled = MutableStateFlow(prefs.getBoolean("video_kv_cache_enabled", false))
    val videoKvCacheEnabled = _videoKvCacheEnabled.asStateFlow()
    fun setVideoKvCacheEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("video_kv_cache_enabled", enabled).apply()
        _videoKvCacheEnabled.value = enabled
    }
    
    private val _videoKvCacheTypeK = MutableStateFlow(prefs.getString("video_kv_cache_type_k", "f16") ?: "f16")
    val videoKvCacheTypeK = _videoKvCacheTypeK.asStateFlow()
    fun setVideoKvCacheTypeK(type: String) {
        prefs.edit().putString("video_kv_cache_type_k", type).apply()
        _videoKvCacheTypeK.value = type
    }
    
    private val _videoKvCacheTypeV = MutableStateFlow(prefs.getString("video_kv_cache_type_v", "f16") ?: "f16")
    val videoKvCacheTypeV = _videoKvCacheTypeV.asStateFlow()
    fun setVideoKvCacheTypeV(type: String) {
        prefs.edit().putString("video_kv_cache_type_v", type).apply()
        _videoKvCacheTypeV.value = type
    }
    
    // File Server settings
    private val _fileServerFolderUri = MutableStateFlow(prefs.getString("file_server_folder_uri", null))
    val fileServerFolderUri = _fileServerFolderUri.asStateFlow()
    fun setFileServerFolderUri(uri: String?) {
        prefs.edit().putString("file_server_folder_uri", uri).apply()
        _fileServerFolderUri.value = uri
    }
    
    private val _fileServerPort = MutableStateFlow(prefs.getInt("file_server_port", 9111))
    val fileServerPort = _fileServerPort.asStateFlow()
    fun setFileServerPort(port: Int) {
        prefs.edit().putInt("file_server_port", port).apply()
        _fileServerPort.value = port
    }

    // ========== Stable Diffusion Memory Settings ==========
    
    private val _sdVaeTiling = MutableStateFlow(prefs.getBoolean("sd_vae_tiling", false))
    val sdVaeTiling = _sdVaeTiling.asStateFlow()
    fun setSdVaeTiling(enabled: Boolean) {
        prefs.edit().putBoolean("sd_vae_tiling", enabled).apply()
        _sdVaeTiling.value = enabled
    }

    private val _sdVaeTileOverlap = MutableStateFlow(prefs.getFloat("sd_vae_tile_overlap", 0.5f))
    val sdVaeTileOverlap = _sdVaeTileOverlap.asStateFlow()
    fun setSdVaeTileOverlap(overlap: Float) {
        prefs.edit().putFloat("sd_vae_tile_overlap", overlap).apply()
        _sdVaeTileOverlap.value = overlap
    }

    private val _sdVaeTileSize = MutableStateFlow(prefs.getString("sd_vae_tile_size", "32x32") ?: "32x32")
    val sdVaeTileSize = _sdVaeTileSize.asStateFlow()
    fun setSdVaeTileSize(size: String) {
        prefs.edit().putString("sd_vae_tile_size", size).apply()
        _sdVaeTileSize.value = size
    }

    private val _sdVaeRelativeTileSize = MutableStateFlow(prefs.getString("sd_vae_relative_tile_size", "") ?: "")
    val sdVaeRelativeTileSize = _sdVaeRelativeTileSize.asStateFlow()
    fun setSdVaeRelativeTileSize(size: String) {
        prefs.edit().putString("sd_vae_relative_tile_size", size).apply()
        _sdVaeRelativeTileSize.value = size
    }

    private val _sdTensorTypeRules = MutableStateFlow(prefs.getString("sd_tensor_type_rules", "") ?: "")
    val sdTensorTypeRules = _sdTensorTypeRules.asStateFlow()
    fun setSdTensorTypeRules(rules: String) {
        prefs.edit().putString("sd_tensor_type_rules", rules).apply()
        _sdTensorTypeRules.value = rules
    }

    // FLUX Reminder persistence
    private val _showFluxReminderPending = MutableStateFlow(prefs.getBoolean("show_flux_reminder_pending", false))
    val showFluxReminderPending = _showFluxReminderPending.asStateFlow()
    fun setShowFluxReminderPending(pending: Boolean) {
        prefs.edit().putBoolean("show_flux_reminder_pending", pending).apply()
        _showFluxReminderPending.value = pending
    }

    /**
     * Check if external model storage is configured
     */
    fun isExternalStorageConfigured(): Boolean = _modelStorageUri.value != null
    
    /**
     * Model type enum for folder organization
     */
    enum class ModelType {
        LLM,
        SD_CHECKPOINT,
        SD_VAE,
        SD_LORA,
        WHISPER
    }
    
    /**
     * Get the subfolder name for each model type
     */
    fun getSubfolderForType(type: ModelType): String = when (type) {
        ModelType.LLM -> "llm"
        ModelType.SD_CHECKPOINT -> "sd/checkpoints"
        ModelType.SD_VAE -> "sd/vae"
        ModelType.SD_LORA -> "sd/lora"
        ModelType.WHISPER -> "whisper"
    }
    
    // ========== Distributed Inference Layer Distribution Memory ==========
    
    /**
     * Store the last layer distribution for a model (JSON: {workerId: layerProportion})
     * This allows reusing the same distribution to take advantage of worker caches.
     * Format: JSON object like {"worker1:50052": 0.6, "worker2:50052": 0.4}
     */
    private val _lastLayerDistribution = MutableStateFlow(prefs.getString("last_layer_distribution", null))
    val lastLayerDistribution = _lastLayerDistribution.asStateFlow()
    
    fun setLastLayerDistribution(distribution: String?) {
        prefs.edit().putString("last_layer_distribution", distribution).apply()
        _lastLayerDistribution.value = distribution
    }
    
    /**
     * Store the model path that the layer distribution was calculated for.
     * If the model changes, the distribution should be recalculated.
     */
    private val _lastDistributionModelPath = MutableStateFlow(prefs.getString("last_distribution_model_path", null))
    val lastDistributionModelPath = _lastDistributionModelPath.asStateFlow()
    
    fun setLastDistributionModelPath(path: String?) {
        prefs.edit().putString("last_distribution_model_path", path).apply()
        _lastDistributionModelPath.value = path
    }
    
    /**
     * Clear distribution memory (forces recalculation on next run)
     */
    fun clearLayerDistributionMemory() {
        setLastLayerDistribution(null)
        setLastDistributionModelPath(null)
    }
    
    // ========== Workflow Settings (Transcribe+Summary) ==========
    
    private val _workflowWhisperModelPath = MutableStateFlow(prefs.getString("workflow_whisper_model", null))
    val workflowWhisperModelPath = _workflowWhisperModelPath.asStateFlow()
    
    fun setWorkflowWhisperModelPath(path: String?) {
        prefs.edit().putString("workflow_whisper_model", path).apply()
        _workflowWhisperModelPath.value = path
    }
    
    private val _workflowLlmModelPath = MutableStateFlow(prefs.getString("workflow_llm_model", null))
    val workflowLlmModelPath = _workflowLlmModelPath.asStateFlow()
    
    fun setWorkflowLlmModelPath(path: String?) {
        prefs.edit().putString("workflow_llm_model", path).apply()
        _workflowLlmModelPath.value = path
    }
    
    private val _workflowWhisperThreads = MutableStateFlow(prefs.getInt("workflow_whisper_threads", 4))
    val workflowWhisperThreads = _workflowWhisperThreads.asStateFlow()
    
    fun setWorkflowWhisperThreads(count: Int) {
        prefs.edit().putInt("workflow_whisper_threads", count).apply()
        _workflowWhisperThreads.value = count
    }
    
    private val _workflowLlmThreads = MutableStateFlow(prefs.getInt("workflow_llm_threads", 4))
    val workflowLlmThreads = _workflowLlmThreads.asStateFlow()
    
    fun setWorkflowLlmThreads(count: Int) {
        prefs.edit().putInt("workflow_llm_threads", count).apply()
        _workflowLlmThreads.value = count
    }
    
    private val _workflowWhisperLanguage = MutableStateFlow(prefs.getString("workflow_whisper_language", "auto") ?: "auto")
    val workflowWhisperLanguage = _workflowWhisperLanguage.asStateFlow()
    
    fun setWorkflowWhisperLanguage(lang: String) {
        prefs.edit().putString("workflow_whisper_language", lang).apply()
        _workflowWhisperLanguage.value = lang
    }
    
    private val _workflowContext = MutableStateFlow(prefs.getInt("workflow_context", 2048))
    val workflowContext = _workflowContext.asStateFlow()
    
    fun setWorkflowContext(size: Int) {
        prefs.edit().putInt("workflow_context", size).apply()
        _workflowContext.value = size
    }
    
    private val _workflowTemperature = MutableStateFlow(prefs.getFloat("workflow_temperature", 0.7f))
    val workflowTemperature = _workflowTemperature.asStateFlow()
    
    fun setWorkflowTemperature(temp: Float) {
        prefs.edit().putFloat("workflow_temperature", temp).apply()
        _workflowTemperature.value = temp
    }
    
    private val _workflowMaxTokens = MutableStateFlow(prefs.getInt("workflow_max_tokens", 300))
    val workflowMaxTokens = _workflowMaxTokens.asStateFlow()
    
    fun setWorkflowMaxTokens(tokens: Int) {
        prefs.edit().putInt("workflow_max_tokens", tokens).apply()
        _workflowMaxTokens.value = tokens
    }
}
