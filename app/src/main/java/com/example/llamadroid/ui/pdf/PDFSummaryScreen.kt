package com.example.llamadroid.ui.pdf

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.service.PDFService
import com.example.llamadroid.service.PDFSummaryService
import kotlinx.coroutines.launch
import java.io.File
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput

/**
 * PDF AI Summary Screen - Uses LLM to summarize PDF text
 * State persists across tab changes via rememberSaveable
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFSummaryScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val pdfService = remember { PDFService(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    
    // State - using rememberSaveable for tab change persistence
    // URI stored as string for serialization
    var selectedPdfString by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedPdf = selectedPdfString?.let { android.net.Uri.parse(it) }
    
    var extractedText by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var progress by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    
    // Store the PDF name when summarization starts (to use correct name when saving)
    var summarizingPdfName by rememberSaveable { mutableStateOf<String?>(null) }
    
    // PDF-specific settings (with fallback to main LLM settings)
    val llmModels by db.modelDao().getModelsByType(ModelType.LLM).collectAsState(initial = emptyList())
    
    // Local state for settings (synced to/from SettingsRepository)
    val savedModelPath by settingsRepo.pdfModelPath.collectAsState()
    val mainModelPath by settingsRepo.selectedModelPath.collectAsState()
    var selectedModelPath by remember { mutableStateOf(savedModelPath ?: mainModelPath) }
    var threads by remember { mutableIntStateOf(4) }
    var contextSize by remember { mutableIntStateOf(2048) }
    var maxTokens by remember { mutableIntStateOf(512) }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    
    // Initialize from saved settings
    LaunchedEffect(Unit) {
        threads = settingsRepo.pdfThreads.value
        contextSize = settingsRepo.pdfContextSize.value
        maxTokens = settingsRepo.pdfMaxTokens.value
        temperature = settingsRepo.pdfTemperature.value
    }
    
    // Auto-select first available model if none selected
    LaunchedEffect(llmModels, savedModelPath, mainModelPath) {
        if (selectedModelPath == null && llmModels.isNotEmpty()) {
            selectedModelPath = llmModels.first().path
        } else if (selectedModelPath == null && savedModelPath != null) {
            selectedModelPath = savedModelPath
        } else if (selectedModelPath == null && mainModelPath != null) {
            selectedModelPath = mainModelPath
        }
    }
    
    // Track service state
    val isSummarizing by PDFSummaryService.isSummarizing.collectAsState()
    val currentChunk by PDFSummaryService.currentChunk.collectAsState()
    val totalChunks by PDFSummaryService.totalChunks.collectAsState()
    val currentPhase by PDFSummaryService.currentPhase.collectAsState()
    
    // Observe result from service at TOP LEVEL (not inside conditional)
    // This ensures result is processed even if user navigates away
    val serviceResult by PDFSummaryService.result.collectAsState()
    
    LaunchedEffect(serviceResult) {
        serviceResult?.let { result ->
            result.fold(
                onSuccess = { summaryText ->
                    summary = summaryText
                    progress = "Summary complete! Auto-saving..."
                    
                    // Use the stored PDF name (not current selectedPdf which may have changed)
                    val pdfName = summarizingPdfName ?: "PDF"
                    
                    // AUTO-SAVE to Notes
                    try {
                        db.noteDao().insert(
                            NoteEntity(
                                title = "Summary: $pdfName",
                                content = summaryText,
                                type = NoteType.PDF_SUMMARY,
                                sourceFile = pdfName
                            )
                        )
                        com.example.llamadroid.util.DebugLog.log("[PDF-AI] Summary saved to Notes: $pdfName - ${summaryText.length} chars")
                        progress = "Saved to Notes!"
                        Toast.makeText(context, "Summary saved to Notes!", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        com.example.llamadroid.util.DebugLog.log("[PDF-AI] Failed to save: ${e.message}")
                        progress = "Error saving to Notes"
                    }
                    PDFSummaryService.clearResult()
                },
                onFailure = {
                    if (it.message != "Cancelled") {
                        errorMessage = it.message ?: "Summarization failed"
                    }
                    progress = ""
                    PDFSummaryService.clearResult()
                }
            )
        }
    }
    
    // PDF picker
    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { }
            selectedPdfString = it.toString()
            extractedText = ""
            summary = ""
            errorMessage = null
        }
    }
    
    // Note: No DisposableEffect cancel - process survives tab changes
    // Cancel only happens on explicit back button press
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF AI Summary") },
                navigationIcon = {
                    IconButton(onClick = { 
                        // Note: Do NOT cancel on back - process continues in background
                        navController.popBackStack() 
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Settings icon
                    IconButton(onClick = { navController.navigate("settings_pdf") }) {
                        Icon(Icons.Default.Settings, "PDF Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Model Selection Section
            Text("LLM Model", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            var modelExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedModelPath?.let { File(it).name } ?: "Select LLM Model",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    llmModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.filename) },
                            onClick = {
                                selectedModelPath = model.path
                                settingsRepo.setPdfModelPath(model.path)
                                modelExpanded = false
                            }
                        )
                    }
                    if (llmModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No LLM models - download one first") },
                            onClick = { modelExpanded = false }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Parameters Section
            Text("Parameters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Threads slider
            IntSliderWithInput(
                value = threads,
                onValueChange = { 
                    threads = it
                    settingsRepo.setPdfThreads(threads)
                },
                valueRange = 1..16,
                label = "Threads"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Context size slider
            IntSliderWithInput(
                value = contextSize,
                onValueChange = { 
                    contextSize = it
                    settingsRepo.setPdfContextSize(contextSize)
                },
                valueRange = 512..8192,
                label = "Context"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Max tokens slider
            IntSliderWithInput(
                value = maxTokens,
                onValueChange = { 
                    maxTokens = it
                    settingsRepo.setPdfMaxTokens(maxTokens)
                },
                valueRange = 64..2048,
                label = "Max Tokens"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Temperature slider
            SliderWithInput(
                value = temperature,
                onValueChange = { 
                    temperature = it
                    settingsRepo.setPdfTemperature(temperature)
                },
                valueRange = 0f..2f,
                label = "Temperature",
                decimalPlaces = 1
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // KV Cache Quantization
            val kvCacheEnabled by settingsRepo.pdfKvCacheEnabled.collectAsState()
            val kvCacheTypeK by settingsRepo.pdfKvCacheTypeK.collectAsState()
            val kvCacheTypeV by settingsRepo.pdfKvCacheTypeV.collectAsState()
            val cacheTypes = listOf("f16", "q8_0", "q4_0")
            var showTypeKMenu by remember { mutableStateOf(false) }
            var showTypeVMenu by remember { mutableStateOf(false) }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("💾 KV Cache Quantization", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = kvCacheEnabled,
                    onCheckedChange = { settingsRepo.setPdfKvCacheEnabled(it) }
                )
            }
            
            if (kvCacheEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Box {
                        OutlinedButton(onClick = { showTypeKMenu = true }) {
                            Text("K: $kvCacheTypeK")
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = showTypeKMenu, onDismissRequest = { showTypeKMenu = false }) {
                            cacheTypes.forEach { type ->
                                DropdownMenuItem(text = { Text(type) }, onClick = {
                                    settingsRepo.setPdfKvCacheTypeK(type)
                                    showTypeKMenu = false
                                })
                            }
                        }
                    }
                    Box {
                        OutlinedButton(onClick = { showTypeVMenu = true }) {
                            Text("V: $kvCacheTypeV")
                            Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded = showTypeVMenu, onDismissRequest = { showTypeVMenu = false }) {
                            cacheTypes.forEach { type ->
                                DropdownMenuItem(text = { Text(type) }, onClick = {
                                    settingsRepo.setPdfKvCacheTypeV(type)
                                    showTypeVMenu = false
                                })
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Error display
            errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "❌ $error",
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        IconButton(onClick = { errorMessage = null }) {
                            Icon(Icons.Default.Close, "Dismiss")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            // PDF selection
            if (selectedPdf == null) {
                OutlinedButton(
                    onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select PDF")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄", style = MaterialTheme.typography.headlineSmall)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            selectedPdf?.lastPathSegment ?: "Selected PDF",
                            modifier = Modifier.weight(1f),
                            maxLines = 2
                        )
                        IconButton(onClick = { 
                            selectedPdfString = null
                            extractedText = ""
                            summary = ""
                            errorMessage = null
                        }) {
                            Icon(Icons.Default.Close, "Remove")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Step 1: Extract text
                if (extractedText.isEmpty()) {
                    Button(
                        onClick = {
                            scope.launch {
                                isExtracting = true
                                progress = "Extracting text from PDF..."
                                errorMessage = null
                                try {
                                    val result = pdfService.extractText(selectedPdf!!)
                                    result.fold(
                                        onSuccess = { text ->
                                            extractedText = text
                                            progress = "Extracted ${text.length} characters"
                                        },
                                        onFailure = {
                                            errorMessage = "Failed to extract text: ${it.message}"
                                            progress = ""
                                        }
                                    )
                                } finally {
                                    isExtracting = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isExtracting && selectedModelPath != null
                    ) {
                        if (isExtracting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Step 1: Extract Text")
                    }
                } else {
                    // Show extracted text info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("✅ Text Extracted", fontWeight = FontWeight.Bold)
                            Text(
                                "${extractedText.length} characters, ~${extractedText.split("\\s+".toRegex()).size} words",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Step 2: Summarize
                    if (summary.isEmpty()) {
                        Button(
                            onClick = {
                                progress = "Summarizing with AI..."
                                errorMessage = null
                                
                                // Store the PDF name NOW so it's correct when saving later
                                val pdfName = selectedPdf?.lastPathSegment ?: "PDF"
                                summarizingPdfName = pdfName
                                
                                PDFSummaryService.startSummarization(
                                    context = context,
                                    modelPath = selectedModelPath!!,
                                    text = extractedText,
                                    pdfFileName = pdfName,
                                    threads = threads,
                                    contextSize = contextSize,
                                    temperature = temperature,
                                    maxTokens = maxTokens
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isSummarizing
                        ) {
                            if (isSummarizing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                val progressText = when {
                                    totalChunks > 1 && currentChunk > 0 -> 
                                        "Chunk $currentChunk/$totalChunks..."
                                    currentPhase.isNotEmpty() -> 
                                        currentPhase
                                    else -> 
                                        "Summarizing..."
                                }
                                Text(progressText)
                            } else {
                                Text("Step 2: Generate AI Summary")
                            }
                        }
                        
                        // Show detailed progress when chunking
                        if (isSummarizing && totalChunks > 1) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("📊 Multi-chunk Processing", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { if (currentChunk > 0) currentChunk.toFloat() / totalChunks else 0f },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        when {
                                            currentChunk > 0 -> "Summarizing chunk $currentChunk of $totalChunks..."
                                            currentPhase.contains("Unifying") -> "Combining all summaries..."
                                            else -> currentPhase
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        
                        if (isSummarizing) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { PDFSummaryService.cancel() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Cancel")
                            }
                        }
                    } else {
                        // Show summary
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📝 AI Summary", fontWeight = FontWeight.Bold)
                            Text("✅ Saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(16.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(summary)
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Done button
                        Button(
                            onClick = { navController.popBackStack() },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Check, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Done")
                        }
                    }
                }
                
                // Progress
                if (progress.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        progress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
