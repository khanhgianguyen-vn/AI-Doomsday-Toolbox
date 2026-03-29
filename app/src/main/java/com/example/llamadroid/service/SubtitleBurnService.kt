package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import androidx.documentfile.provider.DocumentFile
import com.example.llamadroid.data.binary.BinaryRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Foreground service for burning subtitles into videos using ffmpeg.
 * Continues processing even when app is in background.
 */
class SubtitleBurnService : Service() {
    
    private val binder = SubtitleBurnBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentJob: Job? = null
    private var currentProcess: Process? = null
    private var taskId: Int = -1
    
    inner class SubtitleBurnBinder : Binder() {
        fun getService(): SubtitleBurnService = this@SubtitleBurnService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        setupFFmpegLibrarySymlinks()
    }
    
    private fun setupFFmpegLibrarySymlinks() {
        try {
            val nativeLibDir = applicationInfo.nativeLibraryDir
            val libDir = File(nativeLibDir)
            
            val symlinkPairs = listOf(
                "libavcodec.so" to "libavcodec.so.61",
                "libavformat.so" to "libavformat.so.61",
                "libavutil.so" to "libavutil.so.59",
                "libswresample.so" to "libswresample.so.5",
                "libswscale.so" to "libswscale.so.8"
            )
            
            for ((source, target) in symlinkPairs) {
                val sourceFile = File(libDir, source)
                val targetFile = File(libDir, target)
                if (sourceFile.exists() && !targetFile.exists()) {
                    try {
                        Runtime.getRuntime().exec(arrayOf("ln", "-s", sourceFile.absolutePath, targetFile.absolutePath)).waitFor()
                    } catch (e: Exception) {
                        DebugLog.log("[SubtitleBurnService] Symlink failed: $source -> $target")
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("[SubtitleBurnService] Library symlink setup failed: ${e.message}")
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        return START_NOT_STICKY
    }
    
    override fun onDestroy() {
        cancel()
        serviceScope.cancel()
        if (taskId != -1) {
            UnifiedNotificationManager.dismissTask(taskId)
        }
        super.onDestroy()
    }
    
    private fun startForegroundWithNotification() {
        val (id, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.VIDEO_UPSCALE,
            "Subtitle Burn"
        )
        taskId = id
        startForeground(id, notification)
    }
    
    private fun updateNotification(text: String, progress: Float = 0f) {
        if (taskId != -1) {
            UnifiedNotificationManager.updateProgress(taskId, progress, text)
        }
    }
    
    /**
     * Start burning subtitles into video
     */
    fun startBurn(config: SubtitleBurnConfig) {
        if (currentJob?.isActive == true) {
            DebugLog.log("[SubtitleBurnService] Already processing")
            return
        }
        
        _state.value = SubtitleBurnState.Processing(0f, "Starting...")
        
        currentJob = serviceScope.launch {
            try {
                burnSubtitles(config)
            } catch (e: CancellationException) {
                _state.value = SubtitleBurnState.Idle
                if (taskId != -1) UnifiedNotificationManager.dismissTask(taskId)
            } catch (e: Exception) {
                _state.value = SubtitleBurnState.Error(e.message ?: "Unknown error")
                if (taskId != -1) UnifiedNotificationManager.failTask(taskId, e.message ?: "Error")
            }
        }
    }
    
    private suspend fun burnSubtitles(config: SubtitleBurnConfig) {
        updateNotification("Preparing files...", 0.05f)
        _state.value = SubtitleBurnState.Processing(0.05f, "Preparing files...")
        
        val binaryRepo = BinaryRepository(this)
        val ffmpegFile: File? = binaryRepo.getFFmpegBinary()
        
        if (ffmpegFile == null || !ffmpegFile.exists()) {
            _state.value = SubtitleBurnState.Error("ffmpeg not found")
            if (taskId != -1) UnifiedNotificationManager.failTask(taskId, "ffmpeg not found")
            return
        }
        
        val ffmpegPath = ffmpegFile.absolutePath
        
        // Copy files to cache
        val cacheDir = cacheDir
        val timestamp = System.currentTimeMillis()
        val videoFile = File(cacheDir, "input_video_$timestamp.mp4")
        val subtitleFile = File(cacheDir, "input_subtitle_$timestamp.srt")
        val assFile = File(cacheDir, "converted_$timestamp.ass")
        val outputFile = File(cacheDir, "output_subtitled_$timestamp.mp4")
        
        try {
            // Copy video
            updateNotification("Copying video...", 0.1f)
            _state.value = SubtitleBurnState.Processing(0.1f, "Copying video...")
            contentResolver.openInputStream(config.videoUri)?.use { input ->
                videoFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Copy subtitle
            updateNotification("Copying subtitle...", 0.15f)
            _state.value = SubtitleBurnState.Processing(0.15f, "Copying subtitle...")
            contentResolver.openInputStream(config.subtitleUri)?.use { input ->
                subtitleFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            // Get video duration for progress calculation
            var videoDurationSecs = 0.0
            val probeCommand = listOf(ffmpegPath, "-i", videoFile.absolutePath)
            val probeProcess = ProcessBuilder(probeCommand).redirectErrorStream(true).start()
            BufferedReader(InputStreamReader(probeProcess.inputStream)).use { reader ->
                reader.forEachLine { line ->
                    // Parse "Duration: 00:02:29.84" format
                    if (line.contains("Duration:")) {
                        val durationMatch = Regex("Duration: (\\d+):(\\d+):(\\d+\\.\\d+)").find(line)
                        durationMatch?.let {
                            val hours = it.groupValues[1].toDoubleOrNull() ?: 0.0
                            val mins = it.groupValues[2].toDoubleOrNull() ?: 0.0
                            val secs = it.groupValues[3].toDoubleOrNull() ?: 0.0
                            videoDurationSecs = hours * 3600 + mins * 60 + secs
                            DebugLog.log("[SubtitleBurnService] Video duration: ${videoDurationSecs}s")
                        }
                    }
                }
            }
            probeProcess.waitFor()
            
            // Step 1: Convert SRT to ASS format
            updateNotification("Converting subtitles...", 0.2f)
            _state.value = SubtitleBurnState.Processing(0.2f, "Converting subtitles to ASS...")
            
            val convertCommand = listOf(
                ffmpegPath, "-y",
                "-i", subtitleFile.absolutePath,
                assFile.absolutePath
            )
            DebugLog.log("[SubtitleBurnService] Convert: ${convertCommand.joinToString(" ")}")
            
            val convertProcess = ProcessBuilder(convertCommand).redirectErrorStream(true).start()
            convertProcess.inputStream.bufferedReader().forEachLine { 
                DebugLog.log("[SubtitleBurnService] Convert: $it")
            }
            val convertExit = convertProcess.waitFor()
            
            if (convertExit != 0 || !assFile.exists()) {
                _state.value = SubtitleBurnState.Error("Failed to convert subtitles")
                if (taskId != -1) UnifiedNotificationManager.failTask(taskId, "Subtitle conversion failed")
                return
            }
            
            // Step 1.5: Setup fonts directory with a real font file
            // Copy DroidSans.ttf from /system/fonts to our cache so libass can find it
            val fontsCacheDir = File(cacheDir, "fonts")
            fontsCacheDir.mkdirs()
            
            val sourceFontFile = File("/system/fonts/DroidSans.ttf")
            val targetFontFile = File(fontsCacheDir, "DroidSans.ttf")
            
            try {
                if (sourceFontFile.exists() && (!targetFontFile.exists() || targetFontFile.length() == 0L)) {
                    sourceFontFile.copyTo(targetFontFile, overwrite = true)
                    DebugLog.log("[SubtitleBurnService] Copied DroidSans.ttf to cache")
                }
            } catch (e: Exception) {
                DebugLog.log("[SubtitleBurnService] Failed to copy font: ${e.message}")
            }
            
            // Step 1.6: Create fontconfig configuration file
            // Fontconfig needs fonts.conf to know where to find fonts
            val fontconfigDir = File(cacheDir, "fontconfig")
            fontconfigDir.mkdirs()
            val fontsConfFile = File(fontconfigDir, "fonts.conf")
            
            try {
                // Include both our cache fonts AND system fonts for full font support
                val fontsConf = """<?xml version="1.0"?>
<!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig>
    <dir>${fontsCacheDir.absolutePath}</dir>
    <dir>/system/fonts</dir>
    <cachedir>${fontconfigDir.absolutePath}/cache</cachedir>
    <match target="pattern">
        <edit name="family" mode="append" binding="weak">
            <string>Droid Sans</string>
        </edit>
    </match>
</fontconfig>"""
                fontsConfFile.writeText(fontsConf)
                File(fontconfigDir, "cache").mkdirs()
                DebugLog.log("[SubtitleBurnService] Created fonts.conf at ${fontsConfFile.absolutePath}")
            } catch (e: Exception) {
                DebugLog.log("[SubtitleBurnService] Failed to create fonts.conf: ${e.message}")
            }
            
            // Determine which font to use - user selected or default (Droid Sans)
            val selectedFont = if (config.fontName.isBlank() || config.fontName == "Default") {
                "Droid Sans"
            } else {
                config.fontName
            }
            
            // Modify ASS file to use the selected font
            try {
                val assContent = assFile.readText()
                val modifiedContent = assContent.replace("Arial", selectedFont)
                assFile.writeText(modifiedContent)
                DebugLog.log("[SubtitleBurnService] Changed ASS font to '$selectedFont'")
            } catch (e: Exception) {
                DebugLog.log("[SubtitleBurnService] Failed to modify ASS: ${e.message}")
            }
            
            // Build force_style string
            val colorHex = String.format(
                "&H00%02X%02X%02X",
                (config.primaryColorBlue * 255).toInt(),
                (config.primaryColorGreen * 255).toInt(),
                (config.primaryColorRed * 255).toInt()
            )
            
            // Style string - use selected font
            val forceStyle = "Fontsize=${config.fontSize},Alignment=${config.alignment},MarginV=${config.marginV},MarginL=${config.marginL},PrimaryColour=$colorHex,FontName=$selectedFont"
            
            // Step 2: Burn subtitles (fontsdir still points to cache, but fontconfig also knows about /system/fonts)
            val subtitleFilter = "subtitles=${assFile.absolutePath}:fontsdir=${fontsCacheDir.absolutePath}:force_style='$forceStyle'"
            
            val command = listOf(
                ffmpegPath,
                "-y",
                "-i", videoFile.absolutePath,
                "-vf", subtitleFilter,
                "-c:v", "libx264",
                "-preset", "fast",
                "-crf", "23",
                "-c:a", "copy",
                outputFile.absolutePath
            )
            
            DebugLog.log("[SubtitleBurnService] Burn: ${command.joinToString(" ")}")
            
            updateNotification("Burning subtitles: 0%", 0.25f)
            _state.value = SubtitleBurnState.Processing(0.25f, "Burning subtitles: 0%")
            
            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)
            processBuilder.environment()["LD_LIBRARY_PATH"] = "${applicationInfo.nativeLibraryDir}:/system/lib64"
            processBuilder.environment()["FONTCONFIG_PATH"] = fontconfigDir.absolutePath
            processBuilder.environment()["FONTCONFIG_FILE"] = fontsConfFile.absolutePath
            
            currentProcess = processBuilder.start()
            
            // Read output and parse progress (DebugLog handles sensitive info filtering)
            val reader = BufferedReader(InputStreamReader(currentProcess!!.inputStream))
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                DebugLog.log("[SubtitleBurnService] $line")
                
                // Parse "time=00:01:23.45" for progress
                if (line?.contains("time=") == true && videoDurationSecs > 0) {
                    val timeMatch = Regex("time=(\\d+):(\\d+):(\\d+\\.\\d+)").find(line!!)
                    timeMatch?.let {
                        val hours = it.groupValues[1].toDoubleOrNull() ?: 0.0
                        val mins = it.groupValues[2].toDoubleOrNull() ?: 0.0
                        val secs = it.groupValues[3].toDoubleOrNull() ?: 0.0
                        val currentSecs = hours * 3600 + mins * 60 + secs
                        val percent = ((currentSecs / videoDurationSecs) * 100).toInt().coerceIn(0, 100)
                        val progress = 0.25f + (percent / 100f * 0.65f)  // 25% to 90%
                        
                        updateNotification("Burning subtitles: $percent%", progress)
                        _state.value = SubtitleBurnState.Processing(progress, "Burning subtitles: $percent%")
                    }
                }
            }
            
            val exitCode = currentProcess!!.waitFor()
            currentProcess = null
            
            if (exitCode == 0 && outputFile.exists()) {
                updateNotification("Saving to folder...", 0.9f)
                _state.value = SubtitleBurnState.Processing(0.9f, "Saving to output folder...")
                
                // Copy to output folder (in SubtitleBurn subfolder)
                var savedPath = "cache"
                if (!config.outputFolderUri.isNullOrEmpty()) {
                    try {
                        val rootFolder = DocumentFile.fromTreeUri(this, Uri.parse(config.outputFolderUri))
                        
                        // Create SubtitleBurn subfolder if it doesn't exist
                        var subtitleFolder = rootFolder?.findFile("SubtitleBurn")
                        if (subtitleFolder == null || !subtitleFolder.isDirectory) {
                            subtitleFolder = rootFolder?.createDirectory("SubtitleBurn")
                        }
                        
                        val targetFolder = subtitleFolder ?: rootFolder
                        val timestamp = System.currentTimeMillis()
                        val outputName = "subtitled_$timestamp.mp4"
                        val newFile = targetFolder?.createFile("video/mp4", outputName)
                        
                        newFile?.uri?.let { destUri ->
                            contentResolver.openOutputStream(destUri)?.use { output ->
                                outputFile.inputStream().use { input ->
                                    input.copyTo(output)
                                }
                            }
                            savedPath = "SubtitleBurn/$outputName"
                            DebugLog.log("[SubtitleBurnService] Saved to: $savedPath")
                        }
                    } catch (e: Exception) {
                        DebugLog.log("[SubtitleBurnService] Save failed: ${e.message}")
                    }
                }
                
                // Update notification to show "Saved" (not "Saving")
                updateNotification("Saved: $savedPath", 1.0f)
                if (taskId != -1) {
                    UnifiedNotificationManager.completeTask(taskId, "✓ Saved: $savedPath")
                }
                _state.value = SubtitleBurnState.Complete(savedPath)
            } else {
                _state.value = SubtitleBurnState.Error("ffmpeg failed with code $exitCode")
                if (taskId != -1) UnifiedNotificationManager.failTask(taskId, "ffmpeg failed: $exitCode")
            }
            
        } finally {
            // Cleanup
            videoFile.delete()
            subtitleFile.delete()
            assFile.delete()
            outputFile.delete()
        }
    }
    
    fun cancel() {
        currentProcess?.destroyForcibly()
        currentProcess = null
        currentJob?.cancel()
        currentJob = null
        _state.value = SubtitleBurnState.Idle
    }
    
    companion object {
        private val _state = MutableStateFlow<SubtitleBurnState>(SubtitleBurnState.Idle)
        val state: StateFlow<SubtitleBurnState> = _state.asStateFlow()
        
        fun resetState() {
            _state.value = SubtitleBurnState.Idle
        }
        
        /**
         * Get list of font names available on Android system.
         * Reads font files from /system/fonts directory.
         */
        fun getSystemFonts(): List<String> {
            val fonts = mutableSetOf<String>()
            try {
                val fontsDir = File("/system/fonts")
                if (fontsDir.exists() && fontsDir.isDirectory) {
                    fontsDir.listFiles()?.forEach { file ->
                        if (file.extension.lowercase() in listOf("ttf", "otf", "ttc")) {
                            // Convert filename to font name (remove extension, replace separators)
                            val fontName = file.nameWithoutExtension
                                .replace("-", " ")
                                .replace("_", " ")
                                .split(" ")
                                .filter { it.isNotEmpty() && !it.matches(Regex("(?i)(regular|bold|italic|medium|light|thin|black|condensed|mono)")) }
                                .joinToString(" ")
                            
                            if (fontName.isNotBlank()) {
                                fonts.add(fontName)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback fonts if /system/fonts not accessible
            }
            
            // Add common fallbacks if list is empty
            if (fonts.isEmpty()) {
                fonts.addAll(listOf("Droid Sans", "Roboto", "Noto Sans", "Noto Serif"))
            }
            
            return listOf("Default") + fonts.sorted().distinct()
        }
    }
}

sealed class SubtitleBurnState {
    object Idle : SubtitleBurnState()
    data class Processing(val progress: Float, val status: String) : SubtitleBurnState()
    data class Complete(val outputPath: String) : SubtitleBurnState()
    data class Error(val message: String) : SubtitleBurnState()
}

data class SubtitleBurnConfig(
    val videoUri: Uri,
    val subtitleUri: Uri,
    val fontSize: Int,
    val alignment: Int,
    val marginV: Int,
    val marginL: Int,
    val primaryColorRed: Float,
    val primaryColorGreen: Float,
    val primaryColorBlue: Float,
    val fontName: String,
    val outputFolderUri: String?
)
