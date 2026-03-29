package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.os.PowerManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import com.example.llamadroid.R
import android.net.Uri

/**
 * File/directory information
 */
data class FileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: String
)

/**
 * Code search result
 */
data class SearchResult(
    val path: String,
    val lineNumber: Int,
    val content: String
)

data class PromptContextSnapshot(
    val rawEstimatedTokens: Int,
    val packedEstimatedTokens: Int,
    val contextSize: Int,
    val omittedCount: Int,
    val percentUsed: Int,
    val thresholdPercent: Int,
    val thresholdTriggered: Boolean,
    val didCompactHistory: Boolean,
    val profileName: String,
    val recentCompactions: List<PromptCompactionEvent> = emptyList()
)

data class PromptCompactionEvent(
    val timestamp: Long,
    val rawEstimatedTokens: Int,
    val packedEstimatedTokens: Int
)


/**
 * AgentService - AI Coding Agent with tool calling
 * 
 * Connects to ai-agent proot via SSH and executes agent tools:
 * - read_file: Read file contents
 * - write_file: Write/create files
 * - run_command: Execute shell commands (requires approval)
 * - list_directory: List files/folders
 * - search_code: Search with ripgrep
 */
class AgentService(private val context: Context, private val isRuntimeOwner: Boolean = false) {
    
    init {
        if (isRuntimeOwner || activeInstance == null) {
            activeInstance = this
        }
    }

