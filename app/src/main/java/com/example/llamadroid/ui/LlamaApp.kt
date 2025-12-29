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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.example.llamadroid.ui.navigation.Screen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect

import com.example.llamadroid.ui.ai.AudioTranscriptionScreen
import com.example.llamadroid.ui.ai.VideoUpscalerScreen
import com.example.llamadroid.ui.models.WhisperModelsScreen
import com.example.llamadroid.ui.models.ModelShareScreen
import com.example.llamadroid.ui.notes.NotesManagerScreen
import com.example.llamadroid.ui.ai.VideoSumupScreen
import com.example.llamadroid.ui.ai.WorkflowsScreen
import com.example.llamadroid.ui.kiwix.ZimManagerScreen
import com.example.llamadroid.ui.kiwix.KiwixViewerScreen
import com.example.llamadroid.ui.distributed.DistributedScreen
import com.example.llamadroid.ui.distributed.WorkerModeScreen
import com.example.llamadroid.ui.distributed.MasterModeScreen
import com.example.llamadroid.ui.distributed.NetworkVisualizationScreen
import com.example.llamadroid.ui.settings.WelcomeScreen
import com.example.llamadroid.ui.settings.AboutScreen
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.SharedFileData
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext

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
                        "🎤 Transcribe" to Screen.AudioTranscription.route,
                        "⚙️ Transcribe + Summary Workflow" to Screen.Workflows.route
                    )
                    showShareChooser = true
                }
                // Video -> User chooses Whisper, Video Upscaler, or Workflow
                mimeType.startsWith("video/") -> {
                    shareOptions = listOf(
                        "🎬 Video Upscaler" to Screen.VideoUpscaler.route,
                        "🎤 Transcribe" to Screen.AudioTranscription.route,
                        "⚙️ Transcribe + Summary Workflow" to Screen.Workflows.route
                    )
                    showShareChooser = true
                }
                // Image -> User chooses SD img2img or upscale
                mimeType.startsWith("image/") -> {
                    shareOptions = listOf(
                        "🎨 SD img2img" to "imagegen_img2img",
                        "📐 SD Upscale" to "imagegen_upscale"
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
            title = { Text("Open with...") },
            text = {
                Column {
                    shareOptions.forEach { (label, targetId) ->
                        TextButton(
                            onClick = {
                                showShareChooser = false
                                pendingShareData?.let { data ->
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
                    Text("Cancel")
                }
            }
        )
    }
    
    // Bottom navigation items - using hubs for AI and Models, Notes has its own tab
    val items = listOf(
        Screen.Dashboard,
        Screen.AIHub,
        Screen.NotesManager,
        Screen.ModelManager,  // This now goes to Model Hub
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
                                Screen.ModelManager -> Icon(Icons.Default.Star, null)
                                Screen.Settings -> Icon(Icons.Default.Settings, null)
                                Screen.Logs -> Icon(Icons.Default.Info, null)
                                else -> Icon(Icons.Default.Home, null)
                            }
                        },
                        label = { 
                            Text(
                                when(screen) {
                                    Screen.Dashboard -> "Home"
                                    Screen.AIHub -> "AI"
                                    Screen.NotesManager -> "Notes"
                                    Screen.ModelManager -> "Models"
                                    Screen.Settings -> "Settings"
                                    Screen.Logs -> "Logs"
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
        }
    }
}
