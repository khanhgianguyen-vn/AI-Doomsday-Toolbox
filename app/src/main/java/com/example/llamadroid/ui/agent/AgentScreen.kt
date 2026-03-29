package com.example.llamadroid.ui.agent

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import androidx.navigation.NavController
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.service.StagedFileCache
import com.example.llamadroid.ui.components.ApprovalQueueDialog
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import com.example.llamadroid.data.db.AgentMessageEntity

// Removed AgentChatMessage data class as it is now in AgentService.ChatMessage

/**
 * AgentScreen - AI Coding Agent Chat Interface
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Services
    val ollamaService = remember { AgentForegroundService.getOllamaService(context) }
    val agentService = remember { AgentForegroundService.getAgentService(context) }
    val db = remember { com.example.llamadroid.data.db.AppDatabase.getDatabase(context) }

    // Initialize Ollama URL from saved settings
    remember { ollamaService.initFromSettings() }
    
    // Auto-check Ollama connection on startup to populate models list
    LaunchedEffect(Unit) {
        ollamaService.checkConnection()
    }
    
    // State - use STATIC companion object for navigation persistence
    val messages by AgentService.messages.collectAsState()
    val isLoading by AgentService.isLoading.collectAsState()
    val selectedModel by AgentService.selectedModel.collectAsState()
    val currentAgent by AgentService.currentAgent.collectAsState()
    val currentTask by AgentService.currentTask.collectAsState()
    val debugLog by AgentService.debugLog.collectAsState()
    
    // UI Local state
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    var currentConversationId by remember { mutableStateOf<Long?>(null) }
    var showConversations by remember { mutableStateOf(false) }
    val settingsRepository = remember { com.example.llamadroid.data.SettingsRepository(context) }

    // --- Core Functions ---
    fun saveCurrentConversation() {
        val convId = currentConversationId ?: return
        val currentAgentRef = AgentService.currentAgent.value
        val currentTaskRef = AgentService.currentTask.value
        scope.launch {
            db.agentChatDao().updateConversationState(
                convId, 
                currentAgentRef.name, 
                currentTaskRef
            )
            // Save messages
            val entities = AgentService.messages.value.map { msg ->
                AgentService.chatMessageToEntity(msg, convId)
            }
            db.agentChatDao().deleteAllMessagesInConversation(convId)
            db.agentChatDao().insertMessages(entities)
        }
    }
    // --- End Core Functions ---
    var showModelSelector by remember { mutableStateOf(false) }
    var showSetupInfo by remember { mutableStateOf(false) }
    var showConnectionSettings by remember { mutableStateOf(false) }
    var showDebugPanel by remember { mutableStateOf(false) }
    var showAgentSettings by remember { mutableStateOf(false) }
    val showAllOutputState by settingsRepository.showExtraOutput.collectAsState()
    var showAllOutput by remember { mutableStateOf(showAllOutputState) }
    
    // Update local state when preference changes, and vice versa
    LaunchedEffect(showAllOutputState) {
        showAllOutput = showAllOutputState
    }
    LaunchedEffect(showAllOutput) {
        if (showAllOutput != showAllOutputState) {
            settingsRepository.setShowExtraOutput(showAllOutput)
        }
    }
    var showNewProjectDialog by remember { mutableStateOf(false) } // New project name dialog
    var showCustomTools by remember { mutableStateOf(false) } // Custom Tools screen
    var showCustomAgents by remember { mutableStateOf(false) } // Custom Agents screen
    var showProjectManagement by remember { mutableStateOf(false) } // Project Management screen
    var showDeleteConfirmation by remember { mutableStateOf<Long?>(null) } // Delete confirmation dialog
    var pendingDeleteFolder by remember { mutableStateOf<String?>(null) } // Folder to delete
    var newProjectName by remember { mutableStateOf("") }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf("") }
    var pendingDenyMessage by remember { mutableStateOf<AgentService.Companion.ChatMessage?>(null) }
    var denyExplanation by remember { mutableStateOf("") }
    
    // First-run popup - show once to remind user to create project
    val prefs = remember { context.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE) }
    var showFirstRunPopup by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        if (!prefs.getBoolean("first_run_shown", false)) {
            showFirstRunPopup = true
            prefs.edit().putBoolean("first_run_shown", true).apply()
        }
    }
    
    // Remote connection state
    val isAgentConnected by AgentService.isConnected.collectAsState()
    val agentConnectionStatus by AgentService.connectionStatus.collectAsState()
    val retryMessage by AgentService.retryMessage.collectAsState()
    val isOllamaConnected by OllamaService.isConnected.collectAsState()
    val ollamaIsRecovering by OllamaService.isRecovering.collectAsState()
    val availableModelsData by OllamaService.availableModels.collectAsState()
    val ollamaHasChecked by OllamaService.hasCheckedConnection.collectAsState()
    val availableModels = availableModelsData.map { it.name }
    
    // Connection settings state
    var sshHost by remember { mutableStateOf("127.0.0.1") }
    var sshPort by remember { mutableStateOf("8023") }
    var sshUser by remember { mutableStateOf("root") }
    var sshPassword by remember { mutableStateOf("") }
    val ollamaUrl by ollamaService.baseUrl.collectAsState()
    
    // Database and conversation management
    val conversations by db.agentChatDao().getAllConversations().collectAsState(initial = emptyList())
    
    // Restore last conversation on startup
    LaunchedEffect(Unit) {
        val lastId = settingsRepository.lastAgentConversationId.value
        if (lastId != -1L) {
            currentConversationId = lastId
            // Load conversation details to restore project folder
            val conv = db.agentChatDao().getConversation(lastId)
            conv?.let { c ->
                AgentService.setCurrentProjectFolder(c.projectFolder)
                // Restore agent state if persisted
                c.lastAgentRole?.let { roleStr ->
                    try {
                        val role = AgentService.Companion.AgentRole.valueOf(roleStr)
                        AgentService.setCurrentAgent(role)
                    } catch (e: Exception) {}
                }
                AgentService.setCurrentTask(c.lastTask)
                AgentService.addDebugLog(context.getString(R.string.agent_restored_project, c.projectFolder))
            }
        }
    }
    val customTools by db.customToolDao().getEnabledTools().collectAsState(initial = emptyList())
    LaunchedEffect(customTools) {
        AgentService.setLoadedCustomTools(customTools)
    }
    
    // Load custom agents from database
    val customAgents by db.customAgentDao().getEnabledAgents().collectAsState(initial = emptyList())
    LaunchedEffect(customAgents) {
        AgentService.setLoadedCustomAgents(customAgents)
    }
    

    // Conversation Management Functions
    // Load messages when conversation changes
    LaunchedEffect(currentConversationId) {
        AgentService.setActiveConversationId(currentConversationId)
        if (currentConversationId != null) {
            // Save as last active
            settingsRepository.setLastAgentConversationId(currentConversationId!!)
            
            val entities = db.agentChatDao().getMessagesForConversationSync(currentConversationId!!)
            val restoredMessages = entities.map { AgentService.chatMessageFromEntity(it) }
            val maxSeq = restoredMessages.maxOfOrNull { it.sequenceNumber } ?: 0
            AgentService.resetMessageCounter(maxSeq)
            AgentService.setMessages(restoredMessages)
            if (restoredMessages.isNotEmpty()) {
                AgentService.addDebugLog(context.getString(R.string.agent_restored_messages, restoredMessages.size))
            }
        }
    }
    
    // Auto-save whenever messages change
    LaunchedEffect(messages) {
        if (currentConversationId != null && messages.isNotEmpty()) {
            saveCurrentConversation()
        }
    }
    
    val currentStatusText by AgentService.statusText.collectAsState()
    val promptContextSnapshot by AgentService.promptContextSnapshot.collectAsState()
    fun triggerAgent(isRedo: Boolean = false) {
        val settingsRepo = com.example.llamadroid.data.SettingsRepository(context)
        AgentService.sendMessage(context, ollamaService, settingsRepo, agentService, isRedo)
    }

    fun stopGeneration() {
        AgentService.stopAllJobs()
    }
    
    fun continueAfterToolExecution() {
        triggerAgent()
    }

    fun handleApproval(approved: Boolean, msg: AgentService.Companion.ChatMessage, denyReason: String = "") {
        if (msg.isPlan) {
            if (approved) {
                AgentService.updateMessage(msg.id) { it.copy(isPlanApproved = true) }
                AgentService.addDebugLog(context.getString(R.string.agent_plan_approved))
                AgentService.markMemoryDirty("An implementation plan was approved. Record the chosen direction in project memory before finishing.")
                
                // Send official tool result back to LLM
                AgentService.addMessage(AgentService.Companion.ChatMessage(
                    role = "tool",
                    toolName = "propose_plan",
                    toolCallId = msg.toolCallId,
                    content = context.getString(R.string.agent_plan_approved_msg)
                ))
                
                triggerAgent()
            } else {
                AgentService.updateMessage(msg.id) { it.copy(isPlanApproved = false) }
                AgentService.addDebugLog(context.getString(R.string.agent_plan_rejected))
            }
        } else if (approved) {
            AgentService.updateMessage(msg.id) { it.copy(needsApproval = false, isApproved = true) }
            val toolCall = msg.pendingToolCall ?: com.example.llamadroid.service.OllamaService.ToolCall(
                name = msg.toolName ?: "",
                arguments = msg.toolArgs ?: emptyMap(),
                id = null
            )
            val settingsRepo = com.example.llamadroid.data.SettingsRepository(context)
            AgentService.executeToolCall(context, ollamaService, settingsRepo, agentService, toolCall, isForced = true)
        } else {
            AgentService.updateMessage(msg.id) { it.copy(needsApproval = false, isApproved = false) }
            val toolName = msg.toolName ?: context.getString(R.string.agent_generic_tool)
            val denialContent = if (denyReason.isNotBlank()) {
                "DENIED by user: $toolName. Reason: $denyReason"
            } else {
                context.getString(R.string.agent_denied_execution, toolName)
            }
            AgentService.addMessage(AgentService.Companion.ChatMessage(
                role = "user",
                content = denialContent
            ))
            triggerAgent()
        }
    }
    // --- End Helper Functions ---


    fun loadConversation(convId: Long) {
        scope.launch {
            val conv = db.agentChatDao().getConversation(convId)
            if (conv != null) {
                AgentService.setCurrentProjectFolder(conv.projectFolder)
                val restoredRole = AgentService.Companion.AgentRole.values().find { it.name == conv.lastAgentRole }
                    ?: AgentService.Companion.AgentRole.ORCHESTRATOR
                AgentService.setCurrentAgent(restoredRole)
                AgentService.setCurrentTask(conv.lastTask)
            }
            val loadedEntities = db.agentChatDao().getMessagesForConversationSync(convId)
            val restoredMessages = loadedEntities.map { AgentService.chatMessageFromEntity(it) }
            val maxSeq = restoredMessages.maxOfOrNull { it.sequenceNumber } ?: 0
            AgentService.resetMessageCounter(maxSeq)
            AgentService.setMessages(restoredMessages)
            currentConversationId = convId
            showConversations = false
        }
    }

    fun createNewConversation(projectName: String = context.getString(R.string.agent_project_default_prefix) + System.currentTimeMillis()) {
        scope.launch {
            val safeName = projectName.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50).ifBlank { context.getString(R.string.agent_project_default_prefix) + System.currentTimeMillis() }
            val newId = db.agentChatDao().insertConversation(com.example.llamadroid.data.db.AgentConversationEntity(title = projectName, projectFolder = safeName))
            currentConversationId = newId
            AgentService.setCurrentProjectFolder(safeName)
            AgentService.clearMessages()
            AgentService.setCurrentAgent(AgentService.Companion.AgentRole.ORCHESTRATOR)
            AgentService.setCurrentTask(null)
            StagedFileCache.clear()
            AgentService.setActiveCustomAgent(null)
            
            if (!AgentService.isConnected.value) agentService.connect()
            if (AgentService.isConnected.value) {
                agentService.executeRawCommand("mkdir -p /workspace/$safeName/brain")
            }
            
            AgentService.addMessage(AgentService.Companion.ChatMessage(
                role = "system",
                content = context.getString(R.string.agent_ready_msg, projectName, safeName)
            ))
            showConversations = false
        }
    }

    fun deleteConversation(convId: Long, projectFolder: String? = null) {
        scope.launch {
            if (currentConversationId == convId) AgentService.stopAllJobs()
            db.agentChatDao().deleteConversationById(convId)
            if (currentConversationId == convId) {
                currentConversationId = null
                AgentService.clearMessages()
                AgentService.clearAllSessions()
            }
            if (projectFolder != null && projectFolder.isNotBlank()) {
                val safeName = projectFolder.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                agentService.runCommand("rm -rf /workspace/$safeName")
            }
        }
    }

    fun deleteMessage(id: String) = AgentService.deleteMessage(id)
    
    fun regenerateMessage(id: String) {
        AgentService.truncateHistoryAt(id)
        triggerAgent(isRedo = true)
    }

    fun editMessage(id: String, content: String) {
        editingMessageId = id
        editingText = content
    }

    fun saveEdit() {
        val id = editingMessageId ?: return
        val message = messages.find { it.id == id }
        
        if (message?.isPlan == true) {
            AgentService.handlePlanModified(context, ollamaService, settingsRepository, agentService, id, editingText)
        } else {
            AgentService.updateMessage(id) { it.copy(content = editingText) }
            AgentService.truncateHistoryAt(id, inclusive = false)
            triggerAgent()
        }
        
        editingMessageId = null
    }

    fun sendMessage() {
        if (inputText.isBlank() || isLoading) return
        val userMsg = AgentService.Companion.ChatMessage(role = "user", content = inputText.trim())
        AgentService.addMessage(userMsg)
        inputText = ""
        triggerAgent()
    }

    
    // Smart auto-scroll: only scroll if user is near the bottom
    val isNearBottom by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems == 0 || lastVisibleItem >= totalItems - 3
        }
    }
    
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && isNearBottom) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LaunchedEffect(editingMessageId, messages) {
        val targetId = editingMessageId ?: return@LaunchedEffect
        val editIndex = messages.indexOfFirst { it.id == targetId }
        if (editIndex >= 0) {
            listState.animateScrollToItem(editIndex)
        }
    }
    
    Scaffold(
        topBar = {
            AgentTopBar(
                onShowConversations = { showConversations = true },
                onShowAgentSettings = { showAgentSettings = true },
                onShowSettings = { showConnectionSettings = true },
                onShowSetupInfo = { showSetupInfo = true },
                onShowProjectManagement = { showProjectManagement = true },
                onShowCustomTools = { showCustomTools = true },
                onShowCustomAgents = { showCustomAgents = true },
                showAllOutput = showAllOutput,
                onToggleAllOutput = { showAllOutput = !showAllOutput },
                showDebugPanel = showDebugPanel,
                onToggleDebugPanel = { showDebugPanel = !showDebugPanel },
                onStopAll = { stopGeneration() },
                onNavigateToWorkspace = { navController.navigate(Screen.AgentWorkspace.route) }
            )
        },
        bottomBar = {
            if (editingMessageId == null) {
                AgentInputBar(
                    inputText = inputText,
                    onInputTextChange = { inputText = it },
                    isLoading = isLoading,
                    onSend = { sendMessage() },
                    onStop = { stopGeneration() },
                    canSend = currentAgent == AgentService.Companion.AgentRole.ORCHESTRATOR
                )
            }
        },
        floatingActionButton = {
            if (!isNearBottom && messages.isNotEmpty()) {
                androidx.compose.material3.SmallFloatingActionButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(bottom = 72.dp)
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.llama_scroll_to_bottom)
                    )
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStatusBar(
                isOllamaConnected = isOllamaConnected,
                ollamaIsRecovering = ollamaIsRecovering,
                ollamaHasChecked = ollamaHasChecked,
                agentConnectionStatus = agentConnectionStatus,
                retryMessage = retryMessage,
                onRetry = {
                    scope.launch {
                        if (!isOllamaConnected) ollamaService.checkConnection()
                        if (agentConnectionStatus == AgentService.Companion.ConnectionStatus.DISCONNECTED) {
                            agentService.connect(sshHost)
                        }
                    }
                }
            )

            AgentActivityBanner(
                statusText = currentStatusText,
                isVisible = isLoading && currentStatusText.isNotBlank()
            )

            AgentContextWindowBanner(
                snapshot = promptContextSnapshot
            )
            
            if (showDebugPanel) {
                DebugPanel(
                    debugLog = debugLog,
                    onClear = { AgentService.clearDebugLog() }
                )
            }
            
            AgentChatList(
                messages = messages,
                listState = listState,
                showAllOutput = showAllOutput,
                onApprove = { msg -> handleApproval(true, msg) },
                onDeny = { msg ->
                    pendingDenyMessage = msg
                    denyExplanation = ""
                },
                onDelete = { id -> deleteMessage(id) },
                onRegenerate = { id -> regenerateMessage(id) },
                onEdit = { id, content -> editMessage(id, content) },
                editingMessageId = editingMessageId,
                editingText = editingText,
                onEditingTextChange = { editingText = it },
                onSaveEdit = { saveEdit() },
                onCancelEdit = { editingMessageId = null },
                onToggleOutput = { id -> AgentService.toggleMessageOutput(id) },
                modifier = Modifier
                    .weight(1f)
                    .then(if (editingMessageId != null) Modifier.imePadding() else Modifier)
            )
        }
    }

    // Dialogs
    if (showModelSelector) {
        ModelSelectorDialog(
            currentModel = selectedModel,
            availableModels = availableModelsData,
            onModelSelected = { model ->
                AgentService.setSelectedModel(model)
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false },
            onPullModel = { modelName ->
                scope.launch {
                    Toast.makeText(context, context.getString(R.string.agent_downloading_model, modelName), Toast.LENGTH_SHORT).show()
                    ollamaService.pullModel(modelName) { _: String -> }
                    Toast.makeText(context, context.getString(R.string.agent_model_ready, modelName), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    if (showSetupInfo) {
        SetupInfoDialog(onDismiss = { showSetupInfo = false })
    }

    if (showConnectionSettings) {
        ConnectionSettingsDialog(
            host = sshHost,
            port = sshPort,
            user = sshUser,
            password = sshPassword,
            ollamaUrl = ollamaUrl,
            ollamaService = ollamaService,
            onHostChange = { sshHost = it },
            onPortChange = { sshPort = it },
            onUserChange = { sshUser = it },
            onPasswordChange = { sshPassword = it },
            onOllamaUrlChange = { 
                ollamaService.setBaseUrl(it)
                com.example.llamadroid.data.SettingsRepository(context).setOllamaUrl(it)
            },
            onConnect = {
                scope.launch {
                    val portInt = sshPort.toIntOrNull() ?: 8023
                    ollamaService.initFromSettings()
                    ollamaService.checkConnection()
                    agentService.connect(host = sshHost, port = portInt, username = sshUser, password = sshPassword.ifEmpty { "agent" })
                    showConnectionSettings = false
                }
            },
            onDismiss = { showConnectionSettings = false }
        )
    }

    if (showAgentSettings) {
        // Refresh models list every time the dialog opens
        LaunchedEffect(Unit) {
            ollamaService.checkConnection()
        }
        val settingsRepository = remember { com.example.llamadroid.data.SettingsRepository(context) }
        AgentSettingsDialog(
            settingsRepository = settingsRepository,
            availableModels = availableModels,
            onDismiss = { showAgentSettings = false }
        )
    }
    
    // Custom Tools Screen (full screen)
    if (showCustomTools) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCustomTools = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CustomToolsScreen(onBack = { showCustomTools = false })
        }
    }
    
    // Custom Agents Screen (full screen)
    if (showCustomAgents) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showCustomAgents = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            CustomAgentsScreen(onBack = { showCustomAgents = false })
        }
    }
    
    // Project Management Screen (Export/Import/Snapshots)
    if (showProjectManagement && currentConversationId != null) {
        val currentProjectFolder = AgentService.currentProjectFolder.collectAsState().value ?: "default"
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showProjectManagement = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            ProjectManagementScreen(
                projectFolder = currentProjectFolder,
                conversationId = currentConversationId!!,
                agentService = agentService,
                onBack = { showProjectManagement = false }
            )
        }
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text(stringResource(R.string.agent_new_project_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_new_project_desc), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = newProjectName,
                        onValueChange = { newProjectName = it },
                        label = { Text(stringResource(R.string.agent_project_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    createNewConversation(if (newProjectName.isNotBlank()) newProjectName else "project")
                    showNewProjectDialog = false
                }) { Text(stringResource(R.string.action_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }
    
    // First-run popup dialog
    if (showFirstRunPopup) {
        AlertDialog(
            onDismissRequest = { showFirstRunPopup = false },
            title = { Text(stringResource(R.string.agent_welcome_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_welcome_desc), style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.agent_welcome_step1), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.agent_welcome_step2), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.agent_welcome_step3), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.agent_welcome_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            },
            confirmButton = {
                TextButton(onClick = { showFirstRunPopup = false }) {
                    Text(stringResource(R.string.action_got_it))
                }
            }
        )
    }
    
    // Deny explanation dialog
    if (pendingDenyMessage != null) {
        AlertDialog(
            onDismissRequest = {
                // Quick deny without explanation
                handleApproval(false, pendingDenyMessage!!)
                pendingDenyMessage = null
            },
            title = { Text(stringResource(R.string.agent_deny_reason_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_deny_reason_desc), fontSize = 12.sp, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = denyExplanation,
                        onValueChange = { denyExplanation = it },
                        label = { Text(stringResource(R.string.agent_deny_reason_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    handleApproval(false, pendingDenyMessage!!, denyExplanation)
                    pendingDenyMessage = null
                    denyExplanation = ""
                }) {
                    Text(stringResource(R.string.action_deny))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    handleApproval(false, pendingDenyMessage!!)
                    pendingDenyMessage = null
                    denyExplanation = ""
                }) {
                    Text(stringResource(R.string.agent_deny_skip_reason))
                }
            }
        )
    }
    
    // Conversations drawer dialog
    if (showConversations) {
        AlertDialog(
            onDismissRequest = { showConversations = false },
            title = { Text(stringResource(R.string.agent_conversations_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // New conversation button
                    Button(
                        onClick = { 
                            newProjectName = ""
                            showNewProjectDialog = true 
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.agent_new_project_btn))
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // List of conversations
                    if (conversations.isEmpty()) {
                        Text(
                            stringResource(R.string.agent_no_conversations),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        conversations.forEach { conv ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { loadConversation(conv.id) },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (currentConversationId == conv.id) 
                                        MaterialTheme.colorScheme.primaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            conv.title,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            java.text.SimpleDateFormat("MMM dd, HH:mm")
                                                .format(java.util.Date(conv.updatedAt)),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { 
                                            showDeleteConfirmation = conv.id
                                            pendingDeleteFolder = conv.projectFolder
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            stringResource(R.string.action_delete),
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showConversations = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }
    
    // Delete confirmation dialog
    if (showDeleteConfirmation != null) {
        AlertDialog(
            onDismissRequest = { 
                showDeleteConfirmation = null
                pendingDeleteFolder = null
            },
            icon = { Icon(Icons.Default.Warning, stringResource(R.string.icon_warning_desc), tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.agent_delete_project_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.agent_delete_project_desc))
                    if (pendingDeleteFolder != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.agent_delete_files_warning),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Text(
                                "rm -rf /workspace/${pendingDeleteFolder}",
                                modifier = Modifier.padding(8.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation?.let { convId ->
                            deleteConversation(convId, pendingDeleteFolder)
                        }
                        showDeleteConfirmation = null
                        pendingDeleteFolder = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.action_delete_everything))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeleteConfirmation = null
                    pendingDeleteFolder = null
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
}
