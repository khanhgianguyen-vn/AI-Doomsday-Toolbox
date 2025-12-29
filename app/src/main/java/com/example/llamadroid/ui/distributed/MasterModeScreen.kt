package com.example.llamadroid.ui.distributed

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.SavedWorker
import com.example.llamadroid.service.DistributedService
import com.example.llamadroid.service.LlamaService
import com.example.llamadroid.service.WorkerInfo
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.GGUFParser
import com.example.llamadroid.ui.navigation.Screen
import kotlinx.coroutines.launch

/**
 * Master mode screen - select model, manage workers, start distributed inference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MasterModeScreen(navController: NavController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val settingsRepo = remember { com.example.llamadroid.data.SettingsRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    
    // Collect saved workers from database
    val savedWorkers by db.savedWorkerDao().getAllWorkers().collectAsState(initial = emptyList())
    
    // Collect state from DistributedService (active workers)
    val activeWorkers by DistributedService.workers.collectAsState()
    val masterRamMB by DistributedService.masterRamMB.collectAsState()
    
    // Sync enabled saved workers to DistributedService
    LaunchedEffect(savedWorkers) {
        val enabledWorkers = savedWorkers.filter { it.isEnabled }
        // Clear existing and add from database with proportions
        DistributedService.clearWorkers()
        enabledWorkers.forEach { saved ->
            DistributedService.addWorkerManually(
                saved.ip, 
                saved.port, 
                saved.deviceName, 
                saved.ramMB,
                saved.assignedProportion  // Pass the proportion!
            )
        }
    }
    
    // Get ALL models from database and filter properly
    val allModels by db.modelDao().getAllModels().collectAsState(initial = emptyList())
    
    // Debug: log what models we have
    LaunchedEffect(allModels) {
        com.example.llamadroid.util.DebugLog.log("[MasterMode] Total models in DB: ${allModels.size}")
        allModels.forEach {
            val fileExists = java.io.File(it.path).exists()
            com.example.llamadroid.util.DebugLog.log("[MasterMode] Model: ${it.filename}, type=${it.type}, downloaded=${it.isDownloaded}, fileExists=$fileExists")
        }
    }
    
    // Filter to models that can be used for chat inference
    // Include LLM, VISION, and EMBEDDING types
    // Check if file actually exists (for imported models) OR isDownloaded flag is true
    val llmModels = allModels.filter { model ->
        val fileExists = java.io.File(model.path).exists()
        val isUsableType = model.type == ModelType.LLM || model.type == ModelType.VISION || model.type == ModelType.EMBEDDING
        val isAvailable = model.isDownloaded || fileExists
        isUsableType && isAvailable
    }
    
    var selectedModel by remember { mutableStateOf<ModelEntity?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    var showAddWorkerDialog by remember { mutableStateOf(false) }
    var showEditWorkerDialog by remember { mutableStateOf(false) }
    var workerToEdit by remember { mutableStateOf<SavedWorker?>(null) }
    var isStarting by remember { mutableStateOf(false) }
    
    // Model configuration settings
    var contextSize by remember { mutableIntStateOf(4096) }
    var contextSizeText by remember { mutableStateOf("4096") }  // For text input
    var temperature by remember { mutableFloatStateOf(0.7f) }
    var threads by remember { mutableIntStateOf(4) }
    
    // KV Cache settings
    var kvCacheEnabled by remember { mutableStateOf(true) }
    var kvCacheTypeK by remember { mutableStateOf("f16") }
    var kvCacheTypeV by remember { mutableStateOf("f16") }
    
    // Network visibility - when enabled, uses 0.0.0.0 to allow external connections
    var enableNetworkAccess by remember { mutableStateOf(true) }
    
    // Get device memory info
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memInfo)
    val availableRamMB = (memInfo.availMem / (1024 * 1024)).toInt()
    
    var masterRamSlider by remember { mutableFloatStateOf(masterRamMB.toFloat().coerceIn(1024f, availableRamMB.toFloat())) }
    var masterRamText by remember { mutableStateOf(masterRamMB.toString()) }  // For text input
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Master Mode") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Model Selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Select Model",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        OutlinedCard(
                            onClick = { showModelPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedModel?.filename ?: "Tap to select model",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (selectedModel != null) {
                                            formatFileSize(selectedModel!!.sizeBytes)
                                        } else {
                                            "${llmModels.size} model(s) available"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select"
                                )
                            }
                        }
                    }
                }
            }
            
            // Master RAM Configuration
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Your RAM Contribution",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        // RAM with text input (synced with slider)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${masterRamSlider.toInt()} MB",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = masterRamText,
                                onValueChange = { newValue ->
                                    masterRamText = newValue
                                    newValue.toIntOrNull()?.let { ram ->
                                        val clamped = ram.coerceIn(1024, availableRamMB)
                                        masterRamSlider = clamped.toFloat()
                                        DistributedService.setMasterRam(clamped)
                                    }
                                },
                                label = { Text("MB") },
                                singleLine = true,
                                modifier = Modifier.width(100.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                            )
                        }
                        
                        Slider(
                            value = masterRamSlider,
                            onValueChange = { 
                                masterRamSlider = it
                                masterRamText = it.toInt().toString()
                                DistributedService.setMasterRam(it.toInt())
                            },
                            valueRange = 1024f..availableRamMB.toFloat().coerceAtLeast(1024f),
                            steps = ((availableRamMB - 1024) / 512).coerceAtLeast(0)
                        )
                        
                        Text(
                            text = "Available: $availableRamMB MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Model Settings
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "⚙️ Model Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Context Size with text input
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Context Size", style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = contextSizeText,
                                onValueChange = { text ->
                                    contextSizeText = text
                                    text.toIntOrNull()?.let { value ->
                                        if (value in 512..131072) {
                                            contextSize = value
                                        }
                                    }
                                },
                                modifier = Modifier.width(120.dp),
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium,
                                suffix = { Text("tokens", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        Slider(
                            value = contextSize.toFloat(),
                            onValueChange = { 
                                contextSize = it.toInt()
                                contextSizeText = it.toInt().toString()
                            },
                            valueRange = 512f..16384f,
                            steps = 14
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Temperature
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Temperature", style = MaterialTheme.typography.bodyMedium)
                            Text("%.1f".format(temperature), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..2f,
                            steps = 19
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Threads
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Threads", style = MaterialTheme.typography.bodyMedium)
                            Text("$threads", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Slider(
                            value = threads.toFloat(),
                            onValueChange = { threads = it.toInt() },
                            valueRange = 1f..8f,
                            steps = 6
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Network Visibility
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Network Visibility", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (enableNetworkAccess) "Host: 0.0.0.0 (accessible from network)" else "Host: 127.0.0.1 (local only)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = enableNetworkAccess,
                                onCheckedChange = { enableNetworkAccess = it }
                            )
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        // KV Cache Settings
                        Text(
                            text = "🧠 KV Cache",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Enable KV Cache", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Speeds up repeated tokens in context",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = kvCacheEnabled,
                                onCheckedChange = { kvCacheEnabled = it }
                            )
                        }
                        
                        if (kvCacheEnabled) {
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Cache Type K", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = kvCacheTypeK == "f16",
                                        onClick = { kvCacheTypeK = "f16" },
                                        label = { Text("F16") }
                                    )
                                    FilterChip(
                                        selected = kvCacheTypeK == "q8_0",
                                        onClick = { kvCacheTypeK = "q8_0" },
                                        label = { Text("Q8_0") }
                                    )
                                    FilterChip(
                                        selected = kvCacheTypeK == "q4_0",
                                        onClick = { kvCacheTypeK = "q4_0" },
                                        label = { Text("Q4_0") }
                                    )
                                }
                            }
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Cache Type V", style = MaterialTheme.typography.bodyMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = kvCacheTypeV == "f16",
                                        onClick = { kvCacheTypeV = "f16" },
                                        label = { Text("F16") }
                                    )
                                    FilterChip(
                                        selected = kvCacheTypeV == "q8_0",
                                        onClick = { kvCacheTypeV = "q8_0" },
                                        label = { Text("Q8_0") }
                                    )
                                    FilterChip(
                                        selected = kvCacheTypeV == "q4_0",
                                        onClick = { kvCacheTypeV = "q4_0" },
                                        label = { Text("Q4_0") }
                                    )
                                }
                            }
                        }
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                        
                        // Layer Distribution Memory Toggle
                        Text(
                            text = "📊 Layer Distribution",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        val lastDistribution by settingsRepo.lastLayerDistribution.collectAsState()
                        val lastModelPath by settingsRepo.lastDistributionModelPath.collectAsState()
                        var rememberDistribution by remember { mutableStateOf(lastDistribution != null) }
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Remember Layer Split", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = "Keeps same layer assignment for worker cache",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = rememberDistribution,
                                onCheckedChange = { 
                                    rememberDistribution = it
                                    if (!it) {
                                        // Clear saved distribution when disabled
                                        settingsRepo.clearLayerDistributionMemory()
                                    }
                                }
                            )
                        }
                        
                        if (rememberDistribution && lastDistribution != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Saved distribution for:",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = lastModelPath?.substringAfterLast("/") ?: "Unknown model",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    TextButton(
                                        onClick = { settingsRepo.clearLayerDistributionMemory() }
                                    ) {
                                        Text("Clear & Recalculate")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Workers Section Header with Add button
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Workers (${savedWorkers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Add Worker Button
                    OutlinedButton(onClick = { showAddWorkerDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Worker")
                    }
                }
                
                if (savedWorkers.isEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "📱",
                                style = MaterialTheme.typography.displaySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "No workers connected",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "Start Worker mode on another device,\nthen tap 'Add Worker' to enter their IP:PORT",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
            
            // Worker List (from database)
            items(savedWorkers) { savedWorker ->
                SavedWorkerCard(
                    savedWorker = savedWorker,
                    onToggle = { enabled ->
                        coroutineScope.launch {
                            db.savedWorkerDao().setWorkerEnabled(savedWorker.id, enabled)
                        }
                    },
                    onEdit = {
                        workerToEdit = savedWorker
                        showEditWorkerDialog = true
                    },
                    onDelete = {
                        coroutineScope.launch {
                            db.savedWorkerDao().deleteWorker(savedWorker)
                        }
                    }
                )
            }
            
            // Layer Visualization
            if (savedWorkers.any { it.isEnabled } && selectedModel != null) {
                item {
                    LayerVisualizationCard(
                        masterRamMB = masterRamSlider.toInt(),
                        workers = activeWorkers
                    )
                }
            }
            
            // Total RAM Summary
            item {
                val enabledWorkers = savedWorkers.filter { it.isEnabled }
                val totalRam = masterRamSlider.toInt() + enabledWorkers.sumOf { it.ramMB }
                val modelSizeMB = selectedModel?.sizeBytes?.div(1024 * 1024)?.toInt() ?: 0
                val hasEnoughRam = totalRam >= modelSizeMB
                
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (hasEnoughRam || modelSizeMB == 0)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hasEnoughRam || modelSizeMB == 0) "✓" else "⚠️",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Total RAM: ${totalRam} MB",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (modelSizeMB > 0) {
                                Text(
                                    text = "Model needs ~${modelSizeMB} MB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
            
            // Start Button
            item {
                val enabledWorkersList = savedWorkers.filter { it.isEnabled }
                val canStart = selectedModel != null && enabledWorkersList.isNotEmpty()
                
                Button(
                    onClick = {
                        if (selectedModel != null && enabledWorkersList.isNotEmpty()) {
                            isStarting = true
                            
                            // Set master mode with enabled workers
                            DistributedService.setMasterMode(
                                enabledWorkersList.map { "${it.ip}:${it.port}" }
                            )
                            
                            // Determine host based on network visibility setting
                            val host = if (enableNetworkAccess) "0.0.0.0" else "127.0.0.1"
                            
                            // Start LlamaService with settings passed via Intent extras
                            // This avoids modifying global settings
                            val intent = Intent(context, LlamaService::class.java).apply {
                                action = LlamaService.ACTION_START
                                putExtra(LlamaService.EXTRA_MODEL_PATH, selectedModel?.path)
                                // Pass distributed mode settings via extras (isolated from global settings)
                                putExtra(LlamaService.EXTRA_THREADS, threads)
                                putExtra(LlamaService.EXTRA_CONTEXT_SIZE, contextSize)
                                putExtra(LlamaService.EXTRA_TEMPERATURE, temperature)
                                putExtra(LlamaService.EXTRA_HOST, host)
                            }
                            context.startForegroundService(intent)
                            
                            Toast.makeText(
                                context, 
                                "Started with ${enabledWorkersList.size} worker(s). Check dashboard for status.", 
                                Toast.LENGTH_LONG
                            ).show()
                            
                            // Don't auto-navigate - let user see if it works
                            isStarting = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = canStart && !isStarting
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isStarting) "Starting..." else "Distribute & Start",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                
                if (!canStart && !isStarting) {
                    Text(
                        text = when {
                            selectedModel == null -> "Select a model first"
                            enabledWorkersList.isEmpty() -> "Add and enable at least one worker"
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // View Network Status Button
                OutlinedButton(
                    onClick = { navController.navigate(Screen.NetworkVisualization.route) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Info, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Network Status")
                }
            }
        }
    }
    
    // Model Picker Dialog
    if (showModelPicker) {
        AlertDialog(
            onDismissRequest = { showModelPicker = false },
            title = { Text("Select Model") },
            text = {
                if (llmModels.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text("📦", style = MaterialTheme.typography.displayMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No LLM models found.")
                        Text(
                            "Import or download a GGUF model first.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn {
                        items(llmModels) { model ->
                            Surface(
                                onClick = {
                                    selectedModel = model
                                    showModelPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (model == selectedModel)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = model == selectedModel,
                                        onClick = {
                                            selectedModel = model
                                            showModelPicker = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = model.filename,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${formatFileSize(model.sizeBytes)} • ${model.type}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) {
                    Text("Close")
                }
            }
        )
    }
    
    // Add Worker Dialog - manual IP entry
    if (showAddWorkerDialog) {
        var workerName by remember { mutableStateOf("") }
        var ipAddress by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("50052") }
        var ramMB by remember { mutableStateOf("4096") }
        var layers by remember { mutableStateOf("") }  // blank = auto
        
        AlertDialog(
            onDismissRequest = { showAddWorkerDialog = false },
            title = { Text("Add Worker") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Enter the worker details from the Worker device's screen:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    OutlinedTextField(
                        value = workerName,
                        onValueChange = { workerName = it },
                        label = { Text("Worker Name (optional)") },
                        placeholder = { Text("e.g. Phone 2, Tablet, etc.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = ipAddress,
                        onValueChange = { ipAddress = it },
                        label = { Text("IP Address *") },
                        placeholder = { Text("192.168.1.xxx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it },
                        label = { Text("Port") },
                        placeholder = { Text("50052") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = ramMB,
                        onValueChange = { ramMB = it },
                        label = { Text("Worker RAM (MB)") },
                        placeholder = { Text("4096") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = layers,
                        onValueChange = { layers = it },
                        label = { Text("Load Proportion % (optional)") },
                        placeholder = { Text("e.g. 30 for 30%") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("Leave blank for auto (based on RAM)") }
                    )
                    
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💡", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Look at the Worker device's screen for the IP and PORT values",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val portNum = port.toIntOrNull() ?: 50052
                        val ram = ramMB.toIntOrNull() ?: 4096
                        // Parse proportion: user enters 0-100, we store as 0.0-1.0
                        val proportion = layers.toFloatOrNull()?.let { if (it > 1f) it / 100f else it }  // null if blank/invalid = auto
                        val name = workerName.ifBlank { "Worker ${savedWorkers.size + 1}" }
                        if (ipAddress.isNotBlank()) {
                            // Save to database
                            coroutineScope.launch {
                                db.savedWorkerDao().insertWorker(
                                    SavedWorker(
                                        ip = ipAddress.trim(),
                                        port = portNum,
                                        deviceName = name,
                                        ramMB = ram,
                                        isEnabled = true,
                                        assignedProportion = proportion
                                    )
                                )
                            }
                            Toast.makeText(context, "Added: $name ($ipAddress:$portNum)", Toast.LENGTH_SHORT).show()
                        }
                        showAddWorkerDialog = false
                    }
                ) {
                    Text("Add Worker")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddWorkerDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Edit Worker Dialog - capture worker to local val to prevent null issues during composition
    workerToEdit?.let { editingWorker ->
        if (showEditWorkerDialog) {
            key(editingWorker.id) {
                var editIp by remember { mutableStateOf(editingWorker.ip) }
                var editPort by remember { mutableStateOf(editingWorker.port.toString()) }
                var editName by remember { mutableStateOf(editingWorker.deviceName) }
                var editRam by remember { mutableStateOf(editingWorker.ramMB.toString()) }
                // Display as percentage (0-100), store as 0.0-1.0
                var editProportion by remember { mutableStateOf(editingWorker.assignedProportion?.let { "${(it * 100).toInt()}" } ?: "") }
                
                AlertDialog(
                    onDismissRequest = { 
                        showEditWorkerDialog = false
                        workerToEdit = null
                    },
                    title = { Text("Edit Worker") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = { Text("Device Name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editIp,
                                onValueChange = { editIp = it },
                                label = { Text("IP Address *") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editPort,
                                onValueChange = { editPort = it },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editRam,
                                onValueChange = { editRam = it },
                                label = { Text("Worker RAM (MB)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            OutlinedTextField(
                                value = editProportion,
                                onValueChange = { editProportion = it },
                                label = { Text("Load Proportion % (optional)") },
                                placeholder = { Text("e.g. 30 for 30%") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                supportingText = { Text("Leave blank for auto (based on RAM)") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                // Parse proportion: user enters 0-100, we store as 0.0-1.0
                                val proportion = editProportion.toFloatOrNull()?.let { if (it > 1f) it / 100f else it }  // null = auto
                                coroutineScope.launch {
                                    db.savedWorkerDao().updateWorker(
                                        editingWorker.copy(
                                            ip = editIp.trim(),
                                            port = editPort.toIntOrNull() ?: 50052,
                                            deviceName = editName,
                                            ramMB = editRam.toIntOrNull() ?: 4096,
                                            assignedProportion = proportion
                                        )
                                    )
                                }
                                showEditWorkerDialog = false
                                workerToEdit = null
                                Toast.makeText(context, "Worker updated", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("Save")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { 
                            showEditWorkerDialog = false
                            workerToEdit = null
                        }) {
                            Text("Cancel")
                        }
                    }
                )
            }  // End of key() block
        }
    }
}

@Composable
private fun WorkerCard(worker: WorkerInfo, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "✅",
                style = MaterialTheme.typography.headlineSmall
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = worker.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${worker.ip}:${worker.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (worker.availableRamMB > 0) {
                    Text(
                        text = "RAM: ${worker.availableRamMB} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun SavedWorkerCard(
    savedWorker: SavedWorker,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (savedWorker.isEnabled) 
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            else 
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Enable/Disable Switch
            Switch(
                checked = savedWorker.isEnabled,
                onCheckedChange = onToggle,
                modifier = Modifier.padding(end = 8.dp)
            )
            
            // Worker info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = savedWorker.deviceName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (savedWorker.isEnabled) 
                        MaterialTheme.colorScheme.onSurface
                    else 
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    text = "${savedWorker.ip}:${savedWorker.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedWorker.isEnabled)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    text = "RAM: ${savedWorker.ramMB} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedWorker.isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                // Show proportion if set
                val proportionText = savedWorker.assignedProportion?.let { "${(it * 100).toInt()}% load" } ?: "Auto"
                Text(
                    text = "Load: $proportionText",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (savedWorker.isEnabled)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
                )
            }
            
            // Edit Button
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = "Edit",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            // Delete Button
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LayerVisualizationCard(
    masterRamMB: Int,
    workers: List<WorkerInfo>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📊 Load Distribution",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Check if any worker has assigned proportion
            val totalWorkerProportion = workers.mapNotNull { it.assignedProportion }.sum()
            val hasProportionOverride = totalWorkerProportion > 0f
            
            // Calculate master proportion
            val masterProportion = if (hasProportionOverride) {
                (1f - totalWorkerProportion).coerceIn(0.01f, 0.99f)
            } else {
                val totalRam = masterRamMB + workers.sumOf { it.availableRamMB }
                masterRamMB.toFloat() / totalRam
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("🔵", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Master: ${(masterProportion * 100).toInt()}% (${masterRamMB}MB)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            workers.forEachIndexed { index, worker ->
                val workerProportion = if (worker.assignedProportion != null) {
                    worker.assignedProportion
                } else if (!hasProportionOverride) {
                    val totalRam = masterRamMB + workers.sumOf { it.availableRamMB }
                    worker.availableRamMB.toFloat() / totalRam
                } else {
                    0f
                }
                
                val color = when (index % 3) {
                    0 -> "🟢"
                    1 -> "🟡"
                    else -> "🟣"
                }
                
                val proportionLabel = if (worker.assignedProportion != null) {
                    "${(workerProportion * 100).toInt()}% (set)"
                } else {
                    "${(workerProportion * 100).toInt()}% (auto)"
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(color, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${worker.deviceName}: $proportionLabel (${worker.availableRamMB}MB)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
