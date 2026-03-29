package com.example.llamadroid.ui.ai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R
import androidx.navigation.NavController
import com.example.llamadroid.data.model.TermuxTool
import com.example.llamadroid.data.model.TermuxTools
import com.example.llamadroid.data.model.ToolInfoCards
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.SSHService
import com.example.llamadroid.service.SSHConfig
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch

/**
 * Termux Doomsday Tools Screen
 * Install, uninstall, and run AI tools in Termux proot-distro
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxScreen(navController: NavController) {
    val context = LocalContext.current
    val sshService = remember { SSHService(context) }
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    
    // Connection state
    val isConnected by SSHService.isConnected.collectAsState()
    val sshConfig by SSHService.config.collectAsState()
    
    var host by remember { mutableStateOf<String>(sshConfig.host) }
    var port by remember { mutableStateOf<String>(sshConfig.port.toString()) }
    var username by remember { mutableStateOf<String>(sshConfig.user) }
    var password by remember { mutableStateOf<String>(sshConfig.password) }
    
    var showPassword by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionError by remember { mutableStateOf<String?>(null) }
    
    // Command execution state (global - persists across navigation)
    val output by SSHService.output.collectAsState()
    val isExecuting by SSHService.isExecuting.collectAsState()
    val currentCommand by SSHService.currentCommand.collectAsState()
    
    // Per-tool output state
    val toolOutputs by SSHService.toolOutputs.collectAsState()
    val toolExecuting by SSHService.toolExecuting.collectAsState()
    
    // Confirmation dialog state
    var showConfirmDialog by remember { mutableStateOf(false) }
    var pendingCommand by remember { mutableStateOf("") }
    var pendingCommandDescription by remember { mutableStateOf("") }
    var pendingToolId by remember { mutableStateOf<String?>(null) }  // Track which tool requested the command
    
    // Run All dialog state
    var showRunAllDialog by remember { mutableStateOf(false) }
    var pendingCommands by remember { mutableStateOf(listOf<String>()) }
    var pendingToolName by remember { mutableStateOf("") }
    
    // UI state
    var showSetupGuide by remember { mutableStateOf(false) }
    var expandedCard by remember { mutableStateOf<String?>(null) }
    var expandedToolOutput by remember { mutableStateOf<Set<String>>(emptySet()) }  // Track expanded tool outputs
    
    // Tool running states (GLOBAL persistence)
    val runningTools by SSHService.runningTools.collectAsState()
    
    // Trigger for immediate poll (increment to force a poll)
    var pollTrigger by remember { mutableStateOf(0) }
    
    // Periodic connection health check (every 3 seconds)
    LaunchedEffect(Unit) {
        while (true) {
            SSHService.checkConnection()
            kotlinx.coroutines.delay(3000)
        }
    }
    
    // Periodic server status polling (every 10 seconds or on trigger)
    LaunchedEffect(isConnected, pollTrigger) {
        if (isConnected) {
            while (true) {
                // Check each tool by testing if port responds
                val newRunning = mutableSetOf<String>()
                TermuxTools.allTools.forEach { tool ->
                    if (tool.port > 0) {
                        try {
                            val result = SSHService.executeQuiet(TermuxTools.getStatusCommand(tool.id))
                            if (result?.trim() == "running") {
                                newRunning.add(tool.id)
                            }
                        } catch (e: Exception) {
                            // Ignore errors during status check
                        }
                    }
                }
                SSHService.setRunningTools(newRunning)
                kotlinx.coroutines.delay(10000)  // Wait 10 seconds
            }
        }
    }
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("command", text))
        Toast.makeText(context, context.getString(R.string.termux_copy_toast), Toast.LENGTH_SHORT).show()
    }
    
    fun requestExecute(command: String, description: String = "Execute command", toolId: String? = null) {
        pendingCommand = command
        pendingCommandDescription = description
        pendingToolId = toolId
        showConfirmDialog = true
    }
    
    fun requestRunAll(toolName: String, commands: List<String>) {
        pendingToolName = toolName
        pendingCommands = commands
        showRunAllDialog = true
    }
    
    fun executeCommand(command: String) {
        if (!isConnected) return
        scope.launch {
            sshService.executeCommandStreaming(command)
        }
    }
    
    fun executeCommandForTool(toolId: String, command: String) {
        if (!isConnected) return
        // Auto-expand output for this tool
        expandedToolOutput = expandedToolOutput + toolId
        scope.launch {
            sshService.executeCommandForTool(toolId, command)
        }
    }
    
    fun connect() {
        val p = port.toIntOrNull() ?: 8022
        isConnecting = true
        connectionError = null
        
        // Save to persistent config
        SSHService.setConfig(host, p, username, password)
        
        scope.launch {
            sshService.connect(SSHConfig(host, p, username, password)).onSuccess {
                isConnecting = false
            }.onFailure { e ->
                isConnecting = false
                connectionError = e.message
            }
        }
    }
    
    fun executeAllCommands(commands: List<String>, toolName: String) {
        if (!isConnected) return
        // Use persistent scope so installation continues even if navigating away
        sshService.launchSequentialCommandsPersistent(commands, toolName)
    }
    
    // Confirmation Dialog
    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text(stringResource(R.string.termux_confirm_command)) },
            text = {
                Column {
                    Text(pendingCommandDescription, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
                    ) {
                        SelectionContainer {
                            Text(
                                pendingCommand,
                                modifier = Modifier.padding(8.dp),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = Color.Green
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        val toolId = pendingToolId
                        if (toolId != null) {
                            executeCommandForTool(toolId, pendingCommand)
                        } else {
                            executeCommand(pendingCommand)
                        }
                        pendingToolId = null
                    }
                ) {
                    Text(stringResource(R.string.termux_execute))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showConfirmDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Run All Confirmation Dialog with Edit option
    var isEditingCommands by remember { mutableStateOf(false) }
    var editedCommandsText by remember { mutableStateOf("") }
    
    if (showRunAllDialog) {
        AlertDialog(
            onDismissRequest = { 
                showRunAllDialog = false
                isEditingCommands = false
            },
            title = { Text(if (isEditingCommands) stringResource(R.string.termux_edit_commands) else stringResource(R.string.termux_install_tool, pendingToolName)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (isEditingCommands) {
                        Text(stringResource(R.string.termux_edit_commands_hint), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editedCommandsText,
                            onValueChange = { editedCommandsText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        )
                    } else {
                        Text(stringResource(R.string.termux_run_all_summary, pendingCommands.size), fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        pendingCommands.forEachIndexed { index, cmd ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Text(
                                    "${index + 1}. ${cmd.take(60)}${if (cmd.length > 60) "..." else ""}",
                                    modifier = Modifier.padding(6.dp),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = Color.Green
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (isEditingCommands) {
                    Button(
                        onClick = {
                            val editedCommands = editedCommandsText.lines().filter { it.isNotBlank() }
                            showRunAllDialog = false
                            isEditingCommands = false
                            executeAllCommands(editedCommands, pendingToolName)
                        }
                    ) {
                        Text(stringResource(R.string.termux_run_edited))
                    }
                } else {
                    Row {
                        OutlinedButton(
                            onClick = { 
                                editedCommandsText = pendingCommands.joinToString("\n")
                                isEditingCommands = true 
                            }
                        ) {
                            Text(stringResource(R.string.action_edit))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                showRunAllDialog = false
                                executeAllCommands(pendingCommands, pendingToolName)
                            }
                        ) {
                            Text(stringResource(R.string.termux_run_all))
                        }
                    }
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { 
                    showRunAllDialog = false
                    isEditingCommands = false
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.termux_title)) },
                navigationIcon = {
                    IconButton(onClick = { 
                        // Removed disconnect() call to keep session alive across navigation
                        navController.popBackStack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showSetupGuide = !showSetupGuide }) {
                        Icon(Icons.Default.Info, stringResource(R.string.termux_setup_guide))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Setup Guide
            if (showSetupGuide) {
                item {
                    SetupGuideCard(onCopy = { copyToClipboard(it) })
                }
            }
            
            // WebView behavior info
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.termux_webui_info),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            // Connection Card
            item {
                ConnectionCard(
                    host = host,
                    port = port,
                    username = username,
                    password = password,
                    showPassword = showPassword,
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    connectionError = connectionError,
                    onHostChange = { host = it },
                    onPortChange = { port = it },
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onTogglePassword = { showPassword = !showPassword },
                    onConnect = {
                        scope.launch {
                            isConnecting = true
                            connectionError = null
                            val config = SSHConfig(
                                host = host,
                                port = port.toIntOrNull() ?: 22,
                                user = username,
                                password = password
                            )
                            sshService.connect(config).onFailure { e ->
                                connectionError = e.message ?: "Connection failed"
                            }
                            isConnecting = false
                        }
                    },
                    onDisconnect = {
                        sshService.disconnect()
                        SSHService.clearOutput()
                    }
                )
            }
            
            // Only show tools when connected
            if (isConnected) {
                // Miniconda Prerequisites Card (install once)
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_miniconda_title),
                        subtitle = stringResource(R.string.termux_miniconda_subtitle),
                        isExpanded = expandedCard == "miniconda",
                        onToggle = { expandedCard = if (expandedCard == "miniconda") null else "miniconda" },
                        containerColor = Color(0xFFFF9800).copy(alpha = 0.15f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.termux_miniconda_warning),
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFFF9800)
                            )
                            
                            // Run All Miniconda commands button
                            Button(
                                onClick = { requestRunAll("Miniconda", TermuxTools.MINICONDA_INSTALL_COMMANDS) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                            ) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.termux_install_miniconda, TermuxTools.MINICONDA_INSTALL_COMMANDS.size))
                            }
                            
                            val setupDesc = stringResource(R.string.termux_setup_description)
                            TermuxTools.MINICONDA_INSTALL_COMMANDS.forEach { cmd ->
                                CommandRow(
                                    command = cmd,
                                    onCopy = { copyToClipboard(it) },
                                    onExecute = { requestExecute(it, setupDesc) }
                                )
                            }
                        }
                    }
                }
                
                // Install Card
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_install_tools_title),
                        subtitle = stringResource(R.string.termux_install_tools_subtitle),
                        isExpanded = expandedCard == "install",
                        onToggle = { expandedCard = if (expandedCard == "install") null else "install" },
                        containerColor = Color(0xFF2E7D32).copy(alpha = 0.1f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Individual tools (with Run All button)
                            TermuxTools.allTools.forEach { tool ->
                                val installDesc = stringResource(R.string.termux_install_tool, tool.name)
                                ToolInstallItem(
                                    tool = tool,
                                    onCopy = { copyToClipboard(it) },
                                    onExecute = { cmd -> requestExecute(cmd, installDesc) },
                                    onRunAll = { requestRunAll(tool.name, tool.installCommands) }
                                )
                            }
                        }
                    }
                }
                
                // Uninstall Card
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_remove_tools_title),
                        subtitle = stringResource(R.string.termux_remove_tools_subtitle),
                        isExpanded = expandedCard == "uninstall",
                        onToggle = { expandedCard = if (expandedCard == "uninstall") null else "uninstall" },
                        containerColor = Color(0xFFC62828).copy(alpha = 0.1f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Uninstall all
                            Text(stringResource(R.string.termux_remove_all), fontWeight = FontWeight.Medium)
                            val removeAllConfirm = stringResource(R.string.termux_remove_all_confirm)
                            CommandRow(
                                command = TermuxTools.UNINSTALL_ALL,
                                onCopy = { copyToClipboard(it) },
                                onExecute = { requestExecute(it, removeAllConfirm) },
                                isDanger = true
                            )
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                            Text(stringResource(R.string.termux_remove_individual), fontWeight = FontWeight.Medium)
                            
                            // Individual uninstalls
                            TermuxTools.allTools.forEach { tool ->
                                val removeToolDesc = stringResource(R.string.termux_remove_tool_description, tool.name)
                                ToolUninstallItem(
                                    tool = tool,
                                    onCopy = { copyToClipboard(TermuxTools.getUninstallCommand(tool)) },
                                    onExecute = { requestExecute(TermuxTools.getUninstallCommand(tool), removeToolDesc) }
                                )
                            }
                        }
                    }
                }
                
                // Run Card
                item {
                    ToolsCard(
                        title = stringResource(R.string.termux_run_servers_title),
                        subtitle = stringResource(R.string.termux_run_servers_subtitle),
                        isExpanded = expandedCard == "run",
                        onToggle = { 
                            val newState = if (expandedCard == "run") null else "run"
                            expandedCard = newState
                            if (newState == "run") pollTrigger++  // Trigger immediate poll when opening
                        },
                        containerColor = Color(0xFF1565C0).copy(alpha = 0.1f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TermuxTools.allTools.forEach { tool ->
                                val isRunning = runningTools.contains(tool.id)
                                val toolOutput = toolOutputs[tool.id] ?: ""
                                val isToolExecuting = toolExecuting.contains(tool.id)
                                val isOutputExpanded = expandedToolOutput.contains(tool.id)
                                
                                // Use key() to ensure proper state per tool, with local state for immediate UI updates
                                key(tool.id) {
                                    var isNetworkVisible by remember { 
                                        mutableStateOf(settingsRepository.getToolNetworkVisible(tool.id)) 
                                    }
                                    
                                    val startToolDesc = stringResource(R.string.termux_start_tool_description, tool.name)
                                    val stopToolDesc = stringResource(R.string.termux_stop_tool_description, tool.name)
                                    
                                    Column {
                                        ToolRunItem(
                                            tool = tool,
                                            isRunning = isRunning,
                                            isNetworkVisible = isNetworkVisible,
                                            onVisibilityChange = { enabled ->
                                                // Update local state first for immediate UI response
                                                isNetworkVisible = enabled
                                                // Persist to settings
                                                settingsRepository.setToolNetworkVisible(tool.id, enabled)
                                                // For A1111, run sed command to update webui-user.sh
                                                if (tool.id == "a1111") {
                                                    scope.launch {
                                                        sshService.executeCommand(TermuxTools.getA1111VisibilityCommand(enabled))
                                                    }
                                                }
                                            },
                                            onStart = { 
                                                // Get visibility-aware run command
                                                val runCmd = TermuxTools.getRunCommand(tool.id, isNetworkVisible)
                                                
                                                // For A1111, also run sed pre-command
                                                if (tool.id == "a1111") {
                                                    val sedCmd = TermuxTools.getA1111VisibilityCommand(isNetworkVisible)
                                                    requestExecute("$sedCmd && $runCmd", startToolDesc, tool.id)
                                                } else {
                                                    requestExecute(runCmd, startToolDesc, tool.id)
                                                }
                                                // Trigger poll after a short delay for server to start
                                                scope.launch {
                                                    kotlinx.coroutines.delay(3000)
                                                    pollTrigger++
                                                }
                                            },
                                            onStop = {
                                                requestExecute(TermuxTools.getStopCommand(tool.id), stopToolDesc, tool.id)
                                                // Trigger poll after a short delay for server to stop
                                                scope.launch {
                                                    kotlinx.coroutines.delay(2000)
                                                    pollTrigger++
                                                }
                                            },
                                            onOpenWebView = {
                                                val url = "http://127.0.0.1:${tool.port}"
                                                navController.navigate(Screen.TermuxWebView.createRoute(url, tool.name, tool.id))
                                            },
                                            onOpenGallery = {
                                                navController.navigate(Screen.FastsdGallery.route)
                                            }
                                        )
                                        
                                        // Collapsible output card for this tool
                                        if (toolOutput.isNotBlank() || isToolExecuting) {
                                            ToolOutputCard(
                                                toolName = tool.name,
                                                output = toolOutput,
                                                isExecuting = isToolExecuting,
                                                isExpanded = isOutputExpanded,
                                                onToggle = {
                                                    expandedToolOutput = if (isOutputExpanded) {
                                                        expandedToolOutput - tool.id
                                                    } else {
                                                        expandedToolOutput + tool.id
                                                    }
                                                },
                                                onClear = { SSHService.clearToolOutput(tool.id) },
                                                onCopy = { copyToClipboard(toolOutput) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                
                // Model Manager Card
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navController.navigate(Screen.TermuxFileManager.route) },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📂", fontSize = 32.sp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.termux_file_manager_title), fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.termux_file_manager_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(Icons.Default.KeyboardArrowRight, "Open")
                        }
                    }
                }
                
                // Output
                if (output.isNotBlank() || isExecuting) {
                    item {
                        OutputCard(
                            output = output,
                            isExecuting = isExecuting,
                            currentCommand = currentCommand,
                            onClear = { SSHService.clearOutput() },
                            onCopyAll = { copyToClipboard(it) }
                        )
                    }
                }
            }
            
            // Manual Command (always available when connected)
            if (isConnected) {
                item {
                    ManualCommandCard(
                        onExecute = { cmd -> executeCommand(cmd) },
                        isExecuting = isExecuting
                    )
                }
            }
        }
    }
}

@Composable
fun SetupGuideCard(onCopy: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.termux_setup_debian_title), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            
            val steps = listOf(
                "1. Install Termux from F-Droid" to null,
                "2. Install proot-distro & Ubuntu:" to "pkg install proot-distro\npd install ubuntu\npd login ubuntu",
                "3. Install SSH server:" to "apt update && apt upgrade -y\napt install openssh-server -y",
                "4. Create sshd directory:" to "mkdir -p /run/sshd && chmod 755 /run/sshd",
                "5. Enable root login:" to "echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config\necho 'PasswordAuthentication yes' >> /etc/ssh/sshd_config",
                "6. Set password:" to "passwd",
		"7. Exit the proot:" to "exit",
                "8. Start SSH on port 8022:" to "proot-distro login ubuntu -- /usr/sbin/sshd -p 8022 -D &"
            )
            
            steps.forEach { (label, command) ->
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (command != null) {
                    CommandBlock(command = command, onCopy = onCopy)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            Text(
                "⚠️ Use port 8022, username 'root'",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // One-liner setup command (does not start sshd)
            Text(stringResource(R.string.termux_one_liner_setup), fontWeight = FontWeight.Medium)
            val oneliner = "pkg install proot-distro -y && proot-distro install ubuntu && proot-distro login ubuntu -- bash -c \"apt update && apt install openssh-server -y && mkdir -p /run/sshd && chmod 755 /run/sshd && echo 'PermitRootLogin yes' >> /etc/ssh/sshd_config && echo 'root:termux' | chpasswd\""
            CommandBlock(command = oneliner, onCopy = onCopy)
            Text(
                stringResource(R.string.termux_default_password),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.termux_start_ssh_hint), style = MaterialTheme.typography.bodySmall)
            CommandBlock(command = "/usr/sbin/sshd -p 8022", onCopy = onCopy)
        }
    }
}

@Composable
fun CommandBlock(command: String, onCopy: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Text(
                command,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = Color.Green,
                fontSize = 12.sp
            )
            IconButton(
                onClick = { onCopy(command) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.Share, "Copy", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun CommandRow(
    command: String,
    onCopy: (String) -> Unit,
    onExecute: (String) -> Unit,
    isDanger: Boolean = false
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editedCommand by remember { mutableStateOf(command) }
    
    // Edit dialog
    if (showEditDialog) {
        AlertDialog(
            onDismissRequest = { showEditDialog = false },
            title = { Text(stringResource(R.string.termux_edit_command)) },
            text = {
                OutlinedTextField(
                    value = editedCommand,
                    onValueChange = { editedCommand = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )
            },
            confirmButton = {
                Button(onClick = {
                    showEditDialog = false
                    onExecute(editedCommand)
                }) {
                    Text(stringResource(R.string.termux_run_edited))
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showEditDialog = false
                    editedCommand = command  // Reset
                }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isDanger) Color(0xFF5D1F1F) else Color(0xFF1E1E1E)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                command.take(50) + if (command.length > 50) "..." else "",
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                color = Color.Green,
                fontSize = 11.sp,
                maxLines = 1
            )
            Row {
                IconButton(onClick = { onCopy(command) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, "Copy", tint = Color.White, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { 
                    editedCommand = command
                    showEditDialog = true 
                }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Edit, "Edit", tint = Color.Yellow, modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = { onExecute(command) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.PlayArrow, "Run", tint = Color.Green, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun ConnectionCard(
    host: String,
    port: String,
    username: String,
    password: String,
    showPassword: Boolean,
    isConnected: Boolean,
    isConnecting: Boolean,
    connectionError: String?,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.termux_connection_title), fontWeight = FontWeight.Bold)
                if (isConnected) {
                    Text(stringResource(R.string.termux_connected), color = Color(0xFF4CAF50), fontWeight = FontWeight.Medium)
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = onHostChange,
                    label = { Text(stringResource(R.string.termux_host)) },
                    modifier = Modifier.weight(2f),
                    enabled = !isConnected,
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = onPortChange,
                    label = { Text(stringResource(R.string.termux_port)) },
                    modifier = Modifier.weight(1f),
                    enabled = !isConnected,
                    singleLine = true
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.termux_username)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected,
                singleLine = true
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.termux_password)) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnected,
                singleLine = true,
                visualTransformation = if (showPassword)
                    androidx.compose.ui.text.input.VisualTransformation.None
                else
                    androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = onTogglePassword) {
                        Icon(Icons.Default.Lock, stringResource(R.string.termux_toggle_password))
                    }
                }
            )
            
            connectionError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text("⚠️ $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            if (isConnected) {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.termux_disconnect))
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = username.isNotBlank() && password.isNotBlank() && !isConnecting,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Icon(Icons.Default.PlayArrow, null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isConnecting) stringResource(R.string.termux_connecting) else stringResource(R.string.termux_connect))
                }
            }
        }
    }
}

@Composable
fun ToolsCard(
    title: String,
    subtitle: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    containerColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(
                    if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    "Expand"
                )
            }
            
            if (isExpanded) {
                Spacer(modifier = Modifier.height(16.dp))
                content()
            }
        }
    }
}

@Composable
fun ToolInstallItem(tool: TermuxTool, onCopy: (String) -> Unit, onExecute: (String) -> Unit, onRunAll: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(tool.emoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tool.name, fontWeight = FontWeight.Medium)
                            if (tool.requiresMiniconda) {
                                Text(" 🐍", fontSize = 14.sp)  // Indicates needs Miniconda
                            }
                        }
                        Text(stringResource(tool.descriptionResId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (tool.requiresMiniconda) {
                            Text(stringResource(R.string.termux_miniconda_warning), style = MaterialTheme.typography.labelSmall, color = Color(0xFFFF9800))
                        }
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.action_port, tool.port), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.dataset_steps, tool.installCommands.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                
                // Run All button prominently at top
                Button(
                    onClick = onRunAll,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.termux_run_all_steps, tool.installCommands.size))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.termux_or_run_individually), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tool.installCommands.forEach { cmd ->
                    CommandRow(command = cmd, onCopy = onCopy, onExecute = onExecute)
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
fun ToolUninstallItem(tool: TermuxTool, onCopy: () -> Unit, onExecute: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tool.emoji, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(tool.name)
            }
            Row {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Share, "Copy", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onExecute, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun ToolRunItem(
    tool: TermuxTool,
    isRunning: Boolean,
    isNetworkVisible: Boolean = false,
    onVisibilityChange: (Boolean) -> Unit = {},
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenWebView: () -> Unit,
    onOpenGallery: () -> Unit = {}
) {
    var showInfo by remember { mutableStateOf(false) }
    val toolInfo = ToolInfoCards.getInfo(tool.id)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) Color(0xFF1B5E20).copy(alpha = 0.3f) else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(tool.emoji, fontSize = 24.sp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(tool.name, fontWeight = FontWeight.Medium)
                        Text(
                            if (isRunning) stringResource(R.string.termux_status_running, tool.port) else stringResource(R.string.termux_status_stopped),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isRunning) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Info button
                    IconButton(
                        onClick = { showInfo = !showInfo },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            if (showInfo) Icons.Default.KeyboardArrowUp else Icons.Default.Info,
                            stringResource(R.string.action_info),
                            tint = if (showInfo) MaterialTheme.colorScheme.primary else Color.Gray
                        )
                    }
                    // Gallery button for FastSD
                    if (tool.id == "fastsdcpu") {
                        IconButton(
                            onClick = onOpenGallery,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.AccountBox, stringResource(R.string.fastsd_gallery_title), tint = Color(0xFF9C27B0))
                        }
                    }
                    // WebView button (only when running and has web UI)
                    if (isRunning && tool.port > 0 && tool.hasWebUI) {
                        IconButton(
                            onClick = onOpenWebView,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Home, stringResource(R.string.termux_open_webui), tint = Color(0xFF2196F3))
                        }
                    }
                    // Start/Stop button
                    if (isRunning) {
                        Button(
                            onClick = onStop,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_stop), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_stop))
                        }
                    } else {
                        Button(
                            onClick = onStart,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, stringResource(R.string.action_start), modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.action_start))
                        }
                    }
                }
            }
            
            // Network visibility toggle row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isNetworkVisible) stringResource(R.string.termux_lan) else stringResource(R.string.termux_local),
                        fontSize = 12.sp,
                        color = if (isNetworkVisible) Color(0xFF4CAF50) else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (isNetworkVisible) stringResource(R.string.termux_lan_desc) else stringResource(R.string.termux_local_desc),
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isNetworkVisible,
                    onCheckedChange = onVisibilityChange,
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF4CAF50),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }
            
            // Info card section (expandable)
            if (showInfo && toolInfo != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                
                // RAM requirement
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("💾", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.termux_ram_requirement), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(toolInfo.ramRequirement, fontSize = 12.sp, color = Color(0xFF4CAF50))
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Low-RAM tips
                Text(stringResource(R.string.termux_low_ram_tips), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                toolInfo.lowRamTips.forEach { tip ->
                    Row(modifier = Modifier.padding(start = 16.dp, top = 2.dp)) {
                        Text("•", fontSize = 12.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(tip, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Integration
                Text(stringResource(R.string.termux_integration), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Text(
                    toolInfo.integration,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Features row
                Text(stringResource(R.string.termux_features), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                Row(
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    toolInfo.features.forEach { feature ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                feature,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OutputCard(output: String, isExecuting: Boolean, currentCommand: String, onClear: () -> Unit, onCopyAll: (String) -> Unit = {}) {
    val scrollState = rememberScrollState()
    
    // Auto-scroll to bottom when output changes
    LaunchedEffect(output) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(stringResource(R.string.termux_output_title), fontWeight = FontWeight.Bold, color = Color.White)
                    if (currentCommand.isNotBlank()) {
                        Text(
                            "$ ${currentCommand.take(40)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
                Row {
                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Green)
                    }
                    if (output.isNotBlank()) {
                        IconButton(onClick = { onCopyAll(output) }) {
                            Icon(Icons.Default.Share, stringResource(R.string.action_copy_all), tint = Color.White)
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Default.Clear, stringResource(R.string.action_clear), tint = Color.White)
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            SelectionContainer {
                Text(
                    text = if (output.isBlank() && isExecuting) "Running..." else output.ifBlank { "No output" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color(0xFF00FF00),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState)
                )
            }
        }
    }
}

@Composable
fun ToolOutputCard(
    toolName: String,
    output: String,
    isExecuting: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    // Auto-scroll to bottom when output changes
    LaunchedEffect(output) {
        if (isExpanded) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E).copy(alpha = 0.9f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row - always visible
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                        contentDescription = if (isExpanded) stringResource(R.string.action_collapse) else stringResource(R.string.action_expand),
                        tint = Color.Gray,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.termux_output_title) + " " + toolName,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    if (isExecuting) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color.Green
                        )
                    }
                }
                Row {
                    if (output.isNotBlank()) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Share, "Copy", tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = onClear, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Clear, stringResource(R.string.action_clear), tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            
            // Expandable content
            AnimatedVisibility(visible = isExpanded) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainer {
                        Text(
                            text = if (output.isBlank() && isExecuting) stringResource(R.string.termux_output_running) else output.ifBlank { stringResource(R.string.termux_output_none) },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = Color(0xFF00FF00),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 150.dp)
                                .verticalScroll(scrollState)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ManualCommandCard(onExecute: (String) -> Unit, isExecuting: Boolean) {
    var command by remember { mutableStateOf("") }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.termux_manual_command_title), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    label = { Text(stringResource(R.string.action_command)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                IconButton(
                    onClick = { 
                        if (command.isNotBlank()) {
                            onExecute(command)
                            command = ""  // Clear field after execution
                        }
                    },
                    enabled = command.isNotBlank() && !isExecuting
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.termux_execute))
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.termux_quick_commands_hint), style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ls", "pwd", "whoami", "df -h").forEach { cmd ->
                    FilterChip(
                        selected = false,
                        onClick = { command = cmd },
                        label = { Text(cmd, fontSize = 11.sp) }
                    )
                }
            }
        }
    }
}
