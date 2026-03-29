package com.example.llamadroid.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.llamadroid.ui.dashboard.DashboardScreen
import com.example.llamadroid.ui.models.ModelManagerScreen
import com.example.llamadroid.ui.models.ModelHubScreen
import com.example.llamadroid.ui.chat.ChatScreen
import com.example.llamadroid.ui.chat.ChatWebViewHolder
import com.example.llamadroid.ui.settings.SettingsHubScreen
import com.example.llamadroid.ui.settings.GeneralSettingsScreen
import com.example.llamadroid.ui.settings.LLMSettingsScreen
import com.example.llamadroid.ui.settings.ImageGenSettingsScreen
import com.example.llamadroid.ui.settings.WhisperSettingsScreen
import com.example.llamadroid.ui.settings.VideoUpscalerSettingsScreen
import com.example.llamadroid.ui.settings.SystemPromptsSettingsScreen
import com.example.llamadroid.ui.settings.PDFSettingsScreen
import com.example.llamadroid.ui.logs.LogsScreen
import com.example.llamadroid.ui.pdf.PDFToolboxScreen
import com.example.llamadroid.ui.pdf.PDFSummaryScreen
import com.example.llamadroid.ui.ai.AIHubScreen
import com.example.llamadroid.ui.ai.ImageGenScreen
import com.example.llamadroid.ui.ai.SDModelsScreen
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.example.llamadroid.ui.navigation.Screen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R

