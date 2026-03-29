package com.example.llamadroid.ui.ai

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import com.example.llamadroid.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.model.TermuxTool
import com.example.llamadroid.data.model.TermuxTools
import com.example.llamadroid.service.SSHService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File entry from SSH ls output with raw bytes for sorting
 */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: String,
    val sizeBytes: Long,
    val permissions: String,
    val modifiedTime: String
)

/**
 * Sort options for file listing
 */
enum class SortOption(val labelResId: Int) {
    NAME_ASC(R.string.file_sort_name_asc),
    NAME_DESC(R.string.file_sort_name_desc),
    SIZE_ASC(R.string.file_sort_size_asc),
    SIZE_DESC(R.string.file_sort_size_desc),
    TIME_ASC(R.string.file_sort_time_asc),
    TIME_DESC(R.string.file_sort_time_desc)
}

/**
 * Termux File Manager Screen
 * Visual file browser for managing files in proot tools
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TermuxFileManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sshService = remember { SSHService(context) }
    
    val isConnected by SSHService.isConnected.collectAsState()
    
    // Installed tools (re-detected on screen entry)
    var installedTools by remember { mutableStateOf<List<TermuxTool>>(emptyList()) }
    var isDetecting by remember { mutableStateOf(true) }
    
    // Selected tool and current path
    var selectedTool by remember { mutableStateOf<TermuxTool?>(null) }
    var currentPath by remember { mutableStateOf("") }
    
    // File listing
    var files by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    
    // Sorting
    var sortOption by remember { mutableStateOf(SortOption.NAME_ASC) }
    var showSortMenu by remember { mutableStateOf(false) }
    
    // Multi-selection
    var selectedFiles by remember { mutableStateOf(setOf<String>()) }
    var isSelectionMode by remember { mutableStateOf(false) }
    
    // Download dialog
    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadUrl by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    
    // Delete confirmation
    var fileToDelete by remember { mutableStateOf<FileEntry?>(null) }
    
    // Folder picker for downloads
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { targetUri ->
            context.contentResolver.takePersistableUriPermission(
                targetUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            // Download selected files to this folder
            scope.launch {
                isDownloading = true
                downloadFilesToFolder(context, sshService, currentPath, selectedFiles.toList(), targetUri)
                isDownloading = false
                selectedFiles = emptySet()
                isSelectionMode = false
                Toast.makeText(context, context.getString(R.string.file_download_success), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    // Sort files based on current option
    val sortedFiles = remember(files, sortOption) {
        when (sortOption) {
            SortOption.NAME_ASC -> files.sortedBy { it.name.lowercase() }
            SortOption.NAME_DESC -> files.sortedByDescending { it.name.lowercase() }
            SortOption.SIZE_ASC -> files.sortedBy { it.sizeBytes }
            SortOption.SIZE_DESC -> files.sortedByDescending { it.sizeBytes }
            SortOption.TIME_ASC -> files.sortedBy { it.modifiedTime }
            SortOption.TIME_DESC -> files.sortedByDescending { it.modifiedTime }
        }
    }
    
    // Detect installed tools on screen entry
    LaunchedEffect(Unit) {
        if (!isConnected) return@LaunchedEffect
        
        isDetecting = true
        val detected = mutableListOf<TermuxTool>()
        
        // Check all tools by their install path
        for (tool in TermuxTools.allTools) {
            val path = tool.installPath.replace("~", "/root")
            val result = sshService.executeCommand("test -e $path && echo 'exists' || echo 'not'")
            result.onSuccess { output ->
                if (output.trim() == "exists") {
                    detected.add(tool)
                }
            }
        }
        
        installedTools = detected
        isDetecting = false
        
        // Auto-select first tool
        if (detected.isNotEmpty()) {
            selectedTool = detected.first()
            currentPath = detected.first().installPath
        }
    }
    
    // Track file count to detect changes
    var lastFileCount by remember { mutableStateOf(-1) }
    var isCalculatingSizes by remember { mutableStateOf(false) }
    
    // Load files when path changes and auto-refresh every 10 seconds
    LaunchedEffect(currentPath) {
        if (currentPath.isBlank() || !isConnected) return@LaunchedEffect
        
        // Reset on path change - force reload
        lastFileCount = -1
        files = emptyList()
        isLoading = true
        
        while (true) {
            if (!isDownloading) {  // Don't refresh during downloads
                error = null
                val expandedPath = currentPath.replace("~", "/root")
                
                // Step 1: Quick file listing (no sizes, instant)
                if (files.isEmpty()) {
                    val quickResult = sshService.executeCommand("ls -1A $expandedPath 2>/dev/null")
                    quickResult.onSuccess { output ->
                        // Show files immediately with "Calculating..." as size
                        files = output.lines()
                            .filter { it.isNotBlank() && it != "." && it != ".." }
                            .map { name ->
                                FileEntry(
                                    name = name,
                                    isDirectory = false, // Will be corrected
                                    size = context.getString(R.string.file_calculating_size),
                                    sizeBytes = 0,
                                    permissions = "",
                                    modifiedTime = ""
                                )
                            }
                        isLoading = false
                    }.onFailure {
                        isLoading = false
                    }
                }
                
                // Step 2: Get types and sizes (may be slow for folders)
                val countResult = sshService.executeCommand("ls -1A $expandedPath 2>/dev/null | wc -l")
                val currentCount = countResult.getOrNull()?.trim()?.toIntOrNull() ?: 0
                
                if (currentCount != lastFileCount) {
                    lastFileCount = currentCount
                    isCalculatingSizes = true
                    
                    // Get detailed info with sizes
                    val result = sshService.executeCommand("""
                        cd $expandedPath 2>/dev/null && for f in * .[^.]*; do
                            if [ -e "${'$'}f" ]; then
                                if [ -d "${'$'}f" ]; then
                                    size=${'$'}(du -sh "${'$'}f" 2>/dev/null | cut -f1)
                                    echo "DIR|${'$'}size|${'$'}f"
                                else
                                    size=${'$'}(ls -lh "${'$'}f" 2>/dev/null | awk '{print ${'$'}5}')
                                    echo "FILE|${'$'}size|${'$'}f"
                                fi
                            fi
                        done 2>/dev/null
                    """.trimIndent())
                    
                    result.onSuccess { output ->
                        files = parseCustomLsOutput(output)
                    }
                    isCalculatingSizes = false
                }
            }
            
            kotlinx.coroutines.delay(10000)  // Refresh every 10 seconds
        }
    }
    
    // Navigate to folder
    fun navigateTo(folder: String) {
        currentPath = if (folder.startsWith("/")) {
            folder
        } else {
            "$currentPath/$folder"
        }
    }
    
    // Navigate back
    fun navigateBack() {
        val parent = currentPath.substringBeforeLast("/")
        if (parent.isNotBlank() && parent != currentPath) {
            currentPath = parent
        }
    }
    
    // Download file via URL
    fun downloadFile(url: String) {
        if (url.isBlank()) return
        scope.launch {
            isDownloading = true
            showDownloadDialog = false
            
            val filename = url.substringAfterLast("/").ifBlank { "model.bin" }
            val expandedPath = currentPath.replace("~", "/root")
            
            sshService.executeCommandStreaming(
                "cd $expandedPath && wget --progress=dot:mega -O '$filename' '$url'"
            )
            
            isDownloading = false
            downloadUrl = ""
            
            // Refresh file list
            val result = sshService.executeCommand("ls -la --time-style=long-iso $expandedPath 2>/dev/null || ls -la $expandedPath 2>/dev/null")
            result.onSuccess { output ->
                files = parseLsOutputEnhanced(output)
            }
            
            Toast.makeText(context, context.getString(R.string.file_download_success) + ": $filename", Toast.LENGTH_SHORT).show()
        }
    }
    
    // Delete file
    fun deleteFile(file: FileEntry) {
        scope.launch {
            val expandedPath = currentPath.replace("~", "/root")
            val cmd = if (file.isDirectory) "rm -rf" else "rm -f"
            
            sshService.executeCommand("$cmd '$expandedPath/${file.name}'")
            
            // Refresh
            val result = sshService.executeCommand("ls -la --time-style=long-iso $expandedPath 2>/dev/null || ls -la $expandedPath 2>/dev/null")
            result.onSuccess { output ->
                files = parseLsOutputEnhanced(output)
            }
            
            fileToDelete = null
            Toast.makeText(context, context.getString(R.string.file_deleted_success, file.name), Toast.LENGTH_SHORT).show()
        }
    }
    
    // Toggle file selection
    fun toggleSelection(fileName: String) {
        selectedFiles = if (selectedFiles.contains(fileName)) {
            selectedFiles - fileName
        } else {
            selectedFiles + fileName
        }
        if (selectedFiles.isEmpty()) {
            isSelectionMode = false
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.file_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // Sort button
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.action_sort))
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            SortOption.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(option.labelResId)) },
                                    onClick = {
                                        sortOption = option
                                        showSortMenu = false
                                    },
                                    leadingIcon = {
                                        if (sortOption == option) {
                                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    // Refresh button
                    IconButton(onClick = {
                        val expandedPath = currentPath.replace("~", "/root")
                        scope.launch {
                            val result = sshService.executeCommand("ls -la --time-style=long-iso $expandedPath 2>/dev/null || ls -la $expandedPath 2>/dev/null")
                            result.onSuccess { output ->
                                files = parseLsOutputEnhanced(output)
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.action_refresh))
                    }
                }
            )
        },
        bottomBar = {
            // Selection action bar
            if (isSelectionMode && selectedFiles.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            stringResource(R.string.file_selected_count, selectedFiles.size),
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Select All
                            OutlinedButton(onClick = {
                                selectedFiles = sortedFiles.map { it.name }.toSet()
                            }) {
                                Text("All")
                            }
                            // Download selected
                            Button(onClick = {
                                folderPicker.launch(null)
                            }) {
                                Icon(Icons.Default.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.action_download))
                            }
                            // Cancel selection
                            IconButton(onClick = {
                                selectedFiles = emptySet()
                                isSelectionMode = false
                            }) {
                                Icon(Icons.Default.Close, stringResource(R.string.action_cancel))
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Not connected warning
            if (!isConnected) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.ssh_not_connected_error))
                    }
                }
                return@Scaffold
            }
            
            // Detecting tools
            if (isDetecting) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.file_detecting_tools))
                    }
                }
                return@Scaffold
            }
            
            // No tools installed
            if (installedTools.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📭", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.file_no_tools_installed), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.file_no_tools_hint),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                return@Scaffold
            }
            
            // Tool tabs
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Root filesystem chip
                item {
                    FilterChip(
                        selected = selectedTool == null && currentPath == "/",
                        onClick = {
                            selectedTool = null
                            currentPath = "/"
                        },
                        label = {
                            Text(stringResource(R.string.file_root_label))
                        }
                    )
                }
                // HuggingFace cache shortcut
                item {
                    FilterChip(
                        selected = currentPath == "/root/.cache/huggingface",
                        onClick = {
                            selectedTool = null
                            currentPath = "/root/.cache/huggingface"
                        },
                        label = {
                            Text(stringResource(R.string.file_hf_cache_label))
                        }
                    )
                }
                // FastSD results shortcut
                item {
                    FilterChip(
                        selected = currentPath == "/root/fastsdcpu/results",
                        onClick = {
                            selectedTool = null
                            currentPath = "/root/fastsdcpu/results"
                        },
                        label = {
                            Text(stringResource(R.string.file_sd_results_label))
                        }
                    )
                }
                // Tool chips
                items(installedTools) { tool ->
                    FilterChip(
                        selected = selectedTool?.id == tool.id,
                        onClick = {
                            selectedTool = tool
                            currentPath = tool.installPath
                        },
                        label = {
                            Text("${tool.emoji} ${tool.name}")
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Current path breadcrumb
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    if (currentPath != selectedTool?.installPath) {
                        IconButton(
                            onClick = { navigateBack() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = Color.White)
                        }
                    }
                    
                    Text(
                        currentPath,
                        color = Color(0xFF4CAF50),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // File count and calculating indicator
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "${sortedFiles.size} items",
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                        if (isCalculatingSizes) {
                            Text(
                                stringResource(R.string.file_calculating_sizes_status),
                                color = Color(0xFFFFB74D),
                                fontSize = 8.sp
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Download button (URL)
            Button(
                onClick = { showDownloadDialog = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isDownloading
            ) {
                if (isDownloading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_downloading_status))
                } else {
                    Icon(Icons.Default.Add, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.file_download_url))
                }
            }
            
            // Selection mode hint
            if (!isSelectionMode && sortedFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.file_select_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // File grid
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (error != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Error: $error",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (sortedFiles.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(R.string.file_empty_folder),
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(sortedFiles) { file ->
                        val isSelected = selectedFiles.contains(file.name)
                        FileItemEnhanced(
                            file = file,
                            isSelected = isSelected,
                            isSelectionMode = isSelectionMode,
                            onClick = {
                                if (isSelectionMode) {
                                    toggleSelection(file.name)
                                } else if (file.isDirectory) {
                                    navigateTo(file.name)
                                }
                            },
                            onLongClick = {
                                isSelectionMode = true
                                toggleSelection(file.name)
                            },
                            onDelete = { fileToDelete = file }
                        )
                    }
                }
            }
        }
    }
    
    // Download dialog
    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text(stringResource(R.string.file_download_url_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.file_download_url_hint))
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = downloadUrl,
                        onValueChange = { downloadUrl = it },
                        label = { Text(stringResource(R.string.file_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.file_target_path, currentPath),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { downloadFile(downloadUrl) },
                    enabled = downloadUrl.isNotBlank()
                ) {
                    Text(stringResource(R.string.action_download))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDownloadDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
    
    // Delete confirmation dialog
    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(R.string.file_delete_confirm_title, file.name)) },
            text = {
                Text(stringResource(R.string.file_delete_confirm_text, if (file.isDirectory) "folder" else "file", file.name))
            },
            confirmButton = {
                Button(
                    onClick = { deleteFile(file) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { fileToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

/**
 * Enhanced file item with selection support
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileItemEnhanced(
    file: FileEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                file.isDirectory -> Color(0xFF1565C0).copy(alpha = 0.2f)
                else -> MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else null
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Selection checkbox overlay
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.size(24.dp)
                )
            }
            
            // Icon
            Text(
                if (file.isDirectory) "📁" else "📄",
                fontSize = if (isSelectionMode) 24.sp else 32.sp
            )
            
            // Name
            Text(
                file.name,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            
            // Size
            Text(
                file.size,
                fontSize = 8.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Modified time (compact)
            if (file.modifiedTime.isNotBlank()) {
                Text(
                    file.modifiedTime.take(10),  // Just date part
                    fontSize = 7.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            
            // Delete button (not in selection mode)
            if (!isSelectionMode) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Parse `ls -la` output into FileEntry list with size and time.
 * Handles both:
 *   - long-iso format (8 columns): drwxr-xr-x 2 root root 4096 2024-01-01 12:00 dirname
 *   - traditional format (9 columns): drwxr-xr-x 2 root root 4096 Dec 30 12:00 dirname
 */
