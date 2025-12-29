package com.example.llamadroid.service

import android.content.Context
import android.os.PowerManager
import android.widget.Toast
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.NoteEntity
import com.example.llamadroid.data.db.NoteType
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Service to summarize video content using:
 * 1. FFmpeg to extract audio
 * 2. Whisper to transcribe
 * 3. LLM to summarize transcript
 */
object VideoSumupService {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _state = MutableStateFlow<VideoSumupState>(VideoSumupState.Idle)
    val state: StateFlow<VideoSumupState> = _state
    
    private val _result = MutableStateFlow<Result<VideoSumupResult>?>(null)
    val result: StateFlow<Result<VideoSumupResult>?> = _result
    
    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress
    
    private var currentJob: Job? = null
    private var currentProcess: Process? = null
    private var isCancelled = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var notificationTaskId: Int? = null
    
    fun startSummarization(
        context: Context,
        videoPath: String,
        videoFileName: String,
        whisperModelPath: String,
        llmModelPath: String,
        language: String = "auto",
        threads: Int = 4,
        contextSize: Int = 2048,
        maxTokens: Int = 300,
        temperature: Float = 0.7f,
        saveToNotes: Boolean = true,
        noteType: NoteType = NoteType.VIDEO_SUMMARY,  // WORKFLOW for workflow calls
        audioSourcePath: String? = null  // Original audio path for workflow notes
    ) {
        currentJob?.cancel()
        _result.value = null
        isCancelled = false
        
        // Start notification for workflow
        notificationTaskId = UnifiedNotificationManager.startTask(
            UnifiedNotificationManager.TaskType.TRANSCRIPTION,
            "Transcribe+Summary: $videoFileName"
        )
        
        // Acquire wake lock
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LlamaDroid:VideoSumup")
            wakeLock?.acquire(60 * 60 * 1000L)
            DebugLog.log("[VIDEO-SUMUP] Wake lock acquired")
        } catch (e: Exception) {
            DebugLog.log("[VIDEO-SUMUP] Failed to acquire wake lock: ${e.message}")
        }
        
