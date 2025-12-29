package com.example.llamadroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.llamadroid.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Unified notification manager for all AI tasks.
 * Shows grouped notifications with progress tracking.
 */
object UnifiedNotificationManager {
    
    private const val CHANNEL_ID = "doomsday_ai_tasks"
    private const val CHANNEL_NAME = "AI Tasks"
    private const val COMPLETION_CHANNEL_ID = "doomsday_completion"
    private const val COMPLETION_CHANNEL_NAME = "Task Completions"
    private const val GROUP_KEY = "com.example.llamadroid.AI_TASKS"
    private const val SUMMARY_ID = 0
    private const val COMPLETION_ID = 99
    
    /**
     * Represents a running task
     */
    data class TaskInfo(
        val id: Int,
        val type: TaskType,
        val title: String,
        val progress: Float = 0f,  // 0.0 to 1.0
        val progressText: String = "",
        val isComplete: Boolean = false,
        val isError: Boolean = false,
        val errorMessage: String? = null
    )
    
    enum class TaskType(val emoji: String, val label: String) {
        IMAGE_GEN("🎨", "Image Generation"),
        VIDEO_UPSCALE("🎬", "Video Upscaling"),
        TRANSCRIPTION("🎤", "Transcription"),
        LLM_CHAT("💬", "Chat"),
        DOWNLOAD("📥", "Download"),
        PDF_SUMMARY("📄", "PDF Summary"),
        FILE_SERVER("📂", "File Server"),
        MODEL_SHARE("🔁", "Model Share"),
        LLAMA_SERVER("🦙", "LLM Server"),
        ZIM_SHARE("📚", "ZIM File Share")
    }
    
    // Active tasks
    private val _activeTasks = ConcurrentHashMap<Int, TaskInfo>()
    private val _activeTasksFlow = MutableStateFlow<List<TaskInfo>>(emptyList())
    val activeTasks = _activeTasksFlow.asStateFlow()
    
    // Notification IDs
    private var nextId = 100
    
    private lateinit var appContext: Context
    private var notificationManager: NotificationManager? = null
    
    fun init(context: Context) {
        appContext = context.applicationContext
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        
        // Clean up any stale notifications from previous app sessions
        // This fixes the bug where multiple notifications appear after force-close
        cleanupStaleNotifications()
    }
    
