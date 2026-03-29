package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.util.DebugLog
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties

data class SSHConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8022,
    val user: String = "root",
    val password: String = "",
    val privateKeyPath: String? = null
)

/**
 * SSH Service for connecting to Termux proot Debian SSH.
 * Uses SINGLETON pattern to persist connection across navigation.
 * 
 * Default proot Debian SSH setup:
 * - Port: 8022 (Termux SSHD inside proot)
 * - User: root
 * - Auth: password via `passwd`
 */
class SSHService(private val context: Context) {
    
    companion object {
        // Global CoroutineScope that persists across navigation
        private val persistentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        // Global connection state (persists across navigation)
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
        
        // Global output (persists across navigation)
        private val _output = MutableStateFlow("")
        val output: StateFlow<String> = _output.asStateFlow()
        
        // Global execution state
        private val _isExecuting = MutableStateFlow(false)
        val isExecuting: StateFlow<Boolean> = _isExecuting.asStateFlow()
        
        private val _currentCommand = MutableStateFlow("")
        val currentCommand: StateFlow<String> = _currentCommand.asStateFlow()

        // Tools running state (persists across navigation)
        private val _runningTools = MutableStateFlow<Set<String>>(emptySet())
        val runningTools: StateFlow<Set<String>> = _runningTools.asStateFlow()
        
        // Per-tool output tracking (persists across navigation)
        private val _toolOutputs = MutableStateFlow<Map<String, String>>(emptyMap())
        val toolOutputs: StateFlow<Map<String, String>> = _toolOutputs.asStateFlow()
        
        private val _toolExecuting = MutableStateFlow<Set<String>>(emptySet())
        val toolExecuting: StateFlow<Set<String>> = _toolExecuting.asStateFlow()

        fun setRunningTools(tools: Set<String>) {
            _runningTools.value = tools
        }
        
        fun appendToolOutput(toolId: String, text: String) {
            val current = _toolOutputs.value.toMutableMap()
            val existing = current[toolId] ?: ""
            val newOutput = existing + text
            // Limit per-tool output to 30KB
            current[toolId] = if (newOutput.length > 30_000) {
                "...[trimmed]...\n" + newOutput.takeLast(29_980)
            } else {
                newOutput
            }
            _toolOutputs.value = current
        }
        
        fun clearToolOutput(toolId: String) {
            val current = _toolOutputs.value.toMutableMap()
            current.remove(toolId)
            _toolOutputs.value = current
        }
        
        fun setToolExecuting(toolId: String, executing: Boolean) {
            val current = _toolExecuting.value.toMutableSet()
            if (executing) current.add(toolId) else current.remove(toolId)
            _toolExecuting.value = current
        }

        // Host settings (persistent)
        private val _config = MutableStateFlow(SSHConfig())
        val config: StateFlow<SSHConfig> = _config.asStateFlow()

        fun setConfig(host: String, port: Int, user: String, pass: String) {
            _config.value = SSHConfig(host, port, user, pass)
        }
        
        // Singleton session (survives navigation)
        private var session: Session? = null
        private val jsch = JSch()
        
        const val DEFAULT_PORT = 8022
        const val DEFAULT_HOST = "127.0.0.1"
        private const val SSH_SERVER_ALIVE_INTERVAL_MS = 15_000
        private const val SSH_SERVER_ALIVE_COUNT_MAX = 6
        
        // Limit output to prevent lag (50KB max)
        private const val MAX_OUTPUT_LENGTH = 50_000
        
        // Clear output
        fun clearOutput() {
            _output.value = ""
        }
        
        // Append to output with size limit
        fun appendOutput(text: String) {
            val newOutput = _output.value + text
            // Trim from start if too long
            _output.value = if (newOutput.length > MAX_OUTPUT_LENGTH) {
                "...[trimmed]...\n" + newOutput.takeLast(MAX_OUTPUT_LENGTH - 20)
            } else {
                newOutput
            }
        }
        
        // Check if actually connected (connection health check)
        fun checkConnection(): Boolean {
            val currentSession = session
            val connected = currentSession?.isConnected == true
            if (!connected && _isConnected.value) {
                // Server disconnected while we thought we were connected
                _isConnected.value = false
                DebugLog.log("SSH: Connection lost (detected)")
            }
            return connected
        }
        
        /**
         * Execute a command quietly and return output string (or null on error).
         * Use for background polling without any UI feedback.
         */
        suspend fun executeQuiet(command: String): String? = withContext(Dispatchers.IO) {
            val currentSession = session
            if (currentSession == null || !currentSession.isConnected) {
                return@withContext null
            }
            
            try {
                val channel = currentSession.openChannel("exec") as ChannelExec
                channel.setCommand(command)
                
                val outputStream = java.io.ByteArrayOutputStream()
                channel.inputStream = null
                channel.setOutputStream(outputStream)
                channel.setErrStream(outputStream)
                
                channel.connect(5000)  // Shorter timeout for polling
                
                while (!channel.isClosed) {
                    Thread.sleep(50)
                }
                
                val output = outputStream.toString()
                channel.disconnect()
                output
            } catch (e: Exception) {
                null
            }
        }

        private fun configureSession(target: Session) {
            target.setServerAliveInterval(SSH_SERVER_ALIVE_INTERVAL_MS)
            target.setServerAliveCountMax(SSH_SERVER_ALIVE_COUNT_MAX)
            target.timeout = 30_000
        }

        private fun isRecoverableSessionFailure(error: Throwable?): Boolean {
            val message = error?.message?.lowercase().orEmpty()
            return error is java.net.SocketException ||
                error is java.io.EOFException ||
                message.contains("software caused connection abort") ||
                message.contains("connection reset") ||
                message.contains("broken pipe") ||
                message.contains("socket closed") ||
                message.contains("session is down") ||
                message.contains("channel is not opened")
        }
    }
    