fun parseLsOutputEnhanced(output: String): List<FileEntry> {
    return output.lines()
        .drop(1) // Skip "total" line
        .filter { it.isNotBlank() }
        .mapNotNull { line ->
            val parts = line.split(Regex("\\s+"), limit = 9)
            
            // Need at least 8 parts for long-iso format
            if (parts.size < 8) return@mapNotNull null
            
            // Determine format based on date column (index 5)
            // long-iso has YYYY-MM-DD, traditional has month name like "Dec"
            val isLongIso = parts[5].contains("-")
            
            val name: String
            val modifiedTime: String
            
            if (isLongIso) {
                // 8-column format: perms links owner group size YYYY-MM-DD HH:MM name
                name = parts[7]
                modifiedTime = "${parts[5]} ${parts[6]}"
            } else {
                // 9-column format: perms links owner group size Mon DD HH:MM name
                if (parts.size < 9) return@mapNotNull null
                name = parts[8]
                modifiedTime = "${parts[5]} ${parts[6]} ${parts[7]}"
            }
            
            if (name == "." || name == "..") return@mapNotNull null
            
            val sizeBytes = parts[4].toLongOrNull() ?: 0
            
            FileEntry(
                name = name,
                isDirectory = parts[0].startsWith("d"),
                size = formatSizeEnhanced(sizeBytes),
                sizeBytes = sizeBytes,
                permissions = parts[0],
                modifiedTime = modifiedTime
            )
        }
}

