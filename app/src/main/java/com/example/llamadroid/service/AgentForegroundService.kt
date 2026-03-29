package com.example.llamadroid.service

import android.app.Service
import android.os.Binder
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.repository.OllamaRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Foreground service for AI Agent operations.
 * Keeps the agent running when the app is backgrounded or screen is locked.
 * 
 * Uses a persistent notification to satisfy Android's requirements for
 * long-running background work.
 */
class AgentForegroundService : Service() {
    
    companion object {
        private const val TAG = "AgentForegroundService"
        
        const val ACTION_START_AGENT = "start_agent"
        const val ACTION_STOP_AGENT = "stop_agent"
        const val ACTION_STOP_ALL_RUNTIME = "stop_all_runtime"
        const val ACTION_UPDATE_STATUS = "update_status"
        const val ACTION_RESUME_RUNTIME = "resume_runtime"
        
        const val EXTRA_STATUS = "status"
        const val EXTRA_FOREGROUND_TASK_ID = "foreground_task_id"
        
        @Volatile
        private var isRunning = false
        @Volatile
        private var instance: AgentForegroundService? = null

        private val runtimeRetainCount = AtomicInteger(0)
        private val resumeTriggered = AtomicBoolean(false)
        private var runtimeAgentService: AgentService? = null
        private var runtimeOllamaService: OllamaService? = null
        private var runtimeSettingsRepository: SettingsRepository? = null
        private var runtimeOllamaManager: OllamaRuntimeManager? = null

        private fun ensureRuntime(context: Context) {
            val appContext = context.applicationContext
            if (runtimeAgentService == null) {
                runtimeAgentService = AgentService(appContext, isRuntimeOwner = true)
            }
            if (runtimeOllamaService == null) {
                runtimeOllamaService = OllamaService(appContext).also { it.initFromSettings() }
            }
            if (runtimeSettingsRepository == null) {
                runtimeSettingsRepository = SettingsRepository(appContext)
            }
            if (runtimeOllamaManager == null) {
                val database = AppDatabase.getDatabase(appContext)
                runtimeOllamaManager = OllamaRuntimeManager(
                    appContext = appContext,
                    repository = OllamaRepository(database.ollamaServerDao()),
                    runtimeScope = AgentService.agentScope,
                    sshService = SSHService(appContext)
                )
            }
        }

        fun getAgentService(context: Context): AgentService {
            ensureRuntime(context)
            return runtimeAgentService!!
        }

        fun getOllamaService(context: Context): OllamaService {
            ensureRuntime(context)
            return runtimeOllamaService!!
        }

        fun getSettingsRepository(context: Context): SettingsRepository {
            ensureRuntime(context)
            return runtimeSettingsRepository!!
        }

        fun getOllamaRuntimeManager(context: Context): OllamaRuntimeManager {
            ensureRuntime(context)
            return runtimeOllamaManager!!
        }
        
        /**
         * Start the foreground service for agent work.
         * Call this when the agent starts processing a task.
         */
        fun start(
            context: Context,
            status: String = "AI Agent running...",
            foregroundTaskId: Int? = null
        ) {
            ensureRuntime(context)
            if (isRunning) {
                // Already running, just update status
                updateStatus(context, status)
                return
            }
            
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_START_AGENT
                putExtra(EXTRA_STATUS, status)
                foregroundTaskId?.let { putExtra(EXTRA_FOREGROUND_TASK_ID, it) }
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
         * Call this when the agent becomes idle or user cancels.
         */
        fun stop(context: Context) {
            if (!isRunning) return
            if (runtimeRetainCount.get() > 0 || AgentService.isLoading.value) return
            
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP_AGENT
            }
            context.startService(intent)
        }

        fun stopAllRuntime(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_STOP_ALL_RUNTIME
            }
            context.startService(intent)
        }

        fun retainRuntime(context: Context, status: String = "AI task running…") {
            runtimeRetainCount.incrementAndGet()
            start(context, status)
        }

        fun releaseRuntime(context: Context) {
            val remaining = runtimeRetainCount.decrementAndGet().coerceAtLeast(0)
            runtimeRetainCount.set(remaining)
            if (remaining == 0 && !AgentService.isLoading.value) {
                stop(context)
            }
        }

        fun requestResume(context: Context) {
            ensureRuntime(context)
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_RESUME_RUNTIME
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        /**
         * Update the notification status text.
         */
        fun updateStatus(context: Context, status: String) {
            if (!isRunning) return
            
            val intent = Intent(context, AgentForegroundService::class.java).apply {
                action = ACTION_UPDATE_STATUS
                putExtra(EXTRA_STATUS, status)
            }
            context.startService(intent)
        }
        
        /**
         * Check if the service is currently running.
         */
        fun isServiceRunning(): Boolean = isRunning

        fun activeRuntimeCount(): Int = runtimeRetainCount.get()
    }