import com.example.llamadroid.ui.ai.AudioTranscriptionScreen
import com.example.llamadroid.ui.ai.VideoUpscalerScreen
import com.example.llamadroid.ui.models.WhisperModelsScreen
import com.example.llamadroid.ui.models.ModelShareScreen
import com.example.llamadroid.ui.notes.NotesManagerScreen
import com.example.llamadroid.ui.ai.VideoSumupScreen
import com.example.llamadroid.ui.ai.SubtitleBurnScreen
import com.example.llamadroid.ui.ai.WorkflowsScreen
import com.example.llamadroid.ui.kiwix.ZimManagerScreen
import com.example.llamadroid.ui.kiwix.KiwixViewerScreen
import com.example.llamadroid.ui.distributed.DistributedScreen
import com.example.llamadroid.ui.distributed.WorkerModeScreen
import com.example.llamadroid.ui.distributed.MasterModeScreen
import com.example.llamadroid.ui.distributed.NetworkVisualizationScreen
import com.example.llamadroid.ui.settings.WelcomeScreen
import com.example.llamadroid.ui.settings.AboutScreen
import com.example.llamadroid.ui.settings.BenchmarkScreen
import com.example.llamadroid.ui.ai.DatasetScreen
import com.example.llamadroid.ui.ai.TermuxScreen
import com.example.llamadroid.ui.ai.TermuxWebViewScreen
import com.example.llamadroid.ui.ai.TermuxFileManagerScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.SharedFileData
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.game.TamaGameEngine
import com.example.llamadroid.tama.data.EventType
import com.example.llamadroid.tama.game.TamaAgentService
import com.example.llamadroid.tama.game.FarmRepository
import com.example.llamadroid.tama.game.FarmEngine
import com.example.llamadroid.tama.data.CropDefinitions
import com.example.llamadroid.tama.data.InventoryItem
import com.example.llamadroid.tama.data.ItemType
import com.example.llamadroid.tama.ui.TamaChatScreen
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.ui.components.AssetDownloadDialog
import com.example.llamadroid.util.AssetPackManagerUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlamaApp(
    sharedFileData: SharedFileData? = null,
    onSharedFileHandled: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    
    // Check for first run
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }
    val hasCompletedWelcome by settingsRepo.hasCompletedWelcome.collectAsState()
    var showWelcome by remember { mutableStateOf(!hasCompletedWelcome) }
    
    // Shared Tama State
    val tamaDatabase = remember { TamaDatabase.getInstance(context) }
    val farmRepository = remember { FarmRepository(tamaDatabase.farmDao()) }
    val farmEngine = remember { FarmEngine(farmRepository) }
    val tamaGameEngine = remember { TamaGameEngine(context = context, dao = tamaDatabase.tamaDao(), farmEngine = farmEngine) }
    val scope = rememberCoroutineScope()
    val tamaAgentService = remember { 
        TamaAgentService(
            context = context,
            dao = tamaDatabase.tamaDao(),
            settingsRepo = settingsRepo,
            ollamaService = OllamaService(context),
            scope = scope
        )
    }
    
    // Share intent chooser dialog
    var showShareChooser by remember { mutableStateOf(false) }
    var shareOptions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var pendingShareData by remember { mutableStateOf<SharedFileData?>(null) }
    
    // Handle shared file
    LaunchedEffect(sharedFileData) {
        sharedFileData?.let { data ->
            pendingShareData = data  // Store for later use by chooser
            val mimeType = data.mimeType
            when {
                // Audio -> User chooses Whisper or Workflow
                mimeType.startsWith("audio/") -> {
                    shareOptions = listOf(
                        context.getString(R.string.share_transcribe) to Screen.AudioTranscription.route,
                        context.getString(R.string.share_workflow) to Screen.Workflows.route
                    )
                    showShareChooser = true
                }
                // Video -> User chooses Whisper, Video Upscaler, or Workflow
                mimeType.startsWith("video/") -> {
                    shareOptions = listOf(
                        context.getString(R.string.share_upscaler) to Screen.VideoUpscaler.route,
                        context.getString(R.string.share_transcribe) to Screen.AudioTranscription.route,
                        context.getString(R.string.share_workflow) to Screen.Workflows.route
                    )
                    showShareChooser = true
                }
                // Image -> User chooses SD img2img or upscale
                mimeType.startsWith("image/") -> {
                    shareOptions = listOf(
                        context.getString(R.string.share_img2img) to "imagegen_img2img",
                        context.getString(R.string.share_upscale_sd) to "imagegen_upscale"
                    )
                    showShareChooser = true
                }
                // PDF -> PDF Toolbox (future)
                mimeType == "application/pdf" -> {
                    // TODO: Navigate to PDF Toolbox when implemented
                    onSharedFileHandled()
                }
            }
        }
    }
    
    // Share chooser dialog
    if (showShareChooser && pendingShareData != null) {
        AlertDialog(
            onDismissRequest = { 
                showShareChooser = false
                pendingShareData = null
                onSharedFileHandled()
            },
            title = { Text(stringResource(R.string.action_open_with)) },
            text = {
                Column {
                    shareOptions.forEach { (label, targetId) ->
                        TextButton(
                            onClick = {
                                showShareChooser = false
                                pendingShareData?.let { data: SharedFileData ->
                                    // Determine actual navigation route
                                    val route = when (targetId) {
                                        "imagegen_img2img", "imagegen_upscale" -> Screen.ImageGen.route
                                        else -> targetId
                                    }
                                    com.example.llamadroid.data.SharedFileHolder.setPendingFile(data.uri, data.mimeType, targetId)
                                    navController.navigate(route)
                                }
                                pendingShareData = null
                                onSharedFileHandled()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(label, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    showShareChooser = false
                    pendingShareData = null
                    onSharedFileHandled()
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Bottom navigation items
    val items = listOf(
        Screen.Dashboard,
        Screen.AIHub,
        Screen.NotesManager,
        Screen.Tama,  // Virtual pet tab
        Screen.ModelManager,
        Screen.Settings
    )
    
    // Show welcome screen on first run
    if (showWelcome && !hasCompletedWelcome) {
        WelcomeScreen(
            onComplete = {
                showWelcome = false
            }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                items.forEach { screen ->
                    // For AI Hub, also highlight when on Chat or ImageGen screens
                    val isAIRoute = screen == Screen.AIHub && 
                        currentRoute in listOf(
                            Screen.AIHub.route, Screen.Chat.route, Screen.ImageGen.route,
                            Screen.AudioTranscription.route, Screen.VideoUpscaler.route
                        )
                    
                    // For Model Hub, also highlight when on LLMModels or SDModels screens
                    val isModelRoute = screen == Screen.ModelManager && 
                        currentRoute in listOf(
                            Screen.ModelManager.route, Screen.ModelHub.route, 
                            Screen.LLMModels.route, Screen.SDModels.route, Screen.WhisperModels.route
                        )
                    
                    NavigationBarItem(
                        icon = { 
                            when(screen) {
                                Screen.Dashboard -> Icon(Icons.Default.Home, null)
                                Screen.AIHub -> Icon(Icons.Default.PlayArrow, null)
                                Screen.NotesManager -> Icon(Icons.Default.Edit, null)
                                Screen.Tama -> Icon(Icons.Default.Favorite, null)  // Heart for pet
                                Screen.ModelManager -> Icon(Icons.Default.Star, null)
                                Screen.Settings -> Icon(Icons.Default.Settings, null)
                                Screen.Logs -> Icon(Icons.Default.Info, null)
                                else -> Icon(Icons.Default.Home, null)
                            }
                        },
                        label = { 
                            Text(
                                when(screen) {
                                    Screen.Dashboard -> stringResource(R.string.nav_home)
                                    Screen.AIHub -> stringResource(R.string.nav_ai)
                                    Screen.NotesManager -> stringResource(R.string.nav_notes)
                                    Screen.Tama -> stringResource(R.string.nav_tama)
                                    Screen.ModelManager -> stringResource(R.string.nav_models)
                                    Screen.Settings -> stringResource(R.string.nav_settings)
                                    Screen.Logs -> stringResource(R.string.nav_logs)
                                    else -> ""
                                }
                            )
                        },
                        selected = currentRoute == screen.route || isAIRoute || isModelRoute,
                        onClick = {
                            // For hub screens, don't restore state - always go to hub
                            // This lets users switch between sub-screens
                            val isHubScreen = screen == Screen.AIHub || screen == Screen.ModelManager
                            val shouldRestoreState = !isHubScreen
                            
                            // ModelManager tab now goes to ModelHub
                            val targetRoute = if (screen == Screen.ModelManager) {
                                Screen.ModelHub.route
                            } else {
                                screen.route
                            }
                            
                            navController.navigate(targetRoute) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = shouldRestoreState
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController, 
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(navController) }
            composable(Screen.Settings.route) { SettingsHubScreen(navController) }
            composable(Screen.Logs.route) { LogsScreen(navController) }
            // AI screens
            composable(Screen.AIHub.route) { AIHubScreen(navController) }
            composable(Screen.Chat.route) { ChatScreen(navController) }
            composable(Screen.ImageGen.route) { ImageGenScreen(navController) }
            composable(Screen.AudioTranscription.route) { AudioTranscriptionScreen(navController) }
            composable(Screen.VideoUpscaler.route) { VideoUpscalerScreen(navController) }
            composable(Screen.SubtitleBurn.route) { SubtitleBurnScreen(navController) }
            composable(Screen.NotesManager.route) { NotesManagerScreen(navController) }
            composable(Screen.Workflows.route) { WorkflowsScreen(navController) }
            // Model screens
            composable(Screen.ModelHub.route) { ModelHubScreen(navController) }
            composable(Screen.LLMModels.route) { ModelManagerScreen(navController) }
            composable(Screen.SDModels.route) { SDModelsScreen(navController) }
            composable(Screen.WhisperModels.route) { WhisperModelsScreen(navController) }
            composable("model_share") { ModelShareScreen(navController) }
            // Settings sub-screens
            composable("settings_general") { GeneralSettingsScreen(navController) }
            composable("settings_llm") { LLMSettingsScreen(navController) }
            composable("settings_imagegen") { ImageGenSettingsScreen(navController) }
            composable("settings_whisper") { WhisperSettingsScreen(navController) }
            composable("settings_upscaler") { VideoUpscalerSettingsScreen(navController) }
            composable("settings_prompts") { SystemPromptsSettingsScreen(navController) }
            composable("settings_logs") { LogsScreen(navController) }
            // PDF screens
            composable("pdf_toolbox") { PDFToolboxScreen(navController) }
            composable("pdf_summary") { PDFSummaryScreen(navController) }
            composable("settings_pdf") { PDFSettingsScreen(navController) }
            composable("video_sumup") { VideoSumupScreen(navController) }
            composable("about") { AboutScreen(navController) }
            // Kiwix screens
            composable(Screen.ZimManager.route) { ZimManagerScreen(navController) }
            composable(
                route = "kiwix_viewer?zimPath={zimPath}",
                arguments = listOf(
                    androidx.navigation.navArgument("zimPath") {
                        type = androidx.navigation.NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val zimPath = backStackEntry.arguments?.getString("zimPath")
                KiwixViewerScreen(navController, zimPath)
            }
            // Distributed inference screens
            composable(Screen.DistributedHub.route) { DistributedScreen(navController) }
            composable(Screen.WorkerMode.route) { WorkerModeScreen(navController) }
            composable(Screen.MasterMode.route) { MasterModeScreen(navController) }
            composable(Screen.NetworkVisualization.route) { NetworkVisualizationScreen(navController) }
            // Benchmark
            composable(Screen.Benchmark.route) { BenchmarkScreen(navController) }
            // Dataset Creator
            composable(Screen.Dataset.route) { DatasetScreen(navController) }
            composable(
                Screen.DatasetProject.route,
                arguments = listOf(
                    androidx.navigation.navArgument("projectId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStackEntry ->
                val projectId = backStackEntry.arguments?.getLong("projectId") ?: 0L
                com.example.llamadroid.ui.dataset.DatasetProjectScreen(navController, projectId)
            }
            // Termux SSH
            composable(Screen.Termux.route) { TermuxScreen(navController) }
            // Termux WebView for server UIs
            composable(
                Screen.TermuxWebView.route,
                arguments = listOf(
                    androidx.navigation.navArgument("url") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("title") { type = androidx.navigation.NavType.StringType },
                    androidx.navigation.navArgument("toolId") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val url = backStackEntry.arguments?.getString("url") ?: ""
                val title = backStackEntry.arguments?.getString("title") ?: stringResource(R.string.nav_title_server)
                val toolId = backStackEntry.arguments?.getString("toolId") ?: "none"
                TermuxWebViewScreen(navController, url, title, toolId)
            }
            
            // Termux File Manager
            composable(Screen.TermuxFileManager.route) {
                TermuxFileManagerScreen(navController)
            }
            
            // FastSD Gallery
            composable(Screen.FastsdGallery.route) {
                com.example.llamadroid.ui.ai.FastsdGalleryScreen(navController)
            }
            
            // AI Agent
            composable(Screen.Agent.route) {
                com.example.llamadroid.ui.agent.AgentScreen(navController)
            }
            
            // Tama Farming
            composable(Screen.Farm.route) {
                val pet by tamaGameEngine.pet.collectAsState()
                
                // Show loading state instead of auto-navigating back to prevent navigation loop
                if (pet == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                    return@composable
                }
                
                val currentPet = pet!!  // Safe: already checked pet != null above
                com.example.llamadroid.tama.ui.FarmScreen(
                    pet = currentPet,
                    gameEngine = tamaGameEngine,
                    farmRepository = farmRepository,
                    onBack = { navController.popBackStack() }
                )
            }
            
            // Ollama Manager
            composable(Screen.OllamaManager.route) {
                com.example.llamadroid.ui.ai.ollama.OllamaManagerScreen(navController)
            }
            
            // Native Llama Client
            composable(Screen.LlamaServerList.route) {
                com.example.llamadroid.ui.ai.llama.LlamaServerListScreen(navController)
            }
            composable(Screen.LlamaChatList.route) {
                com.example.llamadroid.ui.ai.llama.LlamaChatListScreen(navController)
            }
            composable(
                route = Screen.LlamaChat.route,
                arguments = listOf(
                    androidx.navigation.navArgument("chatId") { type = androidx.navigation.NavType.LongType },
                    androidx.navigation.navArgument("serverId") { type = androidx.navigation.NavType.LongType }
                )
            ) { backStackEntry ->
                val chatId = backStackEntry.arguments?.getLong("chatId") ?: -1L
                val serverId = backStackEntry.arguments?.getLong("serverId") ?: -1L
                com.example.llamadroid.ui.ai.llama.LlamaChatScreen(navController, chatId, serverId)
            }
            
            composable(Screen.Store.route) {
                val petState by tamaGameEngine.pet.collectAsState()
                petState?.let { activePet ->
                    com.example.llamadroid.tama.ui.StoreScreen(
                        pet = activePet,
                        onBuy = { item, qty ->
                            val baseId = item.id.replace("seed_", "").replace("hoe", "wheat").replace("watering_can", "wheat") // Simple price lookup
                            val price = when {
                                item.id.startsWith("seed_") -> CropDefinitions.CROPS[baseId]?.seedPrice?.toLong() ?: 10L
                                item.id == "hoe" -> 100L
                                item.id == "watering_can" -> 150L
                                item.id == "fertilizer" -> 20L
                                else -> 5L
                            }
                            scope.launch { tamaGameEngine.buyItem(item, qty, price.toInt()) }
                        },
                        onSell = { item, qty ->
                            val baseId = item.id.replace("crop_", "")
                            val price = CropDefinitions.CROPS[baseId]?.sellPrice?.toLong() ?: 5L
                            scope.launch { tamaGameEngine.sellItem(item, qty, price) }
                        },
                        onBuyUpgrade = { type, price ->
                            scope.launch {
                                if (tamaGameEngine.spendMoney(price.toLong())) {
                                    farmRepository.buyUpgrade(activePet.id, type, price)
                                    tamaGameEngine.logEvent(activePet.id, EventType.OTHER, context.getString(R.string.event_purchased_upgrade, type.replaceFirstChar { it.uppercase() }))
                                }
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
            
            // Agent Workspace File Manager
            composable(Screen.AgentWorkspace.route) {
                com.example.llamadroid.ui.agent.AgentWorkspaceScreen(navController)
            }
            
            // Tama virtual pet
            composable(Screen.Tama.route) {
                com.example.llamadroid.tama.ui.TamaScreen(
                    navController = navController,
                    gameEngine = tamaGameEngine,
                    onChat = { navController.navigate(Screen.TamaChat.route) }
                )
            }
            
            composable(Screen.TamaChat.route) {
                TamaChatScreen(
                    navController = navController,
                    gameEngine = tamaGameEngine,
                    agentService = tamaAgentService,
                    settingsRepo = settingsRepo
                )
            }
            
            // Tama Dungeon/Adventure
            composable(Screen.Dungeon.route) {
                com.example.llamadroid.tama.ui.DungeonScreen(
                    navController = navController,
                    database = tamaDatabase,
                    settingsRepository = settingsRepo
                )
            }
            
            composable(
                Screen.Adventure.route,
                arguments = listOf(
                    androidx.navigation.navArgument("dungeonType") { type = androidx.navigation.NavType.StringType }
                )
            ) { backStackEntry ->
                val dungeonTypeName = backStackEntry.arguments?.getString("dungeonType") ?: "CHAOS_REALM"
                com.example.llamadroid.tama.ui.AdventureScreen(
                    navController = navController,
                    dungeonTypeName = dungeonTypeName,
                    database = tamaDatabase,
                    settingsRepository = settingsRepo
                )
            }
        }
    }
}
