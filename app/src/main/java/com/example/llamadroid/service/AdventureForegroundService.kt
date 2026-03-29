package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.llamadroid.util.DebugLog

/**
 * Foreground service for Adventure generation.
 * Keeps the LLM generation running when the app is backgrounded or screen is locked.
 */
class AdventureForegroundService : Service() {
    
    companion object {
        private const val TAG = "AdventureForegroundService"
        
        const val ACTION_START = "start_adventure"
        const val ACTION_STOP = "stop_adventure"
        const val ACTION_UPDATE_STATUS = "update_status"
        
        const val EXTRA_STATUS = "status"
        
        @Volatile
        private var isRunning = false
        
        /**
         * Start the foreground service for adventure generation.
         */
        fun start(context: Context, status: String = "Adventure in progress...") {
            if (isRunning) {
                updateStatus(context, status)
                return
            }
            
            val intent = Intent(context, AdventureForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, status)
            }
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Failed to start foreground service: ${e.message}")
            }
        }
        
        /**
         * Stop the foreground service.
         */
        fun stop(context: Context) {
            if (!isRunning) return
            
            val intent = Intent(context, AdventureForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
        
        /**
         * Update the notification status text.
         */
        fun updateStatus(context: Context, status: String) {
            if (!isRunning) return
            
            val intent = Intent(context, AdventureForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status)
            }
            context.startService(intent)
        }
        
        /**
         * Check if the service is currently running.
         */
        fun isServiceRunning(): Boolean = isRunning
    }
    
    private var notificationTaskId: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    override fun onCreate() {
        super.onCreate()
        DebugLog.log("[$TAG] Service created")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Adventure in progress..."
                startForegroundMode(status)
            }
            ACTION_STOP -> {
                stopForegroundMode()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Generating story..."
                updateNotificationStatus(status)
            }
        }
        return START_STICKY
    }
    
    private fun startForegroundMode(status: String) {
        if (isRunning) {
            updateNotificationStatus(status)
            return
        }
        
        isRunning = true
        
        // Acquire wake lock
        acquireWakeLock()
        
        // Start foreground with notification
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.AGENT, // Reuse agent type for now
            "⚔️ Adventure"
        )
        notificationTaskId = taskId
        
        try {
            startForeground(taskId, notification)
            DebugLog.log("[$TAG] Foreground service started with notification ID: $taskId")
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to start foreground: ${e.message}")
            isRunning = false
        }
        
        updateNotificationStatus(status)
    }
    
    private fun stopForegroundMode() {
        DebugLog.log("[$TAG] Stopping foreground service")
        
        isRunning = false
        releaseWakeLock()
        
        notificationTaskId?.let { 
            UnifiedNotificationManager.dismissTask(it) 
        }
        notificationTaskId = null
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun updateNotificationStatus(status: String) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, 0.5f, status)
        }
    }
    
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AI-Doomsday:AdventureForegroundService"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30 * 60 * 1000L) // 30 min max
                DebugLog.log("[$TAG] WakeLock acquired")
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to acquire WakeLock: ${e.message}")
        }
    }
    
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLog.log("[$TAG] WakeLock released")
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        releaseWakeLock()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        DebugLog.log("[$TAG] Service destroyed")
    }
}
