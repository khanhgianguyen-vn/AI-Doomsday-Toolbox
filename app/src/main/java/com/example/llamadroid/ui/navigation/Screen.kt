package com.example.llamadroid.ui.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object ModelManager : Screen("models")       // Now goes to Model Hub
    object Chat : Screen("chat")
    object Settings : Screen("settings")
    object Logs : Screen("logs")
    // AI screens
    object AIHub : Screen("ai_hub")              // Landing page for AI features
    object ImageGen : Screen("image_gen")        // Stable Diffusion image generation
    object AudioTranscription : Screen("audio_transcription") // WhisperCPP
    object VideoUpscaler : Screen("video_upscaler")           // Real-ESRGAN video upscaling
    object NotesManager : Screen("notes_manager")             // Unified notes manager
    object Workflows : Screen("workflows")                     // AI Workflows (Sequential operations)
    // Model screens
    object ModelHub : Screen("model_hub")        // Landing page for model management
    object LLMModels : Screen("llm_models")      // LlamaCpp model management
    object SDModels : Screen("sd_models")        // SD model management
    object WhisperModels : Screen("whisper_models") // Whisper model management
    // Kiwix screens
    object KiwixHub : Screen("kiwix_hub")        // Landing page for Kiwix
    object ZimManager : Screen("zim_manager")    // ZIM file management
    object KiwixViewer : Screen("kiwix_viewer")  // WebView for Kiwix content
    // Distributed inference screens
    object DistributedHub : Screen("distributed")        // Role selection (master/worker)
    object WorkerMode : Screen("distributed_worker")     // Worker configuration
    object MasterMode : Screen("distributed_master")     // Master with worker list
    object NetworkVisualization : Screen("distributed_network") // Network visualization
}
