package com.example.llamadroid.ui.ai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.components.SliderWithInput
import com.example.llamadroid.ui.components.IntSliderWithInput
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow

/**
 * Workflows Screen - Sequential AI operations
 * State is preserved when switching between workflows
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(navController: NavController) {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val settingsRepo = remember { SettingsRepository(context) }
    
    // Selected workflow: 0 = none, 1 = transcribe+summary, 2 = txt2img+upscale
    var selectedWorkflow by remember { mutableIntStateOf(0) }
    
    // Workflow output directory
    val workflowOutputDir = remember { 
        File(context.filesDir, "sd_output/workflow").apply { mkdirs() } 
    }
    
    // ===== txt2img+Upscale workflow state (persisted across tab changes) =====
    var txt2imgModelPath by remember { mutableStateOf<String?>(null) }
    var txt2imgPrompt by remember { mutableStateOf("") }
    var txt2imgNegativePrompt by remember { mutableStateOf("") }
    var txt2imgWidth by remember { mutableIntStateOf(512) }
    var txt2imgHeight by remember { mutableIntStateOf(512) }
    var txt2imgSteps by remember { mutableIntStateOf(20) }
    var txt2imgCfgScale by remember { mutableFloatStateOf(7.0f) }
    var txt2imgSeed by remember { mutableLongStateOf(-1L) }
    var txt2imgSampler by remember { mutableStateOf(SamplingMethod.EULER_A) }
    var txt2imgThreads by remember { mutableIntStateOf(4) }
    var upscalerPath by remember { mutableStateOf<String?>(null) }
    var upscaleFactor by remember { mutableIntStateOf(2) }
    var upscaleRepeats by remember { mutableIntStateOf(1) }
    var upscaleThreads by remember { mutableIntStateOf(4) }
    var txt2imgIsRunning by remember { mutableStateOf(false) }
    var txt2imgStep by remember { mutableStateOf("") }
    var txt2imgProgress by remember { mutableFloatStateOf(0f) }
    var txt2imgResultPath by remember { mutableStateOf<String?>(null) }
    var txt2imgError by remember { mutableStateOf<String?>(null) }
    
    // Observe workflow state holders at top level (persists across tab changes)
    val workflowTxt2imgState by SDModeStateHolder.workflowTxt2img.state.collectAsState()
    val workflowTxt2imgProgress by SDModeStateHolder.workflowTxt2img.progress.collectAsState()
    val workflowUpscaleState by SDModeStateHolder.workflowUpscale.state.collectAsState()
    val workflowUpscaleProgress by SDModeStateHolder.workflowUpscale.progress.collectAsState()
    
    // Update txt2img workflow progress based on state holders (runs at top level, survives tab changes)
    LaunchedEffect(workflowTxt2imgState, workflowTxt2imgProgress, workflowUpscaleState, workflowUpscaleProgress) {
        if (txt2imgIsRunning) {
            when {
                workflowTxt2imgState is SDGenerationState.Generating -> {
                    txt2imgStep = "Step 1/2: Generating... ${(workflowTxt2imgProgress * 100).toInt()}%"
                    txt2imgProgress = workflowTxt2imgProgress * 0.5f  // 0-50% for txt2img
                }
                workflowUpscaleState is SDGenerationState.Generating -> {
                    txt2imgStep = "Step 2/2: Upscaling... ${(workflowUpscaleProgress * 100).toInt()}%"
                    txt2imgProgress = 0.5f + workflowUpscaleProgress * 0.5f  // 50-100% for upscale
                }
            }
        }
    }
    
    // ===== Transcribe+Summary workflow state (persisted via SettingsRepository) =====
    val persistedWhisperModel by settingsRepo.workflowWhisperModelPath.collectAsState()
    val persistedLlmModel by settingsRepo.workflowLlmModelPath.collectAsState()
    val persistedWhisperThreads by settingsRepo.workflowWhisperThreads.collectAsState()
    val persistedLlmThreads by settingsRepo.workflowLlmThreads.collectAsState()
    val persistedLanguage by settingsRepo.workflowWhisperLanguage.collectAsState()
    val persistedContext by settingsRepo.workflowContext.collectAsState()
    val persistedTemperature by settingsRepo.workflowTemperature.collectAsState()
    val persistedMaxTokens by settingsRepo.workflowMaxTokens.collectAsState()
    
    // ===== Transcribe+Summary workflow state (persisted via StateHolder) =====
    var whisperModelPath by remember(persistedWhisperModel) { mutableStateOf(persistedWhisperModel) }
    val audioUri by WorkflowStateHolder.audioUri.collectAsState()
    val audioPath by WorkflowStateHolder.audioPath.collectAsState()
    var llmModelPath by remember(persistedLlmModel) { mutableStateOf(persistedLlmModel) }
    var summarySystemPrompt by remember { mutableStateOf("Summarize the following transcription concisely:") }
    var summaryTemperature by remember(persistedTemperature) { mutableFloatStateOf(persistedTemperature) }
    var summaryThreads by remember(persistedLlmThreads) { mutableIntStateOf(persistedLlmThreads) }
    var whisperThreads by remember(persistedWhisperThreads) { mutableIntStateOf(persistedWhisperThreads) }
    var whisperLanguage by remember(persistedLanguage) { mutableStateOf(persistedLanguage) }
    var summaryContext by remember(persistedContext) { mutableIntStateOf(persistedContext) }
    
    // Key progress state - persisted via StateHolder
    val transcribeIsRunning by WorkflowStateHolder.isRunning.collectAsState()
    val transcribeStep by WorkflowStateHolder.step.collectAsState()
    val transcribeProgress by WorkflowStateHolder.progress.collectAsState()
    val transcriptionText by WorkflowStateHolder.transcriptionText.collectAsState()
    val summaryText by WorkflowStateHolder.summaryText.collectAsState()
    val transcribeError by WorkflowStateHolder.error.collectAsState()
    
    // ===== Recording state for workflow (persisted via StateHolder) =====
    val showRecordingDialog by WorkflowStateHolder.showRecordingDialog.collectAsState()
    val isRecording by WorkflowStateHolder.isRecording.collectAsState()
    val recordingSeconds by WorkflowStateHolder.recordingSeconds.collectAsState()
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var hasRecordPermission by remember { mutableStateOf(false) }
    val savedRecordingPath by WorkflowStateHolder.savedRecordingPath.collectAsState()
    
    // CRITICAL: Use rememberUpdatedState to prevent stale closure capture in LaunchedEffect
    val currentAudioUri by rememberUpdatedState(audioUri)
    val currentAudioPath by rememberUpdatedState(audioPath)
    val currentSavedRecordingPath by rememberUpdatedState(savedRecordingPath)
    val currentWhisperLanguage by rememberUpdatedState(whisperLanguage)
    
    // Permission launcher for recording
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            WorkflowStateHolder.setShowRecordingDialog(true)
        } else {
            WorkflowStateHolder.setError("Recording permission denied")
        }
    }
    
    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            WorkflowStateHolder.setRecordingSeconds(0)
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                WorkflowStateHolder.setRecordingSeconds(recordingSeconds + 1)
            }
        }
    }
    
    // Save settings when they change
    LaunchedEffect(whisperModelPath) { settingsRepo.setWorkflowWhisperModelPath(whisperModelPath) }
    LaunchedEffect(llmModelPath) { settingsRepo.setWorkflowLlmModelPath(llmModelPath) }
    LaunchedEffect(whisperThreads) { settingsRepo.setWorkflowWhisperThreads(whisperThreads) }
    LaunchedEffect(summaryThreads) { settingsRepo.setWorkflowLlmThreads(summaryThreads) }
    LaunchedEffect(whisperLanguage) { settingsRepo.setWorkflowWhisperLanguage(whisperLanguage) }
    LaunchedEffect(summaryContext) { settingsRepo.setWorkflowContext(summaryContext) }
    LaunchedEffect(summaryTemperature) { settingsRepo.setWorkflowTemperature(summaryTemperature) }
    
    // ===== Consume shared audio/video files =====
    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumePendingFile()
        if (pendingFile != null && pendingFile.targetScreen == "workflows") {
            val mimeType = pendingFile.mimeType
            if (mimeType.startsWith("audio/") || mimeType.startsWith("video/")) {
                // Copy to cache for native access
                try {
                    val inputStream = context.contentResolver.openInputStream(pendingFile.uri)
                    val extension = if (mimeType.startsWith("video/")) "mp4" else "audio"
                    val tempFile = File(context.cacheDir, "workflow_shared_${System.currentTimeMillis()}.$extension")
                    inputStream?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                    WorkflowStateHolder.setAudioUri(pendingFile.uri)
                    WorkflowStateHolder.setAudioPath(tempFile.absolutePath)
                    selectedWorkflow = 1  // Auto-select transcribe workflow
                } catch (e: Exception) {
                    android.util.Log.e("WorkflowsScreen", "Failed to load shared file: ${e.message}")
                }
            }
        }
    }
    
    // Observe VideoSumupService for transcribe workflow results
    val videoSumupState by VideoSumupService.state.collectAsState()
    val videoSumupProgress by VideoSumupService.progress.collectAsState()
    val videoSumupResult by VideoSumupService.result.collectAsState()
    
    // Handle VideoSumupService results
    LaunchedEffect(videoSumupState) {
        when (videoSumupState) {
            is VideoSumupState.ExtractingAudio -> {
                WorkflowStateHolder.setStep("Step 1/3: Extracting audio...")
                WorkflowStateHolder.setProgress(0.1f)
            }
            is VideoSumupState.Transcribing -> {
                WorkflowStateHolder.setStep("Step 2/3: Transcribing...")
                WorkflowStateHolder.setProgress(0.4f)
            }
            is VideoSumupState.Summarizing -> {
                WorkflowStateHolder.setStep("Step 3/3: Summarizing...")
                WorkflowStateHolder.setProgress(0.7f)
            }
            is VideoSumupState.Idle -> {
                if (transcribeIsRunning && transcribeProgress > 0f) {
                    // Don't reset if just started
                }
            }
            is VideoSumupState.Error -> {
                WorkflowStateHolder.setIsRunning(false)
                WorkflowStateHolder.setError((videoSumupState as VideoSumupState.Error).message)
            }
        }
    }
    
    LaunchedEffect(videoSumupResult) {
        videoSumupResult?.fold(
            onSuccess = { result ->
                WorkflowStateHolder.onWorkflowComplete(result.transcript, result.summary)
                // Note saving is now handled by VideoSumupService with saveToNotes=true
                android.util.Log.d("WorkflowsScreen", "Workflow complete - note saved by service")
                
                VideoSumupService.clearResult()
            },
            onFailure = { e ->
                WorkflowStateHolder.setIsRunning(false)
                if (e.message != "Cancelled") {
                    WorkflowStateHolder.setError(e.message)
                }
                VideoSumupService.clearResult()
            }
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
            IconButton(onClick = { 
                if (selectedWorkflow == 0) navController.popBackStack()
                else selectedWorkflow = 0
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Text(
                when (selectedWorkflow) {
                    1 -> "🎙️→📝 Transcribe + Summary"
                    2 -> "🎨→⬆️ txt2img + Upscale"
                    else -> "⚙️ AI Workflows"
                },
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold
                )
            )
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Show running workflow indicator (visible on all tabs)
            if (transcribeIsRunning || txt2imgIsRunning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { 
                            // Switch to the running workflow
                            if (transcribeIsRunning) selectedWorkflow = 1
                            else if (txt2imgIsRunning) selectedWorkflow = 2
                        },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (transcribeIsRunning) "🎙️ Transcribe + Summary running..." 
                                else "🎨 txt2img + Upscale running...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (transcribeIsRunning) transcribeStep else txt2imgStep,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        Text(
                            "${((if (transcribeIsRunning) transcribeProgress else txt2imgProgress) * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            when (selectedWorkflow) {
                0 -> {
                    Text(
                        "Chain multiple AI operations together",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    WorkflowCard(
                        emoji = "🎙️→📝",
                        title = "Transcribe + Summary",
                        description = "Transcribe audio/video, then summarize with LLM",
                        gradientColors = listOf(
                            Color(0xFF00BCD4).copy(alpha = 0.15f),
                            Color(0xFF4CAF50).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedWorkflow = 1 }
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    WorkflowCard(
                        emoji = "🎨→⬆️",
                        title = "txt2img + Upscale",
                        description = "Generate image, then upscale it",
                        gradientColors = listOf(
                            Color(0xFF2196F3).copy(alpha = 0.15f),
                            Color(0xFF9C27B0).copy(alpha = 0.3f)
                        ),
                        onClick = { selectedWorkflow = 2 }
                    )
                }
                
                1 -> {
                    TranscribeSummaryWorkflowContent(
                        db = db,
                        settingsRepo = settingsRepo,
                        whisperModelPath = whisperModelPath,
                        onWhisperModelChange = { whisperModelPath = it },
                        audioUri = audioUri,
                        onAudioUriChange = { WorkflowStateHolder.setAudioUri(it) },
                        audioPath = audioPath,
                        onAudioPathChange = { WorkflowStateHolder.setAudioPath(it) },
                        llmModelPath = llmModelPath,
                        onLlmModelChange = { llmModelPath = it },
                        systemPrompt = summarySystemPrompt,
                        onSystemPromptChange = { summarySystemPrompt = it },
                        temperature = summaryTemperature,
                        onTemperatureChange = { summaryTemperature = it },
                        llmThreads = summaryThreads,
                        onLlmThreadsChange = { summaryThreads = it },
                        whisperThreads = whisperThreads,
                        onWhisperThreadsChange = { whisperThreads = it },
                        whisperLanguage = whisperLanguage,
                        onWhisperLanguageChange = { whisperLanguage = it },
                        contextSize = summaryContext,
                        onContextChange = { summaryContext = it },
                        isRunning = transcribeIsRunning,
                        currentStep = transcribeStep,
                        progress = transcribeProgress,
                        transcriptionText = transcriptionText,
                        summaryText = summaryText,
                        errorMessage = transcribeError,
                        onRun = {
                            if (audioPath != null && whisperModelPath != null && llmModelPath != null) {
                                WorkflowStateHolder.setIsRunning(true)
                                WorkflowStateHolder.setError(null)
                                WorkflowStateHolder.setStep("Starting...")
                                WorkflowStateHolder.setProgress(0f)
                                VideoSumupService.startSummarization(
                                    context = context,
                                    videoPath = audioPath!!,
                                    videoFileName = audioUri?.lastPathSegment ?: "audio",
                                    whisperModelPath = whisperModelPath!!,
                                    llmModelPath = llmModelPath!!,
                                    language = whisperLanguage,
                                    threads = whisperThreads,
                                    contextSize = summaryContext,
                                    maxTokens = 1024,
                                    temperature = summaryTemperature,
                                    saveToNotes = true,  // Service handles note saving now
                                    noteType = com.example.llamadroid.data.db.NoteType.WORKFLOW,
                                    audioSourcePath = savedRecordingPath ?: audioPath  // Use saved recording if available
                                )
                            }
                        },
                        onComplete = { transcript, summary ->
                            WorkflowStateHolder.onWorkflowComplete(transcript, summary)
                            
                            // Save as WORKFLOW note with summary above transcript
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                val fileName = audioUri?.lastPathSegment ?: "Recording"
                                val noteContent = buildString {
                                    appendLine("## Summary")
                                    appendLine(summary)
                                    appendLine()
                                    appendLine("## Transcript")
                                    appendLine(transcript)
                                }
                                
                                // CRITICAL: Save audio to permanent Recordings folder
                                var permanentAudioPath: String? = savedRecordingPath
                                if (permanentAudioPath == null && audioPath != null) {
                                    // Audio was picked (not recorded), copy to permanent location
                                    try {
                                        val recordingsDir = File(context.filesDir, "sd_output/Recordings").apply { mkdirs() }
                                        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                                        val extension = audioPath!!.substringAfterLast(".", "m4a")
                                        val savedFile = File(recordingsDir, "audio_${timestamp}.$extension")
                                        File(audioPath!!).copyTo(savedFile, overwrite = true)
                                        permanentAudioPath = savedFile.absolutePath
                                    } catch (e: Exception) {
                                        android.util.Log.e("WorkflowsScreen", "Failed to save audio: ${e.message}")
                                    }
                                }
                                
                                val note = com.example.llamadroid.data.db.NoteEntity(
                                    title = "Transcription: $fileName",
                                    content = noteContent,
                                    type = com.example.llamadroid.data.db.NoteType.WORKFLOW,
                                    sourceFile = audioPath,
                                    language = whisperLanguage,
                                    audioPath = permanentAudioPath
                                )
                                db.noteDao().insert(note)
                            }
                        },
                        onError = { error ->
                            WorkflowStateHolder.setIsRunning(false)
                            WorkflowStateHolder.setError(error)
                        },
                        onCancel = {
                            VideoSumupService.cancel()
                            WorkflowStateHolder.setIsRunning(false)
                            WorkflowStateHolder.setStep("")
                            WorkflowStateHolder.setProgress(0f)
                        },
                        onRecord = {
                            if (hasRecordPermission) {
                                WorkflowStateHolder.setShowRecordingDialog(true)
                            } else {
                                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
                
                2 -> {
                    Txt2ImgUpscaleWorkflowContent(
                        db = db,
                        settingsRepo = settingsRepo,
                        outputDir = workflowOutputDir,
                        modelPath = txt2imgModelPath,
                        onModelChange = { txt2imgModelPath = it },
                        prompt = txt2imgPrompt,
                        onPromptChange = { txt2imgPrompt = it },
                        negativePrompt = txt2imgNegativePrompt,
                        onNegativePromptChange = { txt2imgNegativePrompt = it },
                        width = txt2imgWidth,
                        onWidthChange = { txt2imgWidth = it },
                        height = txt2imgHeight,
                        onHeightChange = { txt2imgHeight = it },
                        steps = txt2imgSteps,
                        onStepsChange = { txt2imgSteps = it },
                        cfgScale = txt2imgCfgScale,
                        onCfgScaleChange = { txt2imgCfgScale = it },
                        seed = txt2imgSeed,
                        onSeedChange = { txt2imgSeed = it },
                        sampler = txt2imgSampler,
                        onSamplerChange = { txt2imgSampler = it },
                        threads = txt2imgThreads,
                        onThreadsChange = { txt2imgThreads = it },
                        upscalerPath = upscalerPath,
                        onUpscalerChange = { upscalerPath = it },
                        upscaleFactor = upscaleFactor,
                        onUpscaleFactorChange = { upscaleFactor = it },
                        upscaleRepeats = upscaleRepeats,
                        onUpscaleRepeatsChange = { upscaleRepeats = it },
                        upscaleThreads = upscaleThreads,
                        onUpscaleThreadsChange = { upscaleThreads = it },
                        isRunning = txt2imgIsRunning,
                        currentStep = txt2imgStep,
                        progress = txt2imgProgress,
                        resultPath = txt2imgResultPath,
                        errorMessage = txt2imgError,
                        onRunningChange = { txt2imgIsRunning = it },
                        onStepChange = { txt2imgStep = it },
                        onProgressChange = { txt2imgProgress = it },
                        onResultChange = { txt2imgResultPath = it },
                        onErrorChange = { txt2imgError = it },
                        onCancel = {
                            SDModeStateHolder.workflowTxt2img.reset()
                            SDModeStateHolder.workflowUpscale.reset()
                            txt2imgIsRunning = false
                            txt2imgStep = ""
                            txt2imgProgress = 0f
                        }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
    
    // Recording Dialog
    if (showRecordingDialog) {
        // Use stable file name for recording (timestamp added when saving to output folder)
        val recordingFile = remember { File(context.cacheDir, "workflow_recording.m4a") }
        
        AlertDialog(
            onDismissRequest = {
                if (isRecording) {
                    try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
                    mediaRecorder = null
                    WorkflowStateHolder.setIsRecording(false)
                }
                WorkflowStateHolder.setShowRecordingDialog(false)
            },
            title = { Text("Record Audio", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val minutes = recordingSeconds / 60
                    val seconds = recordingSeconds % 60
                    Text(
                        String.format("%02d:%02d", minutes, seconds),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (isRecording) {
                        Text("Recording...", color = MaterialTheme.colorScheme.error)
                    } else if (recordingSeconds > 0) {
                        Text("Recording saved", color = MaterialTheme.colorScheme.primary)
                    } else {
                        Text("Tap Start to begin recording")
                    }
                }
            },
            confirmButton = {
                if (!isRecording && recordingSeconds > 0) {
                    TextButton(onClick = {
                        // Save recording to Recordings folder
                        try {
                            val recordingsDir = File(context.filesDir, "sd_output/Recordings").apply { mkdirs() }
                            val timestamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.getDefault()).format(Date())
                            val savedFile = File(recordingsDir, "recording_$timestamp.m4a")
                            recordingFile.copyTo(savedFile, overwrite = true)
                            WorkflowStateHolder.setSavedRecordingPath(savedFile.absolutePath)
                            recordingFile.delete()
                            
                            // Use recording as audio source
                            WorkflowStateHolder.setAudioUri(Uri.fromFile(savedFile))
                            WorkflowStateHolder.setAudioPath(savedFile.absolutePath)
                            
                            WorkflowStateHolder.setShowRecordingDialog(false)
                            WorkflowStateHolder.setRecordingSeconds(0)
                        } catch (e: Exception) {
                            WorkflowStateHolder.setError("Failed to save recording: ${e.message}")
                        }
                    }) { Text("Use Recording") }
                } else if (!isRecording) {
                    TextButton(onClick = {
                        try {
                            @Suppress("DEPRECATION")
                            val recorder = MediaRecorder().apply {
                                setAudioSource(MediaRecorder.AudioSource.MIC)
                                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                setAudioSamplingRate(44100)
                                setAudioEncodingBitRate(128000)
                                setOutputFile(recordingFile.absolutePath)
                                prepare()
                                start()
                            }
                            mediaRecorder = recorder
                            WorkflowStateHolder.setIsRecording(true)
                        } catch (e: Exception) {
                            WorkflowStateHolder.setError("Failed to start recording: ${e.message}")
                            WorkflowStateHolder.setShowRecordingDialog(false)
                        }
                    }) { Text("Start") }
                } else {
                    TextButton(onClick = {
                        try { mediaRecorder?.stop(); mediaRecorder?.release(); mediaRecorder = null; WorkflowStateHolder.setIsRecording(false) } 
                        catch (e: Exception) { WorkflowStateHolder.setError("Failed to stop recording: ${e.message}") }
                    }) { Text("Stop") }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (isRecording) {
                        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
                        mediaRecorder = null
                        WorkflowStateHolder.setIsRecording(false)
                    }
                    WorkflowStateHolder.setRecordingSeconds(0)
                    WorkflowStateHolder.setShowRecordingDialog(false)
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun WorkflowCard(
    emoji: String,
    title: String,
    description: String,
    gradientColors: List<Color>,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = Brush.horizontalGradient(gradientColors))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 36.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * txt2img + Upscale Workflow Content (with all options)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Txt2ImgUpscaleWorkflowContent(
    db: AppDatabase,
    settingsRepo: SettingsRepository,
    outputDir: File,
    modelPath: String?,
    onModelChange: (String?) -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    height: Int,
    onHeightChange: (Int) -> Unit,
    steps: Int,
    onStepsChange: (Int) -> Unit,
    cfgScale: Float,
    onCfgScaleChange: (Float) -> Unit,
    seed: Long,
    onSeedChange: (Long) -> Unit,
    sampler: SamplingMethod,
    onSamplerChange: (SamplingMethod) -> Unit,
    threads: Int,
    onThreadsChange: (Int) -> Unit,
    upscalerPath: String?,
    onUpscalerChange: (String?) -> Unit,
    upscaleFactor: Int,
    onUpscaleFactorChange: (Int) -> Unit,
    upscaleRepeats: Int,
    onUpscaleRepeatsChange: (Int) -> Unit,
    upscaleThreads: Int,
    onUpscaleThreadsChange: (Int) -> Unit,
    isRunning: Boolean,
    currentStep: String,
    progress: Float,
    resultPath: String?,
    errorMessage: String?,
    onRunningChange: (Boolean) -> Unit,
    onStepChange: (String) -> Unit,
    onProgressChange: (Float) -> Unit,
    onResultChange: (String?) -> Unit,
    onErrorChange: (String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    
    // Models
    val sdCheckpoints by db.modelDao().getModelsByType(ModelType.SD_CHECKPOINT).collectAsState(initial = emptyList())
    val fluxDiffusionModels by db.modelDao().getModelsByType(ModelType.SD_DIFFUSION).collectAsState(initial = emptyList())
    val upscalerModels by db.modelDao().getModelsByType(ModelType.SD_UPSCALER).collectAsState(initial = emptyList())
    val allGenerationModels = sdCheckpoints + fluxDiffusionModels
    
    // Service connection
    var sdService by remember { mutableStateOf<SDService?>(null) }
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                sdService = (service as? SDService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) { sdService = null }
        }
    }
    
    DisposableEffect(Unit) {
        val intent = Intent(context, SDService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(serviceConnection) }
    }
    
    // Calculate final resolution
    val finalFactor = upscaleFactor.toDouble().pow(upscaleRepeats.toDouble()).toInt()
    val finalWidth = width * finalFactor
    val finalHeight = height * finalFactor
    
    val runWorkflow: () -> Unit = {
        if (modelPath != null && upscalerPath != null && prompt.isNotBlank()) {
            onRunningChange(true)
            onErrorChange(null)
            onStepChange("Step 1/2: Generating image...")
            onProgressChange(0f)
            
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val txt2imgFile = File(outputDir, "txt2img_$timestamp.png")
            val upscaledFile = File(outputDir, "upscaled_$timestamp.png")
            
            val txt2imgConfig = SDConfig(
                mode = SDMode.TXT2IMG,
                modelPath = modelPath,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = width,
                height = height,
                steps = steps,
                cfgScale = cfgScale,
                seed = seed,
                samplingMethod = sampler,
                outputPath = txt2imgFile.absolutePath,
                threads = threads
            )
            
            context.startForegroundService(Intent(context, SDService::class.java))
            
            sdService?.generate(txt2imgConfig, useWorkflowStateHolder = true) { result ->
                result.fold(
                    onSuccess = {
                        onStepChange("Step 2/2: Upscaling...")
                        onProgressChange(0.5f)
                        
                        val upscaleConfig = SDConfig(
                            mode = SDMode.UPSCALE,
                            modelPath = upscalerPath,
                            prompt = "",
                            outputPath = upscaledFile.absolutePath,
                            initImage = txt2imgFile.absolutePath,
                            upscaleModel = upscalerPath,
                            upscaleRepeats = upscaleRepeats,
                            threads = upscaleThreads
                        )
                        
                        sdService?.generate(upscaleConfig, useWorkflowStateHolder = true) { upscaleResult ->
                            upscaleResult.fold(
                                onSuccess = {
                                    // Copy to custom output folder if set
                                    val customFolderUri = settingsRepo.outputFolderUri.value
                                    if (customFolderUri != null && upscaledFile.exists()) {
                                        try {
                                            val treeUri = Uri.parse(customFolderUri)
                                            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                                            var imagesDoc = rootDoc?.findFile("images")
                                            if (imagesDoc == null) imagesDoc = rootDoc?.createDirectory("images")
                                            var workflowDoc = imagesDoc?.findFile("workflow")
                                            if (workflowDoc == null) workflowDoc = imagesDoc?.createDirectory("workflow")
                                            val newFile = workflowDoc?.createFile("image/png", upscaledFile.name)
                                            if (newFile != null) {
                                                context.contentResolver.openOutputStream(newFile.uri)?.use { outStream ->
                                                    upscaledFile.inputStream().use { inStream -> inStream.copyTo(outStream) }
                                                }
                                            }
                                        } catch (e: Exception) {
                                            android.util.Log.e("WorkflowsScreen", "Failed to copy to output folder: ${e.message}")
                                        }
                                    }
                                    onRunningChange(false)
                                    onProgressChange(1f)
                                    onStepChange("Complete!")
                                    onResultChange(upscaledFile.absolutePath)
                                },
                                onFailure = { e ->
                                    onRunningChange(false)
                                    onErrorChange("Upscale failed: ${e.message}")
                                }
                            )
                        }
                    },
                    onFailure = { e ->
                        onRunningChange(false)
                        onErrorChange("Generation failed: ${e.message}")
                    }
                )
            }
        }
    }
    
    val scope = rememberCoroutineScope()
    
    Column {
        // ===== Template Save/Load for Txt2Img =====
        var showTemplateMenu by remember { mutableStateOf(false) }
        var showSaveDialog by remember { mutableStateOf(false) }
        var showEditDialog by remember { mutableStateOf(false) }
        var templateName by remember { mutableStateOf("") }
        var editingTemplate by remember { mutableStateOf<com.example.llamadroid.data.db.WorkflowTemplateEntity?>(null) }
        val templates by db.workflowTemplateDao().getByType(com.example.llamadroid.data.db.WorkflowType.TXT2IMG_UPSCALE).collectAsState(initial = emptyList())
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                OutlinedButton(onClick = { showTemplateMenu = true }) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Templates (${templates.size})")
                }
                DropdownMenu(expanded = showTemplateMenu, onDismissRequest = { showTemplateMenu = false }) {
                    if (templates.isEmpty()) {
                        DropdownMenuItem(text = { Text("No saved templates", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                    } else {
                        templates.forEach { template ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (template.name.isNotBlank()) template.name else "Template #${template.id}",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                },
                                onClick = {
                                    try {
                                        val config = org.json.JSONObject(template.configJson)
                                        onModelChange(config.optString("model").takeIf { it.isNotEmpty() })
                                        onPromptChange(config.optString("prompt", ""))
                                        onNegativePromptChange(config.optString("negativePrompt", ""))
                                        onWidthChange(config.optInt("width", 512))
                                        onHeightChange(config.optInt("height", 512))
                                        onStepsChange(config.optInt("steps", 20))
                                        onCfgScaleChange(config.optDouble("cfgScale", 7.0).toFloat())
                                        onSamplerChange(SamplingMethod.entries.find { it.name == config.optString("sampler") } ?: SamplingMethod.EULER_A)
                                        onThreadsChange(config.optInt("threads", 4))
                                        onUpscalerChange(config.optString("upscaler").takeIf { it.isNotEmpty() })
                                        onUpscaleFactorChange(config.optInt("upscaleFactor", 2))
                                        onUpscaleRepeatsChange(config.optInt("upscaleRepeats", 1))
                                        onUpscaleThreadsChange(config.optInt("upscaleThreads", 4))
                                    } catch (e: Exception) {}
                                    showTemplateMenu = false
                                },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = {
                                            editingTemplate = template
                                            templateName = template.name
                                            showEditDialog = true
                                            showTemplateMenu = false
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) 
                                        }
                                        IconButton(onClick = {
                                            scope.launch { db.workflowTemplateDao().delete(template) }
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) 
                                        }
                                    }
                                }
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Save current as template...", fontWeight = FontWeight.Bold) },
                        onClick = { showTemplateMenu = false; templateName = ""; showSaveDialog = true },
                        leadingIcon = { Icon(Icons.Default.Add, null) }
                    )
                }
            }
        }
        
        // Edit template dialog
        // Edit template dialog
        if (showEditDialog) {
            val currentEditing = editingTemplate
            if (currentEditing != null) {
                var editName by remember(currentEditing) { mutableStateOf(currentEditing.name) }
                
                AlertDialog(
                    onDismissRequest = { showEditDialog = false; editingTemplate = null },
                    title = { Text("Edit Template") },
                    text = {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Template Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (editName.isNotBlank()) {
                                scope.launch { db.workflowTemplateDao().insert(currentEditing.copy(name = editName)) }
                                showEditDialog = false
                                editingTemplate = null
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { showEditDialog = false; editingTemplate = null }) { Text("Cancel") } }
                )
            } else {
                showEditDialog = false
            }
        }
        
        // Save template dialog
        // Save template dialog
        if (showSaveDialog) {
            var saveName by remember { mutableStateOf("") }
            
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save Template") },
                text = {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Template Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (saveName.isNotBlank()) {
                            val nameToSave = saveName  // Capture before async
                            scope.launch {
                                val configJson = org.json.JSONObject().apply {
                                    put("model", modelPath ?: "")
                                    put("prompt", prompt)
                                    put("negativePrompt", negativePrompt)
                                    put("width", width)
                                    put("height", height)
                                    put("steps", steps)
                                    put("cfgScale", cfgScale.toDouble())
                                    put("sampler", sampler.name)
                                    put("threads", threads)
                                    put("upscaler", upscalerPath ?: "")
                                    put("upscaleFactor", upscaleFactor)
                                    put("upscaleRepeats", upscaleRepeats)
                                    put("upscaleThreads", upscaleThreads)
                                }.toString()
                                
                                db.workflowTemplateDao().insert(
                                    com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                        name = nameToSave,
                                        type = com.example.llamadroid.data.db.WorkflowType.TXT2IMG_UPSCALE,
                                        configJson = configJson
                                    )
                                )
                            }
                            showSaveDialog = false
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
            )
        }
        
        // Step 1: txt2img Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Step 1: Generate Image", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Model selector
                var modelExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = it }) {
                    OutlinedTextField(
                        value = modelPath?.substringAfterLast("/") ?: "Select model",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("SD Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                        allGenerationModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.filename) },
                                onClick = { onModelChange(model.path); modelExpanded = false }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Prompt
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    label = { Text("Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Negative prompt
                OutlinedTextField(
                    value = negativePrompt,
                    onValueChange = onNegativePromptChange,
                    label = { Text("Negative Prompt") },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Dimensions
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width.toString(),
                        onValueChange = { it.toIntOrNull()?.let(onWidthChange) },
                        label = { Text("Width") },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = height.toString(),
                        onValueChange = { it.toIntOrNull()?.let(onHeightChange) },
                        label = { Text("Height") },
                        modifier = Modifier.weight(1f)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Steps slider
                IntSliderWithInput(
                    value = steps,
                    onValueChange = onStepsChange,
                    valueRange = 1..50,
                    label = "Steps"
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // CFG Scale slider
                SliderWithInput(
                    value = cfgScale,
                    onValueChange = onCfgScaleChange,
                    valueRange = 1f..20f,
                    label = "CFG Scale",
                    decimalPlaces = 1
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Threads slider
                IntSliderWithInput(
                    value = threads,
                    onValueChange = onThreadsChange,
                    valueRange = 1..16,
                    label = "Threads"
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Sampler
                var samplerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = samplerExpanded, onExpandedChange = { samplerExpanded = it }) {
                    OutlinedTextField(
                        value = sampler.cliName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Sampler") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(samplerExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = samplerExpanded, onDismissRequest = { samplerExpanded = false }) {
                        SamplingMethod.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.cliName) }, onClick = { onSamplerChange(s); samplerExpanded = false })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Seed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = if (seed == -1L) "" else seed.toString(),
                        onValueChange = { onSeedChange(it.toLongOrNull() ?: -1L) },
                        modifier = Modifier.weight(1f),
                        label = { Text("Seed (-1 = random)") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = { onSeedChange((0..Int.MAX_VALUE).random().toLong()) }) {
                        Icon(Icons.Default.Refresh, "Random")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Step 2: Upscale Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Step 2: Upscale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Upscaler selector
                var upscalerExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = upscalerExpanded, onExpandedChange = { upscalerExpanded = it }) {
                    OutlinedTextField(
                        value = upscalerPath?.substringAfterLast("/") ?: "Select upscaler",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Upscaler Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(upscalerExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = upscalerExpanded, onDismissRequest = { upscalerExpanded = false }) {
                        upscalerModels.forEach { model ->
                            DropdownMenuItem(
                                text = { Text(model.filename) },
                                onClick = {
                                    onUpscalerChange(model.path)
                                    Regex("x(\\d)").find(model.filename.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.let(onUpscaleFactorChange)
                                    upscalerExpanded = false
                                }
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Repeats slider
                IntSliderWithInput(
                    value = upscaleRepeats,
                    onValueChange = onUpscaleRepeatsChange,
                    valueRange = 1..4,
                    label = "Upscale Repeats",
                    steps = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Upscale threads
                IntSliderWithInput(
                    value = upscaleThreads,
                    onValueChange = onUpscaleThreadsChange,
                    valueRange = 1..16,
                    label = "Upscale Threads"
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Final resolution preview
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Final Resolution:", fontWeight = FontWeight.Bold)
                        Text("${finalWidth} × ${finalHeight}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Progress / Error / Result
        if (isRunning) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(currentStep, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        
        errorMessage?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        
        resultPath?.let { path ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text("Workflow Complete!", fontWeight = FontWeight.Bold)
                            Text("Saved: ${path.substringAfterLast("/")}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // Show result image with badge
                    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                    bitmap?.let {
                        Box {
                            Image(
                                bitmap = it,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height),
                                contentScale = ContentScale.Fit
                            )
                            // Workflow badge
                            Surface(
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text("⚙️ Workflow", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), 
                                     color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Run and Cancel buttons
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val canRun = modelPath != null && upscalerPath != null && prompt.isNotBlank() && !isRunning
            Button(
                onClick = runWorkflow, 
                enabled = canRun, 
                modifier = Modifier.weight(1f)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isRunning) "Running..." else "🚀 Run Workflow")
            }
            
            if (isRunning) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Close, null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel")
                }
            }
        }
    }
}

/**
 * Transcribe + Summary Workflow Content
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscribeSummaryWorkflowContent(
    db: AppDatabase,
    settingsRepo: SettingsRepository,
    whisperModelPath: String?,
    onWhisperModelChange: (String?) -> Unit,
    audioUri: Uri?,
    onAudioUriChange: (Uri?) -> Unit,
    audioPath: String?,
    onAudioPathChange: (String?) -> Unit,
    llmModelPath: String?,
    onLlmModelChange: (String?) -> Unit,
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit,
    temperature: Float,
    onTemperatureChange: (Float) -> Unit,
    llmThreads: Int,
    onLlmThreadsChange: (Int) -> Unit,
    whisperThreads: Int,
    onWhisperThreadsChange: (Int) -> Unit,
    whisperLanguage: String,
    onWhisperLanguageChange: (String) -> Unit,
    contextSize: Int,
    onContextChange: (Int) -> Unit,
    isRunning: Boolean,
    currentStep: String,
    progress: Float,
    transcriptionText: String,
    summaryText: String,
    errorMessage: String?,
    onRun: () -> Unit,
    onComplete: (String, String) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
    onRecord: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    val llmModels by db.modelDao().getModelsByType(ModelType.LLM).collectAsState(initial = emptyList())
    
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { 
            onAudioUriChange(it)
            // Copy to cache for native access
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                // Determine file extension from MIME type
                val mimeType = context.contentResolver.getType(it)
                val extension = when {
                    mimeType?.contains("mp3") == true -> "mp3"
                    mimeType?.contains("wav") == true -> "wav"
                    mimeType?.contains("mp4") == true -> "mp4"
                    mimeType?.contains("m4a") == true -> "m4a"
                    mimeType?.contains("ogg") == true -> "ogg"
                    mimeType?.contains("flac") == true -> "flac"
                    else -> "audio"
                }
                val tempFile = File(context.cacheDir, "workflow_audio_${System.currentTimeMillis()}.$extension")
                inputStream?.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
                onAudioPathChange(tempFile.absolutePath)
                android.util.Log.d("WorkflowsScreen", "File picked: ${uri.lastPathSegment}, saved to: ${tempFile.absolutePath}")
            } catch (e: Exception) {
                onError("Failed to load audio: ${e.message}")
            }
        }
    }
    
    Column {
        // ===== Template Save/Load =====
        var showTemplateMenu by remember { mutableStateOf(false) }
        var showSaveDialog by remember { mutableStateOf(false) }
        var showEditDialog by remember { mutableStateOf(false) }
        var templateName by remember { mutableStateOf("") }
        var editingTemplate by remember { mutableStateOf<com.example.llamadroid.data.db.WorkflowTemplateEntity?>(null) }
        val templates by db.workflowTemplateDao().getByType(com.example.llamadroid.data.db.WorkflowType.TRANSCRIBE_SUMMARY).collectAsState(initial = emptyList())
        
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Load template dropdown
            Box {
                OutlinedButton(onClick = { showTemplateMenu = true }) {
                    Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Templates (${templates.size})")
                }
                DropdownMenu(expanded = showTemplateMenu, onDismissRequest = { showTemplateMenu = false }) {
                    if (templates.isEmpty()) {
                        DropdownMenuItem(text = { Text("No saved templates", color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = {})
                    } else {
                        templates.forEach { template ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (template.name.isNotBlank()) template.name else "Template #${template.id}",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                },
                                onClick = {
                                    // Load template config
                                    try {
                                        val config = org.json.JSONObject(template.configJson)
                                        onWhisperModelChange(config.optString("whisperModel").takeIf { it.isNotEmpty() })
                                        onLlmModelChange(config.optString("llmModel").takeIf { it.isNotEmpty() })
                                        onWhisperLanguageChange(config.optString("language", "auto"))
                                        onWhisperThreadsChange(config.optInt("whisperThreads", 4))
                                        onLlmThreadsChange(config.optInt("llmThreads", 4))
                                        onTemperatureChange(config.optDouble("temperature", 0.7).toFloat())
                                        onContextChange(config.optInt("contextSize", 2048))
                                    } catch (e: Exception) {}
                                    showTemplateMenu = false
                                },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = {
                                            editingTemplate = template
                                            templateName = template.name
                                            showEditDialog = true
                                            showTemplateMenu = false
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)) 
                                        }
                                        IconButton(onClick = {
                                            scope.launch { db.workflowTemplateDao().delete(template) }
                                        }, modifier = Modifier.size(32.dp)) { 
                                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp)) 
                                        }
                                    }
                                }
                            )
                        }
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Save current as template...", fontWeight = FontWeight.Bold) },
                        onClick = { showTemplateMenu = false; templateName = ""; showSaveDialog = true },
                        leadingIcon = { Icon(Icons.Default.Add, null) }
                    )
                }
            }
        }
        
        // Edit template dialog
        if (showEditDialog) {
            val currentEditing = editingTemplate
            if (currentEditing != null) {
                var editName by remember(currentEditing) { mutableStateOf(currentEditing.name) }
                
                AlertDialog(
                    onDismissRequest = { showEditDialog = false; editingTemplate = null },
                    title = { Text("Edit Template") },
                    text = {
                        OutlinedTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = { Text("Template Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (editName.isNotBlank()) {
                                scope.launch {
                                    db.workflowTemplateDao().insert(
                                        currentEditing.copy(name = editName)
                                    )
                                }
                                showEditDialog = false
                                editingTemplate = null
                            }
                        }) { Text("Save") }
                    },
                    dismissButton = { TextButton(onClick = { showEditDialog = false; editingTemplate = null }) { Text("Cancel") } }
                )
            } else {
                showEditDialog = false
            }
        }
        
        // Save template dialog
        if (showSaveDialog) {
            var saveName by remember { mutableStateOf("") }
            
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save Template") },
                text = {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        label = { Text("Template Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (saveName.isNotBlank()) {
                            val nameToSave = saveName  // Capture value before async
                            scope.launch {
                                val configJson = org.json.JSONObject().apply {
                                    put("whisperModel", whisperModelPath ?: "")
                                    put("llmModel", llmModelPath ?: "")
                                    put("language", whisperLanguage)
                                    put("whisperThreads", whisperThreads)
                                    put("llmThreads", llmThreads)
                                    put("temperature", temperature.toDouble())
                                    put("contextSize", contextSize)
                                }.toString()
                                
                                db.workflowTemplateDao().insert(
                                    com.example.llamadroid.data.db.WorkflowTemplateEntity(
                                        name = nameToSave,
                                        type = com.example.llamadroid.data.db.WorkflowType.TRANSCRIBE_SUMMARY,
                                        configJson = configJson
                                    )
                                )
                            }
                            showSaveDialog = false
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } }
            )
        }
        
        // Step 1: Transcription Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Step 1: Transcribe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // Whisper model
                var whisperExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = whisperExpanded, onExpandedChange = { whisperExpanded = it }) {
                    OutlinedTextField(
                        value = whisperModelPath?.substringAfterLast("/") ?: "Select Whisper model",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Whisper Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(whisperExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = whisperExpanded, onDismissRequest = { whisperExpanded = false }) {
                        whisperModels.forEach { model ->
                            DropdownMenuItem(text = { Text(model.filename) }, onClick = { onWhisperModelChange(model.path); whisperExpanded = false })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Language selector
                var languageExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = languageExpanded, onExpandedChange = { languageExpanded = it }) {
                    OutlinedTextField(
                        value = WhisperLanguages.languages.find { it.first == whisperLanguage }?.second ?: "Auto-detect",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Language") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(languageExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = languageExpanded, onDismissRequest = { languageExpanded = false }) {
                        WhisperLanguages.languages.take(20).forEach { (code, name) ->
                            DropdownMenuItem(text = { Text(name) }, onClick = { onWhisperLanguageChange(code); languageExpanded = false })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Whisper Threads
                IntSliderWithInput(
                    value = whisperThreads,
                    onValueChange = onWhisperThreadsChange,
                    valueRange = 1..16,
                    label = "Whisper Threads"
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Audio file picker OR Record
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { filePicker.launch("audio/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.List, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(audioUri?.lastPathSegment?.take(12) ?: "Audio/Video")
                    }
                    OutlinedButton(
                        onClick = onRecord,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Record")
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // Step 2: Summary Settings
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Step 2: Summarize", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                // LLM model
                var llmExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = llmExpanded, onExpandedChange = { llmExpanded = it }) {
                    OutlinedTextField(
                        value = llmModelPath?.substringAfterLast("/") ?: "Select LLM model",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("LLM Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(llmExpanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = llmExpanded, onDismissRequest = { llmExpanded = false }) {
                        llmModels.forEach { model ->
                            DropdownMenuItem(text = { Text(model.filename) }, onClick = { onLlmModelChange(model.path); llmExpanded = false })
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // System prompt
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text("System Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Temperature
                SliderWithInput(
                    value = temperature,
                    onValueChange = onTemperatureChange,
                    valueRange = 0f..2f,
                    label = "Temperature",
                    decimalPlaces = 2
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // LLM Threads
                IntSliderWithInput(
                    value = llmThreads,
                    onValueChange = onLlmThreadsChange,
                    valueRange = 1..16,
                    label = "LLM Threads"
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Context size
                IntSliderWithInput(
                    value = contextSize,
                    onValueChange = onContextChange,
                    valueRange = 512..32768,
                    label = "Context Size"
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Progress
        if (isRunning) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(currentStep, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        
        errorMessage?.let { error ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error, modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        
        // Summary result
        if (summaryText.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📝 Summary", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(summaryText)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Run/Cancel buttons
        val canRun = whisperModelPath != null && llmModelPath != null && audioUri != null && !isRunning
        
        if (isRunning) {
            // Cancel button when running
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Close, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel")
            }
        } else {
            // Run button when not running
            Button(
                onClick = onRun,
                enabled = canRun,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("🚀 Run Workflow")
            }
        }
    }
}