        currentJob = serviceScope.launch {
            try {
                val result = summarizeVideo(context, videoPath, videoFileName, whisperModelPath, llmModelPath, language, threads, contextSize, maxTokens, temperature, saveToNotes, noteType, audioSourcePath)
                _result.value = result
                
                // Complete notification on success
                result.fold(
                    onSuccess = {
                        notificationTaskId?.let { id ->
                            UnifiedNotificationManager.completeTask(id, "Summary ready!")
                        }
                    },
                    onFailure = { e ->
                        notificationTaskId?.let { id ->
                            UnifiedNotificationManager.failTask(id, e.message ?: "Failed")
                        }
                    }
                )
            } catch (e: Exception) {
                notificationTaskId?.let { id ->
                    UnifiedNotificationManager.failTask(id, e.message ?: "Error")
                }
            } finally {
                releaseWakeLock()
                notificationTaskId = null
            }
        }
    }
    
    private suspend fun summarizeVideo(
        context: Context,
        videoPath: String,
        videoFileName: String,
        whisperModelPath: String,
        llmModelPath: String,
        language: String,
        threads: Int,
        contextSize: Int,
        maxTokens: Int,
        temperature: Float,
        saveToNotes: Boolean,
        noteType: NoteType,
        audioSourcePath: String?
    ): Result<VideoSumupResult> = withContext(Dispatchers.IO) {
        try {
            // Step 1: Extract audio
            _state.value = VideoSumupState.ExtractingAudio
            _progress.value = "Extracting audio..."
            notificationTaskId?.let { id -> UnifiedNotificationManager.updateProgress(id, 0.1f, "Extracting audio...") }
            DebugLog.log("[VIDEO-SUMUP] Step 1: Extracting audio")
            
            val audioFile = File(context.cacheDir, "video_audio.wav")
            val extractResult = extractAudio(context, videoPath, audioFile.absolutePath)
            if (extractResult.isFailure) {
                _state.value = VideoSumupState.Error(extractResult.exceptionOrNull()?.message ?: "Extract failed")
                return@withContext Result.failure(extractResult.exceptionOrNull()!!)
            }
            if (isCancelled) return@withContext Result.failure(Exception("Cancelled"))
            
            // Step 2: Transcribe
            _state.value = VideoSumupState.Transcribing
            _progress.value = "Transcribing with Whisper..."
            notificationTaskId?.let { id -> UnifiedNotificationManager.updateProgress(id, 0.5f, "Transcribing...") }
            DebugLog.log("[VIDEO-SUMUP] Step 2: Transcribing")
            
            val transcriptResult = transcribe(context, audioFile.absolutePath, whisperModelPath, language, threads)
            if (transcriptResult.isFailure) {
                _state.value = VideoSumupState.Error(transcriptResult.exceptionOrNull()?.message ?: "Transcribe failed")
                return@withContext Result.failure(transcriptResult.exceptionOrNull()!!)
            }
            val transcript = transcriptResult.getOrThrow()
            if (isCancelled) return@withContext Result.failure(Exception("Cancelled"))
            
            // Step 3: Summarize
            _state.value = VideoSumupState.Summarizing
            _progress.value = "Summarizing with AI..."
            notificationTaskId?.let { id -> UnifiedNotificationManager.updateProgress(id, 0.9f, "Summarizing...") }
            DebugLog.log("[VIDEO-SUMUP] Step 3: Summarizing (${transcript.length} chars)")
            
            val summaryResult = summarize(context, llmModelPath, transcript, 
                contextSize, threads, temperature, maxTokens)
            if (summaryResult.isFailure) {
                _state.value = VideoSumupState.Error(summaryResult.exceptionOrNull()?.message ?: "Summary failed")
                return@withContext Result.failure(summaryResult.exceptionOrNull()!!)
            }
            val summary = summaryResult.getOrThrow()
            
            // Cleanup and save
            audioFile.delete()
            
            // Save to notes - always save, use noteType parameter (VIDEO_SUMMARY or WORKFLOW)
            if (saveToNotes) {
                try {
                    val db = AppDatabase.getDatabase(context)
                    val settingsRepo = SettingsRepository(context)
                    
                    // Copy audio to permanent Recordings folder
                    var permanentAudioPath: String? = null
                    val sourceAudio = audioSourcePath ?: videoPath
                    if (sourceAudio.isNotEmpty() && java.io.File(sourceAudio).exists()) {
                        try {
                            val recordingsDir = java.io.File(context.filesDir, "sd_output/Recordings").apply { mkdirs() }
                            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                            val extension = sourceAudio.substringAfterLast(".", "m4a")
                            val savedFile = java.io.File(recordingsDir, "audio_${timestamp}.$extension")
                            java.io.File(sourceAudio).copyTo(savedFile, overwrite = true)
                            permanentAudioPath = savedFile.absolutePath
                            DebugLog.log("[VIDEO-SUMUP] Audio saved to app storage: $permanentAudioPath")
                            
                            // Also copy to user's SAF output folder if configured
                            val outputFolderUri = settingsRepo.outputFolderUri.value
                            if (!outputFolderUri.isNullOrEmpty()) {
                                try {
                                    val treeUri = android.net.Uri.parse(outputFolderUri)
                                    val rootFolder = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                                    
                                    // Create/get Recordings subfolder
                                    var recordingsDoc = rootFolder?.findFile("Recordings")
                                    if (recordingsDoc == null) {
                                        recordingsDoc = rootFolder?.createDirectory("Recordings")
                                    }
                                    
                                    if (recordingsDoc != null) {
                                        val mimeType = when (extension) {
                                            "mp3" -> "audio/mpeg"
                                            "m4a" -> "audio/mp4"
                                            "wav" -> "audio/wav"
                                            "ogg" -> "audio/ogg"
                                            else -> "audio/*"
                                        }
                                        val destFile = recordingsDoc.createFile(mimeType, "audio_${timestamp}.$extension")
                                        destFile?.uri?.let { destUri ->
                                            context.contentResolver.openOutputStream(destUri)?.use { output ->
                                                savedFile.inputStream().use { input ->
                                                    input.copyTo(output)
                                                }
                                            }
                                            DebugLog.log("[VIDEO-SUMUP] Audio copied to output folder: ${destFile.name}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    DebugLog.log("[VIDEO-SUMUP] Failed to copy to output folder: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            DebugLog.log("[VIDEO-SUMUP] Failed to save audio: ${e.message}")
                        }
                    }
                    
                    db.noteDao().insert(NoteEntity(
                        title = if (noteType == NoteType.WORKFLOW) "Transcription: $videoFileName" else "Video: $videoFileName",
                        content = "## Summary\n\n$summary\n\n## Transcript\n\n$transcript",
                        type = noteType,
                        sourceFile = videoFileName,
                        language = language,
                        audioPath = permanentAudioPath
                    ))
                    DebugLog.log("[VIDEO-SUMUP] Saved to Notes with audio: $permanentAudioPath")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Summary saved!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    DebugLog.log("[VIDEO-SUMUP] Save failed: ${e.message}")
                }
            }
            
            _state.value = VideoSumupState.Idle
            _progress.value = "Complete!"
            Result.success(VideoSumupResult(transcript, summary))
        } catch (e: Exception) {
            DebugLog.log("[VIDEO-SUMUP] Error: ${e.message}")
            _state.value = VideoSumupState.Error(e.message ?: "Error")
            Result.failure(e)
        }
    }
    
    private fun extractAudio(context: Context, videoPath: String, outputPath: String): Result<Unit> {
        return try {
            val binaryRepo = com.example.llamadroid.data.binary.BinaryRepository(context)
            val ffmpeg = binaryRepo.getFFmpegBinary()
            if (ffmpeg == null || !ffmpeg.exists()) return Result.failure(Exception("FFmpeg not found"))
            
            val args = listOf(ffmpeg.absolutePath, "-y", "-i", videoPath, "-vn", 
                "-acodec", "pcm_s16le", "-ar", "16000", "-ac", "1", outputPath)
            DebugLog.log("[VIDEO-SUMUP] FFmpeg: ${args.joinToString(" ")}")
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val symlinkDir = File(context.cacheDir, "ffmpeg_libs")
            symlinkDir.mkdirs()
            pb.environment()["LD_LIBRARY_PATH"] = "${binaryRepo.getLibraryDir()}:${symlinkDir.absolutePath}"
            
            val process = pb.start()
            currentProcess = process
            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) Result.failure(Exception("FFmpeg exit $exitCode"))
            else Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }
    
    private fun transcribe(context: Context, audioPath: String, modelPath: String, language: String, threads: Int): Result<String> {
        return try {
            val binaryRepo = com.example.llamadroid.data.binary.BinaryRepository(context)
            val whisper = binaryRepo.getWhisperCliBinary()
            if (whisper == null || !whisper.exists()) return Result.failure(Exception("Whisper not found"))
            
            val outputBase = File(context.cacheDir, "whisper_sumup")
            val args = listOf(whisper.absolutePath, "-m", modelPath, "-f", audioPath, 
                "-l", language, "-t", threads.toString(), "--no-gpu", "-otxt", "-of", outputBase.absolutePath)
            DebugLog.log("[VIDEO-SUMUP] Whisper: ${args.joinToString(" ")}")
            
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val symlinkDir = File(context.cacheDir, "ffmpeg_libs")
            pb.environment()["LD_LIBRARY_PATH"] = "${binaryRepo.getLibraryDir()}:${symlinkDir.absolutePath}"
            
            val process = pb.start()
            currentProcess = process
            process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            
            if (exitCode != 0) return Result.failure(Exception("Whisper exit $exitCode"))
            
            val transcriptFile = File("${outputBase.absolutePath}.txt")
            if (!transcriptFile.exists()) return Result.failure(Exception("No transcript"))
            
            val transcript = transcriptFile.readText().trim()
            transcriptFile.delete()
            Result.success(transcript)
        } catch (e: Exception) { Result.failure(e) }
    }
    
    private fun summarize(context: Context, modelPath: String, transcript: String, 
                          contextSize: Int, threads: Int, temperature: Float, maxTokens: Int): Result<String> {
        return try {
            val binaryRepo = com.example.llamadroid.data.binary.BinaryRepository(context)
            val llama = binaryRepo.getLlamaCliBinary()
            if (llama == null || !llama.exists()) return Result.failure(Exception("LLM not found"))
            
            // Limit transcript to avoid memory issues
            val maxLen = 8000
            val text = if (transcript.length > maxLen) transcript.take(maxLen) else transcript
            
            val prompt = "Summarize this:\n\n$text\n\nSummary:"
            val outputFile = File(context.cacheDir, "video_sumup_output.txt")
            outputFile.delete()
            
            // Flags to prevent interactive mode:
            // -no-cnv: disable conversation mode
            // --simple-io: disable colorful/interactive prompt output (prevents "> " spam)
            // --log-disable: reduce log noise
            val args = mutableListOf(
                llama.absolutePath, 
                "-m", modelPath,
                "-p", prompt,
                "-n", maxTokens.toString(),
                "-c", contextSize.toString(),
                "-t", threads.toString(),
                "--temp", temperature.toString(),
                "-no-cnv",
                "--simple-io",
                "--log-disable"
            )
            
            // Add KV cache quantization flags if enabled
            val settingsRepo = com.example.llamadroid.data.SettingsRepository(context)
            if (settingsRepo.videoKvCacheEnabled.value) {
                args.add("--cache-type-k")
                args.add(settingsRepo.videoKvCacheTypeK.value)
                args.add("--cache-type-v")
                args.add(settingsRepo.videoKvCacheTypeV.value)
                DebugLog.log("[VIDEO-SUMUP] KV cache enabled: K=${settingsRepo.videoKvCacheTypeK.value}, V=${settingsRepo.videoKvCacheTypeV.value}")
            }
            
            // LOG THE FULL COMMAND
            val fullCommand = args.mapIndexed { i, arg ->
                if (i == 4 && arg.length > 100) "\"${arg.take(100)}...[${arg.length} chars]\"" else "\"$arg\""
            }.joinToString(" ")
            DebugLog.log("[VIDEO-SUMUP] FULL COMMAND: $fullCommand")
            DebugLog.log("[VIDEO-SUMUP] Model exists: ${File(modelPath).exists()}, size: ${File(modelPath).length()} bytes")
            
            val pb = ProcessBuilder(args)
            pb.directory(context.cacheDir)
            pb.environment()["LD_LIBRARY_PATH"] = binaryRepo.getLibraryDir()
            pb.environment()["HOME"] = context.cacheDir.absolutePath
            pb.redirectOutput(outputFile)
            pb.redirectErrorStream(true)
            
            DebugLog.log("[VIDEO-SUMUP] LLM: Starting...")
            
            val process = pb.start()
            currentProcess = process
            process.outputStream.close()
            
            // Wait with periodic monitoring for interactive mode spam
            val startTime = System.currentTimeMillis()
            var lastGoodSize = 0L
            var spamDetected = false
            var extractedGoodContent: String? = null  // Store good content when spam detected
            
            while (process.isAlive) {
                val waitResult = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                
                if (waitResult) break
                
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                
                // Check for interactive mode spam (use try-catch to prevent crashes)
                try {
                    if (outputFile.exists()) {
                        val currentSize = outputFile.length()
                        
                        if (currentSize - lastGoodSize > 1_000_000) {
                            // Read only the LAST 1KB to check for spam (avoid OOM)
                            val last1KB = java.io.RandomAccessFile(outputFile, "r").use { raf ->
                                val readStart = maxOf(0L, currentSize - 1024)
                                raf.seek(readStart)
                                val buffer = ByteArray(minOf(1024, currentSize.toInt()))
                                raf.read(buffer)
                                String(buffer)
                            }
                            
                            val promptCount = last1KB.windowed(2).count { it == "> " }
                            
                            if (promptCount > 50) {
                                DebugLog.log("[VIDEO-SUMUP] LLM: Detected interactive mode spam! Stopping...")
                                spamDetected = true
                                
                                // Read only the good portion BEFORE destroying process
                                extractedGoodContent = java.io.RandomAccessFile(outputFile, "r").use { raf ->
                                    val readSize = minOf(lastGoodSize.toInt(), 100_000)
                                    val buffer = ByteArray(readSize)
                                    raf.read(buffer)
                                    String(buffer)
                                }
                                
                                DebugLog.log("[VIDEO-SUMUP] LLM: Extracted ${extractedGoodContent.length} chars before spam")
                                
                                // Delete file immediately to prevent further corruption
                                outputFile.delete()
                                
                                // Now kill the process
                                process.destroyForcibly()
                                break
                            }
                        }
                        
                        if (!spamDetected && currentSize < lastGoodSize + 500_000) {
                            lastGoodSize = currentSize
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.log("[VIDEO-SUMUP] LLM: Error checking for spam: ${e.message}")
                }
                
                if (elapsed > 600) {
                    DebugLog.log("[VIDEO-SUMUP] LLM: Timeout")
                    process.destroyForcibly()
                    return Result.failure(Exception("LLM timeout"))
                }
            }
            
            // Wait for process to fully terminate if we killed it
            if (spamDetected) {
                try {
                    process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                } catch (_: Exception) {}
            }
            
            val totalTime = (System.currentTimeMillis() - startTime) / 1000
            DebugLog.log("[VIDEO-SUMUP] LLM: Completed after ${totalTime}s (spam=$spamDetected)")
            
            // Use extracted content if spam was detected, otherwise read from file
            val output = if (spamDetected && extractedGoodContent != null) {
                DebugLog.log("[VIDEO-SUMUP] LLM: Using extracted content: ${extractedGoodContent.length} chars")
                extractedGoodContent
            } else {
                val fileContent = if (outputFile.exists()) outputFile.readText() else ""
                outputFile.delete()
                DebugLog.log("[VIDEO-SUMUP] LLM: Read from file: ${fileContent.length} chars")
                fileContent
            }
            
            if (output.length < 500) {
                DebugLog.log("[VIDEO-SUMUP] LLM: Full=$output")
            }
            
            if (!spamDetected && output.isBlank()) {
                return Result.failure(Exception("LLM failed - empty output"))
            }
            
            val cleaned = cleanLlamaOutput(output)
            if (cleaned.isBlank()) {
                return Result.failure(Exception("No summary after cleaning"))
            }
            
            Result.success(cleaned)
        } catch (e: Exception) { 
            DebugLog.log("[VIDEO-SUMUP] LLM error: ${e.message}")
            Result.failure(e) 
        }
    }
    
    private fun cleanLlamaOutput(output: String): String {
        // The output contains:
        // 1. llama-cli warnings/errors
        // 2. ASCII art banner
        // 3. Model info (build, model, modalities)
        // 4. Available commands
        // 5. Echoed prompt (lines starting with "> ") - may end with "(truncated)"
        // 6. ACTUAL RESPONSE (what we want)
        // 7. Performance stats
        
        // Strategy 0: If output contains "(truncated)", the response starts right after it
        val truncatedIndex = output.indexOf("(truncated)")
        if (truncatedIndex >= 0) {
            val afterTruncated = output.substring(truncatedIndex + "(truncated)".length).trimStart()
            
            val endMarkers = listOf(
                "common_perf_print:", "llama_perf_", "llama_memory_breakdown",
                "sampling time =", "ms per token", "tokens per second",
                "Exiting...", "\n> \n"
            )
            var responseEnd = afterTruncated.length
            for (marker in endMarkers) {
                val markerIdx = afterTruncated.indexOf(marker)
                if (markerIdx > 0) {
                    responseEnd = minOf(responseEnd, markerIdx)
                }
            }
            
            val result = afterTruncated.substring(0, responseEnd).trim()
            DebugLog.log("[VIDEO-SUMUP] Extracted after (truncated): ${result.length} chars")
            if (result.isNotBlank()) {
                return result
            }
        }
        
        val lines = output.lines()
        var responseStartIndex = -1
        var responseEndIndex = lines.size
        
        // Strategy 1: Find where the echoed prompt ends
        var lastPromptEchoLine = -1
        for ((index, line) in lines.withIndex()) {
            if (line.trimStart().startsWith("> ") || line.trimStart().startsWith(">")) {
                lastPromptEchoLine = index
            }
        }
        
        if (lastPromptEchoLine >= 0) {
            responseStartIndex = lastPromptEchoLine + 1
        }
        
        // Strategy 2: Look for common response starters
        if (responseStartIndex < 0) {
            val responseStarters = listOf(
                "Here's", "Here is", "Summary", "**Summary", "## Summary",
                "This", "The ", "Based on", "I've", "I have"
            )
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (responseStarters.any { trimmed.startsWith(it) }) {
                    responseStartIndex = index
                    break
                }
            }
        }
        
        // Strategy 3: Skip known metadata
        if (responseStartIndex < 0) {
            val metadataMarkers = listOf(
                "--no-conversation", "please use", "Loading model",
                "▄", "▀", "██", "build", "model", "modalities",
                "available commands", "/exit", "/regen", "/clear", "/read"
            )
            for ((index, line) in lines.withIndex()) {
                val isMetadata = metadataMarkers.any { line.contains(it) } || 
                                 line.isBlank() ||
                                 line.trim().length < 3
                if (!isMetadata) {
                    responseStartIndex = index
                    break
                }
            }
        }
        
        // Find the end
        val endMarkers = listOf(
            "common_perf_print:", "llama_perf_", "llama_memory_breakdown",
            "sampling time =", "ms per token", "tokens per second",
            "Exiting...", "> \n"
        )
        
        for ((index, line) in lines.withIndex()) {
            if (index > responseStartIndex) {
                for (marker in endMarkers) {
                    if (line.contains(marker)) {
                        responseEndIndex = minOf(responseEndIndex, index)
                        break
                    }
                }
            }
        }
        
        // Trim trailing empty lines
        while (responseEndIndex > responseStartIndex && 
               (lines.getOrNull(responseEndIndex - 1)?.isBlank() == true ||
                lines.getOrNull(responseEndIndex - 1)?.trim() == ">")) {
            responseEndIndex--
        }
        
        if (responseStartIndex >= 0 && responseStartIndex < responseEndIndex) {
            val responseLines = lines.subList(responseStartIndex, responseEndIndex)
            
            val cleanedLines = responseLines.filter { line ->
                val trimmed = line.trim()
                !trimmed.startsWith("llama_") &&
                !trimmed.startsWith("ggml_") &&
                !trimmed.startsWith("common_") &&
                !trimmed.startsWith("main:") &&
                !trimmed.startsWith("build:") &&
                !trimmed.startsWith("model:") &&
                !trimmed.startsWith("modalities:") &&
                !trimmed.startsWith("> ") &&
                !trimmed.startsWith("available commands") &&
                !trimmed.startsWith("/exit") &&
                !trimmed.startsWith("/regen") &&
                !trimmed.contains("tokens per second") &&
                !trimmed.contains("ms per token") &&
                !trimmed.all { it == '▄' || it == '▀' || it == '█' || it == ' ' }
            }
            
            val result = cleanedLines.joinToString("\n").trim()
            DebugLog.log("[VIDEO-SUMUP] Cleaned output: startIdx=$responseStartIndex, endIdx=$responseEndIndex, resultChars=${result.length}")
            return result
        }
        
        return fallbackClean(output)
    }
    
    private fun fallbackClean(output: String): String {
        val patterns = listOf(
            Regex("--no-conversation[^\\n]*\\n"),
            Regex("please use[^\\n]*\\n"),
            Regex("Loading model[^\\n]*\\n"),
            Regex("[▄▀█\\s]+\\n"),
            Regex("build\\s*:[^\\n]*\\n"),
            Regex("model\\s*:[^\\n]*\\n"),
            Regex("modalities\\s*:[^\\n]*\\n"),
            Regex("available commands:[^\\n]*\\n"),
            Regex("/exit[^\\n]*\\n"),
            Regex("/regen[^\\n]*\\n"),
            Regex("/clear[^\\n]*\\n"),
            Regex("/read[^\\n]*\\n"),
            Regex("> [^\\n]*\\n"),
            Regex("llama_[^\\n]*\\n"),
            Regex("ggml_[^\\n]*\\n"),
            Regex("common_[^\\n]*\\n"),
            Regex("Exiting[^\\n]*\\n")
        )
        
        var result = output
        for (pattern in patterns) {
            result = result.replace(pattern, "")
        }
        
        return result.trim()
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.release()
            wakeLock = null
            DebugLog.log("[VIDEO-SUMUP] Wake lock released")
        } catch (_: Exception) {}
    }
    
    fun cancel() {
        isCancelled = true
        currentProcess?.destroyForcibly()
        currentJob?.cancel()
        _state.value = VideoSumupState.Idle
        _progress.value = ""
        releaseWakeLock()
    }
    
    fun clearResult() { _result.value = null }
}

sealed class VideoSumupState {
    object Idle : VideoSumupState()
    object ExtractingAudio : VideoSumupState()
    object Transcribing : VideoSumupState()
    object Summarizing : VideoSumupState()
    data class Error(val message: String) : VideoSumupState()
}

data class VideoSumupResult(val transcript: String, val summary: String)