    private val settingsRepo = SettingsRepository(context)
    
    /**
     * Connect to SSH server.
     */
    suspend fun connect(config: SSHConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Disconnect existing session first
            session?.disconnect()
            
            DebugLog.log("SSH: Connecting to ${config.host}:${config.port}")
            
            // Configure session
            session = jsch.getSession(config.user, config.host, config.port).apply {
                // Set password or key
                if (config.privateKeyPath != null) {
                    jsch.addIdentity(config.privateKeyPath)
                } else {
                    setPassword(config.password)
                }
                
                // Disable strict host key checking (for localhost)
                val props = Properties()
                props["StrictHostKeyChecking"] = "no"
                setConfig(props)
            }
            session?.let { configureSession(it) }
            
            session?.connect()
            _isConnected.value = true
            _output.value = "Connected to ${config.host}:${config.port}\n"
            DebugLog.log("SSH: Connected successfully")
            
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("SSH: Connection failed - ${e.message}")
            _isConnected.value = false
            _output.value = "Connection failed: ${e.message}\n"
            Result.failure(e)
        }
    }
    
    /**
     * Execute a command silently and return output (doesn't update global StateFlow).
     */
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        suspend fun runExec(targetSession: Session): Result<String> {
            return try {
                val channel = targetSession.openChannel("exec") as ChannelExec
                channel.setCommand(command)

                val outputStream = ByteArrayOutputStream()
                channel.inputStream = null
                channel.setOutputStream(outputStream)
                channel.setErrStream(outputStream)

                channel.connect(10000)

                while (!channel.isClosed) {
                    Thread.sleep(50)
                }

                val output = outputStream.toString()
                channel.disconnect()

                Result.success(output)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        val currentSession = session
        if (currentSession == null || !currentSession.isConnected) {
            return@withContext Result.failure(Exception("Not connected"))
        }

        val firstAttempt = runExec(currentSession)
        val firstError = firstAttempt.exceptionOrNull()
        if (firstAttempt.isSuccess || !isRecoverableSessionFailure(firstError)) {
            return@withContext firstAttempt
        }

        try {
            session?.disconnect()
        } catch (_: Exception) {}
        _isConnected.value = false

        val reconnect = connect(_config.value)
        if (reconnect.isFailure) {
            return@withContext Result.failure(firstError ?: Exception("SSH command failed"))
        }

        val retriedSession = session ?: return@withContext Result.failure(firstError ?: Exception("SSH reconnect failed"))
        runExec(retriedSession)
    }
    
    /**
     * Execute a command quietly and return output string (or null on error).
     * Use for background polling without any UI feedback.
     */
    suspend fun executeQuiet(command: String): String? {
        return executeCommand(command).getOrNull()
    }
    
    /**
     * Execute a command and stream output to global StateFlow.
     */
    suspend fun executeCommandStreaming(command: String): Result<Int> = withContext(Dispatchers.IO) {
        // Check connection first
        if (!checkConnection()) {
            appendOutput("\n[Error] Not connected to SSH server\n")
            return@withContext Result.failure(Exception("Not connected"))
        }
        
        val currentSession = session!!
        
        try {
            DebugLog.log("SSH: Executing: $command")
            _isExecuting.value = true
            _currentCommand.value = command
            appendOutput("\n\$ $command\n")
            
            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            
            val inputStream = channel.inputStream
            val errorStream = channel.extInputStream
            channel.connect(30000)
            
            val buffer = ByteArray(1024)
            
            while (true) {
                // Read stdout
                while (inputStream.available() > 0) {
                    val read = inputStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendOutput(text)
                }
                
                // Read stderr
                while (errorStream.available() > 0) {
                    val read = errorStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendOutput("[stderr] $text")
                }
                
                if (channel.isClosed) {
                    if (inputStream.available() > 0 || errorStream.available() > 0) continue
                    break
                }
                Thread.sleep(100)
            }
            
            val exitStatus = channel.exitStatus
            channel.disconnect()
            
            appendOutput("\n[Exit code: $exitStatus]\n")
            _isExecuting.value = false
            _currentCommand.value = ""
            
            DebugLog.log("SSH: Command completed with exit code $exitStatus")
            Result.success(exitStatus)
        } catch (e: Exception) {
            DebugLog.log("SSH: Command failed - ${e.message}")
            appendOutput("\n[Error] ${e.message}\n")
            _isExecuting.value = false
            _currentCommand.value = ""
            
            // Check if connection was lost
            checkConnection()
            
            Result.failure(e)
        }
    }
    
    /**
     * Execute a command and stream output to per-tool StateFlow.
     * Each tool gets its own output channel.
     */
    suspend fun executeCommandForTool(toolId: String, command: String): Result<Int> = withContext(Dispatchers.IO) {
        if (!checkConnection()) {
            appendToolOutput(toolId, "\n[Error] Not connected to SSH server\n")
            return@withContext Result.failure(Exception("Not connected"))
        }
        
        val currentSession = session!!
        
        try {
            DebugLog.log("SSH[$toolId]: Executing: $command")
            setToolExecuting(toolId, true)
            appendToolOutput(toolId, "\n\$ $command\n")
            
            val channel = currentSession.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            
            val inputStream = channel.inputStream
            val errorStream = channel.extInputStream
            channel.connect(30000)
            
            val buffer = ByteArray(1024)
            
            while (true) {
                while (inputStream.available() > 0) {
                    val read = inputStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendToolOutput(toolId, text)
                }
                
                while (errorStream.available() > 0) {
                    val read = errorStream.read(buffer, 0, 1024)
                    if (read < 0) break
                    val text = String(buffer, 0, read)
                    appendToolOutput(toolId, "[stderr] $text")
                }
                
                if (channel.isClosed) {
                    if (inputStream.available() > 0 || errorStream.available() > 0) continue
                    break
                }
                Thread.sleep(100)
            }
            
            val exitStatus = channel.exitStatus
            channel.disconnect()
            
            appendToolOutput(toolId, "\n[Exit code: $exitStatus]\n")
            setToolExecuting(toolId, false)
            
            DebugLog.log("SSH[$toolId]: Command completed with exit code $exitStatus")
            Result.success(exitStatus)
        } catch (e: Exception) {
            DebugLog.log("SSH[$toolId]: Command failed - ${e.message}")
            appendToolOutput(toolId, "\n[Error] ${e.message}\n")
            setToolExecuting(toolId, false)
            checkConnection()
            Result.failure(e)
        }
    }
    
    /**
     * Execute multiple commands sequentially. Shows confirmation for each.
     */
    suspend fun executeCommandsSequentially(
        commands: List<String>,
        onProgress: (Int, Int, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        for ((index, command) in commands.withIndex()) {
            onProgress(index + 1, commands.size, command)
            val result = executeCommandStreaming(command)
            if (result.isFailure) {
                return@withContext Result.failure(result.exceptionOrNull() ?: Exception("Command failed"))
            }
        }
        Result.success(Unit)
    }
    
    /**
     * Launch sequential commands in PERSISTENT scope (survives navigation).
     * Use this for Run All installations that should continue in background.
     */
    fun launchSequentialCommandsPersistent(commands: List<String>, toolName: String) {
        persistentScope.launch {
            appendOutput("\n=== Installing $toolName (${commands.size} steps) ===\n")
            appendOutput("⚠️ This will continue even if you navigate away\n\n")
            
            for ((index, command) in commands.withIndex()) {
                appendOutput("[Step ${index + 1}/${commands.size}]\n")
                _currentCommand.value = command
                
                val result = executeCommandStreaming(command)
                if (result.isFailure) {
                    appendOutput("\n❌ Installation failed at step ${index + 1}\n")
                    break
                }
            }
            
            appendOutput("\n=== $toolName installation complete ===\n")
        }
    }
    
    /**
     * Disconnect from SSH server.
     */
    fun disconnect() {
        session?.disconnect()
        session = null
        _isConnected.value = false
        _isExecuting.value = false
        _currentCommand.value = ""
        appendOutput("\n[Disconnected]\n")
        DebugLog.log("SSH: Disconnected")
    }
}
