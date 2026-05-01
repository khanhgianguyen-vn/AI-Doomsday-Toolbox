package com.example.llamadroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.llamadroid.R
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.tama.adventure.AdventureService
import com.example.llamadroid.tama.adventure.DungeonType
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AdventureGenerationState(
    val petId: String,
    val dungeonTypeName: String,
    val status: String,
    val progress: Float,
    val isBuildingWorld: Boolean
)

class AdventureForegroundService : Service() {

    companion object {
        private const val TAG = "AdventureForegroundService"

        const val ACTION_BUILD_WORLD = "build_adventure_world"
        const val ACTION_SUBMIT_CHOICE = "submit_adventure_choice"
        const val ACTION_STOP = "stop_adventure"
        const val ACTION_UPDATE_STATUS = "update_status"

        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_PET_ID = "pet_id"
        const val EXTRA_DUNGEON_TYPE = "dungeon_type"
        const val EXTRA_PLAYER_CHOICE = "player_choice"

        @Volatile
        private var isRunning = false

        private val _generationState = MutableStateFlow<AdventureGenerationState?>(null)
        val generationState: StateFlow<AdventureGenerationState?> = _generationState.asStateFlow()

        fun startWorldBuild(context: Context, petId: String, dungeonTypeName: String) {
            val intent = Intent(context, AdventureForegroundService::class.java).apply {
                action = ACTION_BUILD_WORLD
                putExtra(EXTRA_PET_ID, petId)
                putExtra(EXTRA_DUNGEON_TYPE, dungeonTypeName)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Failed to start world build service: ${e.message}")
            }
        }

        fun submitChoice(
            context: Context,
            petId: String,
            dungeonTypeName: String,
            playerChoice: String
        ) {
            val intent = Intent(context, AdventureForegroundService::class.java).apply {
                action = ACTION_SUBMIT_CHOICE
                putExtra(EXTRA_PET_ID, petId)
                putExtra(EXTRA_DUNGEON_TYPE, dungeonTypeName)
                putExtra(EXTRA_PLAYER_CHOICE, playerChoice)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                DebugLog.log("[$TAG] Failed to start choice service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            if (!isRunning) return
            context.startService(
                Intent(context, AdventureForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun updateStatus(context: Context, status: String, progress: Float = 0f) {
            if (!isRunning) return
            context.startService(
                Intent(context, AdventureForegroundService::class.java).apply {
                    action = ACTION_UPDATE_STATUS
                    putExtra(EXTRA_STATUS, status)
                    putExtra(EXTRA_PROGRESS, progress)
                }
            )
        }

        fun isServiceRunning(): Boolean = isRunning
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var processingJob: Job? = null
    private var notificationTaskId: Int? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BUILD_WORLD -> {
                if (processingJob?.isActive == true) return START_STICKY
                val petId = intent.getStringExtra(EXTRA_PET_ID)
                val dungeonTypeName = intent.getStringExtra(EXTRA_DUNGEON_TYPE)
                if (petId.isNullOrBlank() || dungeonTypeName.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                processingJob = serviceScope.launch {
                    runWorldBuild(petId, dungeonTypeName)
                }
            }

            ACTION_SUBMIT_CHOICE -> {
                if (processingJob?.isActive == true) return START_STICKY
                val petId = intent.getStringExtra(EXTRA_PET_ID)
                val dungeonTypeName = intent.getStringExtra(EXTRA_DUNGEON_TYPE)
                val playerChoice = intent.getStringExtra(EXTRA_PLAYER_CHOICE)
                if (petId.isNullOrBlank() || dungeonTypeName.isNullOrBlank() || playerChoice.isNullOrBlank()) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                processingJob = serviceScope.launch {
                    runChoiceGeneration(petId, dungeonTypeName, playerChoice)
                }
            }

            ACTION_UPDATE_STATUS -> {
                val status = intent.getStringExtra(EXTRA_STATUS) ?: getString(R.string.adventure_status_generating)
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                updateNotificationStatus(status, progress)
            }

            ACTION_STOP -> stopForegroundMode()
        }
        return START_STICKY
    }

    private suspend fun runWorldBuild(petId: String, dungeonTypeName: String) {
        val dungeonType = runCatching { DungeonType.valueOf(dungeonTypeName) }.getOrDefault(DungeonType.CHAOS_REALM)
        startForegroundMode(getString(R.string.adventure_status_building_world))
        publishState(
            AdventureGenerationState(
                petId = petId,
                dungeonTypeName = dungeonTypeName,
                status = getString(R.string.adventure_status_building_world),
                progress = 0.05f,
                isBuildingWorld = true
            )
        )
        val database = TamaDatabase.getInstance(applicationContext)
        val service = AdventureService(database, SettingsRepository(applicationContext), applicationContext)
        val result = runCatching {
            service.initializeOrContinue(
                petId = petId,
                dungeonType = dungeonType,
                onProgress = { progress, status ->
                    publishState(
                        AdventureGenerationState(
                            petId = petId,
                            dungeonTypeName = dungeonTypeName,
                            status = status,
                            progress = progress,
                            isBuildingWorld = true
                        )
                    )
                    updateNotificationStatus(status, progress)
                }
            )
        }.getOrElse { Result.failure(it) }

        if (result.isSuccess) {
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(it, getString(R.string.adventure_world_ready_notification_body))
            }
            UnifiedNotificationManager.showAdventureReadyNotification(
                title = getString(R.string.adventure_world_ready_notification_title),
                body = getString(R.string.adventure_world_ready_notification_body),
                dungeonTypeName = dungeonTypeName
            )
        } else {
            notificationTaskId?.let {
                UnifiedNotificationManager.failTask(
                    it,
                    result.exceptionOrNull()?.message ?: getString(R.string.error_generic)
                )
            }
        }
        finishRun()
    }

    private suspend fun runChoiceGeneration(petId: String, dungeonTypeName: String, playerChoice: String) {
        val dungeonType = runCatching { DungeonType.valueOf(dungeonTypeName) }.getOrDefault(DungeonType.CHAOS_REALM)
        startForegroundMode(getString(R.string.adventure_status_processing_choice))
        publishState(
            AdventureGenerationState(
                petId = petId,
                dungeonTypeName = dungeonTypeName,
                status = getString(R.string.adventure_status_processing_choice),
                progress = 0.05f,
                isBuildingWorld = false
            )
        )
        val database = TamaDatabase.getInstance(applicationContext)
        val service = AdventureService(database, SettingsRepository(applicationContext), applicationContext)
        val result = runCatching {
            service.submitChoice(
                petId = petId,
                dungeonType = dungeonType,
                playerChoice = playerChoice,
                onProgress = { progress, status ->
                    publishState(
                        AdventureGenerationState(
                            petId = petId,
                            dungeonTypeName = dungeonTypeName,
                            status = status,
                            progress = progress,
                            isBuildingWorld = false
                        )
                    )
                    updateNotificationStatus(status, progress)
                }
            )
        }.getOrElse { Result.failure(it) }

        if (result.isSuccess) {
            notificationTaskId?.let {
                UnifiedNotificationManager.completeTask(it, getString(R.string.adventure_next_scene_notification_body))
            }
            UnifiedNotificationManager.showAdventureReadyNotification(
                title = getString(R.string.adventure_next_scene_notification_title),
                body = getString(R.string.adventure_next_scene_notification_body),
                dungeonTypeName = dungeonTypeName
            )
        } else {
            notificationTaskId?.let {
                UnifiedNotificationManager.failTask(
                    it,
                    result.exceptionOrNull()?.message ?: getString(R.string.error_generic)
                )
            }
        }
        finishRun()
    }

    private fun startForegroundMode(initialStatus: String) {
        if (isRunning) {
            updateNotificationStatus(initialStatus, 0f)
            return
        }
        isRunning = true
        acquireWakeLock()
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.ADVENTURE,
            getString(R.string.adventure_service_title)
        )
        notificationTaskId = taskId
        try {
            startForeground(taskId, notification)
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to start foreground: ${e.message}")
            isRunning = false
        }
        updateNotificationStatus(initialStatus, 0f)
    }

    private fun updateNotificationStatus(status: String, progress: Float) {
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.updateProgress(taskId, progress.coerceIn(0f, 1f), status)
        }
    }

    private fun publishState(state: AdventureGenerationState?) {
        _generationState.value = state
    }

    private fun finishRun() {
        publishState(null)
        processingJob = null
        stopForegroundMode()
    }

    private fun stopForegroundMode() {
        isRunning = false
        releaseWakeLock()
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
                wakeLock?.acquire(30 * 60 * 1000L)
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        processingJob?.cancel()
        serviceScope.cancel()
        publishState(null)
        isRunning = false
        releaseWakeLock()
    }
}
