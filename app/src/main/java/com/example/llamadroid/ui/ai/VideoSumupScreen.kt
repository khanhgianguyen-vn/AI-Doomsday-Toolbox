package com.example.llamadroid.ui.ai

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.example.llamadroid.service.VideoSumupService
import com.example.llamadroid.service.VideoSumupState
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSumupScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    
    var selectedVideoString by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedVideo = selectedVideoString?.let { android.net.Uri.parse(it) }
    
    val state by VideoSumupService.state.collectAsState()
    val progress by VideoSumupService.progress.collectAsState()
    val result by VideoSumupService.result.collectAsState()
    
    var transcript by rememberSaveable { mutableStateOf("") }
    var summary by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    
    // Model selection
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val llmModels by db.modelDao().getModelsByType(ModelType.LLM).collectAsState(initial = emptyList())
    var selectedWhisperPath by remember { mutableStateOf<String?>(null) }
    var selectedLlmPath by remember { mutableStateOf<String?>(null) }
    
    // LLM Parameters (local state)
    var threads by remember { mutableIntStateOf(4) }
    var contextSize by remember { mutableIntStateOf(2048) }
    var maxTokens by remember { mutableIntStateOf(300) }
    var temperature by remember { mutableFloatStateOf(0.7f) }
    
    // Auto-select first available models
    LaunchedEffect(whisperModels) {
        if (selectedWhisperPath == null && whisperModels.isNotEmpty()) {
            selectedWhisperPath = whisperModels.first().path
        }
    }
    LaunchedEffect(llmModels) {
        if (selectedLlmPath == null && llmModels.isNotEmpty()) {
            selectedLlmPath = llmModels.first().path
        }
    }
    
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            selectedVideoString = it.toString()
            transcript = ""
            summary = ""
            errorMessage = null
        }
    }
    
    // Observe result
    LaunchedEffect(result) {
        result?.fold(
            onSuccess = { r ->
                transcript = r.transcript
                summary = r.summary
                errorMessage = null
                VideoSumupService.clearResult()
            },
            onFailure = { e ->
                if (e.message != "Cancelled") {
                    errorMessage = e.message ?: "Failed"
                }
                VideoSumupService.clearResult()
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("🎥 " + stringResource(R.string.video_sumup_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
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
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🎥 Video Sumup AI", fontWeight = FontWeight.Bold)
                    Text(
                        "Extract audio → Transcribe with Whisper → Summarize with LLM",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Model selection
            Text("Models", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Whisper model
            var whisperExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = whisperExpanded,
                onExpandedChange = { whisperExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedWhisperPath?.let { File(it).name } ?: "Select Whisper Model",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Whisper Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = whisperExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = whisperExpanded,
                    onDismissRequest = { whisperExpanded = false }
                ) {
                    whisperModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.filename) },
                            onClick = {
                                selectedWhisperPath = model.path
                                whisperExpanded = false
                            }
                        )
                    }
                    if (whisperModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No Whisper models - download one first") },
                            onClick = { whisperExpanded = false }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // LLM model
            var llmExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = llmExpanded,
                onExpandedChange = { llmExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedLlmPath?.let { File(it).name } ?: "Select LLM Model",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("LLM Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = llmExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = llmExpanded,
                    onDismissRequest = { llmExpanded = false }
                ) {
                    llmModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.filename) },
                            onClick = {
                                selectedLlmPath = model.path
                                llmExpanded = false
                            }
                        )
                    }
                    if (llmModels.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("No LLM models - download one first") },
                            onClick = { llmExpanded = false }
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // LLM Parameters Section
            Text("LLM Parameters", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Threads slider
            IntSliderWithInput(
                value = threads,
                onValueChange = { threads = it },
                valueRange = 1..16,
                label = "Threads"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Context size slider
            IntSliderWithInput(
                value = contextSize,
                onValueChange = { contextSize = it },
                valueRange = 512..8192,
                label = "Context Size"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Max tokens slider
            IntSliderWithInput(
                value = maxTokens,
                onValueChange = { maxTokens = it },
                valueRange = 64..2048,
                label = "Max Tokens"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Temperature slider
            SliderWithInput(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0f..2f,
                label = "Temperature",
                decimalPlaces = 1
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // KV Cache Quantization
            val kvCacheEnabled by settingsRepo.videoKvCacheEnabled.collectAsState()
            val kvCacheTypeK by settingsRepo.videoKvCacheTypeK.collectAsState()
            val kvCacheTypeV by settingsRepo.videoKvCacheTypeV.collectAsState()
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
                    onCheckedChange = { settingsRepo.setVideoKvCacheEnabled(it) }
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
                                    settingsRepo.setVideoKvCacheTypeK(type)
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
                                    settingsRepo.setVideoKvCacheTypeV(type)
                                    showTypeVMenu = false
                                })
                            }
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Step 1: Select video
            if (selectedVideo == null) {
                Button(
                    onClick = { videoPicker.launch(arrayOf("video/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select Video")
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🎬", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            selectedVideo.lastPathSegment ?: "Video",
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { selectedVideoString = null }) {
                            Icon(Icons.Default.Close, "Remove")
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Processing state
                when (val currentState = state) {
                    is VideoSumupState.Idle -> {
                        if (summary.isEmpty()) {
                            Button(
                                onClick = {
                                    if (selectedWhisperPath == null) {
                                        Toast.makeText(context, "Please select a Whisper model", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    if (selectedLlmPath == null) {
                                        Toast.makeText(context, "Please select an LLM model", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    
                                    scope.launch {
                                        // Copy video to cache
                                        val videoPath = try {
                                            context.contentResolver.openInputStream(selectedVideo)?.use { input ->
                                                val tempFile = File(context.cacheDir, "temp_video.mp4")
                                                tempFile.outputStream().use { output ->
                                                    input.copyTo(output)
                                                }
                                                tempFile.absolutePath
                                            }
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Could not read video: ${e.message}", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        
                                        if (videoPath != null) {
                                            VideoSumupService.startSummarization(
                                                context = context,
                                                videoPath = videoPath,
                                                videoFileName = selectedVideo.lastPathSegment ?: "video.mp4",
                                                whisperModelPath = selectedWhisperPath!!,
                                                llmModelPath = selectedLlmPath!!,
                                                threads = threads,  // Use local UI value
                                                contextSize = contextSize,
                                                maxTokens = maxTokens,
                                                temperature = temperature
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = selectedWhisperPath != null && selectedLlmPath != null
                            ) {
                                Icon(Icons.Default.PlayArrow, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Summarize Video")
                            }
                        }
                    }
                    
                    is VideoSumupState.ExtractingAudio,
                    is VideoSumupState.Transcribing,
                    is VideoSumupState.Summarizing -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(progress, fontWeight = FontWeight.Medium)
                                Text(
                                    when (currentState) {
                                        is VideoSumupState.ExtractingAudio -> "Step 1/3"
                                        is VideoSumupState.Transcribing -> "Step 2/3"
                                        is VideoSumupState.Summarizing -> "Step 3/3"
                                        else -> ""
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        OutlinedButton(
                            onClick = { VideoSumupService.cancel() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Close, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cancel")
                        }
                    }
                    
                    is VideoSumupState.Error -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                currentState.message,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                
                // Error message
                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(error, modifier = Modifier.padding(16.dp))
                    }
                }
                
                // Results
                if (summary.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("📝 Summary", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(summary)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    var showTranscript by remember { mutableStateOf(false) }
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showTranscript = !showTranscript }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("📜 Transcript", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                Icon(
                                    if (showTranscript) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    null
                                )
                            }
                            if (showTranscript) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(transcript, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        "✅ Saved to Notes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
