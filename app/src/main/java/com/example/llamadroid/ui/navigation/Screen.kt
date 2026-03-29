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
    // Settings screens
    object Benchmark : Screen("benchmark")               // Thread benchmark tool
    object Dataset : Screen("dataset")                   // Dataset creation tool (project list)
    object DatasetProject : Screen("dataset_project/{projectId}") {
        fun createRoute(projectId: Long): String = "dataset_project/$projectId"
    }
    object Termux : Screen("termux")                     // Termux SSH integration
    object TermuxWebView : Screen("termux_webview/{url}/{title}/{toolId}") {
        fun createRoute(url: String, title: String, toolId: String = "none"): String {
            val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            return "termux_webview/$encodedUrl/$encodedTitle/$toolId"
        }
    }
    object TermuxFileManager : Screen("termux_file_manager")  // File manager for Termux tools
    object FastsdGallery : Screen("fastsd_gallery")           // FastSD CPU generated images gallery
    // AI Agent screens
    object Agent : Screen("agent")                             // AI coding agent chat
    object AgentWorkspace : Screen("agent_workspace")          // Agent workspace file manager
    // Tama virtual pet
    object Tama : Screen("tama")                               // Virtual pet companion
    object TamaChat : Screen("tama_chat")                     // AI Chat with pet
    object Farm : Screen("farm")                               // Farming grid
    object Store : Screen("store")                             // Farm supply store
    object SubtitleBurn : Screen("subtitle_burn")              // Subtitle burning tool
    object Dungeon : Screen("dungeon")                         // Dungeon selection
    object Adventure : Screen("adventure/{dungeonType}") {     // Text adventure
        fun createRoute(dungeonType: String): String = "adventure/$dungeonType"
    }
    object OllamaManager : Screen("ollama_manager")            // Ollama server/model manager
    
    // Native Llama Client
    object LlamaServerList : Screen("llama_server_list")       // Server management (entry point)
    object LlamaChatList : Screen("llama_chat_list")           // Chat history
    object LlamaChat : Screen("llama_chat/{chatId}/{serverId}") { // Chat interface
        fun createRoute(chatId: Long, serverId: Long): String = "llama_chat/$chatId/$serverId"
    }
}