    /**
     * Uses a shell channel to support persistence and interaction.
     */
    suspend fun runInteractiveCommand(messageId: String, command: String, lines: Int = 10): Result<String> = withContext(Dispatchers.IO) {
        val requestedLines = clampCommandLines(lines)
        val projectFolder = _currentProjectFolder.value.ifBlank { "default_project" }
        val projectPath = "$WORKSPACE_PATH/$projectFolder"
        val commandSession = createBackgroundCommand(messageId, command, projectPath, requestedLines)
            .getOrElse {
                updateTerminalOutput(messageId, "\n[Error: ${it.message}]")
                return@withContext Result.failure(it)
            }

        activeCommands[commandSession.id] = commandSession
        AgentService.recordAgentEvent("command_start", "Started command ${commandSession.id}", command)

        try {
            commandSession.channel.connect(10_000)
            commandSession.stdin.write(("cd '$projectPath' && clear\n").toByteArray())
            commandSession.stdin.flush()
            delay(250)
            commandSession.stdin.write((
                "$command\nprintf '\\n${commandSession.sentinel} %s\\n' $?\n"
            ).toByteArray())
            commandSession.stdin.flush()

            val timeoutMillis = 30_000L
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMillis && commandSession.isRunning) {
                delay(200)
            }

            if (commandSession.isRunning) {
                startCommandAutoUpdates(commandSession)
            }

            Result.success(
                formatCommandSnapshot(
                    command = commandSession,
                    requestedLines = requestedLines,
                    includeGuidance = true,
                    markAsDelivered = true
                )
            )
        } catch (e: Exception) {
            handleBackgroundCommandFailure(commandSession, e)
            Result.failure(e)
        }
    }


    /**
     * Internal command execution with absolute security
     */
    suspend fun executeRawCommand(command: String, isHeartbeat: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        sshMutex.withLock {
            try {
                val currentSession = ensureConnectedSessionLocked().getOrElse {
                    return@withContext Result.failure(it)
                }
                val firstAttempt = executeCommandOnSession(currentSession, command)
                if (firstAttempt.isSuccess) {
                    return@withContext firstAttempt.map { it.trimEnd() }
                }

                val firstError = firstAttempt.exceptionOrNull()
                if (!isRecoverableSessionFailure(firstError)) {
                    return@withContext Result.failure(firstError ?: Exception("Raw SSH command failed"))
                }

                if (!isHeartbeat) {
                    addDebugLog("🔄 Recovering SSH session after command failure: ${firstError?.message?.take(80)}")
                }
                markSessionDisconnected(firstError ?: Exception("Recoverable SSH failure"))
                val retriedSession = openVerifiedSessionLocked(
                    host = lastConnectionHost,
                    port = lastConnectionPort,
                    username = lastConnectionUser,
                    password = lastConnectionPassword,
                    forceReconnect = true
                ).getOrElse {
                    startScalingRetry(this@AgentService)
                    return@withContext Result.failure(it)
                }
                executeCommandOnSession(retriedSession, command).map { it.trimEnd() }
            } catch (e: Exception) {
                if (!isHeartbeat) {
                    addDebugLog("⚠️ Raw SSH command failed: ${e.message}")
                }
                Result.failure(e)
            }
        }
    }

    suspend fun runCommand(command: String, workingDir: String = WORKSPACE_PATH): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safeDir = sanitizePath(workingDir)
            val fullCommand = "cd '$safeDir' && $command 2>&1"
            addDebugLog("🖥️ SSH: $fullCommand")
            val result = executeCommand(fullCommand)
            result.onSuccess { output ->
                _lastCommandOutput.value = output
                if (shouldMarkCommandAsMemoryDirty(command)) {
                    markMemoryDirty("Command `${command.take(80)}` changed project state.")
                }
            }
            return@withContext result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildCheckpointJson(): String {
        return JSONObject().apply {
            put("messageCount", _messages.value.size)
            put("currentAgent", _currentAgent.value.name)
            put("currentTask", _currentTask.value)
            put("projectFolder", _currentProjectFolder.value)
            put("currentSessionId", _currentSessionId.value)
            put("activeConversationId", _activeConversationId.value)
            put("timestamp", System.currentTimeMillis())
        }.toString()
    }

    private fun persistAgentRuntimeState(status: String) {
        val appContext = context.applicationContext
        val conversationId = _activeConversationId.value
        val sessionId = _currentSessionId.value
        val now = System.currentTimeMillis()
        val jobId = "agent-runtime-${conversationId ?: "global"}"
        val jobKey = "agent|${conversationId ?: "global"}|${_currentProjectFolder.value}"

        agentScope.launch(Dispatchers.IO) {
            runCatching {
                AiRuntimeJobStore.upsert(
                    appContext,
                    com.example.llamadroid.data.db.AiRuntimeJobEntity(
                        jobId = jobId,
                        jobKey = jobKey,
                        type = AiRuntimeJobStore.TYPE_AGENT_CHAT,
                        status = AiRuntimeJobStore.STATUS_RUNNING,
                        conversationId = conversationId,
                        sessionId = sessionId,
                        projectFolder = _currentProjectFolder.value,
                        backendIdentifier = runCatching {
                            AgentForegroundService.getSettingsRepository(appContext).agentBackend.value
                        }.getOrDefault("ollama"),
                        modelName = _selectedModel.value,
                        payloadJson = snapshotPersistentState(),
                        checkpointJson = buildCheckpointJson(),
                        progressText = status,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }.onFailure {
                addDebugLog("⚠️ Failed to persist agent runtime state: ${it.message}")
            }
        }
    }

    private fun completeAgentRuntimeState(finalStatus: String) {
        val appContext = context.applicationContext
        val jobId = "agent-runtime-${_activeConversationId.value ?: "global"}"
        agentScope.launch(Dispatchers.IO) {
            runCatching {
                AiRuntimeJobStore.markState(
                    appContext,
                    jobId = jobId,
                    status = finalStatus,
                    checkpointJson = buildCheckpointJson(),
                    progressText = finalStatus.lowercase()
                )
            }.onFailure {
                addDebugLog("⚠️ Failed to update persisted agent runtime state: ${it.message}")
            }
        }
    }

    fun snapshotPersistentState(): String {
        fun serializeToolCall(toolCall: com.example.llamadroid.service.OllamaService.ToolCall?): JSONObject? {
            return toolCall?.let {
                JSONObject().apply {
                    put("name", it.name)
                    put("id", it.id)
                    put("arguments", JSONObject(it.arguments))
                }
            }
        }

        fun serializeMessage(message: ChatMessage): JSONObject {
            return JSONObject().apply {
                put("id", message.id)
                put("role", message.role)
                put("content", message.content)
                put("thinking", message.thinking)
                put("toolName", message.toolName)
                put("toolCallId", message.toolCallId)
                put("toolArgs", message.toolArgs?.let { JSONObject(it) })
                put("toolOutput", message.toolOutput)
                put("terminalOutput", message.terminalOutput)
                put("isTerminalVisible", message.isTerminalVisible)
                put("isStreaming", false)
                put("needsApproval", message.needsApproval)
                put("isApproved", message.isApproved)
                put("isPlan", message.isPlan)
                put("isPlanApproved", message.isPlanApproved)
                put("planModifiedContent", message.planModifiedContent)
                put("isDelegation", message.isDelegation)
                put("agentRole", message.agentRole)
                put("customAgentName", message.customAgentName)
                put("isSuspicious", message.isSuspicious)
                put("pendingToolCall", serializeToolCall(message.pendingToolCall))
                put("isOutputExpanded", message.isOutputExpanded)
                put("timestamp", message.timestamp)
                put("sequenceNumber", message.sequenceNumber)
            }
        }

        fun serializeSession(session: AgentSession): JSONObject {
            return JSONObject().apply {
                put("id", session.id)
                put("agentType", session.agentType)
                put("parentSessionId", session.parentSessionId)
                put("inputFromParent", session.inputFromParent)
                put("contextFromParent", session.contextFromParent)
                put("contract", session.contract)
                put("startedAt", session.startedAt)
                put(
                    "messages",
                    JSONArray().apply {
                        session.messages.forEach { put(serializeMessage(it)) }
                    }
                )
            }
        }

        return JSONObject().apply {
            put("projectFolder", _currentProjectFolder.value)
            put("currentAgent", _currentAgent.value.name)
            put("currentTask", _currentTask.value)
            put("selectedModel", _selectedModel.value)
            put("activeConversationId", _activeConversationId.value)
            put("currentSessionId", _currentSessionId.value)
            put("memoryDirty", _memoryDirty.value)
            put("memoryDirtyReason", _memoryDirtyReason.value)
            put(
                "messages",
                JSONArray().apply {
                    _messages.value.forEach { put(serializeMessage(it)) }
                }
            )
            put(
                "sessions",
                JSONArray().apply {
                    _sessions.value.values.forEach { put(serializeSession(it)) }
                }
            )
        }.toString()
    }

    fun restorePersistentState(payloadJson: String) {
        fun deserializeToolCall(json: JSONObject?): com.example.llamadroid.service.OllamaService.ToolCall? {
            if (json == null) return null
            val argsJson = json.optJSONObject("arguments")
            val args = mutableMapOf<String, String>()
            argsJson?.keys()?.forEach { key -> args[key] = argsJson.optString(key) }
            return com.example.llamadroid.service.OllamaService.ToolCall(
                name = json.optString("name"),
                arguments = args,
                id = json.optString("id").takeIf { it.isNotBlank() }
            )
        }

        fun deserializeMessage(json: JSONObject): ChatMessage {
            val argsJson = json.optJSONObject("toolArgs")
            val args = argsJson?.let {
                buildMap {
                    it.keys().forEach { key -> put(key, it.optString(key)) }
                }
            }
            return ChatMessage(
                id = json.optString("id"),
                role = json.optString("role"),
                content = json.optString("content"),
                thinking = json.optString("thinking").takeIf { it.isNotBlank() },
                toolName = json.optString("toolName").takeIf { it.isNotBlank() },
                toolCallId = json.optString("toolCallId").takeIf { it.isNotBlank() },
                toolArgs = args,
                toolOutput = json.optString("toolOutput").takeIf { it.isNotBlank() },
                terminalOutput = json.optString("terminalOutput").takeIf { it.isNotBlank() },
                isTerminalVisible = json.optBoolean("isTerminalVisible", false),
                isStreaming = false,
                needsApproval = json.optBoolean("needsApproval", false),
                isApproved = json.opt("isApproved") as? Boolean,
                isPlan = json.optBoolean("isPlan", false),
                isPlanApproved = json.opt("isPlanApproved") as? Boolean,
                planModifiedContent = json.optString("planModifiedContent").takeIf { it.isNotBlank() },
                isDelegation = json.optBoolean("isDelegation", false),
                agentRole = json.optString("agentRole").takeIf { it.isNotBlank() },
                customAgentName = json.optString("customAgentName").takeIf { it.isNotBlank() },
                isSuspicious = json.optBoolean("isSuspicious", false),
                pendingToolCall = deserializeToolCall(json.optJSONObject("pendingToolCall")),
                isOutputExpanded = json.optBoolean("isOutputExpanded", false),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                sequenceNumber = json.optInt("sequenceNumber", 0)
            )
        }

        val payload = JSONObject(payloadJson)
        _currentProjectFolder.value = payload.optString("projectFolder", _currentProjectFolder.value)
        _currentAgent.value = runCatching { AgentRole.valueOf(payload.optString("currentAgent", AgentRole.ORCHESTRATOR.name)) }
            .getOrDefault(AgentRole.ORCHESTRATOR)
        _currentTask.value = payload.optString("currentTask").takeIf { it.isNotBlank() }
        _selectedModel.value = payload.optString("selectedModel", _selectedModel.value)
        _activeConversationId.value = payload.optLong("activeConversationId").takeIf {
            payload.has("activeConversationId") && (!payload.isNull("activeConversationId") || it != 0L)
        }
        _currentSessionId.value = payload.optString("currentSessionId").takeIf { it.isNotBlank() }
        _memoryDirty.value = payload.optBoolean("memoryDirty", false)
        _memoryDirtyReason.value = payload.optString("memoryDirtyReason").takeIf { it.isNotBlank() }

        val messagesArray = payload.optJSONArray("messages") ?: JSONArray()
        val restoredMessages = mutableListOf<ChatMessage>()
        for (i in 0 until messagesArray.length()) {
            restoredMessages += deserializeMessage(messagesArray.getJSONObject(i))
        }
        _messages.value = restoredMessages
        resetMessageCounter(restoredMessages.maxOfOrNull { it.sequenceNumber } ?: 0)

        val sessionsArray = payload.optJSONArray("sessions") ?: JSONArray()
        val restoredSessions = linkedMapOf<String, AgentSession>()
        for (i in 0 until sessionsArray.length()) {
            val sessionJson = sessionsArray.getJSONObject(i)
            val sessionMessages = mutableListOf<ChatMessage>()
            val sessionMessagesJson = sessionJson.optJSONArray("messages") ?: JSONArray()
            for (j in 0 until sessionMessagesJson.length()) {
                sessionMessages += deserializeMessage(sessionMessagesJson.getJSONObject(j))
            }
            val session = AgentSession(
                id = sessionJson.optString("id"),
                agentType = sessionJson.optString("agentType"),
                parentSessionId = sessionJson.optString("parentSessionId").takeIf { it.isNotBlank() },
                inputFromParent = sessionJson.optString("inputFromParent").takeIf { it.isNotBlank() },
                contextFromParent = sessionJson.optString("contextFromParent").takeIf { it.isNotBlank() },
                contract = sessionJson.optString("contract").takeIf { it.isNotBlank() },
                messages = sessionMessages,
                startedAt = sessionJson.optLong("startedAt", System.currentTimeMillis())
            )
            restoredSessions[session.id] = session
        }
        _sessions.value = restoredSessions
    }

    suspend fun listDirectory(path: String): Result<List<FileInfo>> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            val listingCommand = """
                if [ ! -d '$safePath' ]; then
                    echo "__AGENT_LIST_ERROR__\tnot_a_directory\t$safePath"
                    exit 3
                fi
                find '$safePath' -mindepth 1 -maxdepth 1 -printf '%f\t%y\t%s\t%M\n' 2>/dev/null
            """.trimIndent()

            val result = executeCommandDetailed(listingCommand, timeoutMs = 20_000)
            result.fold(
                onSuccess = { details ->
                    val output = details.output.trim()
                    if (details.exitCode != 0) {
                        val errorLine = output.lineSequence().firstOrNull()
                        val message = when {
                            errorLine?.startsWith("__AGENT_LIST_ERROR__") == true -> {
                                val parts = errorLine.split('\t')
                                context.getString(
                                    R.string.agent_workspace_error_unavailable,
                                    parts.getOrNull(2) ?: safePath
                                )
                            }
                            output.isNotBlank() -> output.lineSequence().first().trim()
                            else -> context.getString(R.string.agent_workspace_error_list_failed, safePath)
                        }
                        addDebugLog("📁 listDirectory failed for $safePath: $message")
                        Result.failure(Exception(message))
                    } else {
                        val files = output.lineSequence()
                            .filter { it.isNotBlank() }
                            .mapNotNull { line ->
                                val parts = line.split('\t', limit = 4)
                                if (parts.size < 4) return@mapNotNull null
                                val name = parts[0]
                                if (name == "." || name == "..") return@mapNotNull null
                                FileInfo(
                                    name = name,
                                    path = if (safePath.endsWith("/")) "$safePath$name" else "$safePath/$name",
                                    isDirectory = parts[1] == "d",
                                    size = parts[2].toLongOrNull() ?: 0L,
                                    permissions = parts[3]
                                )
                            }
                            .toList()
                        Result.success(files)
                    }
                },
                onFailure = { error ->
                    addDebugLog("📁 listDirectory exception for $safePath: ${error.message}")
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            addDebugLog("📁 listDirectory crashed for $path: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun checkCommand(id: String, lines: Int = 10): Result<String> {
        val cmd = activeCommands[id] ?: return Result.failure(Exception("Command ID not found: $id"))
        val requestedLines = clampCommandLines(lines)
        cmd.lastRequestedLines = requestedLines
        return Result.success(
            formatCommandSnapshot(
                command = cmd,
                requestedLines = requestedLines,
                includeGuidance = cmd.isRunning,
                markAsDelivered = true
            )
        )
    }

    suspend fun waitCommand(id: String, waitSeconds: Int, lines: Int = 10): Result<String> {
        val cmd = activeCommands[id] ?: return Result.failure(Exception("Command ID not found: $id"))
        val requestedLines = clampCommandLines(lines)
        cmd.lastRequestedLines = requestedLines
        val timeoutMillis = (waitSeconds * 1000L).coerceAtMost(30000L).coerceAtLeast(1000L)
        val startTime = System.currentTimeMillis()
        val startVersion = cmd.outputVersion
        while (System.currentTimeMillis() - startTime < timeoutMillis && cmd.isRunning && cmd.outputVersion == startVersion) {
            kotlinx.coroutines.delay(500)
        }
        return Result.success(
            formatCommandSnapshot(
                command = cmd,
                requestedLines = requestedLines,
                includeGuidance = cmd.isRunning,
                markAsDelivered = true
            )
        )
    }

    suspend fun listCommands(): Result<String> = withContext(Dispatchers.IO) {
        val commands = activeCommands.values.sortedByDescending { it.startedAt }
        if (commands.isEmpty()) {
            return@withContext Result.success("No tracked commands.")
        }

        val output = buildString {
            appendLine("Tracked commands:")
            commands.forEach { command ->
                val status = if (command.isRunning) "running" else "finished (${command.exitCode})"
                val lastOutput = synchronized(command.stateLock) { command.tailLines.lastOrNull() }.orEmpty()
                appendLine("- ${command.id} | $status | started ${formatCommandTimestamp(command.startedAt)} | command: ${command.command.take(120)}")
                if (lastOutput.isNotBlank()) {
                    appendLine("  last output: ${lastOutput.take(160)}")
                }
            }
        }.trimEnd()

        Result.success(output)
    }

    suspend fun cancelCommand(id: String): Result<String> = withContext(Dispatchers.IO) {
        val command = activeCommands[id] ?: return@withContext Result.failure(Exception("Command ID not found: $id"))
        if (!command.isRunning) {
            return@withContext Result.success(
                "Command is already finished.\n" + formatCommandSnapshot(
                    command = command,
                    requestedLines = command.lastRequestedLines,
                    includeGuidance = false,
                    markAsDelivered = true
                )
            )
        }

        synchronized(command.stateLock) {
            command.isRunning = false
            command.exitCode = 130
            command.lastActivityAt = System.currentTimeMillis()
            command.outputVersion += 1
        }
        command.autoUpdateJob?.cancel()
        appendVisibleCommandOutput(command, "[Command cancelled by agent]\n")
        closeBackgroundCommand(command)
        AgentService.recordAgentEvent("command_cancelled", "Cancelled command ${command.id}", command.command)

        Result.success(
            "Cancelled command ${command.id}.\n" + formatCommandSnapshot(
                command = command,
                requestedLines = command.lastRequestedLines,
                includeGuidance = false,
                markAsDelivered = true
            )
        )
    }

    suspend fun sendCommandInput(id: String, input: String, appendNewline: Boolean = true): Result<String> = withContext(Dispatchers.IO) {
        val command = activeCommands[id] ?: return@withContext Result.failure(Exception("Command ID not found: $id"))
        if (!command.isRunning) {
            return@withContext Result.failure(Exception("Command $id is not running"))
        }

        try {
            val payload = if (appendNewline) "$input\n" else input
            command.stdin.write(payload.toByteArray(Charsets.UTF_8))
            command.stdin.flush()
            synchronized(command.stateLock) {
                command.lastActivityAt = System.currentTimeMillis()
            }
            AgentService.recordAgentEvent("command_input", "Sent input to command ${command.id}", input)
            Result.success("Sent input to command ${command.id}. Use wait_command or check_command to inspect the response.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun createBackgroundCommand(
        messageId: String,
        command: String,
        projectPath: String,
        requestedLines: Int
    ): Result<BackgroundCommand> = withContext(Dispatchers.IO) {
        val commandSession = openDedicatedCommandSession().getOrElse { return@withContext Result.failure(it) }
        try {
            val commandId = "cmd_${System.currentTimeMillis()}"
            val channel = commandSession.openChannel("shell") as com.jcraft.jsch.ChannelShell
            val outPipe = java.io.PipedOutputStream()
            val inPipe = java.io.PipedInputStream(outPipe)
            channel.inputStream = inPipe

            val backgroundCommand = BackgroundCommand(
                id = commandId,
                command = command,
                terminalMessageId = messageId,
                projectPath = projectPath,
                sentinel = "__CMD_DONE_${commandId}__",
                session = commandSession,
                channel = channel,
                stdin = outPipe,
                lastRequestedLines = requestedLines,
                startedAt = System.currentTimeMillis(),
                lastActivityAt = System.currentTimeMillis()
            )

            channel.setOutputStream(createCommandOutputStream(backgroundCommand))
            Result.success(backgroundCommand)
        } catch (e: Exception) {
            commandSession.disconnect()
            Result.failure(e)
        }
    }

    private fun createCommandOutputStream(command: BackgroundCommand): java.io.OutputStream {
        return object : java.io.OutputStream() {
            override fun write(b: Int) {
                val char = b.toChar()
                if (char == '\r') return
                synchronized(command.stateLock) {
                    command.pendingLine.append(char)
                    if (char == '\n') {
                        flushPendingCommandLine(command)
                    }
                }
            }
        }
    }

    private fun flushPendingCommandLine(command: BackgroundCommand) {
        val rawLine = command.pendingLine.toString()
        command.pendingLine.setLength(0)
        val cleanLine = rawLine.removeSuffix("\n")

        if (cleanLine.startsWith(command.sentinel)) {
            command.exitCode = cleanLine.substringAfter(command.sentinel).trim().toIntOrNull() ?: 0
            command.isRunning = false
            command.lastActivityAt = System.currentTimeMillis()
            command.outputVersion += 1
            command.autoUpdateJob?.cancel()
            AgentService.recordAgentEvent(
                "command_finish",
                "Command ${command.id} finished with exit ${command.exitCode}",
                command.command
            )
            if (command.exitCode == 0 && shouldMarkCommandAsMemoryDirty(command.command)) {
                markMemoryDirty("Command `${command.command.take(80)}` changed project state.")
            }
            closeBackgroundCommand(command)
            return
        }

        appendVisibleCommandOutput(command, rawLine)
    }

    private fun appendVisibleCommandOutput(command: BackgroundCommand, rawLine: String) {
        synchronized(command.stateLock) {
            command.fullTranscript.append(rawLine)
            command.tailLines += rawLine.removeSuffix("\n")
            if (command.tailLines.size > MAX_COMMAND_TAIL_LINES) {
                command.tailLines.removeAt(0)
            }
            command.lastActivityAt = System.currentTimeMillis()
            command.outputVersion += 1
        }
        updateTerminalOutput(command.terminalMessageId, rawLine)
    }

    private fun handleBackgroundCommandFailure(command: BackgroundCommand, error: Exception) {
        synchronized(command.stateLock) {
            command.isRunning = false
            command.exitCode = -1
            command.lastActivityAt = System.currentTimeMillis()
            appendVisibleCommandOutput(command, "[Terminal Error: ${error.message}]\n")
        }
        AgentService.recordAgentEvent("command_error", "Command ${command.id} failed", error.message ?: command.command)
        closeBackgroundCommand(command)
    }

    private fun closeBackgroundCommand(command: BackgroundCommand) {
        try {
            command.stdin.close()
        } catch (_: Exception) {
        }
        try {
            if (command.channel.isConnected) {
                command.channel.disconnect()
            }
        } catch (_: Exception) {
        }
        try {
            if (command.session.isConnected) {
                command.session.disconnect()
            }
        } catch (_: Exception) {
        }
    }

    private fun formatCommandSnapshot(
        command: BackgroundCommand,
        requestedLines: Int,
        includeGuidance: Boolean,
        markAsDelivered: Boolean
    ): String {
        val tailLines = synchronized(command.stateLock) { command.tailLines.takeLast(requestedLines) }
        val status = if (command.isRunning) {
            "running"
        } else {
            "finished (exit code: ${command.exitCode})"
        }
        if (markAsDelivered) {
            command.deliveredVersion = command.outputVersion
        }

        return buildString {
            appendLine("Command ID: ${command.id}")
            appendLine("Status: $status")
            appendLine("Requested tail lines: $requestedLines")
            if (includeGuidance && command.isRunning) {
                appendLine("Command is still running. Use wait_command to wait up to 30 seconds for more output, or increase lines if you need more context.")
            }
            appendLine("Output:")
            if (tailLines.isEmpty()) {
                appendLine("[no output yet]")
            } else {
                append(tailLines.joinToString("\n"))
            }
        }.trim()
    }

    private fun clampCommandLines(lines: Int): Int = lines.coerceIn(1, MAX_COMMAND_TAIL_LINES)

    private fun formatCommandTimestamp(timestamp: Long): String {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(timestamp))
    }

    private fun startCommandAutoUpdates(command: BackgroundCommand) {
        if (command.autoUpdateJob?.isActive == true) return
        command.autoUpdateJob = agentScope.launch(Dispatchers.IO) {
            while (command.isRunning) {
                delay(30_000)
                if (!command.isRunning) break
                val update = formatCommandSnapshot(
                    command = command,
                    requestedLines = command.lastRequestedLines,
                    includeGuidance = true,
                    markAsDelivered = false
                )
                pushBackgroundCommandUpdate(update)
            }
        }
    }

    private fun configureSshSession(session: com.jcraft.jsch.Session) {
        session.setServerAliveInterval(SSH_SERVER_ALIVE_INTERVAL_MS)
        session.setServerAliveCountMax(SSH_SERVER_ALIVE_COUNT_MAX)
        session.timeout = 60_000
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

    private fun markSessionDisconnected(error: Throwable) {
        try {
            session?.disconnect()
        } catch (_: Exception) {}
        session = null
        _isConnected.value = false
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        addDebugLog("🔌 SSH session dropped: ${error.message?.take(80) ?: error.javaClass.simpleName}")
    }

    private suspend fun openDedicatedCommandSession(): Result<com.jcraft.jsch.Session> = withContext(Dispatchers.IO) {
        try {
            if (lastConnectionHost.isBlank()) {
                connect()
            }

            val dedicatedSession = jsch.getSession(lastConnectionUser, lastConnectionHost, lastConnectionPort).apply {
                setPassword(lastConnectionPassword)
                val props = java.util.Properties()
                props["StrictHostKeyChecking"] = "no"
                setConfig(props)
            }
            configureSshSession(dedicatedSession)
            dedicatedSession.connect()
            Result.success(dedicatedSession)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchCode(query: String): Result<List<SearchResult>> = withContext(Dispatchers.IO) {
        try {
            val folder = _currentProjectFolder.value ?: "default"
            val output = executeRawCommand("cd $WORKSPACE_PATH/$folder && rg --vimgrep --no-heading \"$query\" .").getOrThrow()
            val results = mutableListOf<SearchResult>()
            output.lines().forEach { line ->
                if (line.isBlank()) return@forEach
                val parts = line.split(":", limit = 4)
                if (parts.size >= 3) {
                    results.add(SearchResult(path = parts[0], lineNumber = parts[1].toIntOrNull() ?: 0, content = parts.lastOrNull() ?: ""))
                }
            }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    
    companion object {
        private const val TAG = "AgentService"
        private const val PROMPT_CONTEXT_AUTOCOMPACT_RATIO = 0.80
        private const val PROMPT_CONTEXT_AUTOCOMPACT_PERCENT = 80
        private const val SSH_SERVER_ALIVE_INTERVAL_MS = 15_000
        private const val SSH_SERVER_ALIVE_COUNT_MAX = 6
        private const val SSH_HEARTBEAT_INTERVAL_MS = 15_000
        private const val TOOL_READ_FILE_DEFAULT_LINES = 160
        private const val TOOL_READ_FILE_MAX_LINES = 400

        // Instance-specific loading state (now in companion for static tool access)
        private val _isLoading = MutableStateFlow(false)
        val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

        // Reference counter for loading state to prevent premature service stopping
        private val loadingRefCount = java.util.concurrent.atomic.AtomicInteger(0)

        // Instance-specific status text for UI (now in companion)
        private val _statusText = MutableStateFlow("")
        val statusText: StateFlow<String> = _statusText.asStateFlow()

        private val _memoryDirty = MutableStateFlow(false)
        val memoryDirty: StateFlow<Boolean> = _memoryDirty.asStateFlow()

        private val _memoryDirtyReason = MutableStateFlow<String?>(null)
        val memoryDirtyReason: StateFlow<String?> = _memoryDirtyReason.asStateFlow()

        private val _promptContextSnapshot = MutableStateFlow<PromptContextSnapshot?>(null)
        val promptContextSnapshot: StateFlow<PromptContextSnapshot?> = _promptContextSnapshot.asStateFlow()
        private val recentCompactionEvents = ArrayDeque<PromptCompactionEvent>(4)

        private fun idleStatusText(appContext: Context): String {
            return if (_memoryDirty.value) {
                appContext.getString(R.string.agent_status_memory_update_required)
            } else {
                appContext.getString(R.string.agent_status_idle)
            }
        }

        fun setIsLoading(loading: Boolean, status: String? = null) {
            val appContext = com.example.llamadroid.LlamaApplication.instance
            
            // Update reference counter
            val count = if (loading) {
                loadingRefCount.incrementAndGet()
            } else {
                val newCount = loadingRefCount.decrementAndGet()
                if (newCount < 0) {
                    loadingRefCount.set(0)
                    0
                } else {
                    newCount
                }
            }
            
            val isActuallyLoading = count > 0
            _isLoading.value = isActuallyLoading
            
            val newStatus = status ?: if (isActuallyLoading) {
                appContext.getString(R.string.agent_status_working)
            } else {
                idleStatusText(appContext)
            }
            _statusText.value = newStatus
            
            // Manage foreground service for background reliability
            try {
                if (isActuallyLoading) {
                    // Start or update foreground service
                    AgentForegroundService.start(appContext, newStatus)
                    activeInstance?.persistAgentRuntimeState(newStatus)
                    acquireWakeLock()
                    addDebugLog("🔄 Agent active (refCount: $count): $newStatus")
                } else {
                    // Only stop if refCount is 0
                    activeInstance?.completeAgentRuntimeState(AiRuntimeJobStore.STATUS_COMPLETED)
                    releaseWakeLock()
                    AgentForegroundService.stop(appContext)
                    addDebugLog("⏹️ Agent idle (refCount: 0)")
                }
            } catch (e: Exception) {
                addDebugLog("⚠️ Foreground service error: ${e.message}")
            }
        }
        
        fun setStatusText(status: String) {
            _statusText.value = status
            // Also update notification if service is running
            if (loadingRefCount.get() > 0) {
                val appContext = com.example.llamadroid.LlamaApplication.instance
                AgentForegroundService.updateStatus(appContext, status)
            }
        }

        private fun refreshIdleStatusIfNeeded() {
            if (loadingRefCount.get() == 0) {
                val appContext = com.example.llamadroid.LlamaApplication.instance
                _statusText.value = idleStatusText(appContext)
            }
        }

        /**
         * Chat message data class (in companion for sharing)
         */
        // Atomic counter for message ordering
        private val _messageCounter = java.util.concurrent.atomic.AtomicInteger(0)
        private val _eventCounter = java.util.concurrent.atomic.AtomicInteger(0)
        
        fun resetMessageCounter(startFrom: Int) {
            _messageCounter.set(startFrom)
        }
        
        data class ChatMessage(
            val id: String = java.util.UUID.randomUUID().toString(),
            val role: String,  // "user", "assistant", "tool", "system"
            val content: String,
            val thinking: String? = null,  // Chain-of-thought (foldable)
            val toolName: String? = null,
            val toolCallId: String? = null,
            val toolArgs: Map<String, String>? = null,
            val toolOutput: String? = null,
            val terminalOutput: String? = null, // Real-time terminal output
            val isTerminalVisible: Boolean = false,
            val isStreaming: Boolean = false,
            val needsApproval: Boolean = false,
            val isApproved: Boolean? = null,
            val isPlan: Boolean = false,
            val isPlanApproved: Boolean? = null,
            val planModifiedContent: String? = null, // Content after user modification
            val isDelegation: Boolean = false,  // Collapsible delegation message
            val agentRole: String? = null,  // Which agent produced this message
            val customAgentName: String? = null,  // Name of custom agent (if applicable)
            val isSuspicious: Boolean = false, // Command triggers security pattern
            val pendingToolCall: com.example.llamadroid.service.OllamaService.ToolCall? = null,
            val isOutputExpanded: Boolean = false, // Individual toggle for tool output
            val timestamp: Long = System.currentTimeMillis(),
            val sequenceNumber: Int = _messageCounter.incrementAndGet()
        ) {
            fun toOllamaMessage(includeThinking: Boolean = true): com.example.llamadroid.service.OllamaService.ChatMessage {
                return com.example.llamadroid.service.OllamaService.ChatMessage(
                    role = this.role,
                    content = this.content,
                    toolCallId = this.toolCallId,
                    thinking = this.thinking?.takeIf { includeThinking },
                    toolCalls = this.pendingToolCall?.let { listOf(it) }
                )
            }
        }

        fun serializeToolArgs(toolArgs: Map<String, String>?): String? =
            toolArgs?.let { JSONObject(it).toString() }

        fun deserializeToolArgs(jsonStr: String?): Map<String, String>? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val json = JSONObject(jsonStr)
                buildMap {
                    json.keys().forEach { key -> put(key, json.optString(key)) }
                }
            } catch (_: Exception) {
                null
            }
        }

        fun serializeToolCall(toolCall: com.example.llamadroid.service.OllamaService.ToolCall?): String? {
            if (toolCall == null) return null
            return JSONObject().apply {
                put("name", toolCall.name)
                put("id", toolCall.id)
                put("arguments", JSONObject(toolCall.arguments))
            }.toString()
        }

        fun deserializeToolCall(jsonStr: String?): com.example.llamadroid.service.OllamaService.ToolCall? {
            if (jsonStr.isNullOrBlank()) return null
            return try {
                val json = JSONObject(jsonStr)
                val argsJson = json.optJSONObject("arguments")
                val args = mutableMapOf<String, String>()
                argsJson?.keys()?.forEach { key -> args[key] = argsJson.optString(key) }
                com.example.llamadroid.service.OllamaService.ToolCall(
                    name = json.optString("name"),
                    arguments = args,
                    id = json.optString("id").takeIf { it.isNotBlank() }
                )
            } catch (_: Exception) {
                null
            }
        }

        fun chatMessageToJson(message: ChatMessage): JSONObject {
            return JSONObject().apply {
                put("id", message.id)
                put("role", message.role)
                put("content", message.content)
                put("thinking", message.thinking)
                put("toolName", message.toolName)
                put("toolCallId", message.toolCallId)
                put("toolArgs", message.toolArgs?.let { JSONObject(it) })
                put("toolOutput", message.toolOutput)
                put("terminalOutput", message.terminalOutput)
                put("isTerminalVisible", message.isTerminalVisible)
                put("isStreaming", false)
                put("needsApproval", message.needsApproval)
                put("isApproved", message.isApproved)
                put("isPlan", message.isPlan)
                put("isPlanApproved", message.isPlanApproved)
                put("planModifiedContent", message.planModifiedContent)
                put("isDelegation", message.isDelegation)
                put("agentRole", message.agentRole)
                put("customAgentName", message.customAgentName)
                put("isSuspicious", message.isSuspicious)
                put("pendingToolCall", serializeToolCall(message.pendingToolCall)?.let { JSONObject(it) })
                put("isOutputExpanded", message.isOutputExpanded)
                put("timestamp", message.timestamp)
                put("sequenceNumber", message.sequenceNumber)
            }
        }

        fun chatMessageFromJson(json: JSONObject): ChatMessage {
            val argsJson = json.optJSONObject("toolArgs")
            val args = argsJson?.let {
                buildMap {
                    it.keys().forEach { key -> put(key, it.optString(key)) }
                }
            }
            return ChatMessage(
                id = json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                role = json.optString("role"),
                content = json.optString("content"),
                thinking = json.optString("thinking").takeIf { it.isNotBlank() },
                toolName = json.optString("toolName").takeIf { it.isNotBlank() },
                toolCallId = json.optString("toolCallId").takeIf { it.isNotBlank() },
                toolArgs = args,
                toolOutput = json.optString("toolOutput").takeIf { it.isNotBlank() },
                terminalOutput = json.optString("terminalOutput").takeIf { it.isNotBlank() },
                isTerminalVisible = json.optBoolean("isTerminalVisible", false),
                isStreaming = false,
                needsApproval = json.optBoolean("needsApproval", false),
                isApproved = json.opt("isApproved") as? Boolean,
                isPlan = json.optBoolean("isPlan", false),
                isPlanApproved = json.opt("isPlanApproved") as? Boolean,
                planModifiedContent = json.optString("planModifiedContent").takeIf { it.isNotBlank() },
                isDelegation = json.optBoolean("isDelegation", false),
                agentRole = json.optString("agentRole").takeIf { it.isNotBlank() },
                customAgentName = json.optString("customAgentName").takeIf { it.isNotBlank() },
                isSuspicious = json.optBoolean("isSuspicious", false),
                pendingToolCall = deserializeToolCall(json.optJSONObject("pendingToolCall")?.toString()),
                isOutputExpanded = json.optBoolean("isOutputExpanded", false),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                sequenceNumber = json.optInt("sequenceNumber", 0)
            )
        }

        fun chatMessageToEntity(
            message: ChatMessage,
            conversationId: Long,
            originalIdOverride: String? = null
        ): com.example.llamadroid.data.db.AgentMessageEntity {
            return com.example.llamadroid.data.db.AgentMessageEntity(
                originalId = originalIdOverride ?: message.id,
                conversationId = conversationId,
                role = message.role,
                content = message.content,
                thinking = message.thinking,
                toolName = message.toolName,
                toolCallId = message.toolCallId,
                toolArgs = serializeToolArgs(message.toolArgs),
                toolOutput = message.toolOutput,
                terminalOutput = message.terminalOutput,
                isTerminalVisible = message.isTerminalVisible,
                needsApproval = message.needsApproval,
                isApproved = message.isApproved,
                isPlan = message.isPlan,
                isPlanApproved = message.isPlanApproved,
                planModifiedContent = message.planModifiedContent,
                isStreaming = false,
                agentRole = message.agentRole,
                isDelegation = message.isDelegation,
                customAgentName = message.customAgentName,
                isSuspicious = message.isSuspicious,
                pendingToolCall = serializeToolCall(message.pendingToolCall),
                isOutputExpanded = message.isOutputExpanded,
                timestamp = message.timestamp,
                sequenceNumber = message.sequenceNumber
            )
        }

        fun chatMessageFromEntity(entity: com.example.llamadroid.data.db.AgentMessageEntity): ChatMessage {
            return ChatMessage(
                id = entity.originalId,
                role = entity.role,
                content = entity.content,
                thinking = entity.thinking,
                toolName = entity.toolName,
                toolCallId = entity.toolCallId,
                toolArgs = deserializeToolArgs(entity.toolArgs),
                toolOutput = entity.toolOutput,
                terminalOutput = entity.terminalOutput,
                isTerminalVisible = entity.isTerminalVisible,
                isStreaming = false,
                needsApproval = entity.needsApproval,
                isApproved = entity.isApproved,
                isPlan = entity.isPlan,
                isPlanApproved = entity.isPlanApproved,
                planModifiedContent = entity.planModifiedContent,
                isDelegation = entity.isDelegation,
                agentRole = entity.agentRole,
                customAgentName = entity.customAgentName,
                isSuspicious = entity.isSuspicious,
                pendingToolCall = deserializeToolCall(entity.pendingToolCall),
                isOutputExpanded = entity.isOutputExpanded,
                timestamp = entity.timestamp,
                sequenceNumber = entity.sequenceNumber
            )
        }

        private const val PROMPT_CONTEXT_RATIO = 0.65
        private const val MIN_PROMPT_CONTEXT_TOKENS = 1024
        private const val RECENT_PROMPT_MESSAGES = 10
        private const val CONTEXT_DIGEST_MAX_ITEMS = 12
        const val WORKSPACE_PATH = "/workspace"
        const val AI_AGENT_SSH_PORT = 8023  // Separate port from Termux tools (8022)
        const val AI_AGENT_USER = "root"
        // Password is now dynamically generated - see AgentCredentials.getPassword()
        private val DEFAULT_BRAIN_FILES = linkedMapOf(
            "summary.md" to """
# Project Summary

## Current State
- No project summary recorded yet.

## Recent Changes
- None recorded yet.

## Files Modified
- None recorded yet.

## Next Steps
- Inspect the repository and replace this placeholder with a factual summary.
""".trimIndent(),
            "current_task.md" to """
# Current Task

## Active Agent
- ORCHESTRATOR

## Task
- No active task.
""".trimIndent(),
            "todo.md" to """
# TODO

- No pending tasks recorded yet.
""".trimIndent(),
            "decisions.md" to """
# Decisions

- No architectural decisions recorded yet.
""".trimIndent(),
            "changed_files.md" to """
# Changed Files

- No tracked file changes yet.
""".trimIndent(),
            "timeline.md" to """
# Timeline

- No timeline events recorded yet.
""".trimIndent()
        )
        
        // ========== AGENT ROLES FOR MULTI-AGENT ORCHESTRATION ==========
        // Disabled built-in agents (user can toggle these)
        private val _disabledBuiltInAgents = MutableStateFlow<Set<String>>(emptySet())
        val disabledBuiltInAgents = _disabledBuiltInAgents.asStateFlow()
        
        fun setBuiltInAgentEnabled(agentName: String, enabled: Boolean) {
            val current = _disabledBuiltInAgents.value.toMutableSet()
            if (enabled) current.remove(agentName.uppercase()) else current.add(agentName.uppercase())
            _disabledBuiltInAgents.value = current
            // Persist
            val prefs = com.example.llamadroid.LlamaApplication.instance.getSharedPreferences("settings", 0)
            prefs.edit().putStringSet("disabled_built_in_agents", _disabledBuiltInAgents.value).apply()
        }
        
        fun loadDisabledAgents() {
            val prefs = com.example.llamadroid.LlamaApplication.instance.getSharedPreferences("settings", 0)
            _disabledBuiltInAgents.value = prefs.getStringSet("disabled_built_in_agents", emptySet()) ?: emptySet()
        }
        
        fun isBuiltInAgentEnabled(agentName: String): Boolean {
            return agentName.uppercase() !in _disabledBuiltInAgents.value
        }
        
        enum class AgentRole(val displayName: String, val emoji: String, val systemPrompt: String) {
            ORCHESTRATOR(
                "Orchestrator",
                "🎯",
                """You are the Orchestrator agent. You coordinate tasks using tools and specialized agents.

## YOUR CRITICAL WORKFLOW (follow EVERY time):
1. **READ MEMORY FIRST**: Read `summary.md` and `current_task.md`. Check `todo.md`, `decisions.md`, and `changed_files.md` when they are relevant.
2. Call list_directory to understand the project structure.
3. Call search_code or read_file_lines to gather context about what needs to change.
4. Break the task into steps. Use propose_plan tool and WAIT for approval.
5. Once approved, delegate to agents: CODER, REVIEWER, EXECUTOR, SUMMARIZER.
6. **AFTER EVERY sub-task completes, ALWAYS call SUMMARIZER to write updated memory.**
7. For long commands, expect background status notices and use wait_command/check_command/command_list instead of rerunning the command.
8. Before declaring success, ensure REVIEWER and EXECUTOR have verified the relevant files or commands.

## DELEGATION IS MANDATORY:
- **CODER**: For ALL code changes. Do NOT write code yourself. ALWAYS delegate to CODER.
- **REVIEWER**: After CODER finishes, ALWAYS call REVIEWER to check the code.
- **EXECUTOR**: After REVIEWER approves, call EXECUTOR to build/test. Analyze its output.
- **SUMMARIZER**: Reads/writes project brain. Call VERY frequently (before AND after work).

## TYPICAL WORKFLOW:
call_agent("SUMMARIZER", "Read project summary") → explore files → propose_plan → 
call_agent("CODER", "Implement: <specific changes>") → 
call_agent("REVIEWER", "Review changes in: <files>") → 
call_agent("EXECUTOR", "Build and test: <command>") → 
call_agent("SUMMARIZER", "Update summary: <what was done>")

## RULES:
- When user approves your plan via UI button, you get a tool result. Do NOT ask again. PROCEED.
- Use tools yourself (list_directory, read_file, search_code) before delegating.
- Keep the structured brain files usable: summary.md for state, current_task.md for the active goal, todo.md for pending work, decisions.md for architectural choices, changed_files.md for touched files.
- Do NOT invent tool names that don't exist.
- Always validate your JSON arguments before calling tools."""
            ),
            CODER(
                "Coder",
                "👷",
                """You are the Coder agent. You write, edit, and create code files.

## YOUR WORKFLOW:
1. Use list_directory and search_code to understand the codebase first.
2. Use file_line_count to check file sizes before reading.
3. Use read_file_lines to read specific sections of code.
4. For MODIFICATIONS to existing files: prefer apply_patch for precise multi-hunk changes. Use edit_lines for very small, line-local edits.
5. Use write_file ONLY for creating brand new files or fully replacing a file when that is clearly necessary.
6. After editing, re-read the changed sections to verify the file looks exactly as intended.

## ⚠️ CRITICAL: write_file OVERWRITES the entire file!
- write_file completely replaces the file content. All existing code will be DELETED.
- For fixing bugs, editing functions, or modifying existing files: prefer apply_patch or edit_lines instead.
- Workflow: search_code → read_file_lines → apply_patch/edit_lines → re-read the changed section

## RULES:
- ALWAYS explore the codebase before writing code. Never guess file structure.
- Do NOT ask for permission — use write_file/edit_lines and let the approval queue handle it.
- Prefer apply_patch over whole-file rewrites when touching existing files in multiple places.
- If you need to run commands, delegate to EXECUTOR via Orchestrator.
- Write clean, well-commented code following the project's existing patterns.
- After file changes, remember that current_task.md and changed_files.md are maintained for you, and still add a short "what changed and why" note to memory.
- When done, ALWAYS call finish_task to return control to Orchestrator.
- Do NOT invent tool names. Your tools: read_file, read_file_lines, file_line_count, write_file, edit_lines, apply_patch, list_directory, search_code."""
            ),
            REVIEWER(
                "Reviewer",
                "🔍",
                """You are the Reviewer agent. You review code for quality and correctness.

## YOUR WORKFLOW:
1. Use read_file_lines to examine the code that was written or changed.
2. Use search_code to find related code and check for consistency.
3. Check changed_files.md when you need a compact view of the files touched during the task.
4. Check for: bugs, security issues, best practices, performance, and mismatches between the requested task and the implementation.
5. Provide specific feedback with file names and line numbers.
6. If issues found, list exact fixes for CODER to implement.

## RULES:
- Be specific. Say "line 42 in foo.kt: missing null check" not "check for nulls".
- When done, ALWAYS call finish_task with a summary of your review.
- Do NOT invent tool names. Your tools: read_file, read_file_lines, file_line_count, list_directory, search_code."""
            ),
            EXECUTOR(
                "Executor",
                "⚡",
                """You are the Executor agent. You run commands to build, test, and debug.

## YOUR WORKFLOW:
1. Use run_command to execute shell commands. All require user approval.
2. Command tools return only the last 10 lines by default. Increase the optional lines argument only when you need more context.
3. Use wait_command while a command is still running. Use check_command to revisit an earlier command by ID. Use command_list to recover or inspect running command IDs.
4. Use send_command_input when a running command prompts for interactive stdin.
5. Cancel obviously bad or stuck commands with cancel_command instead of launching duplicates.
6. Analyze command output carefully and report results.
7. If a build fails, read the error and suggest fixes for CODER.

## RULES:
- ALWAYS run from the project root (/workspace/<project>).
- Run one command at a time and analyze the output before the next.
- Prefer focused verification commands that prove the change worked instead of broad noisy commands when possible.
- When done, ALWAYS call finish_task with a summary of results.
- Do NOT invent tool names. Your tools: run_command, wait_command, check_command, command_list, cancel_command, send_command_input, read_file, read_file_lines, list_directory."""
            ),
            SUMMARIZER(
                "Summarizer",
                "📝",
                """You are the Summarizer agent. You maintain the project's persistent memory ("brain").
THIS IS CRITICAL — without your updates, all progress context is lost between sessions.

## YOUR WORKFLOW:
1. Call list_memory() to see existing brain files.
2. Call read_memory("summary.md") and read_memory("current_task.md") to read the current state.
3. Update summary.md with what was accomplished, modified, and what's next.
4. Keep todo.md, decisions.md, and changed_files.md tidy when they are stale, duplicated, or too long.
5. Call write_memory for append-only notes, or rewrite_memory when consolidating a file.
6. ALWAYS call finish_task when done.
7. If summary.md grows too much, read it, consolidate it, and use rewrite_memory to keep it compact.

## SUMMARY FORMAT:
# Project Summary
## Current State
- Brief description of project status

## Recent Changes  
- What was just done (be specific: file names, feature names)

## Files Modified
- Exact list of files changed and what changed

## Next Steps
- What should be done next

## RULES:
- Keep summaries concise but comprehensive. Focus on FACTS.
- ALWAYS read the existing summary before writing, so you don't lose prior context.
- Use delete_memory or rewrite_memory to keep the structured brain files compact and relevant.
- When done, ALWAYS call finish_task.
- Do NOT invent tool names. Your tools: list_memory, read_memory, write_memory, rewrite_memory, delete_memory."""
            )
        }
        
        // Current active agent role
        private val _currentAgent = MutableStateFlow(AgentRole.ORCHESTRATOR)
        val currentAgent: StateFlow<AgentRole> = _currentAgent.asStateFlow()
        
        // Task being worked on
        private val _currentTask = MutableStateFlow<String?>(null)
        val currentTask: StateFlow<String?> = _currentTask.asStateFlow()
        
        private val _activeConversationId = MutableStateFlow<Long?>(null)
        val activeConversationId: StateFlow<Long?> = _activeConversationId.asStateFlow()
        
        fun setCurrentAgent(role: AgentRole) {
            _currentAgent.value = role
            syncCurrentTaskMemoryAsync(_currentTask.value)
        }

        fun setCurrentTask(task: String?) {
            _currentTask.value = task?.trim()?.takeIf { it.isNotEmpty() }
            syncCurrentTaskMemoryAsync(_currentTask.value)
        }

        fun setActiveConversationId(conversationId: Long?) {
            _activeConversationId.value = conversationId
        }

        private fun currentAssistantIdentity(): Pair<String?, String?> {
            val activeCustom = _activeCustomAgent.value
            return if (activeCustom != null) {
                null to (activeCustom.displayName.takeIf { it.isNotBlank() } ?: activeCustom.name)
            } else {
                _currentAgent.value.name to null
            }
        }

        fun markMemoryDirty(reason: String) {
            val trimmedReason = reason.trim().ifBlank { "Recent work changed project state." }
            val wasDirty = _memoryDirty.value
            val previousReason = _memoryDirtyReason.value
            _memoryDirty.value = true
            _memoryDirtyReason.value = trimmedReason
            if (!wasDirty || previousReason != trimmedReason) {
                addDebugLog("🧠 Memory update required: $trimmedReason")
                recordAgentEvent("memory_dirty", "Memory needs an update", trimmedReason, persist = false)
            }
            refreshIdleStatusIfNeeded()
        }

        fun clearMemoryDirty(reason: String) {
            if (!_memoryDirty.value) return
            _memoryDirty.value = false
            _memoryDirtyReason.value = null
            addDebugLog("🧠 Memory updated: ${reason.trim().ifBlank { "Memory gate cleared." }}")
            recordAgentEvent("memory_clean", "Memory gate cleared", reason, persist = false)
            refreshIdleStatusIfNeeded()
        }

        private fun buildMemoryGateSystemPrompt(): String? {
            if (!_memoryDirty.value || _currentAgent.value == AgentRole.SUMMARIZER) return null
            val reason = _memoryDirtyReason.value ?: "Recent work changed project state."
            return buildString {
                appendLine("MEMORY UPDATE REQUIRED:")
                appendLine(reason)
                appendLine("Before you finish or present the task as done, update project memory.")
                appendLine("Use write_memory to record what changed and why, or delegate to the SUMMARIZER.")
                appendLine("current_task.md and changed_files.md are maintained for you automatically, but they do NOT satisfy this requirement by themselves.")
                appendLine("Tool calls must be emitted as real structured tool calls outside <think>, markdown fences, and plain text.")
            }
        }

        private fun buildMemoryGateRecoveryInstruction(): String {
            val reason = _memoryDirtyReason.value ?: "Recent work changed project state."
            return buildString {
                appendLine("Your last step tried to finish without updating project memory.")
                appendLine("Reason: $reason")
                appendLine("Before finishing, call write_memory to record what changed and why, or use the SUMMARIZER to update the brain files.")
                appendLine("If you need a reminder of the tool syntax, first emit this tool call: `{\"name\": \"read_file\", \"arguments\": {\"path\": \"brain/tools_reference.md\"}}`")
                appendLine("Emit the next tool call as a real structured tool call outside <think>, markdown fences, and plain text.")
            }.trim()
        }

        private fun buildToolCallRecoveryInstruction(
            suspectedToolName: String? = null,
            reason: String
        ): String {
            return buildString {
                appendLine("Your previous response attempted a tool call incorrectly.")
                appendLine("Reason: $reason")
                suspectedToolName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { toolName ->
                        appendLine()
                        appendLine(buildSuspectedToolGuidance(toolName))
                    }
                appendLine()
                appendLine("If you need a full syntax reminder, first emit this tool call: `{\"name\": \"read_file\", \"arguments\": {\"path\": \"brain/tools_reference.md\"}}`")
                appendLine("Emit the next tool call as a real structured tool call outside <think>, markdown fences, and plain text.")
            }.trim()
        }

        private fun shouldGateCompletionForMemory(content: String): Boolean {
            if (!_memoryDirty.value || _currentAgent.value == AgentRole.SUMMARIZER) return false
            if (_currentSessionId.value != null && _currentAgent.value != AgentRole.ORCHESTRATOR) return true

            val completionRegex = Regex(
                "\\b(done|complete(?:d)?|finish(?:ed)?|implemented|all set|ready for testing|task completed|hecho|completad[oa]|terminad[oa]|listo)\\b",
                RegexOption.IGNORE_CASE
            )
            return completionRegex.containsMatchIn(content)
        }

        private fun shouldMarkCommandAsMemoryDirty(command: String): Boolean {
            val normalized = command.lowercase()
            if (normalized.contains("ollama run hf.co/")) return false

            val patterns = listOf(
                Regex("""(^|\s)(mkdir|touch|cp|mv|rm|chmod|chown|ln|install)\b"""),
                Regex("""\b(sed\s+-i|perl\s+-0?pi|tee\s+)"""),
                Regex("""(^|\s)(git\s+(init|add|rm|mv|restore|checkout|commit))\b"""),
                Regex("""(^|\s)(npm|pnpm|yarn)\s+(install|add|remove)\b"""),
                Regex("""(^|\s)(pip|pip3)\s+install\b"""),
                Regex("""(^|\s)(cargo\s+add|go\s+get)\b"""),
                Regex(""">\s*[^ ]|>>\s*[^ ]""")
            )
            return patterns.any { it.containsMatchIn(normalized) }
        }
        
        // ========== SESSION MANAGEMENT (for context isolation) ==========
        private val _sessions = MutableStateFlow<Map<String, AgentSession>>(emptyMap())
        val sessions: StateFlow<Map<String, AgentSession>> = _sessions.asStateFlow()
        
        private val _currentSessionId = MutableStateFlow<String?>(null)
        val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()
        
        /**
         * Start a new agent session with isolated context
         * @param agentType The agent type (ORCHESTRATOR, CODER, etc.)
         * @param parentId Parent session ID (null for orchestrator)
         * @param input Task description from parent agent
         * @param context Additional context from parent
         * @return The new session ID
         */
        fun startSession(agentType: String, parentId: String? = null, input: String? = null, context: String? = null): String {
            val session = AgentSession(
                agentType = agentType,
                parentSessionId = parentId,
                inputFromParent = input,
                contextFromParent = context,
                contract = buildAgentContract(agentType)
            )
            _sessions.value = _sessions.value + (session.id to session)
            _currentSessionId.value = session.id
            addDebugLog("📂 Started session ${session.id.take(8)} for $agentType" + 
                (if (parentId != null) " (parent: ${parentId.take(8)})" else ""))
            recordAgentEvent("agent_session_start", "Started $agentType session", "Task: ${input ?: "no task provided"}")
            return session.id
        }
        
        /**
         * End current session and return to parent
         * @param summary Summary of what was accomplished to pass to parent
         */
        fun endSession(summary: String) {
            val currentId = _currentSessionId.value ?: return
            val currentSession = _sessions.value[currentId] ?: return
            val parentId = currentSession.parentSessionId
            
            addDebugLog("📂 Ending session ${currentId.take(8)} (${currentSession.agentType})")
            
            // Clean up ended session to save memory
            _sessions.value = _sessions.value - currentId
            _currentSessionId.value = parentId

            if (parentId != null) {
                // Add completion message to parent session
                addMessage(ChatMessage(
                    role = "user",
                    content = "✅ **${currentSession.agentType} Agent Finished:**\n\n$summary\n\nPlease continue with the next step or task."
                ))
                
                addDebugLog("📂 Returned to parent session ${parentId.take(8)}")
            }
            when {
                currentSession.agentType.equals("SUMMARIZER", ignoreCase = true) -> {
                    clearMemoryDirty("Summarizer session completed and refreshed project memory.")
                }
                summary.isNotBlank() && !summary.startsWith("ERROR", ignoreCase = true) -> {
                    markMemoryDirty("${currentSession.agentType} completed work that should be recorded in memory.")
                }
            }
            recordAgentEvent("agent_session_end", "Finished ${currentSession.agentType} session", summary)
        }
        
        /**
         * Get current session
         */
        fun getCurrentSession(): AgentSession? {
            return _sessions.value[_currentSessionId.value]
        }
        
        /**
         * Get messages for current session (for LLM context)
         * Returns only this session's messages, ensuring isolation
         */
        fun getCurrentSessionMessages(): List<ChatMessage> {
            val session = getCurrentSession() ?: return _messages.value  // Fallback to global
            
            // Start with input from parent if exists
            val sessionMessages = mutableListOf<ChatMessage>()
            session.inputFromParent?.let {
                sessionMessages.add(ChatMessage(
                    role = "user",
                    content = buildString {
                        append("**Task:** $it")
                        session.contextFromParent?.let { ctx -> append("\n\n**Context:** $ctx") }
                        session.contract?.let { contract -> append("\n\n**Execution Contract:** $contract") }
                    },
                    agentRole = session.parentSessionId?.let { pid -> _sessions.value[pid]?.agentType } ?: "USER"
                ))
            }
            
            // Add session's own messages
            sessionMessages.addAll(session.messages)
            return sessionMessages
        }
        
        /**
         * Add message to current session
         */
        fun addMessageToSession(message: ChatMessage) {
            getCurrentSession()?.addMessage(message)
        }
        
        /**
         * Clear all sessions (e.g., new conversation)
         */
        fun clearAllSessions() {
            _sessions.value = emptyMap()
            _currentSessionId.value = null
        }
        
        // Current project folder (for brain path)
        private val _currentProjectFolder = MutableStateFlow("default_project")
        val currentProjectFolder: StateFlow<String> = _currentProjectFolder.asStateFlow()
        
        fun setCurrentProjectFolder(folder: String) {
            _currentProjectFolder.value = folder
            initializedBrainProject = null
            ensureBrainScaffoldAsync()
            syncCurrentTaskMemoryAsync(_currentTask.value)
        }

        private fun rememberRuntimeRefs(
            context: Context,
            ollamaService: OllamaService,
            settingsRepo: com.example.llamadroid.data.SettingsRepository,
            agentService: AgentService
        ) {
            lastRuntimeRefs = AgentRuntimeRefs(context.applicationContext, ollamaService, settingsRepo, agentService)
        }
        
        // Active custom agent (persistent)
        private val _activeCustomAgent = MutableStateFlow<com.example.llamadroid.data.db.CustomAgentEntity?>(null)
        val activeCustomAgent: StateFlow<com.example.llamadroid.data.db.CustomAgentEntity?> = _activeCustomAgent.asStateFlow()
        
        fun setActiveCustomAgent(agent: com.example.llamadroid.data.db.CustomAgentEntity?) {
            _activeCustomAgent.value = agent
        }
        
        // Helper to get brain path for current project
        fun getBrainPath(): String = "$WORKSPACE_PATH/${_currentProjectFolder.value}/brain"

        private fun shouldTrackMessageAsCurrentTask(message: ChatMessage): Boolean {
            if (message.role != "user") return false
            val trimmed = message.content.trim()
            if (trimmed.isBlank()) return false
            return !trimmed.startsWith("✅ **") && !trimmed.startsWith("Approved tool:")
        }

        private fun ensureBrainScaffoldAsync() {
            val projectFolder = _currentProjectFolder.value
            if (initializedBrainProject == projectFolder) return
            val svc = activeInstance ?: return
            agentScope.launch(Dispatchers.IO) {
                svc.ensureStructuredBrainFiles()
                    .onSuccess { initializedBrainProject = projectFolder }
                    .onFailure { addDebugLog("⚠️ Failed to ensure brain scaffold: ${it.message}") }
            }
        }

        private fun syncCurrentTaskMemoryAsync(task: String?) {
            val svc = activeInstance ?: return
            agentScope.launch(Dispatchers.IO) {
                svc.ensureStructuredBrainFiles()
                    .onSuccess { initializedBrainProject = _currentProjectFolder.value }
                    .onFailure { addDebugLog("⚠️ Failed to ensure brain scaffold before task sync: ${it.message}") }
                svc.syncCurrentTaskMemory(task)
                    .onFailure { addDebugLog("⚠️ Failed to sync current_task.md: ${it.message}") }
            }
        }

        fun recordAgentEvent(kind: String, summary: String, details: String? = null, persist: Boolean = true) {
            val event = AgentEvent(
                kind = kind,
                summary = extractSummarySnippet(summary, 220),
                details = details?.takeIf { it.isNotBlank() }?.let { extractSummarySnippet(it, 400) }
            )
            synchronized(eventTimelineDeque) {
                if (eventTimelineDeque.size >= 200) eventTimelineDeque.removeFirst()
                eventTimelineDeque.addLast(event)
                _eventTimeline.value = eventTimelineDeque.toList()
            }
            if (!persist) return

            val svc = activeInstance ?: return
            agentScope.launch(Dispatchers.IO) {
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(event.timestamp))
                val entry = buildString {
                    append("- $timestamp | ${event.kind} | ${event.summary}")
                    event.details?.let {
                        appendLine()
                        append("  details: $it")
                    }
                }
                svc.ensureStructuredBrainFiles()
                    .onFailure { addDebugLog("⚠️ Failed to ensure brain scaffold before timeline write: ${it.message}") }
                svc.writeMemory("timeline.md", entry, countsAsMemoryUpdate = false)
                    .onFailure { addDebugLog("⚠️ Failed to append timeline event: ${it.message}") }
            }
        }

        private fun buildAgentContract(agentType: String): String {
            return when (agentType.uppercase()) {
                "CODER" -> "Return via finish_task with: files changed, verification reads performed, remaining risks, and a memory update written before finishing."
                "REVIEWER" -> "Return via finish_task with findings first, file/line references, an explicit approval or block recommendation, and a memory update written before finishing."
                "EXECUTOR" -> "Return via finish_task with commands run, command IDs if any, observed results, the next recommended action, and a memory update written before finishing."
                "SUMMARIZER" -> "Return via finish_task with the memory files updated and what future sessions should know first."
                else -> "Return via finish_task with a concise outcome summary, evidence collected, the next recommended step, and a memory update written before finishing."
            }
        }

        private fun resolvePromptPackingProfile(model: String, role: AgentRole, contextSize: Int): PromptPackingProfile {
            val lowerModel = model.lowercase()
            val baseProfile = when {
                lowerModel.contains("thinking") || lowerModel.contains("deepseek-r1") || lowerModel.contains("qwq") || lowerModel.contains("r1") ->
                    PromptPackingProfile("thinking", 0.55, 8, 16, 12, 4, 1500, 550, 1700, 650, 16, 7)
                lowerModel.contains("coder") || lowerModel.contains("codestral") || lowerModel.contains("qwen") ->
                    PromptPackingProfile("coder", 0.70, 12, 14, 10, 4, 1900, 850, 2000, 800, 20, 9)
                contextSize <= 8192 || lowerModel.contains("small") || lowerModel.contains(":1b") || lowerModel.contains(":2b") ->
                    PromptPackingProfile("compact", 0.50, 7, 10, 8, 3, 1200, 450, 1300, 500, 12, 6)
                else ->
                    PromptPackingProfile("balanced", 0.65, 10, 12, 10, 4, 1800, 700, 1800, 700, 18, 8)
            }

            return when (role) {
                AgentRole.SUMMARIZER -> baseProfile.copy(promptContextRatio = (baseProfile.promptContextRatio + 0.05).coerceAtMost(0.75))
                AgentRole.REVIEWER -> baseProfile.copy(digestItems = (baseProfile.digestItems + 2).coerceAtMost(18))
                AgentRole.EXECUTOR -> baseProfile.copy(toolRecentChars = baseProfile.toolRecentChars + 400, toolOldChars = baseProfile.toolOldChars + 200)
                else -> baseProfile
            }
        }
        
        // Dangerous commands that are always blocked
        val BLOCKED_COMMANDS = listOf(
            "rm -rf /",
            "rm -rf /*",
            "dd if=",
            "mkfs",
            ":(){ :|:& };:",
            "> /dev/sda",
            "chmod -R 777 /",
            "mv /* /dev/null"
        )
        
        // ========== STATIC/SINGLETON STATE (persists across navigation) ==========
        // This is SEPARATE from SSHService - uses port 8023 vs 8022
        
        private val jsch = com.jcraft.jsch.JSch()
        // llama-server chat service instance
        private val llamaServerChatService = LlamaServerChatService()
        private const val MAX_COMMAND_TAIL_LINES = 200
        // Active instance reference for companion methods needing SSH
        @Volatile
        var activeInstance: AgentService? = null
        @Volatile
        private var initializedBrainProject: String? = null
        private data class AgentRuntimeRefs(
            val context: Context,
            val ollamaService: OllamaService,
            val settingsRepo: com.example.llamadroid.data.SettingsRepository,
            val agentService: AgentService
        )
        private data class PackedPromptContext(
            val messages: List<ChatMessage>,
            val omittedCount: Int,
            val estimatedTokens: Int,
            val thresholdTriggered: Boolean = false,
            val didCompactHistory: Boolean = false,
            val compactionPasses: Int = 1
        )
        private data class PromptPackingProfile(
            val name: String,
            val promptContextRatio: Double,
            val recentMessages: Int,
            val digestItems: Int,
            val reminderInterval: Int,
            val refreshReminderEvery: Int,
            val assistantRecentChars: Int,
            val assistantOldChars: Int,
            val toolRecentChars: Int,
            val toolOldChars: Int,
            val recentLines: Int,
            val oldLines: Int
        ) {
            fun forRecovery(): PromptPackingProfile = copy(
                promptContextRatio = (promptContextRatio * 0.85).coerceAtLeast(0.45),
                recentMessages = (recentMessages - 2).coerceAtLeast(5),
                digestItems = (digestItems + 2).coerceAtMost(20),
                assistantRecentChars = (assistantRecentChars * 0.8).toInt().coerceAtLeast(900),
                toolRecentChars = (toolRecentChars * 0.8).toInt().coerceAtLeast(1000),
                reminderInterval = (reminderInterval - 2).coerceAtLeast(6)
            )

            fun moreAggressive(): PromptPackingProfile = copy(
                promptContextRatio = (promptContextRatio * 0.82).coerceAtLeast(0.35),
                recentMessages = (recentMessages - 2).coerceAtLeast(4),
                digestItems = (digestItems + 2).coerceAtMost(24),
                reminderInterval = (reminderInterval - 1).coerceAtLeast(5),
                refreshReminderEvery = (refreshReminderEvery - 1).coerceAtLeast(3),
                assistantRecentChars = (assistantRecentChars * 0.82).toInt().coerceAtLeast(700),
                assistantOldChars = (assistantOldChars * 0.8).toInt().coerceAtLeast(320),
                toolRecentChars = (toolRecentChars * 0.82).toInt().coerceAtLeast(850),
                toolOldChars = (toolOldChars * 0.8).toInt().coerceAtLeast(380),
                recentLines = (recentLines - 2).coerceAtLeast(6),
                oldLines = (oldLines - 1).coerceAtLeast(3)
            )
        }
        data class AgentEvent(
            val id: String = java.util.UUID.randomUUID().toString(),
            val kind: String,
            val summary: String,
            val details: String? = null,
            val timestamp: Long = System.currentTimeMillis(),
            val sequenceNumber: Int = _eventCounter.incrementAndGet()
        )
        
        private var session: com.jcraft.jsch.Session? = null
        private var lastConnectionHost: String = "localhost"
        private var lastConnectionPort: Int = AI_AGENT_SSH_PORT
        private var lastConnectionUser: String = AI_AGENT_USER
        private var lastConnectionPassword: String = "agent"
        private var lastRuntimeRefs: AgentRuntimeRefs? = null
        private val eventTimelineDeque = java.util.ArrayDeque<AgentEvent>(200)
        private val _eventTimeline = MutableStateFlow<List<AgentEvent>>(emptyList())
        val eventTimeline: StateFlow<List<AgentEvent>> = _eventTimeline.asStateFlow()
        
        // Mutex for synchronized SSH session access (prevents race conditions)
        private val sshMutex = Mutex()
        
        // Global connection state (persists across navigation)
        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
        
        private val _lastCommandOutput = MutableStateFlow("")
        val lastCommandOutput: StateFlow<String> = _lastCommandOutput.asStateFlow()
        
        // ========== CHAT STATE (persists across navigation) ==========
        private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
        val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
        
        // High-frequency streaming states to avoid recomposing the entire ChatList
        private val _streamingContent = MutableStateFlow("")
        val streamingContent: StateFlow<String> = _streamingContent.asStateFlow()
        
        private val _streamingThinking = MutableStateFlow("")
        val streamingThinking: StateFlow<String> = _streamingThinking.asStateFlow()
        
        private val _streamingMessageId = MutableStateFlow<String?>(null)
        val streamingMessageId: StateFlow<String?> = _streamingMessageId.asStateFlow()
        
        private var currentChatJob: Job? = null
        
        private val _selectedModel = MutableStateFlow("qwen3.5:9b")
        val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()
    
        // Persistent scope for AI jobs (continues in background)
        // Use Dispatchers.IO to avoid blocking main thread with SSH operations
        val agentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var heartbeatJob: Job? = null

        private fun pushBackgroundCommandUpdate(content: String) {
            val refs = lastRuntimeRefs ?: return
            addMessage(ChatMessage(role = "system", content = content))
            sendMessage(refs.context, refs.ollamaService, refs.settingsRepo, refs.agentService)
        }
        
        /**
         * Start SSH heartbeat to keep connection alive
         * Sends a lightweight command every 30 seconds
         */
        fun startHeartbeat(agentService: AgentService) {
            heartbeatJob?.cancel()
            heartbeatJob = agentScope.launch {
                while (isActive) {
                    delay(SSH_HEARTBEAT_INTERVAL_MS.toLong())
                    if (session?.isConnected == true) {
                        try {
                            // Send lightweight keepalive command - SILENTLY
                            agentService.executeRawCommand("true", isHeartbeat = true)
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                        } catch (e: Exception) {
                            addDebugLog("💔 SSH heartbeat failed: ${e.message?.take(50)}")
                            _isConnected.value = false
                            _connectionStatus.value = ConnectionStatus.DISCONNECTED
                            startScalingRetry(agentService)
                        }
                    } else if (_isConnected.value) {
                        // We think we are connected but jsch says no
                        _isConnected.value = false
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        startScalingRetry(agentService)
                    }
                }
            }
            addDebugLog("💓 SSH heartbeat started (${SSH_HEARTBEAT_INTERVAL_MS / 1000}s interval)")
        }
        
        fun stopHeartbeat() {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }
        
        // ========== CONNECTION STATUS & SCALING RETRY ==========
        enum class ConnectionStatus {
            UNKNOWN,      // Initial state - don't show bar
            CONNECTED,
            CONNECTING,
            DISCONNECTED,
            RECONNECTING
        }
        
        private val _connectionStatus = MutableStateFlow(ConnectionStatus.UNKNOWN)
        val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()
        
        private val _retryMessage = MutableStateFlow<String?>(null)
        val retryMessage: StateFlow<String?> = _retryMessage.asStateFlow()
        
        private var retryJob: Job? = null
        private val retryIntervals = listOf(1L, 5L, 10L, 30L, 60L, 300L) // in seconds
        
        fun startScalingRetry(agentService: AgentService) {
            if (retryJob?.isActive == true) return
            
            retryJob = agentScope.launch {
                _connectionStatus.value = ConnectionStatus.RECONNECTING
                for ((index, interval) in retryIntervals.withIndex()) {
                    // Count down for the message
                    for (i in interval downTo 1) {
                        if (session?.isConnected == true) {
                            _connectionStatus.value = ConnectionStatus.CONNECTED
                            _retryMessage.value = null
                            return@launch
                        }
                        val appContext = com.example.llamadroid.LlamaApplication.instance
                        _retryMessage.value = appContext.getString(R.string.agent_retry_message, i, index + 1, retryIntervals.size)
                        delay(1000)
                    }
                    
                    val appContext = com.example.llamadroid.LlamaApplication.instance
                    _retryMessage.value = appContext.getString(R.string.agent_connecting)
                    agentService.connect().onSuccess {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        _retryMessage.value = null
                        addDebugLog("✨ Reconnected successfully!")
                        return@launch
                    }.onFailure {
                        addDebugLog("📡 Reconnection attempt ${index + 1} failed")
                    }
                }
                
                // All retries failed
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                val appContext = com.example.llamadroid.LlamaApplication.instance
                _retryMessage.value = appContext.getString(R.string.agent_retry_failed)
                delay(5000)
                _retryMessage.value = null // Clear message but keep status DISCONNECTED
            }
        }
        
        private var wakeLock: PowerManager.WakeLock? = null
        
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = com.example.llamadroid.LlamaApplication.instance.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AI-Doomsday:AgentTask")
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(30 * 60 * 1000L) // 30 min max
                addDebugLog("🔋 WakeLock acquired for background task")
            }
        } catch (e: Exception) {
            addDebugLog("⚠️ Failed to acquire WakeLock: ${e.message}")
        }
    }
    
    private fun releaseWakeLock() {
        try {
            // Force release even if isHeld check might be unreliable
            wakeLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    addDebugLog("🔋 WakeLock released")
                }
            }
        } catch (e: Exception) {
            // Force release attempt failed - log but don't crash
            addDebugLog("⚠️ WakeLock release failed: ${e.message}")
        }
    }

        fun setSelectedModel(model: String) {
            _selectedModel.value = model
        }
        
        fun stopAllJobs() {
            // Signal OllamaService to stop any active HTTP streams
            OllamaService.stop()
            
            currentChatJob?.cancel()
            currentChatJob = null
            // Reset to Orchestrator so user can type again
            _currentAgent.value = AgentRole.ORCHESTRATOR
            setCurrentTask(null)
            
            // Force reset reference counter
            loadingRefCount.set(0)
            
            // Ensure WakeLock is released (defensive call before setIsLoading)
            releaseWakeLock()
            val appContext = com.example.llamadroid.LlamaApplication.instance
            setIsLoading(false, appContext.getString(R.string.agent_status_interrupted))
            addDebugLog("🛑 All jobs stopped by user. Reset to Orchestrator.")
            recordAgentEvent("agent_stop", "Stopped all running agent work", "User interrupted the active workflow.")
            // Find the last streaming message and mark it as finished
            _messages.value.findLast { it.isStreaming }?.let { lastMsg ->
                updateMessage(lastMsg.id) { it.copy(content = it.content + " [" + appContext.getString(R.string.agent_status_interrupted) + "]", isStreaming = false) }
            }
            
            // Add visible message in chat UI
            addMessage(ChatMessage(
                role = "system",
                content = appContext.getString(R.string.agent_process_stopped)
            ))
            
            _streamingContent.value = ""
            _streamingThinking.value = ""
            _streamingMessageId.value = null
            
            // Reset the stop flag for next request
            OllamaService.resetStop()
            activeInstance?.completeAgentRuntimeState(AiRuntimeJobStore.STATUS_CANCELLED)
        }
        
        
        fun addMessage(message: ChatMessage) {
            _messages.value = _messages.value + message
            // AUTOMATICALLY add to current session history if one is active
            getCurrentSession()?.addMessage(message)
            if (shouldTrackMessageAsCurrentTask(message)) {
                setCurrentTask(message.content)
                recordAgentEvent("user_request", "Updated current task from user message", message.content)
            } else if (message.role == "tool" && message.toolName != null) {
                recordAgentEvent("tool_result", "${message.toolName} returned a result", message.content, persist = false)
            }
        }
        
        fun setMessages(messages: List<ChatMessage>) {
            _messages.value = messages
        }
        
        fun clearMessages() {
            _messages.value = emptyList()
            _promptContextSnapshot.value = null
            synchronized(recentCompactionEvents) {
                recentCompactionEvents.clear()
            }
        }
        
        fun updateMessage(id: String, update: (ChatMessage) -> ChatMessage) {
            _messages.value = _messages.value.map { if (it.id == id) update(it) else it }
            // ALSO update in session list if it exists there - use safe replacement
            getCurrentSession()?.let { session ->
                synchronized(session.messages) {
                    val index = session.messages.indexOfFirst { it.id == id }
                    if (index != -1) {
                        val updated = update(session.messages[index])
                        session.messages[index] = updated
                    }
                }
            }
        }
        
        fun toggleMessageOutput(id: String) {
            updateMessage(id) { it.copy(isOutputExpanded = !it.isOutputExpanded) }
        }
        
        fun handlePlanModified(context: Context, ollamaService: OllamaService, settingsRepo: com.example.llamadroid.data.SettingsRepository, agentService: AgentService, id: String, newContent: String) {
            updateMessage(id) { 
                it.copy(
                    content = newContent, 
                    planModifiedContent = newContent,
                    isPlanApproved = true 
                )
            }
            
            // Send feedback to agent
            agentScope.launch {
                addMessage(ChatMessage(
                    role = "user",
                    content = "I have modified the implementation plan. Please proceed with this updated version:\n\n$newContent"
                ))
                markMemoryDirty("The implementation plan was modified. Record the chosen direction in project memory before finishing.")
                sendMessage(context, ollamaService, settingsRepo, agentService)
            }
        }
        
        private var persistentShell: com.jcraft.jsch.ChannelShell? = null
        private var shellInput: java.io.OutputStream? = null
        private val _activeTerminalMessageId = MutableStateFlow<String?>(null)
        private val llmOutputCollector = StringBuilder()
        private var isWaitingForSentinel = false

        private val _terminalInput = MutableStateFlow<Pair<String, String>?>(null) // id to input
        val terminalInput = _terminalInput.asStateFlow()
        
        fun sendTerminalInput(id: String, input: String) {
            _terminalInput.value = id to input
            val svc = activeInstance ?: return
            agentScope.launch(Dispatchers.IO) {
                val command = svc.activeCommands.values.find { it.terminalMessageId == id }
                if (command == null) {
                    addDebugLog("⚠️ No running command found for terminal message $id")
                    return@launch
                }
                svc.sendCommandInput(command.id, input)
                    .onFailure { addDebugLog("⚠️ Failed to send terminal input: ${it.message}") }
            }
        }

        private fun updateTerminalOutput(id: String, output: String) {
            updateMessage(id) { it.copy(terminalOutput = (it.terminalOutput ?: "") + output) }
        }
        
        fun deleteMessage(id: String) {
            _messages.value = _messages.value.filter { it.id != id }
            // ALSO remove from session
            getCurrentSession()?.let { session ->
                session.messages.removeAll { it.id == id }
            }
        }
        
        // ========== DEBUG LOG (for tracking agent/tool calls) ==========
        private val debugLogDeque = java.util.ArrayDeque<String>(50)
        private val _debugLog = MutableStateFlow<List<String>>(emptyList())
        val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

        fun addDebugLog(entry: String) {
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            synchronized(debugLogDeque) {
                if (debugLogDeque.size >= 50) debugLogDeque.removeFirst()
                debugLogDeque.addLast("[$timestamp] $entry")
                _debugLog.value = debugLogDeque.toList()
            }
        }
        
        fun clearDebugLog() {
            synchronized(debugLogDeque) {
                debugLogDeque.clear()
                _debugLog.value = emptyList()
            }
        }
        
        fun truncateHistoryAt(id: String, inclusive: Boolean = true) {
            val index = _messages.value.indexOfFirst { it.id == id }
            if (index != -1) {
                _messages.value = _messages.value.take(if (inclusive) index else index + 1)
            }
        }
        
        // ========== CUSTOM TOOLS (loaded from database) ==========
        private val _loadedCustomTools = MutableStateFlow<List<com.example.llamadroid.data.db.CustomToolEntity>>(emptyList())
        val loadedCustomTools: StateFlow<List<com.example.llamadroid.data.db.CustomToolEntity>> = _loadedCustomTools.asStateFlow()
        
        fun setLoadedCustomTools(tools: List<com.example.llamadroid.data.db.CustomToolEntity>) {
            _loadedCustomTools.value = tools
            addDebugLog("📦 Loaded ${tools.size} custom tools")
            // Regenerate tools_reference.md when custom tools change
            writeToolsReference()
        }
        
        /**
         * Generate tools_reference.md in the brain folder with full documentation,
         * examples, and parameter descriptions for all available tools.
         * Called at conversation start and when custom tools change.
         */
        private fun buildToolsReferenceContent(): String {
            val tools = getAgentTools()
            val customTools = _loadedCustomTools.value

            return buildString {
                appendLine("# Tools Reference")
                appendLine()
                appendLine("This file documents all tools available to you. Read this when unsure about what tools exist or how to call them.")
                appendLine("If you forget the syntax, call `read_file` with path `brain/tools_reference.md` using a real structured tool call.")
                appendLine("Example: `{\"name\": \"read_file\", \"arguments\": {\"path\": \"brain/tools_reference.md\"}}`")
                appendLine("`read_file` returns up to 160 lines by default and at most 400 lines per call. If it returns `has_more: true`, continue with the returned `next_start_line`.")
                appendLine("If `read_file` returns `has_more: true`, keep reading with the returned `next_start_line` until you have the section you need.")
                appendLine("Tool calls must be emitted outside `<think>` blocks, markdown fences, and plain assistant text. JSON written inside `<think>` does NOT count as a tool call.")
                appendLine("Structured brain files available by default: `summary.md`, `current_task.md`, `todo.md`, `decisions.md`, `changed_files.md`, and `timeline.md`.")
                appendLine("Before mutating a file, read its current state first. After a write, edit, or patch, reread that file or consult `changed_files.md` before touching it again.")
                appendLine()
                appendLine("## Standard Tools")
                appendLine()
                
                for (tool in tools) {
                    appendLine("### ${tool.name}")
                    appendLine("**Purpose:** ${tool.description}")
                    appendLine()
                    if (tool.parameters.isNotEmpty()) {
                        appendLine("**Parameters:**")
                        for ((param, desc) in tool.parameters) {
                            val required = if (param in tool.requiredParams) " *(required)*" else " *(optional)*"
                            appendLine("- `$param`$required: $desc")
                        }
                        appendLine()
                    }
                    // Add usage examples for key tools
                    appendLine("**Example:**")
                    when (tool.name) {
                        else -> appendLine("```json\n${toolExampleJson(tool.name, tool)}\n```")
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
                
                if (customTools.isNotEmpty()) {
                    appendLine("## Custom Tools")
                    appendLine()
                    for (ct in customTools) {
                        appendLine("### ${ct.name}")
                        appendLine("**Purpose:** ${ct.description}")
                        appendLine()
                        appendLine("**Command template:** `${ct.commandTemplate}`")
                        appendLine()
                        appendLine("---")
                        appendLine()
                    }
                }
            }
        }

        private fun toolExampleJson(toolName: String, tool: AgentTool? = getAgentTools().find { it.name == toolName }): String {
            return when (toolName) {
                "read_file" -> "{\"name\": \"read_file\", \"arguments\": {\"path\": \"src/main.py\", \"start_line\": \"1\", \"max_lines\": \"160\"}}"
                "write_file" -> "{\"name\": \"write_file\", \"arguments\": {\"path\": \"src/utils.py\", \"content\": \"def hello():\\n    return 'world'\"}}"
                "run_command" -> "{\"name\": \"run_command\", \"arguments\": {\"command\": \"ls -la src/\", \"lines\": \"10\"}}"
                "check_command" -> "{\"name\": \"check_command\", \"arguments\": {\"command_id\": \"cmd_1234567890\", \"lines\": \"50\"}}"
                "wait_command" -> "{\"name\": \"wait_command\", \"arguments\": {\"command_id\": \"cmd_1234567890\", \"wait_seconds\": \"10\", \"lines\": \"25\"}}"
                "command_list" -> "{\"name\": \"command_list\", \"arguments\": {}}"
                "cancel_command" -> "{\"name\": \"cancel_command\", \"arguments\": {\"command_id\": \"cmd_1234567890\"}}"
                "send_command_input" -> "{\"name\": \"send_command_input\", \"arguments\": {\"command_id\": \"cmd_1234567890\", \"input\": \"y\", \"append_newline\": \"true\"}}"
                "list_directory" -> "{\"name\": \"list_directory\", \"arguments\": {\"path\": \"src/components\"}}"
                "search_code" -> "{\"name\": \"search_code\", \"arguments\": {\"query\": \"TODO\", \"file_pattern\": \"*.py\"}}"
                "edit_lines" -> "{\"name\": \"edit_lines\", \"arguments\": {\"path\": \"src/main.py\", \"start_line\": \"5\", \"end_line\": \"7\", \"new_content\": \"new line 5\\nnew line 6\"}}"
                "apply_patch" -> "{\"name\": \"apply_patch\", \"arguments\": {\"patch\": \"--- app/src/main.py\\n+++ app/src/main.py\\n@@\\n-print('old')\\n+print('new')\"}}"
                "read_memory" -> "{\"name\": \"read_memory\", \"arguments\": {\"filename\": \"summary.md\"}}"
                "write_memory" -> "{\"name\": \"write_memory\", \"arguments\": {\"filename\": \"summary.md\", \"content\": \"## Session 3\\n- Fixed login bug\"}}"
                "rewrite_memory" -> "{\"name\": \"rewrite_memory\", \"arguments\": {\"filename\": \"summary.md\", \"content\": \"# Project Summary\\nConsolidated notes...\"}}"
                "delete_memory" -> "{\"name\": \"delete_memory\", \"arguments\": {\"filename\": \"summary.md\", \"start_line\": \"5\", \"end_line\": \"10\"}}"
                "list_memory" -> "{\"name\": \"list_memory\", \"arguments\": {}}"
                "fetch_url" -> "{\"name\": \"fetch_url\", \"arguments\": {\"url\": \"https://docs.python.org/3/\"}}"
                "get_datetime" -> "{\"name\": \"get_datetime\", \"arguments\": {}}"
                "file_line_count" -> "{\"name\": \"file_line_count\", \"arguments\": {\"path\": \"src/main.py\"}}"
                "read_file_lines" -> "{\"name\": \"read_file_lines\", \"arguments\": {\"path\": \"src/main.py\", \"start_line\": \"1\", \"end_line\": \"50\"}}"
                "web_search" -> "{\"name\": \"web_search\", \"arguments\": {\"query\": \"kotlin coroutines tutorial\"}}"
                "kiwix_search" -> "{\"name\": \"kiwix_search\", \"arguments\": {\"query\": \"binary search algorithm\"}}"
                "run_tools_sequential" -> "{\"name\": \"run_tools_sequential\", \"arguments\": {\"tools_json\": \"[{\\\"name\\\": \\\"read_file\\\", \\\"arguments\\\": {\\\"path\\\": \\\"a.py\\\"}}, {\\\"name\\\": \\\"read_file\\\", \\\"arguments\\\": {\\\"path\\\": \\\"b.py\\\"}}]\"}}"
                else -> {
                    val requiredJson = tool?.requiredParams
                        ?.joinToString(", ") { "\"$it\": \"...\"" }
                        .orEmpty()
                    "{\"name\": \"$toolName\", \"arguments\": {$requiredJson}}"
                }
            }
        }

        private fun buildSuspectedToolGuidance(toolName: String): String {
            val tool = getAgentTools().find { it.name.equals(toolName, ignoreCase = true) }
            if (tool == null) {
                return "It looks like you were trying to call `$toolName`, but that tool is not available to the current agent. Refresh `brain/tools_reference.md` to see the valid tools."
            }

            val required = tool.requiredParams.joinToString(", ").ifBlank { "none" }
            val optional = tool.parameters.keys.filterNot { it in tool.requiredParams }.joinToString(", ").ifBlank { "none" }
            val extraNote = when (tool.name) {
                "read_file" -> " `read_file` returns up to 160 lines by default and at most 400 lines per call. If `has_more: true`, call it again with `next_start_line`."
                "run_command", "wait_command", "check_command" -> " These command tools are the ones whose LLM output is intentionally line-limited."
                else -> ""
            }

            return buildString {
                append("It looks like you were trying to call `${tool.name}`. ")
                append("Required arguments: $required. Optional arguments: $optional. ")
                append("Use it like this: `${toolExampleJson(tool.name, tool)}`.")
                append(extraNote)
            }.trim()
        }

        fun writeToolsReference() {
            val svc = activeInstance ?: return // No instance available yet
            val content = buildToolsReferenceContent()

            // Write asynchronously via SSH (overwrites each time)
            agentScope.launch(Dispatchers.IO) {
                try {
                    val brainPath = getBrainPath()
                    val escaped = content.replace("'", "'\\''")
                    svc.executeRawCommand("mkdir -p '$brainPath' && echo '$escaped' > '$brainPath/tools_reference.md'")
                    addDebugLog("📄 tools_reference.md written to brain folder")
                } catch (e: Exception) {
                    addDebugLog("⚠️ Failed to write tools_reference.md: ${e.message}")
                }
            }
        }

        private fun buildRecoveryToolRefreshPrompt(): String {
            return buildString {
                appendLine("RECOVERY TOOL REFRESH:")
                appendLine("Your last response malformed a tool call or failed to emit one.")
                appendLine("Before retrying, read the tool reference file with a real structured tool call:")
                appendLine("`{\"name\": \"read_file\", \"arguments\": {\"path\": \"brain/tools_reference.md\"}}`")
                appendLine("After reading it, emit the next tool call as a real structured tool call outside `<think>`, markdown fences, and plain assistant text.")
            }
        }

        /**
         * The core agent loop: Send messages to LLM and handle tool calls
         */
        fun sendMessage(
            context: Context,
            ollamaService: OllamaService,
            settingsRepo: com.example.llamadroid.data.SettingsRepository,
            agentService: AgentService,
            isRedo: Boolean = false,
            recoveryInstruction: String? = null,
            recoveryMode: Boolean = false,
            queueBehindActiveJob: Boolean = true
        ): Job {
            rememberRuntimeRefs(context, ollamaService, settingsRepo, agentService)
            // Wait for previous job to finish before starting new cycle
            // This prevents silently dropping continuation calls after tool execution
            val existingJob = currentChatJob
            if (queueBehindActiveJob && existingJob?.isActive == true) {
                return agentScope.launch {
                    existingJob.join()
                    sendMessage(context, ollamaService, settingsRepo, agentService, isRedo, recoveryInstruction, recoveryMode).join()
                }
            }
            
            val currentAgent = _currentAgent.value
            val model = if (currentAgent == AgentRole.ORCHESTRATOR) {
                 settingsRepo.getAgentModelForRole("ORCHESTRATOR")
            } else {
                 settingsRepo.getAgentModelForRole(currentAgent.name)
            }
            
            if (model.isBlank()) {
                addDebugLog("⚠️ No model selected for role ${currentAgent.name}")
                return agentScope.launch { }
            }

            // Set context size for this agent
            val contextSize = settingsRepo.getAgentContextForRole(currentAgent.name)
            val promptProfile = resolvePromptPackingProfile(model, currentAgent, contextSize)
            ollamaService.setNumCtx(contextSize)

            currentChatJob = agentScope.launch {
                try {
                    setIsLoading(true, context.getString(R.string.agent_status_thinking))
                    
                    val currentAgent = _currentAgent.value
                    val activeCustom = _activeCustomAgent.value
                    
                    agentService.ensureStructuredBrainFiles()
                        .onFailure { addDebugLog("⚠️ Failed to ensure structured brain files: ${it.message}") }
                    agentService.syncCurrentTaskMemory(_currentTask.value)
                        .onFailure { addDebugLog("⚠️ Failed to sync current_task.md before prompting: ${it.message}") }
                    val structuredResumeState = agentService.buildStructuredBrainState().getOrNull()

                    // Ensure tools_reference.md is up to date at start of each message
                    writeToolsReference()
                    val recoveryToolRefresh = if (recoveryMode) buildRecoveryToolRefreshPrompt() else null
                    
                    // Build system prompt with specialized info
                    val standardToolNames = getAgentTools(currentAgent)
                        .filter { it.name !in _loadedCustomTools.value.map { ct -> ct.name } }
                        .joinToString(", ") { it.name }
                    val customToolNames = _loadedCustomTools.value.joinToString(", ") { it.name }.ifBlank { "none" }
                    
                    val baseSystemPrompt = activeCustom?.systemPrompt ?: currentAgent.systemPrompt
                    val fullSystemPrompt = buildString {
                        append(baseSystemPrompt)
                        append("\n\n**CONTEXT:**\n")
                        append("Your project path is: /workspace/${_currentProjectFolder.value}\n")
                        append("Your brain path is: ${getBrainPath()}\n")
                        append("Structured brain files: brain/summary.md, brain/current_task.md, brain/todo.md, brain/decisions.md, brain/changed_files.md, brain/timeline.md\n")
                        append("Available standard tools: $standardToolNames\n")
                        append("Available custom tools: $customToolNames\n")
                        append("Your complete tools reference with examples is at: brain/tools_reference.md (use read_file to refresh exact tool syntax)\n")
                        append("Command tools default to the last 10 lines. Increase the optional lines argument only when you need more context.\n")
                        append("When you need a tool, emit a real tool call. Do NOT place tool JSON inside <think>, markdown fences, or plain assistant text.\n")
                        append("Before writing or editing a file, read the current file state first. After you change a file, reread it or check brain/changed_files.md before editing it again.\n")
                        if (currentAgent == AgentRole.ORCHESTRATOR) {
                            append("As ORCHESTRATOR, always delegate specialist work through call_agent. Do not directly do coder, reviewer, executor, or summarizer work yourself.\n")
                        }
                        append("Verify edits with targeted reads, review, and focused build/test commands before claiming completion.\n")
                        append("Older history may be compacted into digests and action rationale notes to stay within the model context window. Context is auto-compacted before it reaches 80% of the selected window.\n")
                    }
                    
                    val msgs = getCurrentSessionMessages()
                    
                    // Inject compact reminders periodically to prevent model drift without resending large tool text
                    val userMsgCount = msgs.count { it.role == "user" }
                    val toolsRefReminder = if (!recoveryMode && userMsgCount > 0 && userMsgCount % promptProfile.refreshReminderEvery == 0) {
                        ChatMessage(role = "system", content = "REMINDER: Refresh tool syntax from brain/tools_reference.md and state from brain/summary.md plus brain/current_task.md when context feels stale. Use wait_command/check_command/command_list instead of rerunning active commands.")
                    } else null
                    
                    val messagesWithReminders = if (!recoveryMode && msgs.size > promptProfile.reminderInterval) {
                        val reminderContent = buildString {
                            append("REMINDER: Stay within the declared tools, keep structured brain files current, and verify changes before finishing. ")
                            if (currentAgent == AgentRole.ORCHESTRATOR) {
                                append("Delegate specialist work with call_agent. ")
                            }
                            append("Current project: /workspace/${_currentProjectFolder.value}. ")
                            append("Commands are tail-limited by default; request more lines only when needed.")
                        }
                        val reminder = ChatMessage(role = "system", content = reminderContent)
                        val result = mutableListOf<ChatMessage>()
                        msgs.forEachIndexed { index, msg ->
                            result.add(msg)
                            if ((index + 1) % promptProfile.reminderInterval == 0 && index < msgs.size - 1) {
                                result.add(reminder)
                            }
                        }
                        // Append tools_reference reminder at end if applicable
                        toolsRefReminder?.let { result.add(it) }
                        result
                    } else {
                        val result = msgs.toMutableList()
                        toolsRefReminder?.let { result.add(it) }
                        result
                    }
                    
                    val pinnedContextMessages = buildList {
                        add(ChatMessage(role = "system", content = fullSystemPrompt))
                        structuredResumeState?.takeIf { it.isNotBlank() }?.let { add(ChatMessage(role = "system", content = it)) }
                        buildMemoryGateSystemPrompt()?.let { add(ChatMessage(role = "system", content = it)) }
                        recoveryInstruction?.takeIf { it.isNotBlank() }?.let { add(ChatMessage(role = "system", content = "RECOVERY MODE: $it")) }
                        recoveryToolRefresh?.takeIf { it.isNotBlank() }?.let { add(ChatMessage(role = "system", content = it)) }
                    }
                    val fullMessages = pinnedContextMessages + messagesWithReminders
                    val rawEstimatedTokens = estimatePromptTokens(fullMessages)
                    val packedContext = packMessagesForContext(
                        fullMessages,
                        contextSize,
                        if (recoveryMode) promptProfile.forRecovery() else promptProfile
                    )
                    val exposePromptSnapshot = _currentSessionId.value == null || currentAgent == AgentRole.ORCHESTRATOR
                    if (exposePromptSnapshot) {
                        updatePromptContextSnapshot(rawEstimatedTokens, packedContext, contextSize, promptProfile.name)
                    }
                    addDebugLog(
                        "🧠 Packed context for ${if (exposePromptSnapshot) currentAgent.name else "background ${currentAgent.name}"}: " +
                            "raw=${fullMessages.size} packed=${packedContext.messages.size} " +
                            "omitted=${packedContext.omittedCount} estTokens=${packedContext.estimatedTokens}/$contextSize " +
                            "mode=${if (packedContext.didCompactHistory) "compacted" else "normalized"} " +
                            "passes=${packedContext.compactionPasses} profile=${promptProfile.name}${if (recoveryMode) ":recovery" else ""}" +
                            if (packedContext.thresholdTriggered) " auto-compact@${PROMPT_CONTEXT_AUTOCOMPACT_PERCENT}%" else " below-threshold"
                    )
                    
                    val assistantMsgId = java.util.UUID.randomUUID().toString()
                    val (assistantAgentRole, assistantCustomAgentName) = currentAssistantIdentity()
                    addMessage(ChatMessage(
                        id = assistantMsgId,
                        role = "assistant",
                        content = "",
                        isStreaming = true,
                        agentRole = assistantAgentRole,
                        customAgentName = assistantCustomAgentName
                    ))
                    
                    _streamingMessageId.value = assistantMsgId
                    var fullContent = ""
                    var fullThinking = ""
                    val thinkingEnabled = settingsRepo.getAgentThinkingEnabledForRole(activeCustom?.name ?: currentAgent.name)
                    
                    _streamingContent.value = ""
                    _streamingThinking.value = ""
                    
                    val response = if (settingsRepo.agentBackend.value == "llama-server") {
                        // Use llama-server (OpenAI-compatible API)
                        val llamaUrl = settingsRepo.llamaServerUrl.value
                        llamaServerChatService.chatWithToolsStreaming(
                            baseUrl = llamaUrl,
                            messages = packedContext.messages.map { it.toOllamaMessage(includeThinking = false) },
                            tools = getAgentTools(currentAgent),
                            thinkingEnabled = thinkingEnabled,
                            numCtx = ollamaService.numCtx.value,
                            onChunk = { chunk, thinkingChunk ->
                                chunk?.let {
                                    fullContent += it
                                    _streamingContent.value = fullContent
                                }
                                thinkingChunk?.let {
                                    if (thinkingEnabled) {
                                        fullThinking += it
                                        _streamingThinking.value = fullThinking
                                    }
                                }
                            }
                        )
                    } else {
                        // Use Ollama (default)
                        ollamaService.chatWithToolsStreaming(
                            model = model,
                            messages = packedContext.messages.map { it.toOllamaMessage(includeThinking = false) },
                            tools = getAgentTools(currentAgent),
                            thinkingEnabled = thinkingEnabled,
                            onChunk = { chunk, thinkingChunk ->
                                chunk?.let {
                                    fullContent += it
                                    _streamingContent.value = fullContent
                                }
                                thinkingChunk?.let {
                                    if (thinkingEnabled) {
                                        fullThinking += it
                                        _streamingThinking.value = fullThinking
                                    }
                                }
                            }
                        )
                    }
                    
                    response.onSuccess { chatResponse ->
                        val finalContent = chatResponse.message.content
                        val toolCall = chatResponse.message.toolCalls?.firstOrNull()
                        val recoveredToolAttempt = if (toolCall == null) {
                            recoverToolCallAttempt(finalContent, fullThinking)
                        } else null
                        val effectiveToolCall = toolCall ?: recoveredToolAttempt?.toolCall
                        
                        updateMessage(assistantMsgId) { it.copy(
                            content = finalContent, 
                            thinking = fullThinking,
                            isStreaming = false,
                            pendingToolCall = effectiveToolCall,
                            toolCallId = effectiveToolCall?.id,
                            toolName = effectiveToolCall?.name,
                            toolArgs = effectiveToolCall?.arguments,
                            isOutputExpanded = effectiveToolCall != null
                        ) }
                        
                        _streamingContent.value = ""
                        _streamingThinking.value = ""
                        _streamingMessageId.value = null
                        
                        when {
                            effectiveToolCall != null -> {
                                if (toolCall != null) {
                                    addDebugLog("🔧 Tool call detected: ${toolCall.name}")
                                } else {
                                    addDebugLog("🛠️ Recovered tool call from ${recoveredToolAttempt?.source ?: "assistant output"}: ${effectiveToolCall.name}")
                                }
                                executeToolCall(context, ollamaService, settingsRepo, agentService, effectiveToolCall).join()
                            }
                            recoveredToolAttempt?.attempted == true -> {
                                val malformedSummary = context.getString(R.string.agent_tool_recovery_failed_summary)
                                val malformedHint = context.getString(R.string.agent_tool_recovery_failed_hint)
                                addDebugLog("⚠️ Malformed tool call attempt detected in ${recoveredToolAttempt.source}: ${recoveredToolAttempt.error ?: malformedSummary}")
                                addMessage(ChatMessage(
                                    role = "tool",
                                    content = buildToolResultEnvelope(
                                        toolName = "tool_call_recovery",
                                        status = "error",
                                        summary = malformedSummary,
                                        nextHint = malformedHint
                                    )
                                ))
                                if (!recoveryMode) {
                                    sendMessage(
                                        context,
                                        ollamaService,
                                        settingsRepo,
                                        agentService,
                                        recoveryInstruction = buildToolCallRecoveryInstruction(
                                            recoveredToolAttempt.suspectedToolName,
                                            recoveredToolAttempt.error ?: "Your previous response attempted a tool call inside plain text, markdown, or <think>."
                                        ),
                                        recoveryMode = true,
                                        queueBehindActiveJob = false
                                    ).join()
                                } else if (_currentSessionId.value != null && currentAgent != AgentRole.ORCHESTRATOR) {
                                    val agentOutput = finalContent.ifBlank { malformedSummary }
                                    endSession(agentOutput)
                                } else {
                                    refreshIdleStatusIfNeeded()
                                }
                            }
                            finalContent.isBlank() -> {
                                val emptySummary = context.getString(R.string.agent_empty_response_summary)
                                val emptyHint = context.getString(R.string.agent_empty_response_hint)
                                addDebugLog("⚠️ Empty assistant response with no structured tool call")
                                addMessage(ChatMessage(
                                    role = "tool",
                                    content = buildToolResultEnvelope(
                                        toolName = "assistant_response",
                                        status = "error",
                                        summary = emptySummary,
                                        nextHint = emptyHint
                                    )
                                ))
                                if (!recoveryMode) {
                                    sendMessage(
                                        context,
                                        ollamaService,
                                        settingsRepo,
                                        agentService,
                                        recoveryInstruction = "Your previous response produced no visible answer and no structured tool call. First emit `{\"name\": \"read_file\", \"arguments\": {\"path\": \"brain/tools_reference.md\"}}`, then reply with either a concise answer or a proper structured tool call only. Tool calls must be emitted outside <think>, markdown fences, and plain text.",
                                        recoveryMode = true,
                                        queueBehindActiveJob = false
                                    ).join()
                                } else if (_currentSessionId.value != null && currentAgent != AgentRole.ORCHESTRATOR) {
                                    endSession(emptySummary)
                                } else {
                                    refreshIdleStatusIfNeeded()
                                }
                            }
                            shouldGateCompletionForMemory(finalContent) -> {
                                val memorySummary = context.getString(R.string.agent_memory_update_required_summary)
                                val memoryHint = context.getString(R.string.agent_memory_update_required_hint)
                                addDebugLog("🧠 Completion blocked until memory is updated")
                                addMessage(ChatMessage(
                                    role = "tool",
                                    content = buildToolResultEnvelope(
                                        toolName = "memory_gate",
                                        status = "error",
                                        summary = memorySummary,
                                        nextHint = memoryHint
                                    )
                                ))
                                if (!recoveryMode) {
                                    sendMessage(
                                        context,
                                        ollamaService,
                                        settingsRepo,
                                        agentService,
                                        recoveryInstruction = buildMemoryGateRecoveryInstruction(),
                                        recoveryMode = true,
                                        queueBehindActiveJob = false
                                    ).join()
                                } else {
                                    setStatusText(context.getString(R.string.agent_status_memory_update_required))
                                }
                            }
                            else -> {
                                // Loop check: if NOT orchestrator, return to parent
                                if (_currentSessionId.value != null && currentAgent != AgentRole.ORCHESTRATOR) {
                                    addDebugLog("🔙 Sub-agent finished. Returning to parent context.")
                                    val agentOutput = chatResponse.message.content
                                    endSession(agentOutput)
                                } else {
                                    refreshIdleStatusIfNeeded()
                                }
                            }
                        }
                    // Flush final content to UI (in case throttling skipped the last chunk)
                    updateMessage(assistantMsgId) { m -> m.copy(content = fullContent, thinking = fullThinking) }
                    
                    }.onFailure { e ->
                        if (e is kotlinx.coroutines.CancellationException) {
                            addDebugLog("🛑 Job cancelled.")
                            updateMessage(assistantMsgId) { it.copy(content = it.content + " [Interrupted]", isStreaming = false) }
                        } else {
                            addDebugLog("❌ LLM Error: ${e.message}")
                            val errorMessage = e.message ?: ""
                            updateMessage(assistantMsgId) {
                                val partialContent = (fullContent.ifBlank { it.content }).trimEnd()
                                val nextContent = if (partialContent.isNotBlank()) {
                                    partialContent + "\n\n" + context.getString(R.string.agent_stream_interrupted_suffix, errorMessage)
                                } else {
                                    context.getString(R.string.agent_error_prefix, errorMessage)
                                }
                                it.copy(content = nextContent, isStreaming = false)
                            }
                            // If sub-agent failed, return to orchestrator so user isn't stuck
                            if (currentAgent != AgentRole.ORCHESTRATOR) {
                                addDebugLog("🔙 Sub-agent ${currentAgent.name} failed. Returning to Orchestrator.")
                                endSession("ERROR: ${e.message}")
                                setCurrentAgent(AgentRole.ORCHESTRATOR)
                                setCurrentTask(null)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    addDebugLog("🛑 Agent job cancelled.")
                    // Find the assistant message and stop streaming
                    // (though it might already be handled by the Failure block above)
                } finally {
                    // Decrement ref count when thinking is done
                    setIsLoading(false)
                }
            }
            return currentChatJob!!
        }

        fun executeToolCall(
            context: Context,
            ollamaService: OllamaService,
            settingsRepo: com.example.llamadroid.data.SettingsRepository,
            agentService: AgentService,
            toolCall: OllamaService.ToolCall,
            isForced: Boolean = false // If true, ignore autoMode check
        ): Job {
            // Increment ref count immediately to bridge the gap
            setIsLoading(true, context.getString(R.string.agent_executing_tool, toolCall.name))

            return agentScope.launch {
                try {
                    var toolHandlesContinuation = false
                    val (assistantAgentRole, assistantCustomAgentName) = currentAssistantIdentity()
                    // Ref count already incremented
                    addDebugLog("🔧 Executing tool: ${toolCall.name}")
                    recordAgentEvent("tool_call", "Executing ${toolCall.name}", buildToolArgsPreview(toolCall.arguments))
                    syncAssistantToolProgress(toolCall)

                    validateToolCall(toolCall, _currentAgent.value)?.let { validationError ->
                        addDebugLog("⚠️ Invalid tool call ${toolCall.name}: $validationError")
                        recordAgentEvent("tool_invalid", "Invalid tool call ${toolCall.name}", validationError)
                        val invalidOutput = buildToolResultEnvelope(
                            toolName = toolCall.name,
                            status = "error",
                            summary = validationError,
                            nextHint = "Retry with a declared tool name and all required string arguments. If you need a reminder, first emit {\"name\": \"read_file\", \"arguments\": {\"path\": \"brain/tools_reference.md\"}}, then emit the tool call outside <think>."
                        )
                        syncAssistantToolProgress(toolCall, invalidOutput)
                        addMessage(ChatMessage(
                            role = "tool",
                            content = invalidOutput,
                            toolName = toolCall.name,
                            toolCallId = toolCall.id
                        ))
                        sendMessage(
                            context,
                            ollamaService,
                            settingsRepo,
                            agentService,
                            recoveryInstruction = buildToolCallRecoveryInstruction(toolCall.name, validationError),
                            recoveryMode = true,
                            queueBehindActiveJob = false
                        ).join()
                        return@launch
                    }

                    if (toolCall.name == "finish_task" && _memoryDirty.value && _currentAgent.value != AgentRole.SUMMARIZER) {
                        val gatedOutput = buildToolResultEnvelope(
                            toolName = "memory_gate",
                            status = "error",
                            summary = context.getString(R.string.agent_memory_update_required_summary),
                            nextHint = context.getString(R.string.agent_memory_update_required_hint)
                        )
                        syncAssistantToolProgress(toolCall, gatedOutput)
                        addMessage(ChatMessage(
                            role = "tool",
                            content = gatedOutput,
                            toolName = toolCall.name,
                            toolCallId = toolCall.id
                        ))
                        sendMessage(
                            context,
                            ollamaService,
                            settingsRepo,
                            agentService,
                            recoveryInstruction = buildMemoryGateRecoveryInstruction(),
                            recoveryMode = true,
                            queueBehindActiveJob = false
                        ).join()
                        return@launch
                    }

                    val result: Result<String> = try {
                        val outputStr: String = when (toolCall.name) {
                            "read_file" -> {
                                val path = toolCall.arguments["path"] ?: ""
                                val startLine = toolCall.arguments["start_line"]?.toIntOrNull() ?: 1
                                val maxLines = toolCall.arguments["max_lines"]?.toIntOrNull() ?: TOOL_READ_FILE_DEFAULT_LINES
                                agentService.readFileForTool(path, startLine, maxLines).getOrThrow()
                            }
                            "write_file" -> {
                                val path = toolCall.arguments["path"] ?: ""
                                val content = toolCall.arguments["content"] ?: ""
                                
                                if (!settingsRepo.autoMode.value && !isForced) {
                                    addMessage(ChatMessage(
                                        role = "assistant",
                                        content = context.getString(R.string.agent_request_write, path),
                                        toolName = toolCall.name,
                                        toolArgs = toolCall.arguments,
                                        needsApproval = true,
                                        pendingToolCall = toolCall,
                                        agentRole = assistantAgentRole,
                                        customAgentName = assistantCustomAgentName
                                    ))
                                    setStatusText(context.getString(R.string.agent_status_awaiting_approval))
                                    return@launch
                                }
                                
                                agentService.writeFile(path, content).getOrThrow()
                                markMemoryDirty("Updated file $path.")
                                context.getString(R.string.agent_file_written, path) + "\nREMINDER: Append what you just did and why to memory using write_memory."
                            }
                            "run_command" -> {
                                val command = toolCall.arguments["command"] ?: ""
                                val requestedLines = toolCall.arguments["lines"]?.toIntOrNull() ?: 10
                                val workingDirectory = toolCall.arguments["working_directory"]?.trim().orEmpty()
                                
                                // Only auto-run run_command if commandAutoAccept is enabled
                                // or if isForced (user clicked individual approve button)
                                if (!settingsRepo.commandAutoAccept.value && !isForced) {
                                    addMessage(ChatMessage(
                                        role = "assistant",
                                        content = context.getString(R.string.agent_request_command, command),
                                        toolName = toolCall.name,
                                        toolArgs = toolCall.arguments,
                                        needsApproval = true,
                                        pendingToolCall = toolCall,
                                        agentRole = assistantAgentRole,
                                        customAgentName = assistantCustomAgentName
                                    ))
                                    setStatusText(context.getString(R.string.agent_status_awaiting_approval))
                                    return@launch
                                }
                                
                                // Start interactive terminal session
                                val terminalMsg = ChatMessage(
                                    role = "assistant",
                                    content = context.getString(R.string.agent_executing_command, command),
                                    toolName = toolCall.name,
                                    toolArgs = toolCall.arguments,
                                    isTerminalVisible = true,
                                    isOutputExpanded = true,
                                    agentRole = assistantAgentRole,
                                    customAgentName = assistantCustomAgentName
                                )
                                val terminalId = terminalMsg.id
                                addMessage(terminalMsg)
                                
                                // Ensure we run in project root
                                val projectFolder = _currentProjectFolder.value ?: "default"
                                val projectPath = "$WORKSPACE_PATH/$projectFolder"
                                val safeCommand = if (workingDirectory.isBlank()) {
                                    command
                                } else {
                                    "cd '$projectPath/$workingDirectory' && $command"
                                }
                                
                                agentService.runInteractiveCommand(terminalId, safeCommand, requestedLines).getOrThrow()
                            }
                            "check_command" -> {
                                val commandId = toolCall.arguments["command_id"] ?: ""
                                val requestedLines = toolCall.arguments["lines"]?.toIntOrNull() ?: 10
                                agentService.checkCommand(commandId, requestedLines).getOrThrow()
                            }
                            "wait_command" -> {
                                val commandId = toolCall.arguments["command_id"] ?: ""
                                val waitSeconds = toolCall.arguments["wait_seconds"]?.toIntOrNull() ?: 10
                                val requestedLines = toolCall.arguments["lines"]?.toIntOrNull() ?: 10
                                agentService.waitCommand(commandId, waitSeconds, requestedLines).getOrThrow()
                            }
                            "command_list" -> {
                                agentService.listCommands().getOrThrow()
                            }
                            "cancel_command" -> {
                                val commandId = toolCall.arguments["command_id"] ?: ""
                                agentService.cancelCommand(commandId).getOrThrow()
                            }
                            "send_command_input" -> {
                                val commandId = toolCall.arguments["command_id"] ?: ""
                                val input = toolCall.arguments["input"] ?: ""
                                val appendNewline = toolCall.arguments["append_newline"]?.toBooleanStrictOrNull() ?: true
                                agentService.sendCommandInput(commandId, input, appendNewline).getOrThrow()
                            }
                            "list_directory" -> {
                                val files = agentService.listDirectory(toolCall.arguments["path"] ?: WORKSPACE_PATH).getOrThrow()
                                files.joinToString("\n") { f -> "${if (f.isDirectory) "📁" else "📄"} ${f.name}" }
                            }
                            "search_code" -> {
                                val results = agentService.searchCode(toolCall.arguments["query"] ?: "").getOrThrow()
                                results.joinToString("\n") { r -> "${r.path}:${r.lineNumber}: ${r.content}" }
                            }
                            "edit_lines" -> {
                                val path = toolCall.arguments["path"] ?: ""
                                val startLine = toolCall.arguments["start_line"]?.toIntOrNull() ?: 0
                                val endLine = toolCall.arguments["end_line"]?.toIntOrNull() ?: 0
                                val newContent = toolCall.arguments["new_content"] ?: ""
                                
                                if (!settingsRepo.autoMode.value && !isForced) {
                                    addMessage(ChatMessage(
                                        role = "assistant",
                                        content = context.getString(R.string.agent_request_edit_lines, path, startLine, endLine),
                                        toolName = toolCall.name,
                                        toolArgs = toolCall.arguments,
                                        needsApproval = true,
                                        pendingToolCall = toolCall,
                                        agentRole = assistantAgentRole,
                                        customAgentName = assistantCustomAgentName
                                    ))
                                    setStatusText(context.getString(R.string.agent_status_awaiting_approval))
                                    return@launch
                                }
                                
                                agentService.editLines(path, startLine, endLine, newContent).getOrThrow().also {
                                    markMemoryDirty("Edited lines in $path.")
                                } + "\nREMINDER: Append what you just did and why to memory using write_memory."
                            }
                            "apply_patch" -> {
                                val patch = toolCall.arguments["patch"] ?: ""

                                if (!settingsRepo.autoMode.value && !isForced) {
                                    val preview = patch.lineSequence().take(12).joinToString("\n").ifBlank { "[empty patch]" }
                                    addMessage(ChatMessage(
                                        role = "assistant",
                                        content = context.getString(R.string.agent_request_apply_patch, preview),
                                        toolName = toolCall.name,
                                        toolArgs = toolCall.arguments,
                                        needsApproval = true,
                                        pendingToolCall = toolCall,
                                        agentRole = assistantAgentRole,
                                        customAgentName = assistantCustomAgentName
                                    ))
                                    setStatusText(context.getString(R.string.agent_status_awaiting_approval))
                                    return@launch
                                }

                                agentService.applyPatch(patch).getOrThrow().also {
                                    markMemoryDirty("Applied a patch that changed project files.")
                                } + "\nREMINDER: Append what you just did and why to memory using write_memory."
                            }
                            "call_agent" -> {
                                val agentName = toolCall.arguments["agent"] ?: "CODER"
                                val task = toolCall.arguments["task"] ?: ""
                                val agentCtx = toolCall.arguments["context"] ?: ""
                                val parentSessionId = _currentSessionId.value
                                val parentAgent = _currentAgent.value
                                val parentTask = _currentTask.value
                                val parentCustomAgent = _activeCustomAgent.value
                                
                                // Check for built-in agents first (including SUMMARIZER)
                                val builtInRole = when (agentName.uppercase()) {
                                    "CODER" -> AgentRole.CODER
                                    "REVIEWER" -> AgentRole.REVIEWER
                                    "EXECUTOR" -> AgentRole.EXECUTOR
                                    "SUMMARIZER" -> AgentRole.SUMMARIZER
                                    else -> null
                                }
                                
                                if (builtInRole != null) {
                                    try {
                                        // Delegate to built-in agent
                                        val childSessionId = startSession(builtInRole.name, _currentSessionId.value, task, agentCtx)
                                        setCurrentTask(task)
                                        setCurrentAgent(builtInRole)
                                        
                                        // Recursive call - wait for the delegated agent to finish
                                        sendMessage(context, ollamaService, settingsRepo, agentService, queueBehindActiveJob = false).join()
                                        val childStillActive = _sessions.value.containsKey(childSessionId)
                                        if (!childStillActive) {
                                            _currentSessionId.value = parentSessionId
                                            _activeCustomAgent.value = parentCustomAgent
                                            setCurrentAgent(parentAgent)
                                            setCurrentTask(parentTask)
                                        } else {
                                            toolHandlesContinuation = true
                                        }
                                    } catch (e: Exception) {
                                        addDebugLog("❌ Agent delegation to $agentName failed: ${e.message}")
                                        // Recover: end the failed session and return to orchestrator
                                        endSession("ERROR: Agent $agentName failed: ${e.message}")
                                        setCurrentAgent(AgentRole.ORCHESTRATOR)
                                        setCurrentTask(null)
                                    }
                                } else {
                                    // Check custom agents
                                    val customAgent = _loadedCustomAgents.value.find { 
                                        it.name.equals(agentName, ignoreCase = true) && it.isEnabled 
                                    }
                                    if (customAgent != null) {
                                        try {
                                            addDebugLog("🤖 Delegating to custom agent: ${customAgent.displayName} (${customAgent.name})")
                                            // Start a session for the custom agent
                                            val childSessionId = startSession(customAgent.name, _currentSessionId.value, task, agentCtx)
                                            setCurrentTask(task)
                                            // Set current agent to CODER as base (custom agents use the same tool set)
                                            setCurrentAgent(AgentRole.CODER)
                                            // Store the custom agent info for system prompt override
                                            _activeCustomAgent.value = customAgent
                                            
                                            sendMessage(context, ollamaService, settingsRepo, agentService, queueBehindActiveJob = false).join()
                                            val childStillActive = _sessions.value.containsKey(childSessionId)
                                            if (!childStillActive) {
                                                _currentSessionId.value = parentSessionId
                                                _activeCustomAgent.value = parentCustomAgent
                                                setCurrentAgent(parentAgent)
                                                setCurrentTask(parentTask)
                                            } else {
                                                toolHandlesContinuation = true
                                            }
                                        } catch (e: Exception) {
                                            addDebugLog("❌ Custom agent ${customAgent.name} failed: ${e.message}")
                                            endSession("ERROR: Custom agent ${customAgent.name} failed: ${e.message}")
                                            setCurrentAgent(AgentRole.ORCHESTRATOR)
                                            setCurrentTask(null)
                                        }
                                    } else {
                                        addDebugLog("⚠️ Unknown agent: $agentName")
                                    }
                                }
                                
                                "Delegated to $agentName"
                            }
                            "propose_plan" -> {
                                val plan = toolCall.arguments["plan"] ?: ""
                                val summary = toolCall.arguments["summary"] ?: "Implementation Plan"
                                
                                val isAuto = settingsRepo.autoMode.value || isForced
                                
                                // Signal that a plan is pending approval by creating a special message
                                addMessage(ChatMessage(
                                    role = "assistant",
                                    content = "### Propose Plan: $summary\n\n$plan",
                                    isPlan = true,
                                    isPlanApproved = isAuto,
                                    toolCallId = toolCall.id,
                                    toolName = "propose_plan",
                                    agentRole = assistantAgentRole,
                                    customAgentName = assistantCustomAgentName
                                ))
                                
                                // PERSIST: Save the plan to the agent's brain folder immediately
                                agentService.writeMemory("plan.md", plan, countsAsMemoryUpdate = false)

                                if (!isAuto) {
                                    setStatusText(context.getString(R.string.agent_status_awaiting_approval))
                                    return@launch // Stop and wait for user approval
                                }

                                markMemoryDirty("An implementation plan was approved. Record the chosen direction in project memory before finishing.")
                                "Plan automatically approved. Proceeding with implementation."
                            }
                            "finish_task" -> {
                                val summary = toolCall.arguments["summary"] ?: "Completed"
                                if (_memoryDirty.value && _currentAgent.value != AgentRole.SUMMARIZER) {
                                    throw IllegalStateException(context.getString(R.string.agent_memory_update_required_summary))
                                }
                                addDebugLog("✅ Task finished: $summary")
                                if (_currentAgent.value == AgentRole.SUMMARIZER) {
                                    clearMemoryDirty("Summarizer finished after updating project memory.")
                                }
                                endSession(summary)
                                setCurrentAgent(AgentRole.ORCHESTRATOR)
                                setCurrentTask(null)
                                toolHandlesContinuation = true
                                // Return summary so orchestrator sees what was accomplished
                                "Task completed. Summary: $summary"
                            }
                            "fetch_url" -> {
                                val url = toolCall.arguments["url"] ?: ""
                                agentService.fetchUrl(url).getOrThrow()
                            }
                            "get_datetime" -> {
                                java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            }
                            "file_line_count" -> {
                                agentService.fileLineCount(toolCall.arguments["path"] ?: "").getOrThrow()
                            }
                            "read_file_lines" -> {
                                val path = toolCall.arguments["path"] ?: ""
                                val startLine = toolCall.arguments["start_line"]?.toIntOrNull() ?: 1
                                val endLine = toolCall.arguments["end_line"]?.toIntOrNull() ?: startLine + 50
                                agentService.readFileLines(path, startLine, endLine).getOrThrow()
                            }
                            "web_search" -> {
                                val query = toolCall.arguments["query"] ?: ""
                                agentService.webSearch(query, ollamaService, settingsRepo).getOrThrow()
                            }
                            "kiwix_search" -> {
                                val query = toolCall.arguments["query"] ?: ""
                                agentService.kiwixSearch(query, ollamaService, settingsRepo).getOrThrow()
                            }
                            "read_memory" -> {
                                agentService.readMemory(toolCall.arguments["filename"] ?: "").getOrThrow()
                            }
                            "write_memory" -> {
                                agentService.writeMemory(toolCall.arguments["filename"] ?: "", toolCall.arguments["content"] ?: "").getOrThrow()
                            }
                            "rewrite_memory" -> {
                                agentService.rewriteMemory(toolCall.arguments["filename"] ?: "", toolCall.arguments["content"] ?: "").getOrThrow()
                            }
                            "delete_memory" -> {
                                val fn = toolCall.arguments["filename"] ?: ""
                                val sl = toolCall.arguments["start_line"]?.toIntOrNull() ?: 1
                                val el = toolCall.arguments["end_line"]?.toIntOrNull() ?: sl
                                agentService.deleteMemoryLines(fn, sl, el).getOrThrow()
                            }
                            "list_memory" -> {
                                agentService.listMemory().getOrThrow()
                            }
                            "run_tools_sequential" -> {
                                val toolsJson = toolCall.arguments["tools_json"] ?: "[]"
                                val results = StringBuilder()
                                try {
                                    val toolsArray = org.json.JSONArray(toolsJson)
                                    for (i in 0 until toolsArray.length()) {
                                        val toolObj = toolsArray.getJSONObject(i)
                                        val toolName = toolObj.getString("name")
                                        val argsObj = toolObj.optJSONObject("arguments") ?: org.json.JSONObject()
                                        
                                        // Block approval-required tools in sequential batches
                                        if (toolName in listOf("write_file", "run_command", "edit_lines", "apply_patch", "cancel_command", "send_command_input", "call_agent", "propose_plan")) {
                                            results.append("[$toolName] ERROR: This tool must be called individually and cannot be used inside run_tools_sequential.\n")
                                            continue
                                        }
                                        
                                        val args = mutableMapOf<String, String>()
                                        argsObj.keys().forEach { key -> args[key] = argsObj.getString(key) }
                                        
                                        try {
                                            val output = when (toolName) {
                                                "read_file" -> {
                                                    val startLine = args["start_line"]?.toIntOrNull() ?: 1
                                                    val maxLines = args["max_lines"]?.toIntOrNull() ?: TOOL_READ_FILE_DEFAULT_LINES
                                                    agentService.readFileForTool(args["path"] ?: "", startLine, maxLines).getOrThrow()
                                                }
                                                "check_command" -> agentService.checkCommand(args["command_id"] ?: "", args["lines"]?.toIntOrNull() ?: 10).getOrThrow()
                                                "wait_command" -> agentService.waitCommand(args["command_id"] ?: "", args["wait_seconds"]?.toIntOrNull() ?: 10, args["lines"]?.toIntOrNull() ?: 10).getOrThrow()
                                                "command_list" -> agentService.listCommands().getOrThrow()
                                                "list_directory" -> {
                                                    val files = agentService.listDirectory(args["path"] ?: WORKSPACE_PATH).getOrThrow()
                                                    files.joinToString("\n") { f -> "${if (f.isDirectory) "📁" else "📄"} ${f.name}" }
                                                }
                                                "search_code" -> {
                                                    val searchResults = agentService.searchCode(args["query"] ?: "").getOrThrow()
                                                    searchResults.joinToString("\n") { r -> "${r.path}:${r.lineNumber}: ${r.content}" }
                                                }
                                                "file_line_count" -> agentService.fileLineCount(args["path"] ?: "").getOrThrow()
                                                "read_file_lines" -> {
                                                    val sLine = args["start_line"]?.toIntOrNull() ?: 1
                                                    val eLine = args["end_line"]?.toIntOrNull() ?: sLine + 50
                                                    agentService.readFileLines(args["path"] ?: "", sLine, eLine).getOrThrow()
                                                }
                                                "web_search" -> agentService.webSearch(args["query"] ?: "", ollamaService, settingsRepo).getOrThrow()
                                                "kiwix_search" -> agentService.kiwixSearch(args["query"] ?: "", ollamaService, settingsRepo).getOrThrow()
                                                "fetch_url" -> agentService.fetchUrl(args["url"] ?: "").getOrThrow()
                                                "read_memory" -> agentService.readMemory(args["filename"] ?: "").getOrThrow()
                                                "write_memory" -> agentService.writeMemory(args["filename"] ?: "", args["content"] ?: "").getOrThrow()
                                                "rewrite_memory" -> agentService.rewriteMemory(args["filename"] ?: "", args["content"] ?: "").getOrThrow()
                                                "delete_memory" -> {
                                                    val sl = args["start_line"]?.toIntOrNull() ?: 1
                                                    val el = args["end_line"]?.toIntOrNull() ?: sl
                                                    agentService.deleteMemoryLines(args["filename"] ?: "", sl, el).getOrThrow()
                                                }
                                                "list_memory" -> agentService.listMemory().getOrThrow()
                                                "get_datetime" -> java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                                "finish_task" -> {
                                                    val s = args["summary"] ?: "Completed"
                                                    if (_memoryDirty.value && _currentAgent.value != AgentRole.SUMMARIZER) {
                                                        throw IllegalStateException(context.getString(R.string.agent_memory_update_required_summary))
                                                    }
                                                    if (_currentAgent.value == AgentRole.SUMMARIZER) {
                                                        clearMemoryDirty("Summarizer finished after updating project memory.")
                                                    }
                                                    endSession(s)
                                                    setCurrentAgent(AgentRole.ORCHESTRATOR)
                                                    setCurrentTask(null)
                                                    "Task completed. Summary: $s"
                                                }
                                                else -> "Unknown tool: $toolName"
                                            }
                                            results.append("[$toolName] $output\n")
                                        } catch (e: Exception) {
                                            results.append("[$toolName] ERROR: ${e.message}\n")
                                        }
                                    }
                                } catch (e: Exception) {
                                    results.append("Failed to parse tools_json: ${e.message}")
                                }
                                results.toString().trimEnd()
                            }
                            else -> {
                                // Check custom tools
                                val custom = _loadedCustomTools.value.find { it.name == toolCall.name }
                                if (custom != null) {
                                    var cmd = custom.commandTemplate
                                    toolCall.arguments.forEach { (k, v) -> cmd = cmd.replace("{$k}", v) }
                                    val res = agentService.runCommand(cmd).getOrThrow()
                                    "Custom tool ${custom.name} output: $res"
                                } else {
                                    throw Exception("Unknown tool: ${toolCall.name}")
                                }
                            }
                        }
                        
                        Result.success(outputStr)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    
                    val output = result.fold(
                        onSuccess = { rawOutput ->
                            buildToolResultEnvelope(
                                toolName = toolCall.name,
                                status = "ok",
                                summary = summarizeToolResult(toolCall.name, rawOutput),
                                importantOutput = rawOutput,
                                nextHint = nextHintForTool(toolCall.name, rawOutput)
                            )
                        },
                        onFailure = {
                            buildToolResultEnvelope(
                                toolName = toolCall.name,
                                status = "error",
                                summary = it.message ?: "Tool execution failed.",
                                nextHint = "Inspect the error and retry with corrected arguments or a narrower follow-up action."
                            )
                        }
                    )
                    if (result.isSuccess) {
                        recordAgentEvent(
                            "tool_success",
                            "Tool ${toolCall.name} completed",
                            summarizeToolResult(toolCall.name, result.getOrNull().orEmpty())
                        )
                    } else {
                        recordAgentEvent(
                            "tool_failure",
                            "Tool ${toolCall.name} failed",
                            result.exceptionOrNull()?.message ?: toolCall.name
                        )
                    }
                    syncAssistantToolProgress(toolCall, output)
                    
                    // Add tool output to chat
                    addMessage(ChatMessage(
                        role = "tool", 
                        content = output,
                        toolName = toolCall.name,
                        toolCallId = toolCall.id
                    ))
                    
                    // Continue conversation with tool output
                    if (!toolHandlesContinuation) {
                        sendMessage(context, ollamaService, settingsRepo, agentService, queueBehindActiveJob = false).join()
                    }
                    
                } catch (e: Exception) {
                    addDebugLog("❌ Tool execution error: ${e.message}")
                    recordAgentEvent("tool_error", "Tool ${toolCall.name} crashed", e.message ?: toolCall.name)
                    val crashOutput = buildToolResultEnvelope(
                        toolName = toolCall.name,
                        status = "error",
                        summary = e.message ?: "Tool execution crashed.",
                        nextHint = "Retry with corrected inputs or switch to a smaller diagnostic step."
                    )
                    syncAssistantToolProgress(toolCall, crashOutput)
                    addMessage(ChatMessage(
                        role = "tool", 
                        content = crashOutput,
                        toolName = toolCall.name,
                        toolCallId = toolCall.id
                    ))
                    // Continue conversation so LLM can see the error and retry/recover
                    try {
                        sendMessage(context, ollamaService, settingsRepo, agentService, queueBehindActiveJob = false).join()
                    } catch (continueError: Exception) {
                        addDebugLog("❌ Failed to continue after error: ${continueError.message}")
                        // Last resort: if we can't continue, reset to orchestrator
                        if (_currentAgent.value != AgentRole.ORCHESTRATOR) {
                            endSession("ERROR: Tool ${toolCall.name} failed and recovery failed")
                            setCurrentAgent(AgentRole.ORCHESTRATOR)
                            setCurrentTask(null)
                        }
                    }
                } finally {
                    // Decrement ref count when tool execution is done
                    setIsLoading(false)
                }
            }
        }


        fun continueAfterToolExecution(
            context: Context,
            ollamaService: OllamaService,
            settingsRepo: com.example.llamadroid.data.SettingsRepository,
            agentService: AgentService
        ) {
            sendMessage(context, ollamaService, settingsRepo, agentService)
        }
        
        // ========== CUSTOM AGENTS (loaded from database) ==========
        private val _loadedCustomAgents = MutableStateFlow<List<com.example.llamadroid.data.db.CustomAgentEntity>>(emptyList())
        val loadedCustomAgents: StateFlow<List<com.example.llamadroid.data.db.CustomAgentEntity>> = _loadedCustomAgents.asStateFlow()
        
        // Currently active custom agent (during delegation)
        
        fun setLoadedCustomAgents(agents: List<com.example.llamadroid.data.db.CustomAgentEntity>) {
            _loadedCustomAgents.value = agents
            addDebugLog("🤖 Loaded ${agents.size} custom agents")
        }
        
        // Check if an agent name refers to a custom agent
        fun getCustomAgent(name: String): com.example.llamadroid.data.db.CustomAgentEntity? {
            return _loadedCustomAgents.value.find { 
                it.name.equals(name, ignoreCase = true) && it.isEnabled 
            }
        }

        /**
         * Check if a command is suspicious or dangerous.
         */
        fun isSuspiciousCommand(command: String): Boolean {
            val lowerCommand = command.lowercase()
            val containsBlocked = BLOCKED_COMMANDS.any { blocked ->
                lowerCommand.contains(blocked.lowercase())
            }
            if (containsBlocked) return true
            
            // Path escape checks
            val projectFolder = _currentProjectFolder.value ?: "default"
            val projectPath = "$WORKSPACE_PATH/$projectFolder"
            if (command.contains("../") || (command.contains("/") && !command.contains(projectPath) && !command.startsWith("ls") && !command.startsWith("grep"))) {
                // If it contains a path not in project and not a common safe tool
                return true
            }
            return false
        }

        /**
         * Get sanitized path
         */
        fun sanitizePath(path: String): String {
            // 1. Basic cleaning: remove traversal and double slashes
            var cleanPath = path.replace("..", "").replace(Regex("/+"), "/")
            
            // 2. Determine the project root
            val projectFolder = _currentProjectFolder.value ?: "default"
            val projectPath = "$WORKSPACE_PATH/$projectFolder"
            
            // 3. Resolve path
            return when {
                // If it starts with WORKSPACE_PATH, ensure it's in the project folder
                cleanPath.startsWith(WORKSPACE_PATH) -> {
                    if (cleanPath.startsWith(projectPath)) {
                        cleanPath
                    } else {
                        // Re-route to project folder if it was trying to escape to other projects
                        val relative = cleanPath.removePrefix(WORKSPACE_PATH).trimStart('/')
                        "$projectPath/$relative"
                    }
                }
                // Absolute path starting from system root
                cleanPath.startsWith("/") -> {
                    val relative = cleanPath.substring(1)
                    "$projectPath/$relative"
                }
                // Relative path
                else -> {
                    "$projectPath/$cleanPath"
                }
            }.trimEnd('/')
        }

        /**
         * Verify if a path is within the project's sandbox.
         */
        fun isPathSafe(path: String): Boolean {
            if (path.contains("..")) return false
            val projectFolder = _currentProjectFolder.value ?: "default"
            val projectPath = "$WORKSPACE_PATH/$projectFolder"
            val sanitized = sanitizePath(path)
            return sanitized.startsWith(projectPath) && sanitized.length > WORKSPACE_PATH.length
        }

        /**
         * Convert an absolute path to a project-relative path for LLM display.
         */
        fun toProjectRelativePath(absolutePath: String): String {
            val projectFolder = _currentProjectFolder.value ?: "default"
            val projectPath = "$WORKSPACE_PATH/$projectFolder"
            
            return when {
                absolutePath.startsWith(projectPath) -> {
                    val relativePart = absolutePath.removePrefix(projectPath)
                    if (relativePart.isEmpty()) "/" else relativePart
                }
                absolutePath.startsWith(WORKSPACE_PATH) -> {
                    "/" + absolutePath.removePrefix(WORKSPACE_PATH).trimStart('/')
                }
                else -> absolutePath
            }
        }

        fun parseLsLine(line: String, basePath: String): FileInfo? {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size < 9) return null
            
            val permissions = parts[0]
            val isDirectory = permissions.startsWith("d")
            val size = parts[4].toLongOrNull() ?: 0L
            val name = parts.drop(8).joinToString(" ")
            
            if (name == "." || name == "..") return null
            
            val absolutePath = "$basePath/$name"
            
            return FileInfo(
                name = name,
                path = absolutePath,  // Store ABSOLUTE path for file operations
                isDirectory = isDirectory,
                size = size,
                permissions = permissions
            )
        }

        fun parseGrepLine(line: String): SearchResult? {
            val colonIndex = line.indexOf(':')
            if (colonIndex < 0) return null
            
            val absolutePath = line.substring(0, colonIndex)
            val rest = line.substring(colonIndex + 1)
            
            val secondColon = rest.indexOf(':')
            if (secondColon < 0) return null
            
            val lineNumber = rest.substring(0, secondColon).toIntOrNull() ?: return null
            val content = rest.substring(secondColon + 1)
            val displayPath = toProjectRelativePath(absolutePath)
            
            return SearchResult(
                path = displayPath,
                lineNumber = lineNumber,
                content = content.trim()
            )
        }

        /**
         * Check if actually connected - with recovery handling
         */

        fun checkConnection(): Boolean {
            val currentSession = session
            val connected = currentSession?.isConnected == true
            if (!connected && _isConnected.value) {
                onConnectionLost()
            }
            return connected
        }
        
        /**
         * Handle connection lost - cancel running tasks and notify user
         */
        private fun onConnectionLost() {
            _isConnected.value = false
            DebugLog.log("[$TAG] Connection lost (detected)")
            
            // Only show message and cancel jobs if there was an active task
            val wasActive = loadingRefCount.get() > 0 || currentChatJob?.isActive == true
            
            // Cancel any running jobs
            currentChatJob?.cancel()
            setIsLoading(false, "Connection lost")
            
            if (wasActive) {
                addDebugLog("🔌 Connection lost during active task")
                addMessage(ChatMessage(
                    role = "system",
                    content = "⚠️ **SSH connection lost.** Please reconnect via ⚙️ settings to continue."
                ))
            } else {
                addDebugLog("🔌 Connection lost (idle, will auto-reconnect)")
            }
        }
        
        /**
         * Truncate long output smartly (keep start and end)
         */
        fun truncateOutput(output: String, maxLength: Int = 4000): String {
            if (output.length <= maxLength) return output
            val half = maxLength / 2 - 30
            val truncatedCount = output.length - maxLength
            return output.take(half) + 
                "\n\n... [$truncatedCount characters truncated] ...\n\n" + 
                output.takeLast(half)
        }

        private fun packMessagesForContext(
            messages: List<ChatMessage>,
            contextSize: Int,
            profile: PromptPackingProfile
        ): PackedPromptContext {
            if (messages.isEmpty()) return PackedPromptContext(emptyList(), 0, 0)

            val thresholdTokens = (contextSize * PROMPT_CONTEXT_AUTOCOMPACT_RATIO).toInt()
                .coerceAtLeast(MIN_PROMPT_CONTEXT_TOKENS)
            val normalizedMessages = normalizeMessagesBeforeThreshold(messages)
            val normalizedEstimate = estimatePromptTokens(normalizedMessages)

            if (normalizedEstimate < thresholdTokens) {
                return PackedPromptContext(
                    messages = normalizedMessages,
                    omittedCount = 0,
                    estimatedTokens = normalizedEstimate,
                    thresholdTriggered = false,
                    didCompactHistory = false,
                    compactionPasses = 0
                )
            }

            var workingProfile = profile
            var packed = packMessagesForContextOnce(normalizedMessages, thresholdTokens, workingProfile)
            var compactionPasses = 1
            while (packed.estimatedTokens > thresholdTokens && compactionPasses < 4) {
                workingProfile = workingProfile.moreAggressive()
                val moreCompact = packMessagesForContextOnce(normalizedMessages, thresholdTokens, workingProfile)
                if (moreCompact.estimatedTokens >= packed.estimatedTokens) break
                packed = moreCompact
                compactionPasses++
            }

            return packed.copy(
                thresholdTriggered = true,
                didCompactHistory = true,
                compactionPasses = compactionPasses
            )
        }

        private fun normalizeMessagesBeforeThreshold(messages: List<ChatMessage>): List<ChatMessage> {
            return messages.mapNotNull { message ->
                if (message.isStreaming) {
                    null
                } else {
                    message.copy(thinking = null)
                }
            }
        }

        private fun packMessagesForContextOnce(
            messages: List<ChatMessage>,
            thresholdTokens: Int,
            profile: PromptPackingProfile
        ): PackedPromptContext {
            val pinnedSystemMessages = messages.takeWhile { it.role == "system" }
            val history = messages.drop(pinnedSystemMessages.size)
            val recentStart = (history.size - profile.recentMessages).coerceAtLeast(0)
            val normalizedHistory = history.mapIndexedNotNull { index, message ->
                normalizeMessageForPrompt(message, isRecent = index >= recentStart, profile = profile)
            }

            if (normalizedHistory.isEmpty()) {
                return PackedPromptContext(pinnedSystemMessages, 0, estimatePromptTokens(pinnedSystemMessages))
            }

            val pinnedBudget = estimatePromptTokens(pinnedSystemMessages)
            val historyBudget = (thresholdTokens - pinnedBudget).coerceAtLeast(thresholdTokens / 2)
            val keptNewestFirst = mutableListOf<ChatMessage>()
            val omitted = mutableListOf<ChatMessage>()
            var usedTokens = 0
            var keptUserMessage = false

            for (message in normalizedHistory.asReversed()) {
                val messageTokens = estimatePromptTokens(message.content)
                val mustKeep = keptNewestFirst.size < profile.recentMessages || (!keptUserMessage && message.role == "user")
                if (mustKeep || usedTokens + messageTokens <= historyBudget) {
                    keptNewestFirst += message
                    usedTokens += messageTokens
                    if (message.role == "user") keptUserMessage = true
                } else {
                    omitted += message
                }
            }

            val kept = keptNewestFirst.asReversed().toMutableList()
            var digest = buildContextDigest(omitted, profile)
            while (digest != null && kept.size > 2 && usedTokens + estimatePromptTokens(digest.content) > historyBudget) {
                val moved = kept.removeAt(0)
                omitted += moved
                usedTokens = kept.sumOf { estimatePromptTokens(it.content) }
                digest = buildContextDigest(omitted, profile)
            }

            val packedMessages = buildList {
                addAll(pinnedSystemMessages)
                digest?.let { add(it) }
                addAll(kept)
            }
            return PackedPromptContext(
                messages = packedMessages,
                omittedCount = omitted.size,
                estimatedTokens = estimatePromptTokens(packedMessages)
            )
        }

        private fun normalizeMessageForPrompt(message: ChatMessage, isRecent: Boolean, profile: PromptPackingProfile): ChatMessage? {
            if (message.isStreaming) return null
            if (message.role == "system" && isRoutineSystemReminder(message) && !isRecent) return null

            val normalizedContent = when (message.role) {
                "assistant" -> normalizeAssistantPromptContent(message, isRecent, profile)
                "tool" -> normalizeToolPromptContent(message, isRecent, profile)
                "user" -> compactTextForContext(message.content, if (isRecent) profile.assistantRecentChars + 400 else profile.assistantOldChars + 250, if (isRecent) profile.recentLines + 4 else profile.oldLines + 2)
                "system" -> compactTextForContext(message.content, if (isRecent) profile.assistantOldChars + 250 else profile.assistantOldChars, if (isRecent) profile.oldLines + 4 else profile.oldLines)
                else -> compactTextForContext(message.content, profile.assistantOldChars, profile.oldLines)
            }

            if (normalizedContent.isBlank()) return null
            return message.copy(content = normalizedContent, thinking = null)
        }

        private fun normalizeAssistantPromptContent(message: ChatMessage, isRecent: Boolean, profile: PromptPackingProfile): String {
            val contentLimit = if (isRecent) profile.assistantRecentChars else profile.assistantOldChars
            val body = compactTextForContext(message.content, contentLimit, if (isRecent) profile.recentLines else profile.oldLines)
            val toolCall = message.pendingToolCall
            if (toolCall == null) return body

            val rationale = extractToolCallRationale(message)
            val argsPreview = buildToolArgsPreview(toolCall.arguments)
            return buildString {
                if (body.isNotBlank()) {
                    appendLine(body)
                }
                append("Action rationale: ")
                append(rationale)
                appendLine()
                append("Tool call: ")
                append(toolCall.name)
                if (argsPreview.isNotBlank()) {
                    append(" (")
                    append(argsPreview)
                    append(")")
                }
            }.trim()
        }

        private fun normalizeToolPromptContent(message: ChatMessage, isRecent: Boolean, profile: PromptPackingProfile): String {
            val maxChars = if (isRecent) profile.toolRecentChars else profile.toolOldChars
            val maxLines = if (isRecent) profile.recentLines else profile.oldLines
            val prefix = message.toolName?.takeIf { it.isNotBlank() }?.let { "Tool $it result:\n" } ?: ""
            val compacted = compactTextForContext(message.content, maxChars - prefix.length, maxLines)
            return (prefix + compacted).trim()
        }

        private fun buildContextDigest(omittedMessages: List<ChatMessage>, profile: PromptPackingProfile): ChatMessage? {
            if (omittedMessages.isEmpty()) return null
            val orderedMessages = omittedMessages.sortedBy { it.sequenceNumber }
            val notes = orderedMessages.mapNotNull { summarizeMessageForDigest(it) }
                .distinct()
                .takeLast(profile.digestItems)

            val digestText = buildString {
                appendLine("CONTEXT DIGEST: Older turns were compacted to fit the model context window.")
                appendLine("Compacted turns: ${orderedMessages.size}")
                if (notes.isEmpty()) {
                    append("Keep focusing on the latest task, recent tool results, and active command state.")
                } else {
                    notes.forEach { note -> appendLine("- $note") }
                }
            }.trim()
            return ChatMessage(role = "system", content = digestText)
        }

        private fun summarizeMessageForDigest(message: ChatMessage): String? {
            return when (message.role) {
                "user" -> "User request: ${extractSummarySnippet(message.content, 220)}"
                "assistant" -> {
                    message.pendingToolCall?.let { toolCall ->
                        "Assistant decided to call ${toolCall.name}: ${extractToolCallRationale(message)}"
                    } ?: message.content.takeIf { it.isNotBlank() }?.let {
                        "Assistant response: ${extractSummarySnippet(it, 220)}"
                    }
                }
                "tool" -> {
                    val prefix = message.toolName?.takeIf { it.isNotBlank() } ?: "tool"
                    "$prefix result: ${extractSummarySnippet(message.content, 220)}"
                }
                "system" -> summarizeSystemMessageForDigest(message.content)
                else -> message.content.takeIf { it.isNotBlank() }?.let { extractSummarySnippet(it, 220) }
            }
        }

        private fun summarizeSystemMessageForDigest(content: String): String? {
            val commandId = content.lineSequence().firstOrNull { it.startsWith("Command ID:") }?.substringAfter(':')?.trim()
            val status = content.lineSequence().firstOrNull { it.startsWith("Status:") }?.substringAfter(':')?.trim()
            return when {
                commandId != null && status != null -> "Background command $commandId is $status."
                content.contains("connection lost", ignoreCase = true) -> extractSummarySnippet(content, 180)
                else -> null
            }
        }

        private fun extractToolCallRationale(message: ChatMessage): String {
            val visibleText = message.content.trim()
            if (visibleText.isNotBlank()) {
                return extractSummarySnippet(visibleText, 220)
            }

            val thinkingText = sanitizeThinkingForContext(message.thinking.orEmpty())
            if (thinkingText.isNotBlank()) {
                return extractSummarySnippet(thinkingText, 220)
            }

            val toolCall = message.pendingToolCall ?: return "Continue the current task with the next required tool."
            return fallbackToolRationale(toolCall)
        }

        private fun fallbackToolRationale(toolCall: com.example.llamadroid.service.OllamaService.ToolCall): String {
            return when (toolCall.name) {
                "read_file" -> "Inspect ${toolCall.arguments["path"] ?: "the requested file"} before making changes."
                "read_file_lines" -> "Inspect the requested line range before deciding how to proceed."
                "search_code" -> "Search the codebase for the relevant implementation details."
                "list_directory" -> "Inspect the project structure before acting."
                "run_command" -> "Run a shell command to gather system or build feedback."
                "check_command" -> "Revisit a background command and inspect its latest status."
                "wait_command" -> "Wait for additional background command output before deciding the next step."
                "command_list" -> "Inspect tracked command sessions before choosing the next command action."
                "cancel_command" -> "Stop a command that is no longer useful or is clearly stuck."
                "send_command_input" -> "Send interactive stdin text to a running command."
                "write_file" -> "Create or replace the target file with the required content."
                "edit_lines" -> "Apply a focused file edit to the requested line range."
                "apply_patch" -> "Apply a precise unified diff to the requested files."
                "write_memory" -> "Record what changed and why in project memory."
                "rewrite_memory" -> "Consolidate memory after it has grown too large."
                "delete_memory" -> "Remove obsolete lines from memory without rewriting the entire file."
                "call_agent" -> "Delegate the next part of the task to the appropriate specialist agent."
                "propose_plan" -> "Present an implementation plan before proceeding."
                else -> "Use ${toolCall.name} to move the current task forward."
            }
        }

        private fun buildToolArgsPreview(arguments: Map<String, String>, maxArgs: Int = 3): String {
            if (arguments.isEmpty()) return ""
            val hiddenKeys = setOf("content", "new_content", "tools_json", "patch")
            return arguments.entries
                .filterNot { it.key in hiddenKeys }
                .take(maxArgs)
                .joinToString(", ") { (key, value) ->
                    "$key=${extractSummarySnippet(value, 60)}"
                }
        }

        private fun sanitizeThinkingForContext(thinking: String): String {
            return thinking
                .replace("<think>", "")
                .replace("</think>", "")
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        private fun extractSummarySnippet(text: String, maxChars: Int): String {
            val cleaned = text
                .replace(Regex("\\s+"), " ")
                .replace("<think>", "")
                .replace("</think>", "")
                .trim()
            if (cleaned.length <= maxChars) return cleaned

            val sentencePieces = cleaned.split(Regex("(?<=[.!?])\\s+"))
            val summary = StringBuilder()
            for (piece in sentencePieces) {
                if (piece.isBlank()) continue
                if (summary.isNotEmpty() && summary.length + piece.length + 1 > maxChars) break
                if (summary.isNotEmpty()) summary.append(' ')
                summary.append(piece)
                if (summary.length >= maxChars / 2) break
            }
            return if (summary.isNotEmpty()) {
                summary.toString().take(maxChars).trim()
            } else {
                cleaned.take(maxChars).trimEnd() + "..."
            }
        }

        private fun compactTextForContext(text: String, maxChars: Int, maxLines: Int): String {
            if (text.isBlank()) return ""
            val normalized = text.replace("\r", "").trim()
            val lines = normalized.lines().filter { it.isNotBlank() }
            val limitedLines = when {
                lines.size <= maxLines -> lines
                maxLines <= 4 -> lines.take(maxLines)
                else -> {
                    val head = maxLines / 2
                    val tail = (maxLines - head - 1).coerceAtLeast(1)
                    lines.take(head) + listOf("... [${lines.size - head - tail} lines omitted] ...") + lines.takeLast(tail)
                }
            }
            val compacted = limitedLines.joinToString("\n")
            return if (compacted.length <= maxChars) {
                compacted
            } else {
                truncateOutput(compacted, maxChars)
            }
        }

        private fun estimatePromptTokens(messages: List<ChatMessage>): Int {
            return messages.sumOf {
                estimatePromptTokens(it.content) +
                    (it.toolName?.length ?: 0) / 4 +
                    (it.pendingToolCall?.arguments?.entries?.sumOf { entry -> entry.key.length + entry.value.length } ?: 0) / 4 + 6
            }
        }

        private fun estimatePromptTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

        private fun updatePromptContextSnapshot(
            rawEstimatedTokens: Int,
            packedContext: PackedPromptContext,
            contextSize: Int,
            profileName: String
        ) {
            val safeContextSize = contextSize.coerceAtLeast(1)
            val percentUsed = ((packedContext.estimatedTokens.toDouble() / safeContextSize.toDouble()) * 100)
                .toInt()
                .coerceIn(0, 999)
            val recentCompactions = synchronized(recentCompactionEvents) {
                if (packedContext.didCompactHistory && rawEstimatedTokens > packedContext.estimatedTokens) {
                    if (recentCompactionEvents.size == 4) {
                        recentCompactionEvents.removeFirst()
                    }
                    recentCompactionEvents.addLast(
                        PromptCompactionEvent(
                            timestamp = System.currentTimeMillis(),
                            rawEstimatedTokens = rawEstimatedTokens,
                            packedEstimatedTokens = packedContext.estimatedTokens
                        )
                    )
                }
                recentCompactionEvents.toList().asReversed()
            }
            _promptContextSnapshot.value = PromptContextSnapshot(
                rawEstimatedTokens = rawEstimatedTokens,
                packedEstimatedTokens = packedContext.estimatedTokens,
                contextSize = safeContextSize,
                omittedCount = packedContext.omittedCount,
                percentUsed = percentUsed,
                thresholdPercent = PROMPT_CONTEXT_AUTOCOMPACT_PERCENT,
                thresholdTriggered = packedContext.thresholdTriggered,
                didCompactHistory = packedContext.didCompactHistory,
                profileName = profileName,
                recentCompactions = recentCompactions
            )
        }

        private fun isRoutineSystemReminder(message: ChatMessage): Boolean {
            return message.role == "system" && (
                message.content.startsWith("REMINDER:") ||
                    message.content.startsWith("CONTEXT DIGEST:")
                )
        }

        private data class ToolCallRecoveryAttempt(
            val toolCall: com.example.llamadroid.service.OllamaService.ToolCall? = null,
            val attempted: Boolean,
            val source: String,
            val error: String? = null,
            val suspectedToolName: String? = null
        )

        private fun recoverToolCallAttempt(content: String, thinking: String): ToolCallRecoveryAttempt? {
            val sources = listOf(
                "assistant text" to content,
                "thinking block" to thinking
            )
            var sawIntent = false
            var firstError: ToolCallRecoveryAttempt? = null

            for ((source, rawText) in sources) {
                if (rawText.isBlank()) continue
                val text = sanitizeThinkingForContext(rawText)
                if (text.isBlank()) continue
                val sourceToolHint = detectLikelyToolName(text)

                val candidates = collectToolCallJsonCandidates(text)
                if (candidates.isNotEmpty()) {
                    sawIntent = true
                }

                for (candidate in candidates) {
                    try {
                        val toolCall = parseRecoveredToolCall(candidate)
                        if (toolCall != null) {
                            return ToolCallRecoveryAttempt(
                                toolCall = toolCall,
                                attempted = true,
                                source = source,
                                suspectedToolName = toolCall.name
                            )
                        }
                    } catch (e: Exception) {
                        if (firstError == null) {
                            firstError = ToolCallRecoveryAttempt(
                                attempted = true,
                                source = source,
                                error = e.message,
                                suspectedToolName = detectLikelyToolName(candidate) ?: sourceToolHint
                            )
                        }
                    }
                }

                if (!sawIntent && Regex("(tool_call|\"name\"\\s*:|\"arguments\"\\s*:|<tool)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    sawIntent = true
                }
            }

            return when {
                firstError != null -> firstError
                sawIntent -> ToolCallRecoveryAttempt(
                    attempted = true,
                    source = "assistant output",
                    error = "No valid structured tool call could be recovered.",
                    suspectedToolName = detectLikelyToolName(content + "\n" + thinking)
                )
                else -> null
            }
        }

        private fun detectLikelyToolName(text: String): String? {
            if (text.isBlank()) return null
            val knownTools = getAgentTools().map { it.name }.distinct().sortedByDescending { it.length }

            Regex("\"name\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                .find(text)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { namedTool ->
                    return knownTools.firstOrNull { it.equals(namedTool, ignoreCase = true) } ?: namedTool
                }

            return knownTools.firstOrNull { toolName ->
                Regex("""(?<![A-Za-z0-9_])${Regex.escape(toolName)}(?![A-Za-z0-9_])""", RegexOption.IGNORE_CASE)
                    .containsMatchIn(text)
            }
        }

        private fun collectToolCallJsonCandidates(text: String): List<String> {
            val candidates = mutableListOf<String>()

            Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
                .findAll(text)
                .forEach { match ->
                    match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(candidates::add)
                }

            Regex("\"name\"\\s*:").findAll(text).forEach { match ->
                extractJsonObjectAtOrBefore(text, match.range.first)?.let(candidates::add)
            }

            return candidates.distinct()
        }

        private fun extractJsonObjectAtOrBefore(text: String, index: Int): String? {
            val start = text.lastIndexOf('{', index)
            if (start == -1) return null

            var depth = 0
            var inString = false
            var escaped = false

            for (i in start until text.length) {
                val ch = text[i]
                when {
                    escaped -> escaped = false
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = !inString
                    !inString && ch == '{' -> depth++
                    !inString && ch == '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(start, i + 1)
                        }
                    }
                }
            }

            return null
        }

        private fun parseRecoveredToolCall(candidate: String): com.example.llamadroid.service.OllamaService.ToolCall? {
            val root = JSONObject(candidate)
            val normalized = when {
                root.has("name") || root.has("function") -> root
                root.has("tool") && root.opt("tool") is JSONObject -> root.getJSONObject("tool")
                else -> null
            } ?: return null

            val id = normalized.optString("id").takeIf { it.isNotBlank() }
            val functionObject = normalized.optJSONObject("function")
            val name = when {
                functionObject != null -> functionObject.optString("name")
                normalized.has("name") -> normalized.optString("name")
                else -> ""
            }.trim()
            if (name.isBlank()) return null

            val argsObject = when {
                functionObject?.opt("arguments") is JSONObject -> functionObject.getJSONObject("arguments")
                normalized.opt("arguments") is JSONObject -> normalized.getJSONObject("arguments")
                normalized.opt("args") is JSONObject -> normalized.getJSONObject("args")
                else -> JSONObject()
            }

            val args = mutableMapOf<String, String>()
            argsObject.keys().forEach { key ->
                args[key] = argsObject.opt(key)?.toString().orEmpty()
            }

            return com.example.llamadroid.service.OllamaService.ToolCall(
                name = name,
                arguments = args,
                id = id
            )
        }

        private fun syncAssistantToolProgress(
            toolCall: com.example.llamadroid.service.OllamaService.ToolCall,
            toolOutput: String? = null
        ) {
            val targetId = _messages.value
                .asReversed()
                .firstOrNull { message ->
                    message.role == "assistant" &&
                        message.toolName == toolCall.name &&
                        (toolCall.id == null || message.toolCallId == toolCall.id)
                }?.id ?: return

            updateMessage(targetId) { message ->
                message.copy(
                    toolName = toolCall.name,
                    toolArgs = toolCall.arguments,
                    toolCallId = message.toolCallId ?: toolCall.id,
                    pendingToolCall = message.pendingToolCall ?: toolCall,
                    toolOutput = toolOutput ?: message.toolOutput,
                    isOutputExpanded = true
                )
            }
        }

        private fun validateToolCall(toolCall: com.example.llamadroid.service.OllamaService.ToolCall, role: AgentRole = _currentAgent.value): String? {
            val tool = getAgentTools(role).find { it.name == toolCall.name }
                ?: return "Tool `${toolCall.name}` is not available to the current agent."
            val missing = tool.requiredParams.filter { toolCall.arguments[it].isNullOrBlank() }
            if (missing.isNotEmpty()) {
                return "Tool `${toolCall.name}` is missing required arguments: ${missing.joinToString(", ")}."
            }
            return null
        }

        private fun buildToolResultEnvelope(
            toolName: String,
            status: String,
            summary: String,
            importantOutput: String? = null,
            nextHint: String? = null
        ): String {
            val (maxChars, maxLines) = when (toolName) {
                "run_command", "wait_command", "check_command", "cancel_command", "command_list", "send_command_input" -> 2200 to 20
                else -> 18_000 to 260
            }
            return buildString {
                appendLine("status: $status")
                appendLine("tool: $toolName")
                appendLine("summary: ${extractSummarySnippet(summary, 220)}")
                importantOutput?.takeIf { it.isNotBlank() }?.let {
                    appendLine("important_output:")
                    appendLine(compactTextForContext(it, maxChars, maxLines))
                }
                nextHint?.takeIf { it.isNotBlank() }?.let {
                    appendLine("next_hint: ${extractSummarySnippet(it, 220)}")
                }
            }.trim()
        }

        private fun summarizeToolResult(toolName: String, rawOutput: String): String {
            val lines = rawOutput.lines().filter { it.isNotBlank() }
            return when (toolName) {
                "run_command", "wait_command", "check_command", "cancel_command" ->
                    lines.firstOrNull { it.startsWith("Status:") } ?: lines.firstOrNull() ?: "Command state updated."
                "command_list" -> lines.firstOrNull() ?: "Listed tracked commands."
                "send_command_input" -> lines.firstOrNull() ?: "Sent input to the running command."
                "write_file" -> "File write completed."
                "edit_lines" -> lines.firstOrNull() ?: "Line edit completed."
                "apply_patch" -> lines.firstOrNull() ?: "Patch applied."
                "write_memory" -> lines.firstOrNull() ?: "Memory appended."
                "rewrite_memory" -> lines.firstOrNull() ?: "Memory rewritten."
                "delete_memory" -> lines.firstOrNull() ?: "Memory lines deleted."
                "read_file" ->
                    lines.firstOrNull { it.startsWith("Lines ") }
                        ?: lines.firstOrNull { it.startsWith("File: ") }
                        ?: "Requested file content was read successfully."
                "read_file_lines", "read_memory", "search_code", "list_directory" -> "Requested data was read successfully."
                else -> lines.firstOrNull() ?: "$toolName completed successfully."
            }
        }

        private fun nextHintForTool(toolName: String, rawOutput: String): String? {
            return when (toolName) {
                "run_command", "wait_command", "check_command" ->
                    if (rawOutput.contains("Command is still running", ignoreCase = true) || rawOutput.contains("Status: running", ignoreCase = true)) {
                        "Use wait_command, check_command, command_list, or send_command_input if the command needs interaction."
                    } else {
                        "Analyze the command result and decide whether another focused command or a code change is needed."
                    }
                "command_list" -> "Pick a command ID and use check_command, wait_command, cancel_command, or send_command_input as needed."
                "send_command_input" -> "Use wait_command or check_command to inspect the command response after the input."
                "write_file" -> "If the write looks correct, append a short memory note and reread the file or consult changed_files.md before editing it again."
                "edit_lines" -> "If the edit looks correct, append a short memory note and reread the file before making another edit to the same area."
                "apply_patch" -> "If the patch looks correct, append a short memory note and reread the affected files or changed_files.md before patching again."
                "write_memory" -> "If memory is getting long, read it back and use rewrite_memory to consolidate it."
                "read_file" ->
                    if (rawOutput.contains("has_more: true")) {
                        "Continue with read_file using next_start_line from this chunk, or use read_file_lines for a specific range."
                    } else {
                        "Use the observed context to decide the next edit, review, or command."
                    }
                "read_file_lines", "search_code", "list_directory" -> "Use the observed context to decide the next edit, review, or command."
                else -> null
            }
        }

        /**
         * Get available agent tools for Ollama
         */
        fun getAgentTools(role: AgentRole = _currentAgent.value): List<AgentTool> {
            val settingsRepo = com.example.llamadroid.data.SettingsRepository(com.example.llamadroid.LlamaApplication.instance)
            val webSearchEnabled = settingsRepo.agentWebSearchEnabled.value
            val kiwixEnabled = settingsRepo.agentKiwixEnabled.value
            
            val tools = mutableListOf(
                AgentTool(
                    name = "read_file",
                    description = "Read a file with line numbers. Small files are returned in full. Large files are returned as a chunk with has_more and next_start_line so you can continue reading the same file without losing your place. Do NOT include /workspace in paths.",
                    parameters = mapOf(
                        "path" to "File path relative to project root, e.g., 'src/main.py' or 'package.json'",
                        "start_line" to "Optional first line to read for paged reads (default: 1)",
                        "max_lines" to "Optional number of lines to return for this chunk (default: 160, max: 400)"
                    ),
                    requiredParams = listOf("path")
                ),
                AgentTool(
                    name = "write_file",
                    description = "Write content to a file. Creates parent directories if needed. Use paths like 'src/app.py' without /workspace prefix. Read the current file first unless you are creating a new file, then reread it before editing it again.",
                    parameters = mapOf(
                        "path" to "File path relative to project root, e.g., 'src/app.py' or 'lib/utils.js'",
                        "content" to "Content to write to the file"
                    ),
                    requiredParams = listOf("path", "content")
                ),
                AgentTool(
                    name = "run_command",
                    description = "Execute a shell command in the project directory. Long-running commands run in the background and return an ID. The LLM receives only the last requested lines (default 10, max 200). Use wait_command/check_command to revisit the same command or request more lines.",
                    parameters = mapOf(
                        "command" to "The shell command to execute",
                        "working_directory" to "Working directory relative to project root (default: project root)",
                        "lines" to "Optional number of output lines to return to the LLM (default: 10, max: 200)"
                    ),
                    requiredParams = listOf("command")
                ),
                AgentTool(
                    name = "check_command",
                    description = "Check the latest output and status of a background command started by run_command. You can revisit an older command ID at any time and optionally request more lines.",
                    parameters = mapOf(
                        "command_id" to "The ID of the command (returned by run_command)",
                        "lines" to "Optional number of output lines to return (default: 10, max: 200)"
                    ),
                    requiredParams = listOf("command_id")
                ),
                AgentTool(
                    name = "wait_command",
                    description = "Wait for a background command to finish or emit more output, then return its latest tail window. Use this instead of rerunning a command that is still active.",
                    parameters = mapOf(
                        "command_id" to "The ID of the command (returned by run_command)",
                        "wait_seconds" to "How long to wait for more output before returning (max 30s)",
                        "lines" to "Optional number of output lines to return (default: 10, max: 200)"
                    ),
                    requiredParams = listOf("command_id", "wait_seconds")
                ),
                AgentTool(
                    name = "command_list",
                    description = "List tracked command sessions with their IDs, status, start time, and the latest output preview. Use this when you need to recover command IDs or inspect parallel background work.",
                    parameters = emptyMap(),
                    requiredParams = emptyList()
                ),
                AgentTool(
                    name = "cancel_command",
                    description = "Cancel a tracked background command by ID. Use this when a command is hung, no longer needed, or clearly going down the wrong path.",
                    parameters = mapOf(
                        "command_id" to "The ID of the command to cancel"
                    ),
                    requiredParams = listOf("command_id")
                ),
                AgentTool(
                    name = "send_command_input",
                    description = "Send stdin text to a running command. Use this for prompts, confirmations, REPLs, or long-running tools that need interactive input after run_command.",
                    parameters = mapOf(
                        "command_id" to "The ID of the running command",
                        "input" to "Text to send to the command stdin",
                        "append_newline" to "Optional true/false flag. Defaults to true so the input is submitted as a line."
                    ),
                    requiredParams = listOf("command_id", "input")
                ),
                AgentTool(
                    name = "list_directory",
                    description = "List files and directories. Use '.' or empty for project root, 'src' for src folder. Do NOT use /workspace prefix.",
                    parameters = mapOf(
                        "path" to "Directory path relative to project root, e.g., '.', 'src', or 'lib/components'"
                    ),
                    requiredParams = listOf("path")
                ),
                AgentTool(
                    name = "search_code",
                    description = "Search for a pattern in project files using ripgrep. Returns matching lines with file paths.",
                    parameters = mapOf(
                        "query" to "Text or regex pattern to search for",
                        "directory" to "Directory to search in (default: project root)",
                        "file_pattern" to "Glob pattern for files, e.g., '*.py' or '*.js' (default: all files)"
                    ),
                    requiredParams = listOf("query")
                ),
                AgentTool(
                    name = "edit_lines",
                    description = "Replace specific lines in a file. More efficient than rewriting the whole file. Use read_file first to see line numbers and current content, then reread the file after editing before making another change.",
                    parameters = mapOf(
                        "path" to "File path relative to project root",
                        "start_line" to "First line number to replace (1-indexed, from read_file output)",
                        "end_line" to "Last line number to replace (inclusive)",
                        "new_content" to "Replacement content for those lines"
                    ),
                    requiredParams = listOf("path", "start_line", "end_line", "new_content")
                ),
                AgentTool(
                    name = "apply_patch",
                    description = "Apply a unified diff patch to one or more files. Prefer this for precise multi-hunk edits after reading the current code. Reread affected files after applying the patch before patching them again. Requires approval like write_file/edit_lines.",
                    parameters = mapOf(
                        "patch" to "Unified diff patch text with ---/+++ file headers"
                    ),
                    requiredParams = listOf("patch")
                ),
                // Memory tools - no approval required, stored in /workspace/brain/
                AgentTool(
                    name = "read_memory",
                    description = "Read a file from the agent's memory/brain folder. Returns content WITH LINE NUMBERS. Structured files include summary.md, current_task.md, todo.md, decisions.md, changed_files.md, and timeline.md. Use line numbers with delete_memory to remove specific content.",
                    parameters = mapOf(
                        "filename" to "Name of the memory file (e.g., 'summary.md', 'plan.md', 'todo.md')"
                    ),
                    requiredParams = listOf("filename")
                ),
                AgentTool(
                    name = "write_memory",
                    description = "APPENDS content to a memory file in the brain folder. Creates the file if it doesn't exist. NEVER overwrites - always appends. Use rewrite_memory to overwrite when summarizing. REMINDER: After using write_file or edit_lines, always call write_memory to record what you did and why. If the file grows too large, read it and use rewrite_memory to consolidate it.",
                    parameters = mapOf(
                        "filename" to "Name of the memory file (e.g., 'summary.md', 'plan.md', 'todo.md')",
                        "content" to "Content to append to the file"
                    ),
                    requiredParams = listOf("filename", "content")
                ),
                AgentTool(
                    name = "rewrite_memory",
                    description = "OVERWRITES the entire memory file with new content. Use this ONLY after reading memory with read_memory to consolidate and summarize old entries. Do NOT use this for regular writes - use write_memory instead.",
                    parameters = mapOf(
                        "filename" to "Name of the memory file to rewrite",
                        "content" to "Complete new content that replaces everything in the file"
                    ),
                    requiredParams = listOf("filename", "content")
                ),
                AgentTool(
                    name = "delete_memory",
                    description = "Delete specific lines from a memory file. Use read_memory FIRST to see line numbers, then specify which lines to remove. Useful for cleaning up outdated entries without rewriting the whole file.",
                    parameters = mapOf(
                        "filename" to "Name of the memory file",
                        "start_line" to "First line number to delete (from read_memory output)",
                        "end_line" to "Last line number to delete (inclusive)"
                    ),
                    requiredParams = listOf("filename", "start_line", "end_line")
                ),
                AgentTool(
                    name = "list_memory",
                    description = "List all files in the agent's memory/brain folder, including the default structured files used to resume work later.",
                    parameters = emptyMap(),
                    requiredParams = emptyList()
                ),
                AgentTool(
                    name = "fetch_url",
                    description = "Fetch content from a URL. Useful for reading documentation or external APIs.",
                    parameters = mapOf(
                        "url" to "The URL to fetch"
                    ),
                    requiredParams = listOf("url")
                ),
                AgentTool(
                    name = "get_datetime",
                    description = "Get the current date and time.",
                    parameters = emptyMap(),
                    requiredParams = emptyList()
                ),
                AgentTool(
                    name = "file_line_count",
                    description = "Get the total number of lines in a file. Use this before reading large files to know their size. Much cheaper than reading the whole file.",
                    parameters = mapOf(
                        "path" to "File path relative to project root, e.g., 'src/main.py'"
                    ),
                    requiredParams = listOf("path")
                ),
                AgentTool(
                    name = "read_file_lines",
                    description = "Read a specific range of lines from a file. Returns lines with their original line numbers. Use file_line_count first to know the file size, then read only the range you need. Much more efficient than reading entire large files.",
                    parameters = mapOf(
                        "path" to "File path relative to project root",
                        "start_line" to "First line to read (1-indexed)",
                        "end_line" to "Last line to read (inclusive)"
                    ),
                    requiredParams = listOf("path", "start_line", "end_line")
                ),
                AgentTool(
                    name = "web_search",
                    description = "Search the web for information. Returns a list of result titles, URLs, and snippets. Use this when you need up-to-date information, documentation, or answers that may not be in the project files.",
                    parameters = mapOf(
                        "query" to "Search query string"
                    ),
                    requiredParams = listOf("query")
                ),
                AgentTool(
                    name = "run_tools_sequential",
                    description = "Execute multiple tools sequentially in a single call. Useful for batching read-only operations like reading multiple files or searching. Tools that require approval or mutate execution state (write_file, run_command, edit_lines, apply_patch, cancel_command, send_command_input, call_agent, propose_plan) are NOT allowed here — call them individually. Provide a JSON array of tool calls.",
                    parameters = mapOf(
                        "tools_json" to "JSON array of tool calls. Each element: {\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}. Example: [{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.py\"}}, {\"name\": \"read_file\", \"arguments\": {\"path\": \"b.py\"}}]"
                    ),
                    requiredParams = listOf("tools_json")
                )
            )
            
            if (kiwixEnabled) {
                tools.add(
                    AgentTool(
                        name = "kiwix_search",
                        description = "Search the local offline Kiwix library (Wikipedia, StackOverflow, etc.). Use this as an alternative to web_search for offline knowledge access.",
                        parameters = mapOf(
                            "query" to "Search query string"
                        ),
                        requiredParams = listOf("query")
                    )
                )
            }

            // Only Orchestrator can call other agents to prevent loops
            if (role == AgentRole.ORCHESTRATOR) {
                val disabled = _disabledBuiltInAgents.value
                val builtInList = listOf("CODER", "REVIEWER", "EXECUTOR", "SUMMARIZER")
                val enabledBuiltIn = builtInList.filter { it !in disabled }
                val disabledBuiltIn = builtInList.filter { it in disabled }
                val customAgentNames = loadedCustomAgents.value.filter { it.isEnabled }.map { it.name }
                
                val availableParts = mutableListOf<String>()
                if (enabledBuiltIn.isNotEmpty()) availableParts.add(enabledBuiltIn.joinToString(", "))
                if (customAgentNames.isNotEmpty()) availableParts.add(customAgentNames.joinToString(", "))
                val available = availableParts.joinToString(", ")
                
                val disabledNote = if (disabledBuiltIn.isNotEmpty()) {
                    " DISABLED (do NOT call): ${disabledBuiltIn.joinToString(", ")}."
                } else ""
                
                tools.add(
                    AgentTool(
                        name = "call_agent",
                        description = "Delegate a task to a specialized agent. Available: $available.$disabledNote",
                        parameters = mapOf(
                            "agent" to "Agent to call ($available)",
                            "task" to "Detailed description of the task to perform",
                            "context" to "Any relevant context or file paths"
                        ),
                        requiredParams = listOf("agent", "task")
                    )
                )
                
                tools.add(
                    AgentTool(
                        name = "propose_plan",
                        description = "Propose an implementation plan for user approval. Use this when you have a multi-step plan that needs user review before proceeding. The user can modify or approve the plan.",
                        parameters = mapOf(
                            "plan" to "Detailed implementation plan in markdown format",
                            "summary" to "One-line summary of what the plan accomplishes"
                        ),
                        requiredParams = listOf("plan", "summary")
                    )
                )
            }
            
            // Sub-agents can signal task completion to return to Orchestrator
            if (role != AgentRole.ORCHESTRATOR) {
                tools.add(
                    AgentTool(
                        name = "finish_task",
                        description = "Signal that your assigned task is complete. ALWAYS update project memory first after meaningful changes, then call this to return control to the Orchestrator.",
                        parameters = mapOf(
                            "summary" to "Brief summary of what was accomplished",
                            "status" to "SUCCESS or FAILED"
                        ),
                        requiredParams = listOf("summary")
                    )
                )
            }
            
            // Add custom tools loaded from database
            loadedCustomTools.value.filter { it.isEnabled }.forEach { customTool ->
                try {
                    val params = org.json.JSONObject(customTool.parametersJson)
                    val paramMap = mutableMapOf<String, String>()
                    params.keys().forEach { key ->
                        paramMap[key] = params.getString(key)
                    }
                    
                    val requiredParamsArray = org.json.JSONArray(customTool.requiredParamsJson)
                    val requiredList = mutableListOf<String>()
                    for (i in 0 until requiredParamsArray.length()) {
                        requiredList.add(requiredParamsArray.getString(i))
                    }
                    
                    tools.add(
                        AgentTool(
                            name = customTool.name,
                            description = "${customTool.description}\n\nExample: ${customTool.exampleUsage}",
                            parameters = paramMap,
                            requiredParams = requiredList
                        )
                    )
                } catch (e: Exception) {
                    addDebugLog("⚠️ Failed to load custom tool ${customTool.name}: ${e.message}")
                }
            }
            
        return tools
        }
    } // End of companion object

    /**
     * Connect to ai-agent proot SSH using STATIC session
     * This PERSISTS across navigation and does NOT interfere with Termux tools SSH (port 8022)
     */
    suspend fun connect(
        host: String = "localhost",
        port: Int = AI_AGENT_SSH_PORT,
        username: String = AI_AGENT_USER,
        password: String = "agent"  // Default password, can be overridden by user in settings
    ): Result<Unit> = withContext(Dispatchers.IO) {
        sshMutex.withLock {
            lastConnectionHost = host
            lastConnectionPort = port
            lastConnectionUser = username
            lastConnectionPassword = password
            openVerifiedSessionLocked(
                host = host,
                port = port,
                username = username,
                password = password,
                forceReconnect = true
            ).map { Unit }
        }
    }
    
    /**
     * Disconnect from ai-agent proot
     */
    fun disconnect() {
        stopHeartbeat()
        session?.disconnect()
        session = null
        _isConnected.value = false
    }

    private suspend fun openVerifiedSessionLocked(
        host: String,
        port: Int,
        username: String,
        password: String,
        forceReconnect: Boolean
    ): Result<com.jcraft.jsch.Session> {
        if (!forceReconnect) {
            val current = session
            if (current?.isConnected == true) {
                return Result.success(current)
            }
        }

        return try {
            try {
                session?.disconnect()
            } catch (_: Exception) {}

            DebugLog.log("[$TAG] Connecting to $host:$port (persistent session)")
            val newSession = jsch.getSession(username, host, port).apply {
                setPassword(password)
                val props = java.util.Properties()
                props["StrictHostKeyChecking"] = "no"
                setConfig(props)
            }
            configureSshSession(newSession)
            newSession.connect()

            val verification = executeCommandOnSession(newSession, "echo 'connected'")
            if (verification.isFailure) {
                try {
                    newSession.disconnect()
                } catch (_: Exception) {}
                _isConnected.value = false
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                Result.failure(verification.exceptionOrNull() ?: Exception("Failed to verify connection"))
            } else {
                session = newSession
                _isConnected.value = true
                _connectionStatus.value = ConnectionStatus.CONNECTED
                DebugLog.log("[$TAG] Connected to ai-agent proot (port $port)")
                startHeartbeat(this@AgentService)
                Result.success(newSession)
            }
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Connection failed: ${e.message}")
            _isConnected.value = false
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
            Result.failure(e)
        }
    }

    private suspend fun ensureConnectedSessionLocked(): Result<com.jcraft.jsch.Session> {
        val current = session
        if (current?.isConnected == true) {
            return Result.success(current)
        }
        return openVerifiedSessionLocked(
            host = lastConnectionHost,
            port = lastConnectionPort,
            username = lastConnectionUser,
            password = lastConnectionPassword,
            forceReconnect = false
        )
    }

    private suspend fun executeCommandOnSession(
        currentSession: com.jcraft.jsch.Session,
        command: String,
        timeoutMs: Long = 60_000
    ): Result<String> {
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                val channel = currentSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                try {
                    channel.setCommand(command)

                    val outputStream = java.io.ByteArrayOutputStream()
                    channel.inputStream = null
                    channel.setOutputStream(outputStream)
                    channel.setErrStream(outputStream)

                    channel.connect(10_000)
                    while (!channel.isClosed) {
                        delay(50)
                    }

                    outputStream.toString()
                } finally {
                    try {
                        channel.disconnect()
                    } catch (_: Exception) {}
                }
            }

            if (result != null) {
                Result.success(result)
            } else {
                addDebugLog("⏱️ Command timed out after ${timeoutMs / 1000}s: ${command.take(50)}...")
                Result.failure(Exception("Command timed out after ${timeoutMs / 1000} seconds"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun executeCommandDetailedOnSession(
        currentSession: com.jcraft.jsch.Session,
        command: String,
        timeoutMs: Long = 60_000
    ): Result<CommandExecutionDetails> {
        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                val channel = currentSession.openChannel("exec") as com.jcraft.jsch.ChannelExec
                try {
                    channel.setCommand(command)

                    val outputStream = java.io.ByteArrayOutputStream()
                    channel.inputStream = null
                    channel.setOutputStream(outputStream)
                    channel.setErrStream(outputStream)

                    channel.connect(10_000)
                    while (!channel.isClosed) {
                        delay(50)
                    }

                    CommandExecutionDetails(
                        output = outputStream.toString(),
                        exitCode = channel.exitStatus
                    )
                } finally {
                    try {
                        channel.disconnect()
                    } catch (_: Exception) {}
                }
            }

            if (result != null) {
                Result.success(result)
            } else {
                addDebugLog("⏱️ Command timed out after ${timeoutMs / 1000}s: ${command.take(50)}...")
                Result.failure(Exception("Command timed out after ${timeoutMs / 1000} seconds"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Execute command using this agent's independent SSH session
     * @param command SSH command to execute
     * @param timeoutMs Maximum time to wait for command completion (default: 60 seconds)
     */
    private suspend fun executeCommand(command: String, timeoutMs: Long = 60_000): Result<String> = withContext(Dispatchers.IO) {
        // Use mutex for synchronized SSH session access
        sshMutex.withLock {
            val currentSession = ensureConnectedSessionLocked().getOrElse {
                return@withContext Result.failure(it)
            }
            val firstAttempt = executeCommandOnSession(currentSession, command, timeoutMs)
            if (firstAttempt.isSuccess) {
                return@withContext firstAttempt
            }

            val firstError = firstAttempt.exceptionOrNull()
            if (!isRecoverableSessionFailure(firstError)) {
                return@withContext Result.failure(firstError ?: Exception("SSH command failed"))
            }

            addDebugLog("🔄 Recovering persistent SSH session after socket drop")
            markSessionDisconnected(firstError ?: Exception("Recoverable SSH failure"))
            val retriedSession = openVerifiedSessionLocked(
                host = lastConnectionHost,
                port = lastConnectionPort,
                username = lastConnectionUser,
                password = lastConnectionPassword,
                forceReconnect = true
            ).getOrElse {
                startScalingRetry(this@AgentService)
                return@withContext Result.failure(it)
            }
            executeCommandOnSession(retriedSession, command, timeoutMs)
        }
    }

    private data class CommandExecutionDetails(
        val output: String,
        val exitCode: Int
    )

    private suspend fun executeCommandDetailed(command: String, timeoutMs: Long = 60_000): Result<CommandExecutionDetails> = withContext(Dispatchers.IO) {
        sshMutex.withLock {
            val currentSession = ensureConnectedSessionLocked().getOrElse {
                return@withContext Result.failure(it)
            }
            val firstAttempt = executeCommandDetailedOnSession(currentSession, command, timeoutMs)
            if (firstAttempt.isSuccess) {
                return@withContext firstAttempt
            }

            val firstError = firstAttempt.exceptionOrNull()
            if (!isRecoverableSessionFailure(firstError)) {
                return@withContext Result.failure(firstError ?: Exception("SSH command failed"))
            }

            addDebugLog("🔄 Recovering persistent SSH session after detailed command failure")
            markSessionDisconnected(firstError ?: Exception("Recoverable SSH failure"))
            val retriedSession = openVerifiedSessionLocked(
                host = lastConnectionHost,
                port = lastConnectionPort,
                username = lastConnectionUser,
                password = lastConnectionPassword,
                forceReconnect = true
            ).getOrElse {
                startScalingRetry(this@AgentService)
                return@withContext Result.failure(it)
            }
            executeCommandDetailedOnSession(retriedSession, command, timeoutMs)
        }
    }
    
    /**
     * Execute command with retry logic for reliability
     * Automatically reconnects if the connection was lost
     */
    private suspend fun executeCommandWithRetry(command: String, maxRetries: Int = 3): Result<String> = withContext(Dispatchers.IO) {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            if (session == null || !session!!.isConnected) {
                addDebugLog("🔄 Reconnecting SSH (attempt ${attempt + 1}/$maxRetries)...")
                connect()
            }
            
            val result = executeCommand(command)
            if (result.isSuccess) {
                return@withContext result
            }
            
            lastException = result.exceptionOrNull() as? Exception
            if (attempt < maxRetries - 1) {
                delay(1000L * (attempt + 1)) // Exponential backoff
            }
        }
        
        Result.failure(lastException ?: Exception("Command failed after $maxRetries retries"))
    }
    
    // ========== TOOL IMPLEMENTATIONS ==========
    
    /**
     * Fetch content from a URL
     */
    suspend fun fetchUrl(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                
            val request = okhttp3.Request.Builder()
                .url(url)
                .build()
                
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error code: ${response.code}"))
            }
            
            var body = response.body?.string() ?: ""
            
            // Check content type to see if we should strip HTML
            val contentType = response.header("Content-Type", "") ?: ""
            if (contentType.contains("text/html", ignoreCase = true) || body.trimStart().startsWith("<")) {
                // Strip HTML tags, scripts, and styles for cleaner text output
                body = body
                    .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("&[a-zA-Z]+;"), " ")
                    .replace(Regex("&#\\d+;"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }
            
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read file contents - checks staged cache first
     * Uses cat -n to show line numbers so AI can reference specific lines
     */
    suspend fun readFile(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            
            // Check if file is staged (pending approval)
            val stagedContent = StagedFileCache.getStagedContent(safePath)
            if (stagedContent != null) {
                // Add line numbers to staged content too
                val numberedContent = stagedContent.lines().mapIndexed { idx, line ->
                    String.format("%6d  %s", idx + 1, line)
                }.joinToString("\n")
                return@withContext Result.success("[STAGED]\n$numberedContent")
            }
            
            // Use cat -n for line numbers
            executeCommand("cat -n '$safePath'")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readFileBytes(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            val stagedContent = StagedFileCache.getStagedContent(safePath)
            if (stagedContent != null) {
                return@withContext Result.success(stagedContent.toByteArray(Charsets.UTF_8))
            }

            val encoded = executeCommand("base64 -w 0 '$safePath'").getOrThrow().trim()
            Result.success(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun readFileForTool(
        path: String,
        startLine: Int = 1,
        maxLines: Int = TOOL_READ_FILE_DEFAULT_LINES
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            val projectRelativePath = toProjectRelativePath(safePath)
            val requestedStart = maxOf(1, startLine)
            val requestedLines = maxLines.coerceIn(1, TOOL_READ_FILE_MAX_LINES)

            val stagedContent = StagedFileCache.getStagedContent(safePath)
            if (stagedContent != null) {
                val stagedLines = if (stagedContent.isEmpty()) emptyList() else stagedContent.split("\n")
                return@withContext Result.success(
                    formatReadFileChunk(
                        projectRelativePath = projectRelativePath,
                        lines = stagedLines,
                        requestedStart = requestedStart,
                        requestedLines = requestedLines,
                        staged = true
                    )
                )
            }

            val totalLines = executeCommand("wc -l < '$safePath'").getOrThrow().trim().toIntOrNull() ?: 0
            if (totalLines == 0) {
                return@withContext Result.success(
                    buildString {
                        appendLine("File: $projectRelativePath")
                        appendLine("Lines: 0")
                        append("has_more: false")
                    }
                )
            }

            if (requestedStart > totalLines) {
                return@withContext Result.success(
                    buildString {
                        appendLine("File: $projectRelativePath")
                        appendLine("requested_start_line: $requestedStart")
                        appendLine("total_lines: $totalLines")
                        appendLine("has_more: false")
                        append("No content at or after the requested start line.")
                    }
                )
            }

            val endLine = minOf(totalLines, requestedStart + requestedLines - 1)
            val chunk = executeCommand(
                "awk 'NR>=$requestedStart && NR<=$endLine {printf \"%6d  %s\\n\", NR, \$0}' '$safePath'"
            ).getOrThrow()
            Result.success(
                buildString {
                    appendLine("File: $projectRelativePath")
                    appendLine("Lines $requestedStart-$endLine of $totalLines")
                    appendLine("has_more: ${endLine < totalLines}")
                    if (endLine < totalLines) {
                        appendLine("next_start_line: ${endLine + 1}")
                    }
                    append(chunk.ifBlank { "[No readable content in this range]" })
                }.trimEnd()
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatReadFileChunk(
        projectRelativePath: String,
        lines: List<String>,
        requestedStart: Int,
        requestedLines: Int,
        staged: Boolean
    ): String {
        val totalLines = lines.size
        return buildString {
            if (staged) {
                appendLine("[STAGED]")
            }
            appendLine("File: $projectRelativePath")
            if (totalLines == 0) {
                appendLine("Lines: 0")
                append("has_more: false")
                return@buildString
            }

            if (requestedStart > totalLines) {
                appendLine("requested_start_line: $requestedStart")
                appendLine("total_lines: $totalLines")
                appendLine("has_more: false")
                append("No content at or after the requested start line.")
                return@buildString
            }

            val endLine = minOf(totalLines, requestedStart + requestedLines - 1)
            appendLine("Lines $requestedStart-$endLine of $totalLines")
            appendLine("has_more: ${endLine < totalLines}")
            if (endLine < totalLines) {
                appendLine("next_start_line: ${endLine + 1}")
            }
            append(
                lines.subList(requestedStart - 1, endLine)
                    .mapIndexed { index, line -> String.format("%6d  %s", requestedStart + index, line) }
                    .joinToString("\n")
            )
        }.trimEnd()
    }
    
    /**
     * Write content to file
     * Uses base64 encoding to safely handle all special characters and prevent shell injection.
     */
    suspend fun writeFile(path: String, content: String, trackChange: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            // Ensure parent directory exists
            val parentDir = safePath.substringBeforeLast("/")
            executeCommand("mkdir -p '$parentDir'")
            
            // Use base64 encoding to safely handle all special characters
            // This prevents shell injection and handles newlines, quotes, $(), backticks, etc.
            val base64Content = android.util.Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val result = executeCommand("echo '$base64Content' | base64 -d > '$safePath'")
            
            if (result.isSuccess) {
                if (trackChange) {
                    appendChangedFilesLog(listOf(path), "write_file")
                        .onFailure { addDebugLog("⚠️ Failed to track changed file $path: ${it.message}") }
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to write file"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun writeFileBytes(path: String, bytes: ByteArray, trackChange: Boolean = true): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            val parentDir = safePath.substringBeforeLast("/")
            executeCommand("mkdir -p '$parentDir'")

            val base64Content = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val chunkSize = 65536

            if (base64Content.length <= chunkSize) {
                executeCommand("echo '$base64Content' | base64 -d > '$safePath'").getOrThrow()
            } else {
                executeCommand("rm -f '$safePath'").getOrThrow()
                var offset = 0
                while (offset < base64Content.length) {
                    val chunk = base64Content.substring(offset, minOf(offset + chunkSize, base64Content.length))
                    val op = if (offset == 0) ">" else ">>"
                    executeCommand("echo '$chunk' | base64 -d $op '$safePath'").getOrThrow()
                    offset += chunkSize
                }
            }

            if (trackChange) {
                appendChangedFilesLog(listOf(path), "write_file")
                    .onFailure { addDebugLog("⚠️ Failed to track changed file $path: ${it.message}") }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Write content to file directly (bypasses staging, for approved files)
     */
    suspend fun writeFileRaw(path: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            // Ensure parent directory exists
            val parentDir = safePath.substringBeforeLast("/")
            executeCommand("mkdir -p '$parentDir'")
            
            // Use base64 encoding to safely handle all special characters
            // This prevents shell injection and handles newlines, quotes, etc.
            val base64Content = android.util.Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val result = executeCommand("echo '$base64Content' | base64 -d > '$safePath'")
            
            if (result.isSuccess) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to write file"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Upload a file from local Android Uri to remote SSH path
     */
    suspend fun uploadFile(localUri: Uri, remotePath: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(remotePath)
            val parentDir = safePath.substringBeforeLast("/")
            
            // Step 1: Read file bytes from SAF URI
            val bytes = context.contentResolver.openInputStream(localUri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("Failed to open local file"))
            
            // Step 2: Base64 encode the content
            val base64Content = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            
            // Step 3: Ensure parent directory & upload via base64 piping (same method as writeFileRaw)
            executeCommand("mkdir -p '$parentDir'")
            
            // Split into chunks if very large (shell has line length limits)
            val chunkSize = 65536 // 64KB chunks of base64
            if (base64Content.length <= chunkSize) {
                val result = executeCommand("echo '$base64Content' | base64 -d > '$safePath'")
                if (result.isFailure) return@withContext Result.failure(Exception("Upload failed"))
            } else {
                // For large files, write chunks
                executeCommand("rm -f '$safePath'") // Clean first
                var offset = 0
                while (offset < base64Content.length) {
                    val chunk = base64Content.substring(offset, minOf(offset + chunkSize, base64Content.length))
                    val op = if (offset == 0) ">" else ">>"
                    val result = executeCommand("echo '$chunk' | base64 -d $op '$safePath'")
                    if (result.isFailure) return@withContext Result.failure(Exception("Upload failed at chunk"))
                    offset += chunkSize
                }
            }
            
            DebugLog.log("[$TAG] Upload successful: $safePath (${bytes.size} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Upload failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Download a file from remote SSH path to local Android Uri
     * Uses base64 encoding over SSH (same proven approach as Termux Tools file manager)
     */
    suspend fun downloadFile(remotePath: String, localUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(remotePath)
            
            // Step 1: Get file content as base64 via SSH
            val result = executeCommand("base64 '$safePath' 2>/dev/null")
            val base64Content = result.getOrNull()
                ?: return@withContext Result.failure(Exception("Failed to read remote file"))
            
            if (base64Content.isBlank()) {
                return@withContext Result.failure(Exception("File is empty or not found"))
            }
            
            // Step 2: Decode base64 to bytes
            val bytes = android.util.Base64.decode(base64Content.trim(), android.util.Base64.DEFAULT)
            
            // Step 3: Write bytes to SAF URI
            context.contentResolver.openOutputStream(localUri)?.use { os ->
                os.write(bytes)
            } ?: return@withContext Result.failure(Exception("Failed to open local destination"))
            
            DebugLog.log("[$TAG] Download successful: $safePath (${bytes.size} bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.log("[$TAG] Download failed: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Compress files or directories into a tar.gz archive
     */
    suspend fun compress(paths: List<String>, destinationTarGz: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safePaths = paths.joinToString(" ") { "'${sanitizePath(it)}'" }
            val safeDest = sanitizePath(destinationTarGz)
            val result = executeCommand("tar -czf '$safeDest' $safePaths")
            if (result.isSuccess) Result.success(Unit) else Result.failure(Exception("Tar failed: ${result.getOrNull()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Uncompress a tar.gz archive
     */
    suspend fun uncompress(tarGzPath: String, destinationDir: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safeZip = sanitizePath(tarGzPath)
            val safeDest = sanitizePath(destinationDir)
            executeCommand("mkdir -p '$safeDest'")
            val result = executeCommand("tar -xzf '$safeZip' -C '$safeDest'")
            if (result.isSuccess) Result.success(Unit) else Result.failure(Exception("Untar failed: ${result.getOrNull()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Copy a file or directory
     */
    suspend fun copy(source: String, destination: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safeSrc = sanitizePath(source)
            val safeDest = sanitizePath(destination)
            val result = executeCommand("cp -r '$safeSrc' '$safeDest'")
            if (result.isSuccess) Result.success(Unit) else Result.failure(Exception("Copy failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Move a file or directory
     */
    suspend fun move(source: String, destination: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val safeSrc = sanitizePath(source)
            val safeDest = sanitizePath(destination)
            val result = executeCommand("mv '$safeSrc' '$safeDest'")
            if (result.isSuccess) Result.success(Unit) else Result.failure(Exception("Move failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizePatchPath(rawPath: String): String? {
        val token = rawPath.trim()
            .substringBefore('\t')
            .substringBefore(' ')
            .trim()
        if (token.isBlank() || token == "/dev/null") return null
        return when {
            token.startsWith("a/") || token.startsWith("b/") -> token.drop(2)
            else -> token
        }
    }

    private fun extractPatchPaths(patch: String): List<String> {
        val paths = linkedSetOf<String>()
        patch.lineSequence().forEach { line ->
            when {
                line.startsWith("--- ") -> normalizePatchPath(line.removePrefix("--- "))?.let(paths::add)
                line.startsWith("+++ ") -> normalizePatchPath(line.removePrefix("+++ "))?.let(paths::add)
            }
        }
        return paths.toList()
    }

    private fun determinePatchStripLevel(patch: String): Int {
        val rawPaths = patch.lineSequence()
            .mapNotNull { line ->
                when {
                    line.startsWith("--- ") -> line.removePrefix("--- ").trim().substringBefore('\t').substringBefore(' ').trim()
                    line.startsWith("+++ ") -> line.removePrefix("+++ ").trim().substringBefore('\t').substringBefore(' ').trim()
                    else -> null
                }
            }
            .filter { it.isNotBlank() && it != "/dev/null" }
            .toList()
        return if (rawPaths.isNotEmpty() && rawPaths.all { it.startsWith("a/") || it.startsWith("b/") }) 1 else 0
    }

    suspend fun applyPatch(patch: String): Result<String> = withContext(Dispatchers.IO) {
        var tempPatchPath: String? = null
        try {
            if (patch.isBlank()) {
                return@withContext Result.failure(Exception("Patch content is empty"))
            }

            val patchPaths = extractPatchPaths(patch)
            if (patchPaths.isEmpty()) {
                return@withContext Result.failure(Exception("Patch must include unified diff file headers (---/+++)."))
            }
            for (path in patchPaths) {
                if (path.startsWith("/") || path.contains("..")) {
                    return@withContext Result.failure(Exception("Unsafe patch path: $path"))
                }
            }

            val projectPath = sanitizePath(".")
            val brainPath = getBrainPath()
            executeCommand("mkdir -p '$brainPath'").getOrThrow()
            patchPaths.forEach { path ->
                val parentDir = sanitizePath(path).substringBeforeLast("/")
                executeCommand("mkdir -p '$parentDir'").getOrThrow()
            }

            tempPatchPath = "$brainPath/apply_patch_${System.currentTimeMillis()}.diff"
            val base64Patch = android.util.Base64.encodeToString(
                patch.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            executeCommand("echo '$base64Patch' | base64 -d > '$tempPatchPath'").getOrThrow()

            val stripLevel = determinePatchStripLevel(patch)
            var result = executeCommandDetailed(
                "cd '$projectPath' && patch --batch --forward -p$stripLevel < '$tempPatchPath' 2>&1"
            ).getOrThrow()

            if (result.exitCode == 127) {
                result = executeCommandDetailed(
                    "cd '$projectPath' && git apply --whitespace=nowarn --unsafe-paths '$tempPatchPath' 2>&1"
                ).getOrThrow()
            }

            if (result.exitCode != 0) {
                val errorText = result.output.trim().ifBlank { "Patch command failed with exit code ${result.exitCode}" }
                return@withContext Result.failure(Exception(errorText))
            }

            appendChangedFilesLog(patchPaths, "apply_patch")
                .onFailure { addDebugLog("⚠️ Failed to track patch changes: ${it.message}") }

            val summary = buildString {
                appendLine("Patch applied successfully.")
                appendLine("Files touched:")
                patchPaths.forEach { appendLine("- $it") }
            }.trimEnd()
            Result.success(summary)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (tempPatchPath != null) {
                executeCommand("rm -f '$tempPatchPath'")
            }
        }
    }
    
    /**
     * Edit specific lines in a file - more efficient than rewriting entire file
     * @param path File path relative to project root
     * @param startLine First line to replace (1-indexed)
     * @param endLine Last line to replace (inclusive)
     * @param newContent Replacement content for those lines
     */
    suspend fun editLines(path: String, startLine: Int, endLine: Int, newContent: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            
            // Read current file content (without line numbers for manipulation)
            val currentResult = executeCommand("cat '$safePath'")
            if (currentResult.isFailure) {
                return@withContext Result.failure(Exception("File not found: $path"))
            }
            
            val lines = currentResult.getOrThrow().lines().toMutableList()
            
            // Validate line range
            if (startLine < 1) {
                return@withContext Result.failure(Exception("start_line must be >= 1, got $startLine"))
            }
            if (endLine > lines.size) {
                return@withContext Result.failure(Exception("end_line ($endLine) exceeds file length (${lines.size} lines)"))
            }
            if (startLine > endLine) {
                return@withContext Result.failure(Exception("start_line ($startLine) must be <= end_line ($endLine)"))
            }
            
            // Remove old lines and insert new content
            val linesToRemove = endLine - startLine + 1
            repeat(linesToRemove) { lines.removeAt(startLine - 1) }
            lines.addAll(startLine - 1, newContent.lines())
            
            // Write back to file
            val newFileContent = lines.joinToString("\n")
            val writeResult = writeFile(path, newFileContent, trackChange = false)
            
            if (writeResult.isSuccess) {
                appendChangedFilesLog(listOf(path), "edit_lines")
                    .onFailure { addDebugLog("⚠️ Failed to track changed file $path: ${it.message}") }
                val newLineCount = newContent.lines().size
                Result.success("Replaced lines $startLine-$endLine ($linesToRemove lines) with $newLineCount new lines")
            } else {
                Result.failure(Exception("Failed to write edited file"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun ensureStructuredBrainFiles(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val brainPath = getBrainPath()
            executeCommand("mkdir -p '$brainPath'").getOrThrow()
            for ((filename, content) in DEFAULT_BRAIN_FILES) {
                val fullPath = "$brainPath/$filename"
                val encoded = android.util.Base64.encodeToString(
                    content.toByteArray(Charsets.UTF_8),
                    android.util.Base64.NO_WRAP
                )
                executeCommand(
                    "if [ ! -f '$fullPath' ]; then echo '$encoded' | base64 -d > '$fullPath'; fi"
                ).getOrThrow()
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncCurrentTaskMemory(task: String?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            ensureStructuredBrainFiles().getOrThrow()
            val taskBody = task?.takeIf { it.isNotBlank() } ?: "No active task."
            val content = buildString {
                appendLine("# Current Task")
                appendLine()
                appendLine("## Active Agent")
                appendLine("- ${_currentAgent.value.name}")
                appendLine()
                appendLine("## Task")
                appendLine("- ${taskBody.replace("\n", "\n  ")}")
                appendLine()
                appendLine("## Last Updated")
                appendLine("- ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            }.trimEnd()
            rewriteMemory("current_task.md", content, countsAsMemoryUpdate = false).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun appendChangedFilesLog(paths: Collection<String>, operation: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val normalizedPaths = paths
                .map { toProjectRelativePath(sanitizePath(it)).trimStart('/') }
                .filter { it.isNotBlank() }
                .distinct()

            if (normalizedPaths.isEmpty()) {
                return@withContext Result.success(Unit)
            }

            ensureStructuredBrainFiles().getOrThrow()
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val entry = normalizedPaths.joinToString("\n") { path ->
                "- $timestamp | $operation | $path"
            }
            writeMemory("changed_files.md", entry, countsAsMemoryUpdate = false).getOrThrow()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun readBrainFileRaw(filename: String): String {
        val brainPath = getBrainPath()
        val safeName = filename.replace("..", "").replace("/", "")
        val fullPath = "$brainPath/$safeName"
        return executeCommand("cat '$fullPath' 2>/dev/null").getOrNull().orEmpty().trim()
    }

    private fun compactResumeSection(text: String, maxLines: Int, maxChars: Int): String {
        if (text.isBlank()) return ""
        val cleaned = text.replace("\r", "").trim()
        val lines = cleaned.lines().filter { it.isNotBlank() }
        val limited = if (lines.size <= maxLines) lines else lines.take(maxLines)
        val joined = limited.joinToString("\n")
        return if (joined.length <= maxChars) joined else truncateOutput(joined, maxChars)
    }

    suspend fun buildStructuredBrainState(): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureStructuredBrainFiles().getOrThrow()
            val summary = compactResumeSection(readBrainFileRaw("summary.md"), 10, 900)
            val currentTask = compactResumeSection(readBrainFileRaw("current_task.md"), 8, 500)
            val todo = compactResumeSection(readBrainFileRaw("todo.md"), 8, 450)
            val decisions = compactResumeSection(readBrainFileRaw("decisions.md"), 6, 450)
            val changedFiles = compactResumeSection(readBrainFileRaw("changed_files.md").lines().takeLast(8).joinToString("\n"), 8, 500)
            val timeline = compactResumeSection(readBrainFileRaw("timeline.md").lines().takeLast(8).joinToString("\n"), 8, 550)
            val activeCommandSummary = listCommands().getOrNull()?.takeIf { it.isNotBlank() && it != "No tracked commands." }?.let {
                compactResumeSection(it, 8, 550)
            }.orEmpty()

            val content = buildString {
                appendLine("STRUCTURED RESUME STATE:")
                appendLine("Use this as the canonical working-state snapshot before relying on older chat history.")
                if (currentTask.isNotBlank()) {
                    appendLine("Current task snapshot:")
                    appendLine(currentTask)
                }
                if (summary.isNotBlank()) {
                    appendLine("Project summary snapshot:")
                    appendLine(summary)
                }
                if (todo.isNotBlank()) {
                    appendLine("Pending TODO snapshot:")
                    appendLine(todo)
                }
                if (decisions.isNotBlank()) {
                    appendLine("Decision snapshot:")
                    appendLine(decisions)
                }
                if (changedFiles.isNotBlank()) {
                    appendLine("Recent changed files:")
                    appendLine(changedFiles)
                }
                if (timeline.isNotBlank()) {
                    appendLine("Recent timeline:")
                    appendLine(timeline)
                }
                if (activeCommandSummary.isNotBlank()) {
                    appendLine("Tracked commands:")
                    appendLine(activeCommandSummary)
                }
            }.trim()

            Result.success(content)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ========== MEMORY/BRAIN TOOLS (no approval required) ==========
    
    /**
     * Read a file from the project's brain folder
     */
    suspend fun readMemory(filename: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureStructuredBrainFiles().getOrThrow()
            val brainPath = getBrainPath()
            val safeName = filename.replace("..", "").replace("/", "")
            val fullPath = "$brainPath/$safeName"
            
            // Use cat -n to include line numbers so LLM can reference specific lines
            val result = executeCommand("cat -n '$fullPath' 2>/dev/null")
            if (result.isSuccess && result.getOrNull()?.isNotBlank() == true) {
                result
            } else {
                Result.success("No memories found. Use write_memory to save plans, summaries, and notes.")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Append to a file in the project's brain folder (no approval required)
     * Creates the file if it doesn't exist. Always appends, never overwrites.
     * Use rewrite_memory to overwrite when summarizing/consolidating.
     */
    suspend fun writeMemory(
        filename: String,
        content: String,
        countsAsMemoryUpdate: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureStructuredBrainFiles().getOrThrow()
            val brainPath = getBrainPath()
            val safeName = filename.replace("..", "").replace("/", "")
            val fullPath = "$brainPath/$safeName"
            
            // Ensure brain directory exists
            executeCommand("mkdir -p '$brainPath'")
            
            // Create file if it doesn't exist, then APPEND content
            executeCommand("touch '$fullPath'")
            val base64Content = android.util.Base64.encodeToString(
                (content + "\n").toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val result = executeCommand("echo '$base64Content' | base64 -d >> '$fullPath'")
            
            if (result.isSuccess) {
                // Get the total line count so the LLM knows when to summarize
                val lineCountResult = executeCommand("wc -l < '$fullPath'")
                val lineCount = lineCountResult.getOrNull()?.trim()?.toIntOrNull() ?: 0
                
                val tip = if (lineCount > 50) {
                    "\n⚠️ Memory file has $lineCount lines and is getting long. Consider calling SUMMARIZER or read_memory first, then rewrite_memory to summarize and consolidate it."
                } else {
                    "\nTIP: Memory file now has $lineCount lines. If it gets too long, call SUMMARIZER or use rewrite_memory to summarize it."
                }
                if (countsAsMemoryUpdate) {
                    clearMemoryDirty("Updated $safeName with a new memory note.")
                }
                Result.success("✓ Memory appended: $safeName (+${content.lines().size} lines, total: $lineCount lines)$tip")
            } else {
                Result.failure(Exception("Failed to write memory"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Rewrite the entire memory file (overwrites). Used to summarize/consolidate.
     */
    suspend fun rewriteMemory(
        filename: String,
        content: String,
        countsAsMemoryUpdate: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureStructuredBrainFiles().getOrThrow()
            val brainPath = getBrainPath()
            val safeName = filename.replace("..", "").replace("/", "")
            val fullPath = "$brainPath/$safeName"
            
            executeCommand("mkdir -p '$brainPath'")
            
            val base64Content = android.util.Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val result = executeCommand("echo '$base64Content' | base64 -d > '$fullPath'")
            
            if (result.isSuccess) {
                val lineCount = content.lines().size
                if (countsAsMemoryUpdate) {
                    clearMemoryDirty("Rewrote $safeName to consolidate project memory.")
                }
                Result.success("✓ Memory rewritten: $safeName ($lineCount lines)")
            } else {
                Result.failure(Exception("Failed to rewrite memory"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Delete specific lines from a memory file.
     * Use read_memory first to see line numbers.
     */
    suspend fun deleteMemoryLines(
        filename: String,
        startLine: Int,
        endLine: Int,
        countsAsMemoryUpdate: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureStructuredBrainFiles().getOrThrow()
            val brainPath = getBrainPath()
            val safeName = filename.replace("..", "").replace("/", "")
            val fullPath = "$brainPath/$safeName"
            
            if (startLine < 1 || endLine < startLine) {
                return@withContext Result.failure(Exception("Invalid line range: $startLine-$endLine"))
            }
            
            val result = executeCommand("sed -i '${startLine},${endLine}d' '$fullPath'")
            if (result.isSuccess) {
                val lineCountResult = executeCommand("wc -l < '$fullPath'")
                val remaining = lineCountResult.getOrNull()?.trim() ?: "?"
                if (countsAsMemoryUpdate) {
                    clearMemoryDirty("Deleted obsolete lines from $safeName.")
                }
                Result.success("✓ Deleted lines $startLine-$endLine from $safeName. Remaining: $remaining lines.")
            } else {
                Result.failure(Exception("Failed to delete lines"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * List all files in the project's brain folder
     */
    suspend fun listMemory(): Result<String> = withContext(Dispatchers.IO) {
        try {
            ensureStructuredBrainFiles().getOrThrow()
            val brainPath = getBrainPath()
            addDebugLog("📁 list_memory: brainPath=$brainPath")
            
            // Ensure brain directory exists
            val mkdirResult = executeCommand("mkdir -p '$brainPath'")
            addDebugLog("📁 list_memory: mkdir result=${mkdirResult.isSuccess}")
            
            val result = executeCommand("ls -la '$brainPath' 2>/dev/null | tail -n +4")
            addDebugLog("📁 list_memory: ls result=${result.isSuccess}")
            
            if (result.isSuccess) {
                val files = result.getOrNull() ?: ""
                addDebugLog("📁 list_memory: files length=${files.length}")
                if (files.isBlank()) {
                    Result.success("📁 Brain folder is empty. Use write_memory to save plans, summaries, and notes.")
                } else {
                    Result.success("📁 Memory files in ${_currentProjectFolder.value}/brain/:\n$files")
                }
            } else {
                addDebugLog("📁 list_memory: ls failed")
                Result.success("📁 Brain folder is empty.")
            }
        } catch (e: Exception) {
            addDebugLog("📁 list_memory: exception=${e.message}")
            Result.failure(e)
        }
    }

    // ========== FILE INSPECTION TOOLS ==========
    
    /**
     * Get the number of lines in a file
     */
    suspend fun fileLineCount(path: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            val result = executeCommand("wc -l < '$safePath'")
            if (result.isSuccess) {
                val count = result.getOrNull()?.trim() ?: "0"
                Result.success("$count lines in ${toProjectRelativePath(safePath)}")
            } else {
                Result.failure(Exception("Failed to count lines: ${result.exceptionOrNull()?.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Read a specific range of lines from a file with original line numbers
     */
    suspend fun readFileLines(path: String, startLine: Int, endLine: Int): Result<String> = withContext(Dispatchers.IO) {
        try {
            val safePath = sanitizePath(path)
            val start = maxOf(1, startLine)
            val end = maxOf(start, endLine)
            // Use awk to preserve original line numbers
            val result = executeCommand("awk 'NR>=$start && NR<=$end {printf \"%6d  %s\\n\", NR, \$0}' '$safePath'")
            if (result.isSuccess) {
                val content = result.getOrNull() ?: ""
                if (content.isBlank()) {
                    Result.success("[No content in lines $start-$end of ${toProjectRelativePath(safePath)}]")
                } else {
                    Result.success("Lines $start-$end of ${toProjectRelativePath(safePath)}:\n$content")
                }
            } else {
                Result.failure(Exception("Failed to read lines: ${result.exceptionOrNull()?.message}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Search the web using DuckDuckGo HTML, fetch each result page, and summarize via LLM
     */
    suspend fun webSearch(query: String, ollamaService: OllamaService, settingsRepo: com.example.llamadroid.data.SettingsRepository): Result<String> = withContext(Dispatchers.IO) {
        try {
            val maxResults = settingsRepo.agentWebSearchMaxResults.value
            val maxChars = settingsRepo.agentWebSearchMaxChars.value
            val summarizerModel = settingsRepo.agentWebSearchModel.value
            val summarizerCtx = settingsRepo.agentWebSearchNumCtx.value
            
            setStatusText(context.getString(R.string.agent_status_web_searching, query))
            addDebugLog("🌐 Web search: query=$query, maxResults=$maxResults, model=$summarizerModel")
            
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            
            val searchRequest = okhttp3.Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()
            
            val searchResponse = client.newCall(searchRequest).execute()
            if (!searchResponse.isSuccessful) {
                return@withContext Result.failure(Exception("Search failed: HTTP ${searchResponse.code}"))
            }
            
            val html = searchResponse.body?.string() ?: ""
            
            // Extract result links
            val linkPattern = Regex("""<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>([^<]*)</a>""")
            val links = linkPattern.findAll(html).toList()
            
            val resultCount = minOf(links.size, maxResults)
            if (resultCount == 0) {
                return@withContext Result.success("Web search results for: $query\n\nNo results found.")
            }
            
            val output = StringBuilder()
            output.append("Web search results for: $query ($resultCount results summarized)\n\n")
            
            for (i in 0 until resultCount) {
                val link = links[i]
                val href = link.groupValues[1]
                val title = link.groupValues[2].trim()
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#x27;", "'").replace("&quot;", "\"")
                
                // Decode DuckDuckGo redirect URLs
                val actualUrl = if (href.contains("uddg=")) {
                    try {
                        val uddg = href.substringAfter("uddg=").substringBefore("&")
                        java.net.URLDecoder.decode(uddg, "UTF-8")
                    } catch (_: Exception) { href }
                } else href
                
                setStatusText(context.getString(R.string.agent_status_web_fetching, i + 1, resultCount, title))
                
                // Fetch page content
                var summary = ""
                try {
                    val pageRequest = okhttp3.Request.Builder()
                        .url(actualUrl)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                        .build()
                    
                    val pageResponse = client.newCall(pageRequest).execute()
                    if (pageResponse.isSuccessful) {
                        val pageHtml = pageResponse.body?.string() ?: ""
                        
                        // Strip HTML tags and extract text content
                        val textContent = pageHtml
                            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("&[a-zA-Z]+;"), " ")
                            .replace(Regex("&#\\d+;"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        
                        if (textContent.length > 100) {
                            val truncatedContent = if (textContent.length > maxChars) {
                                textContent.take(maxChars) + "... [truncated]"
                            } else textContent
                            
                            setStatusText(context.getString(R.string.agent_status_web_summarizing, i + 1, resultCount, title))
                            addDebugLog("🌐 Summarizing result ${i + 1}: ${title.take(50)}")
                            
                            // Summarize via LLM
                            try {
                                val summaryMessages = listOf(
                                    OllamaService.ChatMessage(
                                        role = "system",
                                        content = "You are a web page summarizer. Given web page content, provide a concise summary in 2-3 sentences covering the key information. Be factual and informative. Do NOT say 'this page' or 'this article', just state the facts directly."
                                    ),
                                    OllamaService.ChatMessage(
                                        role = "user",
                                        content = "Summarize this web page titled \"$title\":\n\n$truncatedContent"
                                    )
                                )
                                
                                // Create a temporary OllamaService with the web search context size
                                val savedCtx = ollamaService.numCtx.value
                                ollamaService.setNumCtx(summarizerCtx)
                                
                                val summaryResult = ollamaService.chatWithToolsStreaming(
                                    model = summarizerModel,
                                    messages = summaryMessages,
                                    tools = emptyList()
                                ) { _, _ -> }
                                
                                // Restore original context size
                                ollamaService.setNumCtx(savedCtx)
                                
                                summaryResult.onSuccess { chatResponse ->
                                    val responseText = chatResponse.message.content.trim()
                                    if (responseText.isNotBlank()) {
                                        summary = responseText
                                    }
                                }
                                summaryResult.onFailure { e ->
                                    addDebugLog("🌐 Summary failed for result ${i + 1}: ${e.message}")
                                    summary = "[Summary failed: ${e.message}]"
                                }
                            } catch (e: Exception) {
                                addDebugLog("🌐 LLM call failed: ${e.message}")
                                summary = "[LLM summarization failed]"
                            }
                        } else {
                            summary = "[Page content too short to summarize]"
                        }
                    } else {
                        summary = "[Failed to fetch: HTTP ${pageResponse.code}]"
                    }
                } catch (e: Exception) {
                    summary = "[Failed to fetch: ${e.message?.take(80)}]"
                }
                
                output.append("${i + 1}. $title\n   URL: $actualUrl\n")
                if (summary.isNotBlank()) output.append("   Summary: $summary\n")
                output.append("\n")
            }
            
            output.append("TIP: Use the fetch_url tool with any URL above to get the full page content if you need more details.")
            
            Companion.refreshIdleStatusIfNeeded()
            Result.success(output.toString().trimEnd())
        } catch (e: Exception) {
            Companion.refreshIdleStatusIfNeeded()
            Result.failure(e)
        }
    }
    /**
     * Search local Kiwix server, fetch result pages, and summarize via LLM
     */
    suspend fun kiwixSearch(query: String, ollamaService: OllamaService, settingsRepo: com.example.llamadroid.data.SettingsRepository): Result<String> = withContext(Dispatchers.IO) {
        try {
            val kiwixUrl = settingsRepo.agentKiwixUrl.value.trimEnd('/')
            val maxResults = settingsRepo.agentKiwixMaxResults.value
            val maxChars = settingsRepo.agentKiwixMaxChars.value
            val summarizerModel = settingsRepo.agentKiwixModel.value
            val summarizerCtx = settingsRepo.agentKiwixNumCtx.value
            
            setStatusText(context.getString(R.string.agent_status_kiwix_searching, query))
            addDebugLog("📚 Kiwix search: query=$query, url=$kiwixUrl, maxResults=$maxResults")
            
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val searchUrl = "$kiwixUrl/search?pattern=$encodedQuery"
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val searchRequest = okhttp3.Request.Builder()
                .url(searchUrl)
                .build()
            
            val searchResponse = client.newCall(searchRequest).execute()
            if (!searchResponse.isSuccessful) {
                return@withContext Result.failure(Exception("Kiwix search failed: HTTP ${searchResponse.code}"))
            }
            
            val html = searchResponse.body?.string() ?: ""
            
            // Extract content links from Kiwix search results
            // Pattern typically: <a href="/content/zim_name/A/Article_Title">Title</a>
            val linkPattern = Regex("""<a[^>]*href="(/content/[^"]*)"[^>]*>([^<]*)</a>""")
            val links = linkPattern.findAll(html).toList()
            
            val resultCount = minOf(links.size, maxResults)
            if (resultCount == 0) {
                return@withContext Result.success("Kiwix search results for: $query\n\nNo results found on server $kiwixUrl.")
            }
            
            val output = StringBuilder()
            output.append("Kiwix search results for: $query ($resultCount summarized)\n\n")
            
            for (i in 0 until resultCount) {
                val link = links[i]
                val relativePath = link.groupValues[1]
                val title = link.groupValues[2].trim()
                val fullResultUrl = "$kiwixUrl$relativePath"
                
                setStatusText(context.getString(R.string.agent_status_kiwix_fetching, i + 1, resultCount, title))
                
                // Fetch page content
                var summary = ""
                try {
                    val pageRequest = okhttp3.Request.Builder()
                        .url(fullResultUrl)
                        .build()
                    
                    val pageResponse = client.newCall(pageRequest).execute()
                    if (pageResponse.isSuccessful) {
                        val pageHtml = pageResponse.body?.string() ?: ""
                        
                        // Strip HTML tags and extract text content
                        val textContent = pageHtml
                            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                            .replace(Regex("<[^>]+>"), " ")
                            .replace(Regex("&[a-zA-Z]+;"), " ")
                            .replace(Regex("&#\\d+;"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        
                        if (textContent.length > 100) {
                            val truncatedContent = if (textContent.length > maxChars) {
                                textContent.take(maxChars) + "... [truncated]"
                            } else textContent
                            
                            setStatusText(context.getString(R.string.agent_status_kiwix_summarizing, i + 1, resultCount, title))
                            
                            // Summarize via LLM
                            try {
                                val summaryMessages = listOf(
                                    OllamaService.ChatMessage(
                                        role = "system",
                                        content = "You are a encyclopedia summarizer. Given an article content, provide a concise summary in 2-3 sentences covering the key information."
                                    ),
                                    OllamaService.ChatMessage(
                                        role = "user",
                                        content = "Summarize this article titled \"$title\":\n\n$truncatedContent"
                                    )
                                )
                                
                                val savedCtx = ollamaService.numCtx.value
                                ollamaService.setNumCtx(summarizerCtx)
                                
                                val summaryResult = ollamaService.chatWithToolsStreaming(
                                    model = summarizerModel,
                                    messages = summaryMessages,
                                    tools = emptyList()
                                ) { _, _ -> }
                                
                                ollamaService.setNumCtx(savedCtx)
                                
                                summaryResult.onSuccess { chatResponse ->
                                    val responseText = chatResponse.message.content.trim()
                                    if (responseText.isNotBlank()) summary = responseText
                                }
                                summaryResult.onFailure { e -> summary = "[Summary failed: ${e.message}]" }
                            } catch (e: Exception) {
                                summary = "[LLM summarization failed]"
                            }
                        } else {
                            summary = "[Content too short]"
                        }
                    } else {
                        summary = "[Failed to fetch: HTTP ${pageResponse.code}]"
                    }
                } catch (e: Exception) {
                    summary = "[Failed to fetch: ${e.message}]"
                }
                
                output.append("${i + 1}. $title\n   URL: $fullResultUrl\n")
                if (summary.isNotBlank()) output.append("   Summary: $summary\n")
                output.append("\n")
            }
            
            Companion.refreshIdleStatusIfNeeded()
            Result.success(output.toString().trimEnd())
        } catch (e: Exception) {
            Companion.refreshIdleStatusIfNeeded()
            Result.failure(e)
        }
    }

    data class BackgroundCommand(
        val id: String,
        val command: String,
        val terminalMessageId: String,
        val projectPath: String,
        val sentinel: String,
        val session: com.jcraft.jsch.Session,
        val channel: com.jcraft.jsch.ChannelShell,
        val stdin: java.io.PipedOutputStream,
        val stateLock: Any = Any(),
        val pendingLine: StringBuilder = StringBuilder(),
        val fullTranscript: StringBuilder = StringBuilder(),
        val tailLines: MutableList<String> = mutableListOf(),
        val startedAt: Long,
        @Volatile var lastActivityAt: Long,
        @Volatile var isRunning: Boolean = true,
        @Volatile var exitCode: Int = -1,
        @Volatile var outputVersion: Int = 0,
        @Volatile var deliveredVersion: Int = 0,
        @Volatile var lastRequestedLines: Int = 10,
        var autoUpdateJob: Job? = null
    )

    private val activeCommands = java.util.concurrent.ConcurrentHashMap<String, BackgroundCommand>()
}
