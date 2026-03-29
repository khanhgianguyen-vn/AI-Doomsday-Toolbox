package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.Downloader
import com.example.llamadroid.data.model.DownloadProgressHolder
import com.example.llamadroid.data.model.PendingDownloadHolder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import java.io.File

/**
 * Foreground service for downloading models.
 * Keeps downloads running when app is backgrounded.
 */
class DownloadService : Service() {
    
    companion object {
        const val ACTION_START_DOWNLOAD = "start_download"
        const val ACTION_CANCEL_DOWNLOAD = "cancel_download"
        const val ACTION_CANCEL_ALL = "cancel_all"
        
        const val EXTRA_URL = "url"
        const val EXTRA_DEST_PATH = "dest_path"
        const val EXTRA_FILENAME = "filename"
        
        private val activeDownloads = mutableMapOf<String, Job>()
        
        fun startDownload(context: Context, url: String, destPath: String, filename: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_DEST_PATH, destPath)
                putExtra(EXTRA_FILENAME, filename)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun cancelDownload(context: Context, filename: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_FILENAME, filename)
            }
            context.startService(intent)
        }
    }
    
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var notificationTaskId: Int? = null
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val destPath = intent.getStringExtra(EXTRA_DEST_PATH) ?: return START_NOT_STICKY
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                
                val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
                    UnifiedNotificationManager.TaskType.DOWNLOAD,
                    filename
                )
                notificationTaskId = taskId
                startForeground(taskId, notification)
                startDownloadInternal(url, destPath, filename)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val filename = intent.getStringExtra(EXTRA_FILENAME) ?: return START_NOT_STICKY
                cancelDownloadInternal(filename)
            }
            ACTION_CANCEL_ALL -> {
                activeDownloads.forEach { (_, job) -> job.cancel() }
                activeDownloads.clear()
                Downloader.cancelAllDownloads()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    
    private fun startDownloadInternal(url: String, destPath: String, filename: String) {
        val destFile = File(destPath)
        
        // Ensure parent directory exists
        destFile.parentFile?.mkdirs()
        
        val job = serviceScope.launch {
            var lastProgress = 0
            var downloadSuccess = false
            
            Downloader.download(url, destFile, this@DownloadService)
                .catch { e ->
                    DebugLog.log("DownloadService: Download failed - ${e.message}")
                    val repoId = DownloadProgressHolder.findRepoIdByFilename(filename)
                    if (repoId != null) {
                        DownloadProgressHolder.updateProgress(repoId, -1f)
                    }
                    PendingDownloadHolder.removePending(filename)
                }
                .collect { progress ->
                    val repoId = DownloadProgressHolder.findRepoIdByFilename(filename)
                    if (repoId != null) {
                        DownloadProgressHolder.updateProgress(repoId, progress)
                    }
                    val progressPercent = (progress * 100).toInt()
                    if (progressPercent >= lastProgress + 5 || progress == 1f) {
                        lastProgress = progressPercent
                        updateNotification(filename, progressPercent)
                    }
                    if (progress >= 1f) {
                        downloadSuccess = true
                    }
                }
            
            // Download complete - save to DB if pending
            if (downloadSuccess) {
                val pending = PendingDownloadHolder.getPending(filename)
                if (pending != null) {
                    try {
                        val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(this@DownloadService)
                        val entity = com.example.llamadroid.data.db.ModelEntity(
                            filename = filename,
                            path = destFile.absolutePath,
                            sizeBytes = destFile.length(),
                            type = pending.type,
                            repoId = pending.repoId,
                            isDownloaded = true,
                            isVision = pending.isVision
                        )
                        db.modelDao().insertModel(entity)
                        DebugLog.log("DownloadService: Saved $filename to DB as ${pending.type}")
                    } catch (e: Exception) {
                        DebugLog.log("DownloadService: Failed to save to DB - ${e.message}")
                    }
                    PendingDownloadHolder.removePending(filename)
                }
            }
            
            activeDownloads.remove(filename)
            if (activeDownloads.isEmpty()) {
                updateNotification("Downloads complete", 100)
                delay(2000)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        
        activeDownloads[filename] = job
    }
    
    private fun cancelDownloadInternal(filename: String) {
        activeDownloads[filename]?.cancel()
        activeDownloads.remove(filename)
        Downloader.cancelDownload(filename)
        DownloadProgressHolder.updateProgress(filename, -1f)
        
        if (activeDownloads.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    
    private fun updateNotification(text: String, progress: Int) {
        notificationTaskId?.let {
            UnifiedNotificationManager.updateProgress(it, progress / 100f, text)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
    }
}