    /**
     * Clean up stale notifications from previous sessions.
     * Called on init to prevent duplicate notification ghosts.
     */
    private fun cleanupStaleNotifications() {
        try {
            // Clear any existing progress notifications from our channel
            // Only keep active ones that match current _activeTasks
            val nm = notificationManager ?: return
            
            // Cancel summary and all task notifications from previous sessions
            nm.activeNotifications.forEach { notification ->
                // Only cancel our app's notifications (IDs >= 100 are task IDs, SUMMARY_ID is 0)
                if (notification.id >= 100 || notification.id == SUMMARY_ID) {
                    // Check if this notification is from a currently active task
                    if (!_activeTasks.containsKey(notification.id)) {
                        nm.cancel(notification.id)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }
    
    /**
     * Cancel all active notifications.
     * Call this when the app is completely shutting down.
     */
    fun cancelAllNotifications() {
        try {
            val nm = notificationManager ?: return
            
            // Cancel all task notifications
            _activeTasks.keys.forEach { taskId ->
                nm.cancel(taskId)
            }
            
            // Clear the active tasks
            _activeTasks.clear()
            updateTasksFlow()
            
            // Cancel summary
            nm.cancel(SUMMARY_ID)
            
            // Cancel completion notification  
            nm.cancel(COMPLETION_ID)
        } catch (e: Exception) {
            // Ignore errors
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Progress channel (silent, ongoing updates)
            val progressChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "AI task progress notifications"
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(progressChannel)
            
            // Completion channel (with sound and vibration)
            val completionChannel = NotificationChannel(
                COMPLETION_CHANNEL_ID,
                COMPLETION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when AI tasks complete"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 200)  // Short double vibrate
                setShowBadge(true)
            }
            notificationManager?.createNotificationChannel(completionChannel)
        }
    }
    
    /**
     * Start a new task and show notification
     */
    fun startTask(type: TaskType, title: String): Int {
        val id = nextId++
        val task = TaskInfo(
            id = id,
            type = type,
            title = title,
            progress = 0f,
            progressText = "Starting..."
        )
        _activeTasks[id] = task
        updateTasksFlow()
        showTaskNotification(task)
        updateSummaryNotification()
        return id
    }
    
    /**
     * Start a new task for a foreground service.
     * Returns Pair(taskId, notification) for use with startForeground().
     */
    fun startTaskForForeground(type: TaskType, title: String): Pair<Int, android.app.Notification> {
        val id = startTask(type, title)
        val notification = getForegroundNotification(id) 
            ?: createBasicForegroundNotification(title)
        return Pair(id, notification)
    }
    
    /**
     * Update task progress
     */
    fun updateProgress(taskId: Int, progress: Float, progressText: String) {
        _activeTasks[taskId]?.let { task ->
            val updated = task.copy(
                progress = progress,
                progressText = progressText
            )
            _activeTasks[taskId] = updated
            updateTasksFlow()
            showTaskNotification(updated)
        }
    }
    
    /**
     * Complete a task successfully
     */
    fun completeTask(taskId: Int, resultText: String = "Complete") {
        _activeTasks[taskId]?.let { task ->
            val updated = task.copy(
                progress = 1f,
                progressText = resultText,
                isComplete = true
            )
            _activeTasks[taskId] = updated
            updateTasksFlow()
            
            // Show completion notification with sound/vibration
            showCompletionNotification(updated)
            
            // Remove from active tasks immediately
            _activeTasks.remove(taskId)
            updateTasksFlow()
            
            // Cancel the progress notification
            try {
                NotificationManagerCompat.from(appContext).cancel(taskId)
            } catch (e: Exception) {}
            
            updateSummaryNotification()
            
            // Auto-dismiss completion notification after 10 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    NotificationManagerCompat.from(appContext).cancel(COMPLETION_ID)
                } catch (e: Exception) {}
            }, 10000)
        }
    }
    
    /**
     * Show completion notification with sound and vibration
     */
    private fun showCompletionNotification(task: TaskInfo) {
        if (!::appContext.isInitialized) return
        
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, COMPLETION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(appContext, COMPLETION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("✅ ${task.type.emoji} ${task.title}")
            .setContentText(task.progressText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        
        try {
            NotificationManagerCompat.from(appContext).notify(COMPLETION_ID, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
    
    /**
     * Fail a task with error
     */
    fun failTask(taskId: Int, errorMessage: String) {
        _activeTasks[taskId]?.let { task ->
            val updated = task.copy(
                progressText = "Error: $errorMessage",
                isError = true,
                errorMessage = errorMessage
            )
            _activeTasks[taskId] = updated
            updateTasksFlow()
            showTaskNotification(updated)
            updateSummaryNotification()
        }
    }
    
    /**
     * Dismiss a task notification
     */
    fun dismissTask(taskId: Int) {
        _activeTasks.remove(taskId)
        updateTasksFlow()
        try {
            NotificationManagerCompat.from(appContext).cancel(taskId)
        } catch (e: Exception) {
            // Ignore
        }
        updateSummaryNotification()
    }
    
    private fun updateTasksFlow() {
        _activeTasksFlow.value = _activeTasks.values.toList().sortedByDescending { it.id }
    }
    
    private fun showTaskNotification(task: TaskInfo) {
        if (!::appContext.isInitialized) return
        
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext, task.id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val icon = when (task.type) {
            TaskType.IMAGE_GEN -> android.R.drawable.ic_menu_gallery
            TaskType.VIDEO_UPSCALE -> android.R.drawable.ic_media_play
            TaskType.TRANSCRIPTION -> android.R.drawable.ic_btn_speak_now
            TaskType.LLM_CHAT -> android.R.drawable.ic_menu_send
            TaskType.DOWNLOAD -> android.R.drawable.stat_sys_download
            TaskType.PDF_SUMMARY -> android.R.drawable.ic_menu_agenda
            TaskType.FILE_SERVER -> android.R.drawable.ic_menu_share
            TaskType.MODEL_SHARE -> android.R.drawable.ic_menu_upload
            TaskType.LLAMA_SERVER -> android.R.drawable.ic_menu_manage
            TaskType.ZIM_SHARE -> android.R.drawable.ic_menu_share
        }
        
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle("${task.type.emoji} ${task.title}")
            .setContentText(task.progressText)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setOngoing(!task.isComplete && !task.isError)
            .setAutoCancel(task.isComplete || task.isError)
        
        // Add progress bar for incomplete tasks
        if (!task.isComplete && !task.isError) {
            builder.setProgress(100, (task.progress * 100).toInt(), false)
        }
        
        // Set appropriate priority/icon for completion state
        when {
            task.isComplete -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                builder.setProgress(0, 0, false)
            }
            task.isError -> {
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                builder.setProgress(0, 0, false)
            }
        }
        
        try {
            NotificationManagerCompat.from(appContext).notify(task.id, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
    
    private fun updateSummaryNotification() {
        if (!::appContext.isInitialized) return
        
        val tasks = _activeTasks.values.toList()
        
        if (tasks.isEmpty()) {
            try {
                NotificationManagerCompat.from(appContext).cancel(SUMMARY_ID)
            } catch (e: Exception) {
                // Ignore
            }
            return
        }
        
        val runningCount = tasks.count { !it.isComplete && !it.isError }
        val completedCount = tasks.count { it.isComplete }
        val errorCount = tasks.count { it.isError }
        
        val summaryText = buildString {
            if (runningCount > 0) append("$runningCount running")
            if (completedCount > 0) {
                if (isNotEmpty()) append(" • ")
                append("$completedCount done")
            }
            if (errorCount > 0) {
                if (isNotEmpty()) append(" • ")
                append("$errorCount failed")
            }
        }
        
        // Build expanded style
        val inbox = NotificationCompat.InboxStyle()
            .setBigContentTitle("Doomsday AI Toolbox")
            .setSummaryText(summaryText)
        
        tasks.take(5).forEach { task ->
            val statusIcon = when {
                task.isComplete -> "✅"
                task.isError -> "❌"
                else -> "⏳"
            }
            val progressPercent = (task.progress * 100).toInt()
            inbox.addLine("$statusIcon ${task.type.emoji} ${task.title}: ${task.progressText}")
        }
        
        if (tasks.size > 5) {
            inbox.addLine("... and ${tasks.size - 5} more")
        }
        
        val intent = Intent(appContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext, SUMMARY_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Doomsday AI Toolbox")
            .setContentText(summaryText)
            .setStyle(inbox)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .setGroupSummary(true)
            .setAutoCancel(false)
            .setOngoing(runningCount > 0)
        
        try {
            NotificationManagerCompat.from(appContext).notify(SUMMARY_ID, builder.build())
        } catch (e: SecurityException) {
            // Notification permission not granted
        }
    }
    
    /**
     * Get a notification builder for foreground services
     */
    fun getForegroundNotification(taskId: Int): android.app.Notification? {
        val task = _activeTasks[taskId] ?: return null
        
        val intent = Intent(appContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext, taskId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("${task.type.emoji} ${task.title}")
            .setContentText(task.progressText)
            .setProgress(100, (task.progress * 100).toInt(), false)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    /**
     * Create a basic foreground notification for services that need to start immediately
     */
    fun createBasicForegroundNotification(title: String): android.app.Notification {
        if (!::appContext.isInitialized) {
            throw IllegalStateException("UnifiedNotificationManager not initialized")
        }
        
        val intent = Intent(appContext, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            appContext, 999, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(title)
            .setContentText("Initializing...")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
