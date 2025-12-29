package com.example.llamadroid.ui.ai

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.MediaRecorder
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.service.*
import com.example.llamadroid.ui.navigation.Screen
import androidx.compose.ui.res.stringResource
import com.example.llamadroid.R
import kotlinx.coroutines.launch
import java.io.File

/**
 * Audio Transcription Screen using WhisperCPP
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioTranscriptionScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository(context) }
    val db = remember { AppDatabase.getDatabase(context) }
    
    // Service binding
    var whisperService by remember { mutableStateOf<WhisperService?>(null) }
    
    DisposableEffect(Unit) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                whisperService = (binder as WhisperService.WhisperBinder).getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                whisperService = null
            }
        }
        val intent = Intent(context, WhisperService::class.java)
        context.startForegroundService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        onDispose {
            context.unbindService(connection)
        }
    }
    
    // State
    val whisperState by whisperService?.state?.collectAsState() ?: remember { mutableStateOf(WhisperState.Idle) }
    val whisperProgress by whisperService?.progress?.collectAsState() ?: remember { mutableStateOf("") }
    
    var selectedAudioPath by remember { mutableStateOf<String?>(null) }
    var selectedLanguage by remember { mutableStateOf("auto") }
    var translateToEnglish by remember { mutableStateOf(false) }
    var outputSrt by remember { mutableStateOf(true) }
    var outputTxt by remember { mutableStateOf(true) }
    var outputVtt by remember { mutableStateOf(false) }
    var outputJson by remember { mutableStateOf(false) }
    var transcriptionResult by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    // Check for shared file (from share intent)
    LaunchedEffect(Unit) {
        val pendingFile = com.example.llamadroid.data.SharedFileHolder.consumePendingFile()
        if (pendingFile != null) {
            // Copy URI to internal storage
            try {
                val inputStream = context.contentResolver.openInputStream(pendingFile.uri)
                val mimeType = pendingFile.mimeType
                val isVideo = mimeType.startsWith("video/")
                val extension = if (isVideo) "mp4" else "audio"
                val tempFile = File(context.cacheDir, "whisper_shared_input.$extension")
                tempFile.outputStream().use { out ->
                    inputStream?.copyTo(out)
                }
                inputStream?.close()
                
                if (isVideo) {
                    // Will need extraction - set path and let user click transcribe
                    // For now, just set path - user can manually transcribe
                    selectedAudioPath = tempFile.absolutePath
                    errorMessage = "Video file loaded. Audio extraction will happen on transcribe."
                } else {
                    selectedAudioPath = tempFile.absolutePath
                }
            } catch (e: Exception) {
                errorMessage = "Failed to load shared file: ${e.message}"
            }
        }
    }
    
    // Model selection
    val whisperModels by db.modelDao().getModelsByType(ModelType.WHISPER).collectAsState(initial = emptyList())
    var selectedModelPath by remember { mutableStateOf<String?>(null) }
    var showModelPicker by remember { mutableStateOf(false) }
    
    // Settings
    val threads by settingsRepo.whisperThreads.collectAsState()
    
    // State for video extraction
    var isExtractingAudio by remember { mutableStateOf(false) }
    var extractionProgress by remember { mutableStateOf("") }
    
    // File picker - accepts both audio and video
    val mediaFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch {
                val mimeType = context.contentResolver.getType(it) ?: ""
                val isVideo = mimeType.startsWith("video/")
                
                // Copy to internal storage
                val inputStream = context.contentResolver.openInputStream(it)
                val extension = if (isVideo) "mp4" else "audio"
                val tempFile = File(context.cacheDir, "whisper_input.$extension")
                tempFile.outputStream().use { out ->
                    inputStream?.copyTo(out)
                }
                inputStream?.close()
                
                if (isVideo) {
                    // Extract audio from video using FFmpeg
                    isExtractingAudio = true
                    extractionProgress = "Extracting audio from video..."
                    
                    val binaryRepo = com.example.llamadroid.data.binary.BinaryRepository(context)
                    val ffmpegBinary = binaryRepo.getFFmpegBinary()
                    val audioOutput = File(context.cacheDir, "whisper_extracted_audio.wav")
                    
                    // Setup FFmpeg library path (like WhisperService does)
                    val libDir = File(context.filesDir, "ffmpeg_libs")
                    if (!libDir.exists()) libDir.mkdirs()
                    
                    try {
                        if (ffmpegBinary == null || !ffmpegBinary.exists()) {
                            throw Exception("FFmpeg binary not found")
                        }
                        
                        android.util.Log.d("AudioTranscription", "FFmpeg binary: ${ffmpegBinary.absolutePath}")
                        android.util.Log.d("AudioTranscription", "FFmpeg exists: ${ffmpegBinary.exists()}")
                        android.util.Log.d("AudioTranscription", "Input file: ${tempFile.absolutePath}")
                        android.util.Log.d("AudioTranscription", "Input exists: ${tempFile.exists()}, size: ${tempFile.length()}")
                        
                        val process = ProcessBuilder(
                            ffmpegBinary.absolutePath,
                            "-y",
                            "-i", tempFile.absolutePath,
                            "-vn",
                            "-acodec", "pcm_s16le",
                            "-ar", "16000",
                            "-ac", "1",
                            audioOutput.absolutePath
                        ).apply {
                            environment()["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:${context.applicationInfo.nativeLibraryDir}"
                            redirectErrorStream(true)
                        }.start()
                        
                        // Read output
                        val output = process.inputStream.bufferedReader().readText()
                        android.util.Log.d("AudioTranscription", "FFmpeg output: $output")
                        
                        val exitCode = process.waitFor()
                        android.util.Log.d("AudioTranscription", "FFmpeg exit code: $exitCode")
                        
                        if (exitCode == 0 && audioOutput.exists()) {
                            selectedAudioPath = audioOutput.absolutePath
                            extractionProgress = "Audio extracted successfully!"
                            android.util.Log.d("AudioTranscription", "Audio extracted: ${audioOutput.length()} bytes")
                        } else {
                            errorMessage = "Failed to extract audio (exit code: $exitCode)"
                            android.util.Log.e("AudioTranscription", "FFmpeg failed: $output")
                        }
                        tempFile.delete()
                    } catch (e: Exception) {
                        android.util.Log.e("AudioTranscription", "FFmpeg error", e)
                        errorMessage = "FFmpeg error: ${e.message}"
                    }
                    isExtractingAudio = false
                } else {
                    selectedAudioPath = tempFile.absolutePath
                }
            }
        }
    }
    
    // Recording state
    var showRecordingDialog by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var hasRecordPermission by remember { mutableStateOf(false) }
    
    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasRecordPermission = granted
        if (granted) {
            showRecordingDialog = true
        } else {
            errorMessage = "Recording permission denied"
        }
    }
    
    // Recording timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingSeconds = 0
            while (isRecording) {
                kotlinx.coroutines.delay(1000)
                recordingSeconds++
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.whisper_title)) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.WhisperModels.route) }) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_models))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Model Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.whisper_model), style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (whisperModels.isEmpty()) {
                        OutlinedButton(
                            onClick = { navController.navigate(Screen.WhisperModels.route) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.action_download))
                        }
                    } else {
                        OutlinedButton(
                            onClick = { showModelPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedModelPath?.substringAfterLast("/") ?: "Select model")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Audio Selection
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Audio Source", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { mediaFilePicker.launch(arrayOf("audio/*", "video/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Audio/Video")
                        }
                        
                        OutlinedButton(
                            onClick = { 
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Record")
                        }
                    }
                    
                    // Show extraction progress for video files
                    if (isExtractingAudio) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                extractionProgress,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    
                    if (selectedAudioPath != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Selected: ${selectedAudioPath!!.substringAfterLast("/")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Settings", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Language selection
                    var languageExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = languageExpanded,
                        onExpandedChange = { languageExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = WhisperLanguages.languages.find { it.first == selectedLanguage }?.second ?: "Auto-detect",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Language") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = languageExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = languageExpanded,
                            onDismissRequest = { languageExpanded = false }
                        ) {
                            WhisperLanguages.languages.take(20).forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedLanguage = code
                                        languageExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Translate toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Translate to English")
                        Switch(
                            checked = translateToEnglish,
                            onCheckedChange = { translateToEnglish = it }
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Output formats
                    Text("Output Formats", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(selected = outputTxt, onClick = { outputTxt = !outputTxt }, label = { Text("TXT") })
                        FilterChip(selected = outputSrt, onClick = { outputSrt = !outputSrt }, label = { Text("SRT") })
                        FilterChip(selected = outputVtt, onClick = { outputVtt = !outputVtt }, label = { Text("VTT") })
                        FilterChip(selected = outputJson, onClick = { outputJson = !outputJson }, label = { Text("JSON") })
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Transcribe Button
            Button(
                onClick = {
                    if (selectedModelPath == null) {
                        errorMessage = "Please select a model"
                        return@Button
                    }
                    if (selectedAudioPath == null) {
                        errorMessage = "Please select an audio file"
                        return@Button
                    }
                    
                    val formats = mutableSetOf<WhisperOutputFormat>()
                    if (outputTxt) formats.add(WhisperOutputFormat.TXT)
                    if (outputSrt) formats.add(WhisperOutputFormat.SRT)
                    if (outputVtt) formats.add(WhisperOutputFormat.VTT)
                    if (outputJson) formats.add(WhisperOutputFormat.JSON)
                    
                    val config = WhisperConfig(
                        modelPath = selectedModelPath!!,
                        audioPath = selectedAudioPath!!,
                        language = selectedLanguage,
                        translate = translateToEnglish,
                        outputFormats = formats,
                        threads = threads
                    )
                    
                    scope.launch {
                        val result = whisperService?.transcribe(config)
                        result?.fold(
                            onSuccess = { transcriptionResult = it.text },
                            onFailure = { errorMessage = it.message }
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = whisperState == WhisperState.Idle || whisperState == WhisperState.Completed
            ) {
                when (whisperState) {
                    is WhisperState.Converting -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Converting audio...")
                    }
                    is WhisperState.Transcribing -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Transcribing...")
                    }
                    else -> {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Transcribe")
                    }
                }
            }
            
            // Progress
            if (whisperProgress.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    whisperProgress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Error
            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            
            // Result
            transcriptionResult?.let {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Transcription", style = MaterialTheme.typography.titleMedium)
                            IconButton(onClick = { /* TODO: Copy */ }) {
                                Icon(Icons.Default.Share, contentDescription = "Copy")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
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
                Column {
                    whisperModels.forEach { model ->
                        TextButton(
                            onClick = {
                                selectedModelPath = model.path
                                showModelPicker = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(model.filename)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelPicker = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Recording Dialog
    if (showRecordingDialog) {
        val recordingFile = remember { File(context.cacheDir, "whisper_recording.m4a") }
        
        AlertDialog(
            onDismissRequest = {
                // Stop recording if active
                if (isRecording) {
                    try {
                        mediaRecorder?.stop()
                        mediaRecorder?.release()
                    } catch (e: Exception) { }
                    mediaRecorder = null
                    isRecording = false
                }
                showRecordingDialog = false
            },
            title = { Text("Record Audio", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Timer display
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
                    // Use and save recording to permanent storage
                    TextButton(onClick = {
                        try {
                            val recordingsDir = java.io.File(context.filesDir, "sd_output/Recordings").apply { mkdirs() }
                            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                            val savedFile = java.io.File(recordingsDir, "recording_$timestamp.m4a")
                            recordingFile.copyTo(savedFile, overwrite = true)
                            recordingFile.delete()
                            selectedAudioPath = savedFile.absolutePath
                            showRecordingDialog = false
                            recordingSeconds = 0
                        } catch (e: Exception) {
                            errorMessage = "Failed to save recording: ${e.message}"
                        }
                    }) {
                        Text("Use Recording")
                    }
                } else if (!isRecording) {
                    // Start recording
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
                            isRecording = true
                        } catch (e: Exception) {
                            errorMessage = "Failed to start recording: ${e.message}"
                            showRecordingDialog = false
                        }
                    }) {
                        Text("Start")
                    }
                } else {
                    // Stop recording
                    TextButton(onClick = {
                        try {
                            mediaRecorder?.stop()
                            mediaRecorder?.release()
                            mediaRecorder = null
                            isRecording = false
                        } catch (e: Exception) {
                            errorMessage = "Failed to stop recording: ${e.message}"
                        }
                    }) {
                        Text("Stop")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (isRecording) {
                        try {
                            mediaRecorder?.stop()
                            mediaRecorder?.release()
                        } catch (e: Exception) { }
                        mediaRecorder = null
                        isRecording = false
                    }
                    recordingSeconds = 0
                    showRecordingDialog = false
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