    inner class LocalBinder : Binder() {
        fun getService(): AgentForegroundService = this@AgentForegroundService
    }
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationTaskId: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureRuntime(applicationContext)
        DebugLog.log("[$TAG] Service created")
        scheduleResumeIfNeeded()
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AGENT -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "AI Agent running..."
                val foregroundTaskId = intent.getIntExtra(EXTRA_FOREGROUND_TASK_ID, -1)
                    .takeIf { it >= 0 }
                startAgentForeground(status, foregroundTaskId)
            }
            ACTION_STOP_AGENT -> {
                stopAgentForeground()
            }
            ACTION_STOP_ALL_RUNTIME -> {
                runtimeOllamaManager?.cancelAll()
                AgentService.stopAllJobs()
                runtimeRetainCount.set(0)
                stopAgentForeground()
            }
            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: "Working..."
                updateNotificationStatus(status)
            }
            ACTION_RESUME_RUNTIME -> scheduleResumeIfNeeded(force = true)
        }
        return START_STICKY  // Restart service if killed
    }

    private fun scheduleResumeIfNeeded(force: Boolean = false) {
        if (!force && !resumeTriggered.compareAndSet(false, true)) return
        serviceScope.launch {
            runCatching {
                resumePersistedJobs()
            }.onFailure {
                DebugLog.log("[$TAG] Runtime resume failed: ${it.message}")
            }
        }
    }

    private suspend fun resumePersistedJobs() {
        val activeJobs = AiRuntimeJobStore.getActiveJobs(applicationContext)
        if (activeJobs.isEmpty()) return

        activeJobs.forEach { job ->
            AiRuntimeJobStore.markState(
                applicationContext,
                jobId = job.jobId,
                status = AiRuntimeJobStore.STATUS_RECOVERING,
                checkpointJson = job.checkpointJson,
                progressText = job.progressText ?: "recovering"
            )
        }

        runtimeOllamaManager?.resumePersistedJobs()

        val agentJob = activeJobs.firstOrNull { it.type == AiRuntimeJobStore.TYPE_AGENT_CHAT }
        val agentService = runtimeAgentService ?: return
        val ollamaService = runtimeOllamaService ?: return
        val settingsRepo = runtimeSettingsRepository ?: return

        if (agentJob != null && !AgentService.isLoading.value) {
            retainRuntime(applicationContext, agentJob.progressText ?: "Recovering agent job…")
            agentService.restorePersistentState(agentJob.payloadJson)
            AgentService.addDebugLog("♻️ Recovering persisted agent job ${agentJob.jobId.take(8)}")
            AgentService.sendMessage(
                applicationContext,
                ollamaService,
                settingsRepo,
                agentService,
                recoveryInstruction = "Recovered after service restart. Continue from the restored conversation state.",
                recoveryMode = true,
                queueBehindActiveJob = false
            )
        }
    }
    
    private fun startAgentForeground(status: String, existingTaskId: Int? = null) {
        if (isRunning) {
            updateNotificationStatus(status)
            return
        }
        
        isRunning = true
        
        // Acquire wake lock to keep CPU running
        acquireWakeLock()
        
        // Start foreground with notification
        val (taskId, notification) = if (existingTaskId != null) {
            val existingNotification = UnifiedNotificationManager.getForegroundNotification(existingTaskId)
            if (existingNotification != null) {
                existingTaskId to existingNotification
            } else {
                UnifiedNotificationManager.startTaskForForeground(
                    UnifiedNotificationManager.TaskType.AGENT,
                    "AI Agent"
                )
            }
        } else {
            UnifiedNotificationManager.startTaskForForeground(
                UnifiedNotificationManager.TaskType.AGENT,
                "AI Agent"
            )
        }
        notificationTaskId = taskId
        
        try {
            startForeground(taskId, notification)
            DebugLog.log("[$TAG] Foreground service started with notification ID: $taskId")
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to start foreground: ${e.message}")
            isRunning = false
        }
        
        // Update with initial status
        updateNotificationStatus(status)
    }
    
    private fun stopAgentForeground() {
        DebugLog.log("[$TAG] Stopping foreground service")
        
        isRunning = false
        resumeTriggered.set(false)
        
        // Release wake lock
        releaseWakeLock()
        
        // Dismiss notification
        notificationTaskId?.let { 
            UnifiedNotificationManager.dismissTask(it) 
        }
        notificationTaskId = null
        
        // Stop foreground and service
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
            // Acquire CPU WakeLock
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AI-Doomsday:AgentForegroundService"
                )
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(60 * 60 * 1000L)  // 1 hour max
                DebugLog.log("[$TAG] WakeLock acquired")
            }
            
            // Acquire WifiLock to keep network alive when screen is off
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AI-Doomsday:AgentWifiLock")
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                DebugLog.log("[$TAG] WifiLock acquired")
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to acquire locks: ${e.message}")
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
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                DebugLog.log("[$TAG] WifiLock released")
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        releaseWakeLock()
        notificationTaskId?.let { UnifiedNotificationManager.dismissTask(it) }
        serviceScope.cancel()
        DebugLog.log("[$TAG] Service destroyed")
    }
}
