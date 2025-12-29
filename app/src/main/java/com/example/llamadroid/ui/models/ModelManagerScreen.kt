package com.example.llamadroid.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.example.llamadroid.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.util.Downloader
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(navController: NavController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val repo = remember { ModelRepository(context, db.modelDao()) }
    val viewModel = remember { ModelManagerViewModel(repo) }
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Installed", "Downloading", "Discover")
    
    val progressMap by viewModel.downloadProgress.collectAsState()
    val activeDownloads = progressMap.filter { it.value < 1f }.size

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                )
            )
    ) {
        // Header
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Models",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 32.sp
                )
            )
            Text(
                "Manage your AI models",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { 
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                title,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                            // Show badge for active downloads
                            if (index == 1 && activeDownloads > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.primary
                                ) {
                                    Text("$activeDownloads")
                                }
                            }
                        }
                    }
                )
            }
        }
        
        when (selectedTab) {
            0 -> InstalledTab(viewModel)
            1 -> DownloadingTab(viewModel)
            2 -> DiscoverTab(viewModel)
        }
    }
}

@Composable
fun InstalledTab(viewModel: ModelManagerViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models by viewModel.installedModels.collectAsState()
    
    // Import state - FILE FIRST approach (FAB launches picker, then show dialog)
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedModelType by remember { mutableStateOf(ModelType.LLM) }
    var hasVisionSupport by remember { mutableStateOf(false) }
    var hasEmbeddingSupport by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingFilename by remember { mutableStateOf("") }
    
    // Import progress state
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importFileName by remember { mutableStateOf("") }
    
    // Export state
    var pendingExportModel by remember { mutableStateOf<com.example.llamadroid.data.db.ModelEntity?>(null) }
    
    // Rename state
    var showRenameDialog by remember { mutableStateOf(false) }
    var modelToRename by remember { mutableStateOf<com.example.llamadroid.data.db.ModelEntity?>(null) }
    var newModelName by remember { mutableStateOf("") }
    
    // Export picker launcher
    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let {
            pendingExportModel?.let { model ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val sourceFile = File(model.path)
                        if (!sourceFile.exists()) {
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Source file not found", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        
                        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it)
                        val targetFile = documentFile?.createFile("*/*", model.filename)
                        
                        if (targetFile != null) {
                            context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                                sourceFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Exported: ${model.filename}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        pendingExportModel = null
                    }
                }
            }
        }
    }
    
    // Export function
    val exportModel: (com.example.llamadroid.data.db.ModelEntity) -> Unit = { model ->
        pendingExportModel = model
        exportPicker.launch(null)
    }
    
    // File picker launcher - FAB triggers this directly
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                pendingUri = it
                // Get filename
                val cursor = context.contentResolver.query(it, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            pendingFilename = c.getString(nameIndex)
                        }
                    }
                }
                // Show import dialog after file is selected
                showImportDialog = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    // Combined import dialog (type selection + capabilities)
    if (showImportDialog && pendingUri != null) {
        AlertDialog(
            onDismissRequest = { 
                showImportDialog = false
                pendingUri = null
            },
            title = { Text("Import Model") },
            text = {
                Column {
                    Text("File: $pendingFilename", style = MaterialTheme.typography.bodyMedium)
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Model type selection
                    Text("Model Type:", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val modelTypes = listOf(
                        ModelType.LLM to "LLM (Language Model)",
                        ModelType.EMBEDDING to "Embedding Model",
                        ModelType.VISION_PROJECTOR to "Vision Projector (mmproj)"
                    )
                    
                    modelTypes.forEach { (type, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedModelType == type,
                                    onClick = { selectedModelType = type }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedModelType == type,
                                onClick = { selectedModelType = type }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    
                    // LLM capabilities (only shown for LLM type)
                    if (selectedModelType == ModelType.LLM) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Capabilities:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = hasVisionSupport,
                                onCheckedChange = { hasVisionSupport = it }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Vision Support (VLM)")
                        }
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = hasEmbeddingSupport,
                                onCheckedChange = { hasEmbeddingSupport = it }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Embedding Model")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showImportDialog = false
                    val uri = pendingUri!!
                    val filename = pendingFilename
                    val type = selectedModelType
                    val vision = hasVisionSupport
                    
                    // Start import with progress tracking
                    importFileName = filename
                    isImporting = true
                    importProgress = 0f
                    
                    scope.launch(Dispatchers.IO) {
                        importModelWithProgress(
                            context = context,
                            viewModel = viewModel,
                            uri = uri,
                            filename = filename,
                            type = type,
                            isVision = vision,
                            sdCaps = null,
                            onProgress = { progress ->
                                importProgress = progress
                            },
                            onComplete = {
                                isImporting = false
                            }
                        )
                    }
                    
                    // Reset
                    pendingUri = null
                    pendingFilename = ""
                    selectedModelType = ModelType.LLM
                    hasVisionSupport = false
                    hasEmbeddingSupport = false
                }) {
                    Text("Import")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImportDialog = false
                    pendingUri = null
                    pendingFilename = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Import progress dialog
    if (isImporting) {
        AlertDialog(
            onDismissRequest = { /* Cannot dismiss while importing */ },
            title = { Text(stringResource(R.string.models_import_title)) },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        importFileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { importProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "${(importProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.models_import_wait),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { /* No confirm button */ }
        )
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        if (models.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No models installed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Go to Discover to download or tap + to import",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(models) { model ->
                    ModelCard(
                        title = model.filename,
                        subtitle = model.repoId,
                        sizeText = FormatUtils.formatFileSize(model.sizeBytes),
                        actionIcon = Icons.Default.Delete,
                        actionColor = MaterialTheme.colorScheme.error,
                        onAction = { viewModel.deleteModel(model) },
                        onExport = { exportModel(model) },
                        onRename = {
                            modelToRename = model
                            newModelName = model.filename.substringBeforeLast(".")
                            showRenameDialog = true
                        }
                    )
                }
            }
        }
        
        // Rename Dialog
        if (showRenameDialog && modelToRename != null) {
            val db = remember { com.example.llamadroid.data.db.AppDatabase.getDatabase(context) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("Rename Model") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newModelName,
                            onValueChange = { newModelName = it },
                            label = { Text("New name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        val extension = modelToRename!!.filename.substringAfterLast(".", "")
                        if (extension.isNotEmpty()) {
                            Text(
                                "Extension: .$extension (will be kept)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val model = modelToRename!!
                            val extension = model.filename.substringAfterLast(".", "")
                            val fullNewName = if (extension.isNotEmpty()) "$newModelName.$extension" else newModelName
                            
                            if (fullNewName.isNotBlank() && fullNewName != model.filename) {
                                scope.launch {
                                    try {
                                        // Rename the file on disk
                                        val oldFile = java.io.File(model.path)
                                        if (oldFile.exists()) {
                                            val newFile = java.io.File(oldFile.parent, fullNewName)
                                            if (oldFile.renameTo(newFile)) {
                                                // Update database with new filename and path
                                                db.modelDao().updateFilename(
                                                    oldFilename = model.filename,
                                                    newFilename = fullNewName,
                                                    newPath = newFile.absolutePath
                                                )
                                                android.widget.Toast.makeText(context, "Renamed to $fullNewName", android.widget.Toast.LENGTH_SHORT).show()
                                            } else {
                                                android.widget.Toast.makeText(context, "Failed to rename file", android.widget.Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    } catch (e: Exception) {
                                        android.widget.Toast.makeText(context, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            showRenameDialog = false
                            modelToRename = null
                        }
                    ) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
                }
            )
        }
        
        // FAB for import - launches file picker directly
        FloatingActionButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Import model")
        }
    }
}

// Helper function to import model
private suspend fun importModel(
    context: android.content.Context,
    viewModel: ModelManagerViewModel,
    uri: android.net.Uri,
    filename: String,
    type: ModelType,
    isVision: Boolean,
    sdCaps: String?
) {
    try {
        val settingsRepo = com.example.llamadroid.data.SettingsRepository(context)
        val modelStorageUri = settingsRepo.modelStorageUri.value
        val targetFilename = filename.ifBlank { "imported_model.gguf" }
        
        // Determine subfolder based on type
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
        
        var finalPath: String
        
        // Use app's external files directory if enabled, otherwise internal
        // Note: Native binaries can only access app-specific directories
        val useExternalStorage = modelStorageUri != null
        
        val modelsDir = if (useExternalStorage) {
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                File(externalDir, "models/$subfolder").apply { mkdirs() }
            } else {
                File(context.filesDir, "models").apply { mkdirs() }
            }
        } else {
            File(context.filesDir, "models").apply { mkdirs() }
        }
        
        val targetFile = File(modelsDir, targetFilename)
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        finalPath = targetFile.absolutePath
        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Saved to: $finalPath")
        
        viewModel.importLocalModel(
            path = finalPath,
            filename = filename,
            modelType = type,
            hasVision = isVision,
            hasEmbedding = false,
            sdCapabilities = sdCaps
        )
    } catch (e: Exception) {
        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Error: ${e.message}")
        e.printStackTrace()
    }
}

// Helper function to import model with progress tracking
private suspend fun importModelWithProgress(
    context: android.content.Context,
    viewModel: ModelManagerViewModel,
    uri: android.net.Uri,
    filename: String,
    type: ModelType,
    isVision: Boolean,
    sdCaps: String?,
    onProgress: (Float) -> Unit,
    onComplete: () -> Unit
) {
    try {
        val settingsRepo = com.example.llamadroid.data.SettingsRepository(context)
        val modelStorageUri = settingsRepo.modelStorageUri.value
        val targetFilename = filename.ifBlank { "imported_model.gguf" }
        
        // Determine subfolder based on type
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
        
        var finalPath: String
        var didCopy = false
        
        // Check if we have "All files access" permission for direct path access
        val hasAllFilesAccess = com.example.llamadroid.util.StoragePermissionHelper.hasAllFilesAccess()
        
        // FIRST: Try to resolve SAF URI to a real file path (for SD card/external storage)
        val directPath = com.example.llamadroid.util.FilePathResolver.getPathFromUri(context, uri)
        
        if (directPath != null && hasAllFilesAccess && com.example.llamadroid.util.FilePathResolver.isPathAccessible(directPath)) {
            // We can access the file directly! No copy needed.
            com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Using direct path (no copy): $directPath")
            finalPath = directPath
            didCopy = false
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onProgress(1f)  // Instant completion
            }
        } else {
            // Check if direct path found but no permission
            if (directPath != null && !hasAllFilesAccess) {
                com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Direct path available but missing 'All files access' permission, copying...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context, 
                        "Tip: Grant 'All files access' in Settings to use models without copying", 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            
            // Fallback: Copy the file to app storage
            com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Direct path not available, copying file...")
            
            // Use app's external files directory if enabled, otherwise internal
            val useExternalStorage = modelStorageUri != null
            
            val modelsDir = if (useExternalStorage) {
                val externalDir = context.getExternalFilesDir(null)
                if (externalDir != null) {
                    File(externalDir, "models/$subfolder").apply { mkdirs() }
                } else {
                    File(context.filesDir, "models").apply { mkdirs() }
                }
            } else {
                File(context.filesDir, "models").apply { mkdirs() }
            }
            
            val targetFile = File(modelsDir, targetFilename)
            
            // Get total file size for progress tracking
            val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { 
                it.length 
            } ?: 0L
            
            com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Starting copy: $filename (${fileSize / (1024*1024)} MB)")
            
            // Copy with progress tracking
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgressUpdate = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Update progress every 100KB to avoid too many UI updates
                        if (totalBytesRead - lastProgressUpdate > 100_000) {
                            val progress = if (fileSize > 0) {
                                (totalBytesRead.toFloat() / fileSize).coerceIn(0f, 1f)
                            } else {
                                0f
                            }
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                onProgress(progress)
                            }
                            lastProgressUpdate = totalBytesRead
                        }
                    }
                }
            }
            
            finalPath = targetFile.absolutePath
            didCopy = true
            com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Saved to: $finalPath")
            
            // Final progress update
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onProgress(1f)
            }
        }
        
        // Parse GGUF to detect layer count
        var layerCount = 0
        if (type == ModelType.LLM && finalPath.endsWith(".gguf")) {
            try {
                val modelInfo = com.example.llamadroid.util.GGUFParser.parse(finalPath)
                if (modelInfo != null) {
                    layerCount = modelInfo.layerCount
                    com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Detected $layerCount layers from GGUF")
                }
            } catch (e: Exception) {
                com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Failed to parse GGUF for layers: ${e.message}")
            }
        }
        
        viewModel.importLocalModel(
            path = finalPath,
            filename = filename,
            modelType = type,
            hasVision = isVision,
            hasEmbedding = false,
            sdCapabilities = sdCaps,
            layerCount = layerCount
        )
        
        if (!didCopy) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context, 
                    "Model linked from external storage (no copy needed)", 
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    } catch (e: Exception) {
        com.example.llamadroid.util.DebugLog.log("[MODEL-IMPORT] Error: ${e.message}")
        e.printStackTrace()
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            onComplete()
        }
    }
}

@Composable
fun DownloadingTab(viewModel: ModelManagerViewModel) {
    val progressMap by viewModel.downloadProgress.collectAsState()
    val activeDownloads = progressMap.filter { it.value < 1f }
    
    if (activeDownloads.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No active downloads",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Models will appear here while downloading",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(activeDownloads.toList()) { (repoId, progress) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    repoId.substringAfterLast("/"),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1
                                )
                                Text(
                                    repoId.substringBeforeLast("/", ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Cancel button
                            IconButton(
                                onClick = { 
                                    val filename = com.example.llamadroid.data.model.DownloadProgressHolder.getFilename(repoId)
                                    if (filename != null) {
                                        Downloader.cancelDownload(filename)
                                    }
                                    com.example.llamadroid.data.model.DownloadProgressHolder.removeProgress(repoId)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Cancel",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverTab(viewModel: ModelManagerViewModel) {
    var query by remember { mutableStateOf("llama-3") }
    val results by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val progressMap by viewModel.downloadProgress.collectAsState()
    val repoVisionCache by viewModel.repoVisionCache.collectAsState()
    
    val selectedRepoId by viewModel.selectedRepoId.collectAsState()
    val availableFiles by viewModel.availableFiles.collectAsState()
    val hasVisionSupport by viewModel.hasVisionSupport.collectAsState()
    val visionFiles by viewModel.visionFiles.collectAsState()
    val showVisionPrompt by viewModel.showVisionPrompt.collectAsState()
    val pendingVisionDownload by viewModel.pendingVisionDownload.collectAsState()
    
    // Vision projector download prompt
    if (showVisionPrompt && pendingVisionDownload != null) {
        val (repoId, visionFile) = pendingVisionDownload!!
        AlertDialog(
            onDismissRequest = { viewModel.dismissVisionPrompt() },
            icon = {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
            },
            title = { 
                Text(
                    "Vision Model Detected",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        "This model supports vision capabilities (image input).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Would you like to download the vision projector file to enable this feature?",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    visionFile.filename,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    visionFile.formattedSize(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.downloadVisionProjector() }) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissVisionPrompt() }) {
                    Text("Skip")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
    
    // State for auto-download mmproj checkbox
    var downloadMmproj by remember { mutableStateOf(true) }
    
    // Reset checkbox when repo changes
    LaunchedEffect(selectedRepoId) {
        downloadMmproj = true
    }
    
    // File selection dialog
    if (selectedRepoId != null && availableFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { viewModel.clearSelection() },
            title = { 
                Column {
                    Text(
                        "Select Quantization",
                        fontWeight = FontWeight.Bold
                    )
                    // Show vision support badge and mmproj checkbox
                    if (hasVisionSupport) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Vision Support",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        // Checkbox to opt-in/out of mmproj download
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = downloadMmproj,
                                onCheckedChange = { downloadMmproj = it }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Download vision projector",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    visionFiles.firstOrNull()?.filename ?: "mmproj file",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            text = {
                LazyColumn {
                    items(availableFiles) { fileInfo ->
                        Card(
                            onClick = {
                                viewModel.downloadModel(selectedRepoId!!, fileInfo.filename, ModelType.LLM)
                                // Auto-download mmproj if checkbox is checked
                                if (hasVisionSupport && downloadMmproj && visionFiles.isNotEmpty()) {
                                    viewModel.downloadVisionProjector()
                                }
                                // Close dialog but keep vision state for prompt after download
                                viewModel.closeFileSelectionDialog()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    fileInfo.filename,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    fileInfo.formattedSize(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSelection() }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search HuggingFace...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
                FilledIconButton(
                    onClick = { viewModel.search(query, ModelType.LLM) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        }
        
        if (isSearching) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(RoundedCornerShape(4.dp))
            )
        }
        
        LazyColumn(
            contentPadding = PaddingValues(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(results) { hfModel ->
                val progress = progressMap[hfModel.id]
                val isDownloading = progress != null && progress < 1f
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    hfModel.id.substringAfterLast("/"),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    hfModel.id.substringBeforeLast("/", ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!isDownloading) {
                                FilledTonalIconButton(
                                    onClick = { viewModel.selectRepoForDownload(hfModel.id) }
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "Download")
                                }
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "${hfModel.downloads}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            // Vision badge - use cache if available, fall back to name pattern
                            val cachedVision = repoVisionCache[hfModel.id]
                            val modelNameLower = hfModel.id.lowercase()
                            val hasVisionByName = modelNameLower.contains("llava") || 
                                modelNameLower.contains("vision") ||
                                modelNameLower.contains("-vl") ||
                                modelNameLower.contains("vlm") ||
                                modelNameLower.contains("visual") ||
                                modelNameLower.contains("pixtral") ||
                                modelNameLower.contains("qwen2-vl") ||
                                modelNameLower.contains("minicpm-v")
                            
                            // Show badge if either cache confirms vision OR name suggests it (while API checks)
                            val showVisionBadge = cachedVision == true || (cachedVision == null && hasVisionByName)
                            
                            if (showVisionBadge) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(MaterialTheme.colorScheme.primaryContainer)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(
                                        if (cachedVision == true) "Vision" else "Vision?",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        
                        if (isDownloading) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { progress!! },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                            Text(
                                "Downloading ${(progress!! * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModelCard(
    title: String,
    subtitle: String,
    sizeText: String,
    actionIcon: ImageVector,
    actionColor: Color,
    onAction: () -> Unit,
    onExport: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1
                    )
                    Text(
                        "$subtitle • $sizeText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row {
                onRename?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Default.Edit, "Rename", tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                onExport?.let {
                    IconButton(onClick = it) {
                        Icon(Icons.Default.Share, "Export", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                IconButton(onClick = onAction) {
                    Icon(actionIcon, null, tint = actionColor)
                }
            }
        }
    }
}
