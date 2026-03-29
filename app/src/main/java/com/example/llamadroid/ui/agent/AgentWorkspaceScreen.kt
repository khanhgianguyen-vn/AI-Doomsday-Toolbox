package com.example.llamadroid.ui.agent

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.example.llamadroid.R
import androidx.navigation.NavController
import com.example.llamadroid.service.AgentForegroundService
import com.example.llamadroid.service.AgentService
import com.example.llamadroid.service.AgentService.Companion.setIsLoading
import com.example.llamadroid.service.FileInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * AgentWorkspaceScreen - File manager for AI Agent workspace
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentWorkspaceScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val agentService = remember { AgentForegroundService.getAgentService(context) }
    
    val currentProjectFolder by AgentService.currentProjectFolder.collectAsState()
    var currentPath by remember { mutableStateOf("${AgentService.WORKSPACE_PATH}/$currentProjectFolder") }
    var files by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // File viewer state
    var viewingFile by remember { mutableStateOf<FileInfo?>(null) }
    var fileContent by remember { mutableStateOf("") }
    var isEditMode by remember { mutableStateOf(false) }
    var editedContent by remember { mutableStateOf("") }
    
    // Delete confirmation
    var deleteTarget by remember { mutableStateOf<FileInfo?>(null) }
    
    // New file/folder dialog
    var showNewDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemIsFolder by remember { mutableStateOf(false) }
    
    // Multi-selection & Actions
    var selectedFiles by remember { mutableStateOf(setOf<FileInfo>()) }
    var isMultiSelectMode by remember { mutableStateOf(false) }
    var clipboardAction by remember { mutableStateOf<Pair<String, List<FileInfo>>?>(null) } // "copy" or "move" to files
    
    val isConnected by AgentService.isConnected.collectAsState()
    val agentConnectionStatus by AgentService.connectionStatus.collectAsState()
    val retryMessage by AgentService.retryMessage.collectAsState()
    
    // Download state
    var downloadTarget by remember { mutableStateOf<FileInfo?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let {
            val target = downloadTarget ?: return@let
            scope.launch {
                setIsLoading(true, context.getString(R.string.status_downloading))
                agentService.downloadFile(target.path, it).onSuccess {
                    Toast.makeText(context, context.getString(R.string.status_complete), Toast.LENGTH_SHORT).show()
                }.onFailure { e: Throwable ->
                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                setIsLoading(false)
            }
        }
        downloadTarget = null
    }
    
    // Load files
    fun loadFiles() {
        isLoading = true
        error = null
        scope.launch {
            agentService.listDirectory(currentPath).onSuccess { fileList ->
                files = fileList.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
            }.onFailure { e: Throwable ->
                error = e.message
                AgentService.addDebugLog("📁 Workspace load failed for $currentPath: ${e.message}")
            }
            isLoading = false
        }
    }

    // File Picker for Upload
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                setIsLoading(true, "Uploading...")
                val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    cursor.moveToFirst()
                    cursor.getString(nameIndex)
                } ?: "uploaded_file"
                
                agentService.uploadFile(uri, "$currentPath/$fileName").onSuccess {
                    Toast.makeText(context, context.getString(R.string.agent_upload_success, fileName), Toast.LENGTH_SHORT).show()
                    loadFiles()
                }.onFailure { e: Throwable ->
                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
                setIsLoading(false)
            }
        }
    }
    

    LaunchedEffect(currentProjectFolder) {
        if (currentProjectFolder.isNotBlank() && !currentPath.startsWith("${AgentService.WORKSPACE_PATH}/$currentProjectFolder")) {
            currentPath = "${AgentService.WORKSPACE_PATH}/$currentProjectFolder"
        }
    }

    // Connect and load using a single path to avoid double startup reloads
    LaunchedEffect(isConnected, currentPath) {
        if (currentPath.isBlank()) return@LaunchedEffect
        if (!isConnected) {
            val connectResult = agentService.connect()
            if (connectResult.isFailure) {
                val connectError = connectResult.exceptionOrNull()
                error = connectError?.message
                AgentService.addDebugLog("📡 Workspace SSH connect failed: ${connectError?.message}")
                isLoading = false
                return@LaunchedEffect
            }
        }
        loadFiles()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.agent_workspace_title),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            currentPath,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Upload
                    IconButton(onClick = { uploadLauncher.launch("*/*") }) {
                        Icon(Icons.Default.Upload, stringResource(R.string.action_upload))
                    }
                    // New file/folder
                    IconButton(onClick = { showNewDialog = true }) {
                        Icon(Icons.Default.Add, stringResource(R.string.agent_new))
                    }
                    // Refresh
                    IconButton(onClick = { loadFiles() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.agent_refresh))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ConnectionStatusBar(
                isOllamaConnected = true, // We don't check ollama here, just agent
                ollamaIsRecovering = false,
                ollamaHasChecked = true,  // Always checked in workspace context
                agentConnectionStatus = agentConnectionStatus,
                retryMessage = retryMessage,
                onRetry = { scope.launch { agentService.connect() } }
            )
            
            // Selection Toolbar
            AnimatedVisibility(visible = isMultiSelectMode) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { 
                                isMultiSelectMode = false
                                selectedFiles = emptySet()
                            }) {
                                Icon(Icons.Default.Close, null)
                            }
                            Text(stringResource(R.string.agent_selected_count, selectedFiles.size), fontWeight = FontWeight.Bold)
                        }
                        Row {
                            IconButton(onClick = { 
                                clipboardAction = "copy" to selectedFiles.toList()
                                isMultiSelectMode = false
                                selectedFiles = emptySet()
                                Toast.makeText(context, context.getString(R.string.action_copy), Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCopy, stringResource(R.string.action_copy))
                            }
                            IconButton(onClick = { 
                                clipboardAction = "move" to selectedFiles.toList()
                                isMultiSelectMode = false
                                selectedFiles = emptySet()
                                Toast.makeText(context, context.getString(R.string.action_move), Toast.LENGTH_SHORT).show()
                            }) {
                                Icon(Icons.Default.ContentCut, stringResource(R.string.action_move))
                            }
                            IconButton(onClick = { 
                                scope.launch {
                                    val tarName = "archive_${System.currentTimeMillis()}.tar.gz"
                                        agentService.compress(selectedFiles.map { it.path }, "$currentPath/$tarName").onSuccess {
                                            Toast.makeText(context, context.getString(R.string.agent_compress_success, tarName), Toast.LENGTH_SHORT).show()
                                            loadFiles()
                                            isMultiSelectMode = false
                                            selectedFiles = emptySet()
                                    }.onFailure { e: Throwable ->
                                        Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                }
                            }) {
                                Icon(Icons.Default.Archive, stringResource(R.string.action_compress))
                            }
                            IconButton(onClick = { 
                                // Bulk Delete
                                selectedFiles.forEach { file ->
                                    scope.launch {
                                        val cmd = if (file.isDirectory) "rm -rf '${file.name}'" else "rm '${file.name}'"
                                        agentService.runCommand(cmd, workingDir = currentPath)
                                    }
                                }
                                scope.launch {
                                    delay(500)
                                    loadFiles()
                                    isMultiSelectMode = false
                                    selectedFiles = emptySet()
                                }
                            }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            // Clipboard Action Bar (Paste)
            AnimatedVisibility(visible = clipboardAction != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${clipboardAction?.second?.size} ${stringResource(R.string.agent_ready_to, clipboardAction?.first ?: "")}",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            TextButton(onClick = { clipboardAction = null }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                            Button(onClick = {
                                val (action, targets) = clipboardAction ?: return@Button
                                scope.launch {
                                    setIsLoading(true, context.getString(R.string.status_processing))
                                    targets.forEach { target ->
                                        val dest = "$currentPath/${target.name}"
                                        if (action == "copy") {
                                            agentService.copy(target.path, dest)
                                        } else {
                                            agentService.move(target.path, dest)
                                        }
                                    }
                                    loadFiles()
                                    clipboardAction = null
                                    setIsLoading(false)
                                }
                            }) {
                                Text(stringResource(R.string.action_paste_here))
                            }
                        }
                    }
                }
            }

            // Breadcrumb navigation
            if (currentPath != "${AgentService.WORKSPACE_PATH}/$currentProjectFolder") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            currentPath = currentPath.substringBeforeLast("/").ifEmpty { AgentService.WORKSPACE_PATH }
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.agent_parent_folder), fontSize = 14.sp)
                }
                HorizontalDivider()
            }
            
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("⚠️", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(error ?: stringResource(R.string.error_unknown))
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { loadFiles() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
                files.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("📂", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(stringResource(R.string.agent_empty_folder))
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(files) { file ->
                            val isSelected = selectedFiles.contains(file)
                            FileItem(
                                file = file,
                                isSelected = isSelected,
                                isMultiSelectMode = isMultiSelectMode,
                                onClick = {
                                    if (isMultiSelectMode) {
                                        selectedFiles = if (isSelected) selectedFiles - file else selectedFiles + file
                                    } else if (file.isDirectory) {
                                        currentPath = file.path
                                    } else {
                                        // View file
                                        viewingFile = file
                                        scope.launch {
                                            agentService.readFile(file.path).onSuccess { content: String ->
                                                fileContent = content
                                                editedContent = content
                                            }.onFailure { e: Throwable ->
                                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!isMultiSelectMode) {
                                        isMultiSelectMode = true
                                        selectedFiles = setOf(file)
                                    }
                                },
                                onDelete = { deleteTarget = file },
                                onAction = { action ->
                                    when (action) {
                                        "compress" -> {
                                            scope.launch {
                                                setIsLoading(true, context.getString(R.string.status_processing))
                                                val archiveName = "${file.name}.tar.gz"
                                                agentService.compress(
                                                    listOf(file.path),
                                                    "$currentPath/$archiveName"
                                                ).onSuccess {
                                                    Toast.makeText(context, context.getString(R.string.agent_compress_success), Toast.LENGTH_SHORT).show()
                                                    loadFiles()
                                                }.onFailure { e: Throwable ->
                                                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                                                }
                                                setIsLoading(false)
                                            }
                                        }
                                        "uncompress" -> {
                                            scope.launch {
                                                setIsLoading(true, context.getString(R.string.status_processing))
                                                agentService.uncompress(file.path, currentPath).onSuccess {
                                                    Toast.makeText(context, context.getString(R.string.agent_uncompress_success), Toast.LENGTH_SHORT).show()
                                                    loadFiles()
                                                }.onFailure { e: Throwable ->
                                                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_LONG).show()
                                                }
                                                setIsLoading(false)
                                            }
                                        }
                                        "download" -> {
                                            downloadTarget = file
                                            downloadLauncher.launch(file.name)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        } // end Column(padding)
    
    // File viewer/editor dialog
    viewingFile?.let { file ->
        FileViewerDialog(
            file = file,
            content = fileContent,
            isEditMode = isEditMode,
            editedContent = editedContent,
            onEditedContentChange = { editedContent = it },
            onToggleEdit = { isEditMode = !isEditMode },
            onSave = {
                scope.launch {
                    agentService.writeFile(file.path, editedContent).onSuccess {
                        Toast.makeText(context, context.getString(R.string.agent_save_toast), Toast.LENGTH_SHORT).show()
                        fileContent = editedContent
                        isEditMode = false
                    }.onFailure { e: Throwable ->
                        Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = {
                viewingFile = null
                isEditMode = false
            }
        )
    }
    
    // Delete confirmation
    deleteTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (file.isDirectory) stringResource(R.string.agent_confirm_delete_folder) else stringResource(R.string.agent_confirm_delete_file)) },
            text = { Text(stringResource(R.string.agent_confirm_delete_msg, file.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val cmd = if (file.isDirectory) "rm -rf '${file.name}'" else "rm '${file.name}'"
                            agentService.runCommand(cmd, workingDir = currentPath).onSuccess {
                                Toast.makeText(context, context.getString(R.string.agent_delete_toast), Toast.LENGTH_SHORT).show()
                                loadFiles()
                            }.onFailure { e: Throwable ->
                                Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show()
                            }
                        }
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // New file/folder dialog
    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text(if (newItemIsFolder) stringResource(R.string.agent_new_folder) else stringResource(R.string.agent_new_file)) },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.agent_new_folder))
                        Switch(
                            checked = !newItemIsFolder,
                            onCheckedChange = { newItemIsFolder = !it }
                        )
                        Text(stringResource(R.string.agent_new_file))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it },
                        label = { Text(stringResource(R.string.agent_name_label)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newItemName.isNotBlank()) {
                            scope.launch {
                                val cmd = if (newItemIsFolder) {
                                    "mkdir -p '$newItemName'"
                                } else {
                                    "touch '$newItemName'"
                                }
                                agentService.runCommand(cmd, workingDir = currentPath).onSuccess {
                                    Toast.makeText(context, context.getString(R.string.agent_create_toast), Toast.LENGTH_SHORT).show()
                                    loadFiles()
                                }.onFailure { e: Throwable ->
                                    Toast.makeText(context, context.getString(R.string.agent_error_prefix, e.message ?: ""), Toast.LENGTH_SHORT).show()
                                }
                            }
                            showNewDialog = false
                            newItemName = ""
                        }
                    }
                ) {
                    Text(stringResource(R.string.agent_create_btn))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false; newItemName = "" }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileItem(
    file: FileInfo,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onDelete: () -> Unit,
    onAction: (String) -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (!file.isDirectory) {
                Text(formatSize(file.size), fontSize = 11.sp)
            }
        },
        leadingContent = {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (file.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    null,
                    tint = if (file.isDirectory) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
                if (isMultiSelectMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                        modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), CircleShape)
                    )
                }
            }
        },
        trailingContent = {
            Row {
                if (file.name.endsWith(".tar.gz") || file.name.endsWith(".zip") || file.name.endsWith(".tgz")) {
                    IconButton(onClick = { onAction("uncompress") }) {
                        Icon(Icons.Default.Unarchive, "Uncompress")
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, null)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_compress)) },
                            onClick = { showMenu = false; onAction("compress") },
                            leadingIcon = { Icon(Icons.Default.Archive, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_download)) },
                            onClick = { showMenu = false; onAction("download") },
                            leadingIcon = { Icon(Icons.Default.Download, null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { showMenu = false; onDelete() },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
    )
}

@Composable
fun FileViewerDialog(
    file: FileInfo,
    content: String,
    isEditMode: Boolean,
    editedContent: String,
    onEditedContentChange: (String) -> Unit,
    onToggleEdit: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        file.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Row {
                        IconButton(onClick = onToggleEdit) {
                            Icon(
                                if (isEditMode) Icons.Default.Check else Icons.Default.Edit,
                                if (isEditMode) stringResource(R.string.action_info) else stringResource(R.string.action_edit)
                            )
                        }
                        if (isEditMode) {
                            IconButton(onClick = onSave) {
                                Icon(Icons.Default.Done, stringResource(R.string.action_save), tint = Color(0xFF4CAF50))
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, stringResource(R.string.action_close))
                        }
                    }
                }
                
                HorizontalDivider()
                
                // Content
                if (isEditMode) {
                    OutlinedTextField(
                        value = editedContent,
                        onValueChange = onEditedContentChange,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        textStyle = LocalTextStyle.current.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )
                } else {
                    Surface(
                        color = Color.Black.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = content,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Color.White,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes ${stringResource(R.string.agent_unit_b)}"
        bytes < 1024 * 1024 -> "${bytes / 1024} ${stringResource(R.string.agent_unit_kb)}"
        else -> String.format("%.1f %s", bytes / (1024.0 * 1024.0), stringResource(R.string.agent_unit_mb))
    }
}
