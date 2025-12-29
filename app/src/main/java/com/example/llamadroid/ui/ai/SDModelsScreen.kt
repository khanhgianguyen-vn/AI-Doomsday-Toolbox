package com.example.llamadroid.ui.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.api.HfModelDto
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.FileInfo
import com.example.llamadroid.data.model.ModelRepository
import com.example.llamadroid.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Recommended SD model search queries
 */
data class SDSearchSuggestion(
    val name: String,
    val query: String,
    val capabilities: List<SDCapability>
)

enum class SDCapability(val label: String, val color: Long) {
    TXT2IMG("txt2img", 0xFF4CAF50),  // Green
    IMG2IMG("img2img", 0xFF2196F3),   // Blue
    UPSCALE("upscale", 0xFFFF9800),   // Orange
    FLUX("FLUX", 0xFF9C27B0)          // Purple - for FLUX components
}

/**
 * SDModelsScreen - Manage Stable Diffusion models with HuggingFace search
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SDModelsScreen(navController: NavController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { com.example.llamadroid.data.SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    val repository = remember { ModelRepository(context, db.modelDao()) }
    val keyboardController = LocalSoftwareKeyboardController.current
    
    // Installed SD models - Classic types
    val sdCheckpoints by db.modelDao().getModelsByType(ModelType.SD_CHECKPOINT)
        .collectAsState(initial = emptyList())
    val sdUpscalers by db.modelDao().getModelsByType(ModelType.SD_UPSCALER)
        .collectAsState(initial = emptyList())
    
    // FLUX-specific component types
    val sdDiffusionModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION)
        .collectAsState(initial = emptyList())
    val sdClipLModels by db.modelDao().getModelsByType(ModelType.SD_CLIP_L)
        .collectAsState(initial = emptyList())
    val sdT5xxlModels by db.modelDao().getModelsByType(ModelType.SD_T5XXL)
        .collectAsState(initial = emptyList())
    val sdVaeModels by db.modelDao().getModelsByType(ModelType.SD_VAE)
        .collectAsState(initial = emptyList())
    val sdControlNetModels by db.modelDao().getModelsByType(ModelType.SD_CONTROLNET)
        .collectAsState(initial = emptyList())
    val sdLoraModels by db.modelDao().getModelsByType(ModelType.SD_LORA)
        .collectAsState(initial = emptyList())
    
    // Total installed count
    val totalInstalledCount = sdCheckpoints.size + sdUpscalers.size + 
        sdDiffusionModels.size + sdClipLModels.size + sdT5xxlModels.size + 
        sdVaeModels.size + sdControlNetModels.size + sdLoraModels.size
    
    // Download progress
    val downloadProgress by DownloadProgressHolder.progress.collectAsState()
    
    // Active downloads count
    val activeDownloads = downloadProgress.filter { it.value > 0f && it.value < 1f }
    
    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<HfModelDto>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    
    // File selection state
    var selectedRepoId by remember { mutableStateOf<String?>(null) }
    var availableFiles by remember { mutableStateOf<List<FileInfo>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    
    // FLUX component reminder dialog
    val showFluxReminderPending by settingsRepo.showFluxReminderPending.collectAsState()
    
    // Selected tab: 0 = Installed, 1 = Downloading, 2 = Discover
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Search suggestions organized by model type and RAM requirements
    val suggestions = remember {
        listOf(
            // === Classic SD Models ===
            SDSearchSuggestion(
                "SD 1.5 GGUF (4GB+)",
                "stable-diffusion gguf q4",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            SDSearchSuggestion(
                "SDXL GGUF (8GB+)",
                "sdxl gguf q4",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            
            // === FLUX Diffusion Models ===
            SDSearchSuggestion(
                "⚡ FLUX Schnell Q4 (8GB+)",
                "city96/FLUX.1-schnell-gguf",
                listOf(SDCapability.FLUX, SDCapability.TXT2IMG)
            ),
            SDSearchSuggestion(
                "⚡ FLUX Dev Q4 (12GB+)",
                "city96/FLUX.1-dev-gguf",
                listOf(SDCapability.FLUX, SDCapability.TXT2IMG)
            ),
            SDSearchSuggestion(
                "⚡ FLUX Lite (4GB+)",
                "flux gguf q2 q3",
                listOf(SDCapability.FLUX, SDCapability.TXT2IMG)
            ),
            
            // === FLUX Text Encoders ===
            SDSearchSuggestion(
                "📝 T5-XXL Encoder (GGUF)",
                "city96/t5-v1_1-xxl-encoder-gguf",
                listOf(SDCapability.FLUX)
            ),
            SDSearchSuggestion(
                "📝 CLIP-L Encoder (GGUF)",
                "zer0int/CLIP-GmP-ViT-L-14",
                listOf(SDCapability.FLUX)
            ),
            
            // === VAE Models ===
            SDSearchSuggestion(
                "🎨 FLUX VAE (GGUF)",
                "city96/FLUX.1-dev-gguf",
                listOf(SDCapability.FLUX)
            ),
            SDSearchSuggestion(
                "🎨 SDXL VAE",
                "sdxl-vae-fp16-fix",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            
            // === ControlNet ===
            SDSearchSuggestion(
                "🎛️ ControlNet GGUF",
                "controlnet gguf",
                listOf(SDCapability.IMG2IMG)
            ),
            
            // === LoRA ===
            SDSearchSuggestion(
                "✨ LoRA Models",
                "lora safetensors",
                listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG)
            ),
            
            // === Upscalers ===
            SDSearchSuggestion(
                "⬆️ ESRGAN 4x Upscaler",
                "esrgan 4x",
                listOf(SDCapability.UPSCALE)
            ),
            SDSearchSuggestion(
                "⬆️ RealESRGAN Anime",
                "realesrgan anime",
                listOf(SDCapability.UPSCALE)
            )
        )
    }
    
    // Search function
    val doSearch: () -> Unit = {
        if (searchQuery.isNotBlank()) {
            isSearching = true
            keyboardController?.hide()
            scope.launch {
                try {
                    searchResults = repository.searchModels(searchQuery)
                } catch (e: Exception) {
                    searchResults = emptyList()
                }
                isSearching = false
            }
        }
    }
    
    // Load files for a repo
    val loadRepoFiles: (String) -> Unit = { repoId ->
        selectedRepoId = repoId
        isLoadingFiles = true
        scope.launch {
            try {
                val files = repository.getGgufFilesWithSize(repoId)
                availableFiles = files
            } catch (e: Exception) {
                availableFiles = emptyList()
            }
            isLoadingFiles = false
        }
    }
    
    // File selection dialog
    if (selectedRepoId != null && availableFiles.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { 
                selectedRepoId = null
                availableFiles = emptyList()
            },
            title = { 
                Column {
                    Text(
                        "Select File",
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        selectedRepoId ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                LazyColumn {
                    items(availableFiles) { fileInfo ->
                        Card(
                            onClick = {
                                // Determine model type based on filename and repo patterns
                                val repoLower = selectedRepoId?.lowercase() ?: ""
                                val fileLower = fileInfo.filename.lowercase()
                                
                                val type = when {
                                    // Upscalers
                                    fileLower.contains("upscale") || fileLower.contains("esrgan") ||
                                    repoLower.contains("esrgan") || repoLower.contains("upscale") -> 
                                        ModelType.SD_UPSCALER
                                    
                                    // FLUX diffusion models
                                    (repoLower.contains("flux") && (fileLower.endsWith(".gguf") || fileLower.contains("diffusion"))) ||
                                    fileLower.contains("flux") && !fileLower.contains("vae") && !fileLower.contains("clip") && !fileLower.contains("t5") ->
                                        ModelType.SD_DIFFUSION
                                    
                                    // T5-XXL encoders
                                    fileLower.contains("t5") || repoLower.contains("t5-v1") || repoLower.contains("t5xxl") ->
                                        ModelType.SD_T5XXL
                                    
                                    // CLIP-L encoders
                                    fileLower.contains("clip") || repoLower.contains("clip-vit") ||
                                    (repoLower.contains("clip") && fileLower.endsWith(".gguf")) ->
                                        ModelType.SD_CLIP_L
                                    
                                    // VAE models
                                    fileLower.contains("vae") || repoLower.contains("vae") ->
                                        ModelType.SD_VAE
                                    
                                    // ControlNet
                                    fileLower.contains("controlnet") || repoLower.contains("controlnet") ->
                                        ModelType.SD_CONTROLNET
                                    
                                    // LoRA
                                    fileLower.contains("lora") || repoLower.contains("lora") ->
                                        ModelType.SD_LORA
                                    
                                    // Default to checkpoint for .gguf and .safetensors files
                                    else -> ModelType.SD_CHECKPOINT
                                }
                                
                                // Use non-suspend async function to avoid crash
                                repository.startDownloadAsync(selectedRepoId!!, fileInfo.filename, type)
                                
                                // Show FLUX component reminder if downloading a FLUX diffusion model
                                if (type == ModelType.SD_DIFFUSION || type == ModelType.SD_CLIP_L || 
                                    type == ModelType.SD_T5XXL || type == ModelType.SD_VAE) {
                                    settingsRepo.setShowFluxReminderPending(true)
                                }
                                
                                selectedRepoId = null
                                availableFiles = emptyList()
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
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        fileInfo.filename,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        fileInfo.formattedSize(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Download",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { 
                    selectedRepoId = null
                    availableFiles = emptyList()
                }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
    
    // Loading dialog
    if (isLoadingFiles) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Loading files...") },
            text = { CircularProgressIndicator() },
            confirmButton = {}
        )
    }
    
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                "🎨 SD Models",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        // Tab Row
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Installed ($totalInstalledCount)") }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Downloading")
                        if (activeDownloads.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Badge { Text("${activeDownloads.size}") }
                        }
                    }
                }
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Discover") }
            )
        }
        
        when (selectedTab) {
            0 -> InstalledSDModelsTab(
                checkpoints = sdCheckpoints,
                upscalers = sdUpscalers,
                diffusionModels = sdDiffusionModels,
                clipLModels = sdClipLModels,
                t5xxlModels = sdT5xxlModels,
                vaeModels = sdVaeModels,
                controlNetModels = sdControlNetModels,
                loraModels = sdLoraModels,
                onDelete = { model ->
                    scope.launch {
                        repository.deleteModel(model)
                    }
                },
                repository = repository,
                settingsRepo = settingsRepo
            )
            1 -> DownloadingTab(
                downloadProgress = downloadProgress,
                onCancel = { filename ->
                    com.example.llamadroid.util.Downloader.cancelDownload(filename)
                    DownloadProgressHolder.removeProgress(filename)
                }
            )
            2 -> DiscoverTab(
                searchQuery = searchQuery,
                onQueryChange = { searchQuery = it },
                onSearch = doSearch,
                isSearching = isSearching,
                searchResults = searchResults,
                suggestions = suggestions,
                onSuggestionClick = { query ->
                    searchQuery = query
                    doSearch()
                },
                onRepoClick = loadRepoFiles
            )
        }
    }
}

@Composable
private fun InstalledSDModelsTab(
    checkpoints: List<ModelEntity>,
    upscalers: List<ModelEntity>,
    diffusionModels: List<ModelEntity>,
    clipLModels: List<ModelEntity>,
    t5xxlModels: List<ModelEntity>,
    vaeModels: List<ModelEntity>,
    controlNetModels: List<ModelEntity>,
    loraModels: List<ModelEntity>,
    onDelete: (ModelEntity) -> Unit,
    repository: ModelRepository,
    settingsRepo: com.example.llamadroid.data.SettingsRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Import state - FILE FIRST approach (FAB launches picker, then show dialog)
    var showImportDialog by remember { mutableStateOf(false) }
    var selectedImportType by remember { mutableStateOf(ModelType.SD_CHECKPOINT) }
    var pendingUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pendingFilename by remember { mutableStateOf("") }
    var supportsTxt2Img by remember { mutableStateOf(true) }
    var supportsImg2Img by remember { mutableStateOf(true) }
    
    // Import progress tracking
    var isImporting by remember { mutableStateOf(false) }
    var importProgress by remember { mutableFloatStateOf(0f) }
    var importingFilename by remember { mutableStateOf("") }
    
    // Export state
    var pendingExportModel by remember { mutableStateOf<ModelEntity?>(null) }
    
    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            pendingExportModel?.let { model ->
                scope.launch(Dispatchers.IO) {
                    try {
                        val sourceFile = File(model.path)
                        if (!sourceFile.exists()) {
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Source file not found", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        
                        val documentFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                        val targetFile = documentFile?.createFile("*/*", model.filename)
                        
                        if (targetFile != null) {
                            context.contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                                sourceFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "Exported: ${model.filename}", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        pendingExportModel = null
                    }
                }
            }
        }
    }
    
    // Export model function
    val exportModel: (ModelEntity) -> Unit = { model ->
        pendingExportModel = model
        exportPicker.launch(null)
    }
    
    // File picker - FAB triggers this directly
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
            title = { Text("Import SD Model") },
            text = {
                val types = listOf(
                    ModelType.SD_CHECKPOINT to "SD Checkpoint (SD1.5/SDXL)",
                    ModelType.SD_DIFFUSION to "FLUX Diffusion Model",
                    ModelType.SD_CLIP_L to "CLIP-L Text Encoder",
                    ModelType.SD_T5XXL to "T5-XXL Text Encoder",
                    ModelType.SD_VAE to "VAE",
                    ModelType.SD_CONTROLNET to "ControlNet",
                    ModelType.SD_LORA to "LoRA",
                    ModelType.SD_UPSCALER to "Upscaler (ESRGAN)"
                )
                
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp)
                ) {
                    item {
                        // Editable filename
                        Text("Filename:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = pendingFilename,
                            onValueChange = { pendingFilename = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("model.safetensors") }
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Model type selection
                        Text("Model Type:", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    items(types) { (type, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedImportType == type,
                                    onClick = { selectedImportType = type }
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedImportType == type,
                                onClick = { selectedImportType = type }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                    
                    // SD capabilities (only shown for checkpoint type)
                    if (selectedImportType == ModelType.SD_CHECKPOINT) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Capabilities:", style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = supportsTxt2Img,
                                    onCheckedChange = { supportsTxt2Img = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Text-to-Image (txt2img)")
                            }
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = supportsImg2Img,
                                    onCheckedChange = { supportsImg2Img = it }
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Image-to-Image (img2img)")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showImportDialog = false
                    val uri = pendingUri!!
                    val filename = pendingFilename
                    val type = selectedImportType
                    
                    val caps = if (type == ModelType.SD_CHECKPOINT) {
                        listOfNotNull(
                            if (supportsTxt2Img) "txt2img" else null,
                            if (supportsImg2Img) "img2img" else null
                        ).joinToString(",")
                    } else {
                        "upscale"
                    }
                    
                    // Show progress dialog
                    isImporting = true
                    importProgress = 0f
                    importingFilename = filename
                    
                    scope.launch(Dispatchers.IO) {
                        importSDModel(context, repository, uri, filename, type, caps) { progress ->
                            importProgress = progress
                        }
                        isImporting = false
                        
                        // Show FLUX component reminder if importing a FLUX model
                        if (type == ModelType.SD_DIFFUSION || type == ModelType.SD_CLIP_L || 
                            type == ModelType.SD_T5XXL || type == ModelType.SD_VAE) {
                            settingsRepo.setShowFluxReminderPending(true)
                        }
                    }
                    
                    // Reset
                    pendingUri = null
                    pendingFilename = ""
                    selectedImportType = ModelType.SD_CHECKPOINT
                    supportsTxt2Img = true
                    supportsImg2Img = true
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
    
    // Import Progress Dialog
    if (isImporting) {
        AlertDialog(
            onDismissRequest = { /* Can't dismiss during import */ },
            title = { Text("Importing Model") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        importingFilename,
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
                        "Please wait...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { /* No confirm button */ }
        )
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        val hasAnyModels = checkpoints.isNotEmpty() || upscalers.isNotEmpty() ||
            diffusionModels.isNotEmpty() || clipLModels.isNotEmpty() || t5xxlModels.isNotEmpty() ||
            vaeModels.isNotEmpty() || controlNetModels.isNotEmpty() || loraModels.isNotEmpty()
        
        if (!hasAnyModels) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Create,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No SD models installed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Go to Discover or tap + to import",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // SD/SDXL Checkpoints
                if (checkpoints.isNotEmpty()) {
                    item {
                        Text(
                            "🎨 Checkpoints (${checkpoints.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(checkpoints) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = listOf(SDCapability.TXT2IMG, SDCapability.IMG2IMG),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
                
                // FLUX Diffusion Models
                if (diffusionModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⚡ FLUX Diffusion (${diffusionModels.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(diffusionModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = listOf(SDCapability.TXT2IMG),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
                
                // CLIP-L Encoders
                if (clipLModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "📝 CLIP-L Encoders (${clipLModels.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(clipLModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = emptyList(),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
                
                // T5-XXL Encoders
                if (t5xxlModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "📝 T5-XXL Encoders (${t5xxlModels.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(t5xxlModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = emptyList(),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
                
                // VAE Models
                if (vaeModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "🎨 VAE (${vaeModels.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(vaeModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = emptyList(),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
                
                // ControlNet Models
                if (controlNetModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "🎛️ ControlNet (${controlNetModels.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(controlNetModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = emptyList(),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
                
                // LoRA Models
                if (loraModels.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "✨ LoRA (${loraModels.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(loraModels) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = emptyList(),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
                
                // Upscalers
                if (upscalers.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "⬆️ Upscalers (${upscalers.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(upscalers) { model ->
                        InstalledModelCard(
                            model = model,
                            capabilities = listOf(SDCapability.UPSCALE),
                            onDelete = { onDelete(model) },
                            onExport = { exportModel(model) }
                        )
                    }
                }
            }
        }
        
        // FAB for import - launches file picker directly
        FloatingActionButton(
            onClick = { filePicker.launch(arrayOf("*/*")) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Import SD model")
        }
    }
}

// Helper function to import SD model with progress (with direct path support)
private suspend fun importSDModel(
    context: android.content.Context,
    repository: ModelRepository,
    uri: android.net.Uri,
    filename: String,
    type: ModelType,
    capabilities: String,
    onProgress: (Float) -> Unit = {}
) {
    try {
        var finalPath: String
        
        // Check if we have "All files access" permission for direct path access
        val hasAllFilesAccess = com.example.llamadroid.util.StoragePermissionHelper.hasAllFilesAccess()
        
        // Try to resolve SAF URI to a real file path (for SD card/external storage)
        val directPath = com.example.llamadroid.util.FilePathResolver.getPathFromUri(context, uri)
        
        if (directPath != null && hasAllFilesAccess && com.example.llamadroid.util.FilePathResolver.isPathAccessible(directPath)) {
            // We can access the file directly! No copy needed.
            com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Using direct path (no copy): $directPath")
            finalPath = directPath
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                onProgress(1f)  // Instant completion
                android.widget.Toast.makeText(
                    context,
                    "Model linked from external storage (no copy needed)",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            // Fallback: Copy the file to app storage
            if (directPath != null && !hasAllFilesAccess) {
                com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Direct path available but missing 'All files access' permission, copying...")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context, 
                        "Tip: Grant 'All files access' in Settings to use models without copying", 
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            
            val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
            val targetFile = File(modelsDir, filename.ifBlank { "imported_sd_model.safetensors" })
            
            // Get file size for progress calculation
            val fileSize = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use {
                it.length
            } ?: 0L
            
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    if (fileSize > 0) {
                        // Copy with progress tracking
                        val buffer = ByteArray(8192)
                        var bytesCopied = 0L
                        var bytes = input.read(buffer)
                        while (bytes >= 0) {
                            output.write(buffer, 0, bytes)
                            bytesCopied += bytes
                            onProgress(bytesCopied.toFloat() / fileSize.toFloat())
                            bytes = input.read(buffer)
                        }
                    } else {
                        // Fallback to simple copy if size unknown
                        input.copyTo(output)
                    }
                }
            }
            
            onProgress(1f)
            finalPath = targetFile.absolutePath
        }
        
        // Get file size
        val file = File(finalPath)
        val sizeBytes = if (file.exists()) file.length() else 0L
        
        val modelEntity = ModelEntity(
            repoId = "local-import",
            filename = filename,
            path = finalPath,
            sizeBytes = sizeBytes,
            type = type,
            sdCapabilities = capabilities
        )
        
        repository.insertModel(modelEntity)
        com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Imported: $filename as ${type.name}")
    } catch (e: Exception) {
        com.example.llamadroid.util.DebugLog.log("[SD-IMPORT] Failed: ${e.message}")
        e.printStackTrace()
    }
}

@Composable
private fun InstalledModelCard(
    model: ModelEntity,
    capabilities: List<SDCapability>,
    onDelete: () -> Unit,
    onExport: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        model.filename,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        FormatUtils.formatFileSize(model.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Export button
                IconButton(onClick = onExport) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Export to Downloads",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                // Delete button
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            // Capability badges
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                capabilities.forEach { cap ->
                    CapabilityBadge(cap)
                }
            }
        }
    }
}

@Composable
private fun DownloadingTab(
    downloadProgress: Map<String, Float>,
    onCancel: (String) -> Unit
) {
    val activeDownloads = downloadProgress.filter { it.value > 0f && it.value < 1f }
    
    if (activeDownloads.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Refresh,
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
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(activeDownloads.toList()) { (key, progress) ->
                val filename = DownloadProgressHolder.getFilename(key) ?: key
                DownloadingCard(
                    filename = filename,
                    progress = progress,
                    onCancel = { onCancel(filename) }
                )
            }
        }
    }
}

@Composable
private fun DownloadingCard(
    filename: String,
    progress: Float,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        filename,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }
}

@Composable
private fun DiscoverTab(
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    isSearching: Boolean,
    searchResults: List<HfModelDto>,
    suggestions: List<SDSearchSuggestion>,
    onSuggestionClick: (String) -> Unit,
    onRepoClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search HuggingFace for SD models...") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, "Clear")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = onSearch,
            modifier = Modifier.fillMaxWidth(),
            enabled = searchQuery.isNotBlank() && !isSearching
        ) {
            if (isSearching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Search, null)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isSearching) "Searching..." else "Search")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Show suggestions if no search yet
            if (searchResults.isEmpty() && !isSearching) {
                item {
                    Text(
                        "Quick Search Suggestions",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap to search HuggingFace",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                items(suggestions) { suggestion ->
                    SuggestionCard(
                        suggestion = suggestion,
                        onClick = { onSuggestionClick(suggestion.query) }
                    )
                }
            }
            
            // Show search results
            if (searchResults.isNotEmpty()) {
                item {
                    Text(
                        "Search Results (${searchResults.size})",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                
                items(searchResults) { result ->
                    SearchResultCard(
                        result = result,
                        onClick = { onRepoClick(result.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: SDSearchSuggestion,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                suggestion.name,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Search: \"${suggestion.query}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestion.capabilities.forEach { cap ->
                    CapabilityBadge(cap)
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: HfModelDto,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.id,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${result.likes}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "${result.downloads}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Default.Add,
                contentDescription = "View files",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CapabilityBadge(capability: SDCapability) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = androidx.compose.ui.graphics.Color(capability.color).copy(alpha = 0.15f)
    ) {
        Text(
            capability.label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = androidx.compose.ui.graphics.Color(capability.color)
        )
    }
}