/**
 * Format file size with appropriate units
 */
fun formatSizeEnhanced(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

/**
 * Parse custom ls output format: DIR|size|name or FILE|size|name
 * This format includes real folder sizes from du -sh
 */
fun parseCustomLsOutput(output: String): List<FileEntry> {
    return output.lines()
        .filter { it.isNotBlank() && it.contains("|") }
        .mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            
            val type = parts[0]
            val size = parts[1]
            val name = parts[2]
            
            if (name == "." || name == ".." || name == "*" || name == ".[^.]*") return@mapNotNull null
            
            // Parse human-readable size to bytes for sorting
            val sizeBytes = parseSizeToBytes(size)
            
            FileEntry(
                name = name,
                isDirectory = type == "DIR",
                size = size.ifBlank { "0 B" },
                sizeBytes = sizeBytes,
                permissions = if (type == "DIR") "d---------" else "----------",
                modifiedTime = ""  // Not available in this format
            )
        }
}

/**
 * Parse human-readable size (1.4G, 53K, etc) to bytes for sorting
 */
fun parseSizeToBytes(sizeStr: String): Long {
    val cleaned = sizeStr.trim().uppercase()
    if (cleaned.isBlank()) return 0
    
    val numPart = cleaned.filter { it.isDigit() || it == '.' }
    val num = numPart.toDoubleOrNull() ?: return 0
    
    return when {
        cleaned.endsWith("T") -> (num * 1_000_000_000_000).toLong()
        cleaned.endsWith("G") -> (num * 1_000_000_000).toLong()
        cleaned.endsWith("M") -> (num * 1_000_000).toLong()
        cleaned.endsWith("K") -> (num * 1_000).toLong()
        else -> num.toLong()
    }
}

/**
 * Download selected files to a folder (via SCP or cat+base64)
 */
suspend fun downloadFilesToFolder(
    context: android.content.Context,
    sshService: SSHService,
    remotePath: String,
    fileNames: List<String>,
    targetUri: android.net.Uri
) = withContext(Dispatchers.IO) {
    val expandedPath = remotePath.replace("~", "/root")
    
    for (fileName in fileNames) {
        try {
            // Get file content via base64 encoding (works for all file types)
            val result = sshService.executeCommand("base64 '$expandedPath/$fileName' 2>/dev/null")
            result.onSuccess { base64Content ->
                if (base64Content.isNotBlank()) {
                    // Decode and write to target folder
                    val bytes = android.util.Base64.decode(base64Content.trim(), android.util.Base64.DEFAULT)
                    
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, targetUri)
                    val newFile = docFile?.createFile("application/octet-stream", fileName)
                    newFile?.uri?.let { fileUri ->
                        context.contentResolver.openOutputStream(fileUri)?.use { os ->
                            os.write(bytes)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Log error but continue with other files
            e.printStackTrace()
        }
    }
}
