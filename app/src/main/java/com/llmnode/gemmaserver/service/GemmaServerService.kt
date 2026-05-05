package com.llmnode.gemmaserver.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.llmnode.gemmaserver.GemmaApp
import com.llmnode.gemmaserver.MainActivity
import com.llmnode.gemmaserver.engine.LiteRtInferenceEngine
import com.llmnode.gemmaserver.server.ApiServer
import com.llmnode.gemmaserver.util.ServerLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class GemmaServerService : Service() {

    companion object {
        private const val TAG = "GemmaServerService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.llmnode.gemmaserver.START"
        const val ACTION_STOP = "com.llmnode.gemmaserver.STOP"
        const val EXTRA_MODEL_PATH = "model_path"

        // Singleton references for UI binding
        private val _serverState = MutableStateFlow(ServerState())
        val serverState: StateFlow<ServerState> = _serverState

        var apiServer: ApiServer? = null
            private set

        var inferenceEngine: LiteRtInferenceEngine? = null
            private set

        private var instance: GemmaServerService? = null

        fun stopServer(context: Context) {
            context.stopService(Intent(context, GemmaServerService::class.java))
        }
    }

    data class ServerState(
        val isRunning: Boolean = false,
        val isModelLoading: Boolean = false,
        val isModelLoaded: Boolean = false,
        val loadProgress: String = "",
        val error: String? = null
    )

    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var server: ApiServer? = null
    private var engine: LiteRtInferenceEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ServerLogger.init(this)
        ServerLogger.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                val modelPath = intent?.getStringExtra(EXTRA_MODEL_PATH) ?: ""
                startForeground(NOTIFICATION_ID, buildNotification("Starting Gemma server..."))
                acquireWakeLock()
                scope.launch { startModelAndServer(modelPath) }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        stopApiServer()
        closeEngine()
        releaseWakeLock()
        _serverState.value = ServerState()
        instance = null
        apiServer = null
        inferenceEngine = null
        ServerLogger.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private suspend fun startModelAndServer(modelPath: String) {
        _serverState.value = ServerState(
            isRunning = true,
            isModelLoading = true,
            loadProgress = "Checking model file..."
        )

        // Validate model path
        if (modelPath.isEmpty()) {
            _serverState.value = _serverState.value.copy(
                isModelLoading = false,
                error = "No model file selected. Please select a .litertlm model file."
            )
            return
        }

        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) {
            _serverState.value = _serverState.value.copy(
                isModelLoading = false,
                error = "Model file not found at: $modelPath"
            )
            return
        }

        // Initialize LiteRT-LM engine
        _serverState.value = _serverState.value.copy(
            loadProgress = "Loading model with LiteRT-LM..."
        )

        try {
            engine = LiteRtInferenceEngine(this)
            engine!!.initialize(modelPath)
            inferenceEngine = engine

            ServerLogger.i(TAG, "LiteRT-LM engine initialized successfully")
        } catch (e: Exception) {
            ServerLogger.e(TAG, "Failed to initialize LiteRT-LM: ${e.message}")
            _serverState.value = _serverState.value.copy(
                isModelLoading = false,
                error = "Failed to load model: ${e.message}"
            )
            return
        }

        // Start NanoHTTPD API server
        startApiServer()

        _serverState.value = ServerState(
            isRunning = true,
            isModelLoading = false,
            isModelLoaded = true,
            loadProgress = "Model loaded & server running"
        )

        updateNotification("Gemma server running on :8080")
        ServerLogger.i(TAG, "Server fully started with LiteRT-LM")
    }

    private fun startApiServer() {
        server = ApiServer(this, port = 8080)
        server?.modelLoaded = true
        server?.start()
        apiServer = server
        ServerLogger.i(TAG, "API server started on :8080")
    }

    private fun stopApiServer() {
        try {
            server?.stop()
            server = null
            ServerLogger.i(TAG, "API server stopped")
        } catch (e: Exception) {
            ServerLogger.e(TAG, "Error stopping API server: ${e.message}")
        }
    }

    private fun closeEngine() {
        try {
            engine?.close()
            engine = null
            ServerLogger.i(TAG, "LiteRT-LM engine closed")
        } catch (e: Exception) {
            ServerLogger.e(TAG, "Error closing engine: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "gemmaserver:serverlock"
        ).apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GemmaServerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, GemmaApp.CHANNEL_ID)
            .setContentTitle("Gemma Server")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .addAction(Notification.Action.Builder(
                null, "Stop", stopIntent
            ).build())
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
