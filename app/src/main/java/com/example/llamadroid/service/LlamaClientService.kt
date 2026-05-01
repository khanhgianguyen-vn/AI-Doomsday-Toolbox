package com.example.llamadroid.service

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.example.llamadroid.data.api.LlamaApi
import com.example.llamadroid.data.api.LlamaChatRequest
import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.api.LlamaChatResponse
import com.example.llamadroid.data.api.LlamaStreamOptions
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.LlamaServerEntity
import com.example.llamadroid.data.model.hasEmbeddedAudioTranscript
import com.example.llamadroid.data.model.mergeUserTextWithAudioTranscript
import kotlinx.coroutines.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.example.llamadroid.R
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import com.example.llamadroid.data.repository.LlamaRepository
import com.example.llamadroid.widget.NoteDisplayWidgetProvider
import com.example.llamadroid.widget.OrganizerCalendarWidgetProvider
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume

internal fun nativeChatToolAwarenessMessages(
    toolConfig: NativeChatToolConfig
): List<OllamaService.ChatMessage> = buildList {
    if (!toolConfig.toolsEnabled) return@buildList
    val currentYear = java.time.LocalDate.now().year

    if (toolConfig.noteToolsEnabled || toolConfig.todoToolsEnabled) {
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Native chat note tools are available. When the user asks you to write, save, create, update, or improve a note, call the appropriate note tool in this turn instead of only saying that you will do it. Use create_note to save durable findings and long research notes with source citations. Use list_notes to discover whitelisted note IDs, then read_note to inspect exact note content and todo item indexes before editing. Do not ask the user to provide note IDs or todo indexes when list_notes/read_note can find them. Use update_note or replace_note_text to revise notes later, and read_note to recover previous research instead of forgetting it. For todo lists, use the todo-list tools for checking, unchecking, adding, editing, or removing items. Existing notes outside the whitelist are intentionally invisible."
            )
        )
    }

    if (toolConfig.imageGenerationEnabled) {
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "When using generate_image, first improve the user's image idea into a stronger prompt with clear subject, composition, style, lighting, color, medium, and constraints. If generated-image review is enabled and an attached result image appears, inspect it before the final answer; regenerate with a better optimized prompt when it misses the request, otherwise answer using the available result."
            )
        )
    }

    if (toolConfig.webSearchEnabled || toolConfig.fetchUrlEnabled) {
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Web navigation tools are available. Use web_search for broad discovery, search_page to inspect links and snippets inside a specific page, and fetch_url to read the full content of a chosen URL. For tasks like latest commits, releases, issues, changelogs, or docs on a project site, first find the official page, then use search_page with a navigation query such as commits or releases, then fetch_url the returned URL before summarizing."
            )
        )
    }

    if (toolConfig.calendarToolsEnabled || toolConfig.alarmToolsEnabled) {
        add(
            OllamaService.ChatMessage(
                role = "system",
                content = "Organizer tools are available. Use list_calendar_events/read_calendar_event and list_alarms/read_alarm to discover IDs before editing or deleting; do not ask the user for event or alarm IDs when the tools can find them. Calendar events can exist without alarms. Create or update phone alarms only when the user asks for an alert/reminder or when it is clearly useful for scheduling. For alarm and event times, use the device-local timezone; if the user gives a month/day without a year, assume the current year ($currentYear) unless they explicitly say another year."
            )
        )
    }
}

class LlamaClientService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Active generation state
    private var job: Job? = null
    private var notificationTaskId: Int? = null
    
    // Repository to save messages
    private lateinit var repository: LlamaRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var ollamaService: OllamaService
    private lateinit var database: AppDatabase
    private val llamaServerChatService = LlamaServerChatService()
    private lateinit var nativeChatToolRuntime: NativeChatToolRuntime
    private val whisperBindingIntent by lazy { Intent(applicationContext, WhisperService::class.java) }
    
    override fun onCreate() {
        super.onCreate()
        // Initialize repository (using manual instantiation for now, assuming DI isn't fully set up)
        database = AppDatabase.getDatabase(applicationContext)
        repository = LlamaRepository(
            database.llamaServerDao(),
            database.llamaChatDao(),
            database.llamaChatFolderDao(),
            database.llamaMessageDao()
        )
        settingsRepo = SettingsRepository(applicationContext)
        ollamaService = OllamaService(applicationContext)
        nativeChatToolRuntime = NativeChatToolRuntime(
            noteDao = database.noteDao(),
            organizerDao = database.organizerDao(),
            alarmScheduler = { alarm -> OrganizerAlarmScheduler.scheduleAlarm(applicationContext, alarm) },
            alarmCanceler = { alarmId -> OrganizerAlarmScheduler.cancelAlarm(applicationContext, alarmId) },
            organizerChanged = { OrganizerCalendarWidgetProvider.refreshAll(applicationContext) },
            notesChanged = { NoteDisplayWidgetProvider.refreshAll(applicationContext) },
            imageGenerator = NativeChatOnnxImageGenerator(applicationContext, database),
            pdfTextExtractor = { pdfBytes, maxChars ->
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
                extractNativePdfTextFromBytes(pdfBytes, maxChars)
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> {
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE)
                val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
                val audioPath = intent.getStringExtra(EXTRA_AUDIO_PATH)
                
                if (chatId != -1L) {
                    startGeneration(chatId, serverId, userMessage, imagePath, audioPath)
                } else {
                    DebugLog.log("LlamaClientService: Missing params for generation")
                    Companion.updateState(GenerationState.Error("Missing parameters for generation", chatId))
                }
            }
            ACTION_STOP -> {
                stopGeneration()
            }
        }
        return START_NOT_STICKY
    }

    private fun startGeneration(
        chatId: Long,
        serverId: Long,
        userMessage: String?,
        imagePath: String?,
        audioPath: String?
    ) {
        if (job?.isActive == true) {
            DebugLog.log("LlamaClientService: Generation already in progress")
            return
        }

        // Start Foreground
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_CLIENT,
            "Llama Chat"
        )
        notificationTaskId = taskId
        startForeground(taskId, notification)
        
        WakeLockManager.acquire(applicationContext, "LlamaClientService")
        WakeLockManager.acquireWifiLock(applicationContext, "LlamaClientService")
        warnIfBatteryOptimizationMayThrottle(chatId)

        resetThinking()
        Companion.updateState(GenerationState.Generating(chatId = chatId, content = ""))

        job = serviceScope.launch {
            var assistantMsgId: Long = -1L
            val progress = StreamingProgress()
            try {
                val server = resolveServerForGeneration(serverId)
                    ?: throw Exception("Server with ID $serverId not found")
                DebugLog.log("LlamaClientService: Connecting to ${server.baseUrl()} for Chat ID $chatId")

                val chat = repository.getChat(chatId) ?: throw Exception("Chat with ID $chatId not found")

                val preparedUserTurn = prepareUserTurnForServer(
                    chatId = chatId,
                    server = server,
                    userMessage = userMessage,
                    imagePath = imagePath,
                    audioPath = audioPath
                )

                if (preparedUserTurn.shouldPersist() && !preparedUserTurn.alreadyPersisted) {
                    repository.addMessage(
                        chatId = chatId,
                        role = "user",
                        content = preparedUserTurn.content,
                        imagePath = preparedUserTurn.imagePath,
                        audioPath = preparedUserTurn.audioPath
                    )
                }

                val history = prepareHistoryForServer(chatId, server)
                if (history.isEmpty()) throw Exception("Chat history is empty")

                val isContinuation = !preparedUserTurn.shouldPersist() && history.last().role == "assistant"
                val assistantPreparation = prepareAssistantMessage(chatId, history, isContinuation)
                assistantMsgId = assistantPreparation.assistantMsgId
                progress.content = assistantPreparation.content
                progress.thinking = assistantPreparation.thinking

                val params = parseChatParams(chat.apiParams)
                val paramEnableThinking = (params["enable_thinking"] as? Boolean) ?: true
                val toolConfig = NativeChatToolConfig.fromParams(params)
                val useNativeTools = !isContinuation && toolConfig.hasEnabledTools()

                if (useNativeTools) {
                    streamNativeToolResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = assistantPreparation.history,
                        assistantMsgId = assistantMsgId,
                        thinkingEnabled = paramEnableThinking,
                        params = params,
                        toolConfig = toolConfig,
                        progress = progress
                    )
                } else if (server.isOllamaEngine()) {
                    streamOllamaResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = assistantPreparation.history,
                        assistantMsgId = assistantMsgId,
                        isContinuation = isContinuation,
                        thinkingEnabled = !isContinuation && paramEnableThinking,
                        progress = progress
                    )
                } else {
                    streamLlamaServerResponse(
                        chatId = chatId,
                        taskId = taskId,
                        chat = chat,
                        server = server,
                        history = assistantPreparation.history,
                        assistantMsgId = assistantMsgId,
                        isContinuation = isContinuation,
                        params = params,
                        progress = progress
                    )
                }

                val finalElapsedMs = System.currentTimeMillis() - progress.streamStartTimeMs
                val finalElapsed = finalElapsedMs / 1000.0
                val finalTps = progress.reportedTokensPerSecond
                    ?: if (finalElapsed > 0.0) progress.tokenCount / finalElapsed else 0.0
                if (progress.completionTokens == 0) {
                    progress.completionTokens = progress.tokenCount
                }

                repository.updateMessageTruncatedStatus(assistantMsgId, progress.isTruncated)
                repository.updateMessageThinkingAndContent(
                    assistantMsgId,
                    progress.content,
                    progress.thinking.takeIf { it.isNotBlank() },
                    promptTokens = progress.promptTokens,
                    completionTokens = progress.completionTokens,
                    tps = finalTps,
                    generationTimeMs = finalElapsedMs
                )
                persistGeneratedImageMessages(chatId, progress)

                Companion.updateState(GenerationState.Completed(
                    chatId = chatId,
                    content = progress.content,
                    thinking = progress.thinking.takeIf { it.isNotBlank() },
                    completionTokens = progress.completionTokens,
                    promptTokens = progress.promptTokens,
                    tokensPerSecond = finalTps
                ))
            } catch (e: Exception) {
                if (e is CancellationException) {
                     DebugLog.log("LlamaClientService: Generation cancelled")
                     if (assistantMsgId != -1L) {
                         val finalElapsedMs = System.currentTimeMillis() - progress.streamStartTimeMs
                         val finalElapsed = finalElapsedMs / 1000.0
                         val finalTps = progress.reportedTokensPerSecond
                             ?: if (finalElapsed > 0.0) progress.tokenCount / finalElapsed else 0.0
                         repository.updateMessageThinkingAndContent(
                             assistantMsgId,
                             progress.content,
                             progress.thinking.takeIf { it.isNotBlank() },
                             promptTokens = progress.promptTokens,
                             completionTokens = if (progress.completionTokens == 0) progress.tokenCount else progress.completionTokens,
                             tps = finalTps,
                             generationTimeMs = finalElapsedMs
                         )
                         persistGeneratedImageMessages(chatId, progress)
                     }
                } else {
                    DebugLog.log("LlamaClientService: Error ${e.message}")
                    Companion.updateState(GenerationState.Error(e.message ?: "Unknown error", chatId))
                }
            } finally {
                WakeLockManager.release("LlamaClientService")
                WakeLockManager.releaseWifiLock("LlamaClientService")
                notificationTaskId?.let { taskId ->
                    UnifiedNotificationManager.dismissTask(taskId)
                }
                notificationTaskId = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                job = null
            }
        }
    }

    private suspend fun resolveServerForGeneration(serverId: Long): LlamaServerEntity? {
        return if (serverId == -1L) {
            database.llamaServerDao().getLastUsedServer()
        } else {
            repository.getServer(serverId)
        }
    }

    private suspend fun prepareUserTurnForServer(
        chatId: Long,
        server: LlamaServerEntity,
        userMessage: String?,
        imagePath: String?,
        audioPath: String?
    ): PreparedUserTurn {
        val persistedImagePath = imagePath?.takeIf { it.isNotBlank() }
        val originalAudioPath = audioPath?.takeIf { it.isNotBlank() }
        if (originalAudioPath == null) {
            return PreparedUserTurn(
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = null
            )
        }

        return if (server.supportsDirectAudioInput()) {
            val preparedAudioPath = prepareAudioPathForNativeLlama(applicationContext, originalAudioPath).getOrThrow()
            PreparedUserTurn(
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = preparedAudioPath
            )
        } else {
            val pendingMessageId = repository.addMessage(
                chatId = chatId,
                role = "user",
                content = userMessage.orEmpty(),
                imagePath = persistedImagePath,
                audioPath = originalAudioPath
            )
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = "",
                    isTranscribingAudio = true,
                    transcribingMessageId = pendingMessageId
                )
            )
            val transcript = try {
                transcribeAudioAttachment(server, originalAudioPath).getOrThrow().text
            } catch (e: Exception) {
                repository.updateMessageErrorStatus(pendingMessageId, true)
                throw e
            }
            val mergedContent = mergeUserTextWithAudioTranscript(userMessage.orEmpty(), transcript)
            repository.updateMessageContentAndError(
                id = pendingMessageId,
                content = mergedContent,
                isError = false
            )
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = "",
                    isTranscribingAudio = false
                )
            )
            PreparedUserTurn(
                content = mergedContent,
                imagePath = persistedImagePath,
                audioPath = originalAudioPath,
                alreadyPersisted = true
            )
        }
    }

    private suspend fun prepareHistoryForServer(
        chatId: Long,
        server: LlamaServerEntity
    ): List<LlamaMessageEntity> {
        val messages = repository.getMessages(chatId).first().filterNot { it.isError }
        return messages.map { message ->
            when {
                server.supportsDirectAudioInput() -> normalizeAudioAttachmentForDirectInput(message)
                else -> ensureHistoryTranscript(message, server)
            }
        }
    }

    private suspend fun normalizeAudioAttachmentForDirectInput(
        message: LlamaMessageEntity
    ): LlamaMessageEntity {
        val originalAudioPath = message.audioPath?.takeIf { it.isNotBlank() } ?: return message
        if (hasEmbeddedAudioTranscript(message.content)) return message

        val normalizedAudioPath = prepareAudioPathForNativeLlama(applicationContext, originalAudioPath).getOrThrow()
        if (normalizedAudioPath != originalAudioPath) {
            repository.updateMessageAudioPath(message.id, normalizedAudioPath)
            return message.copy(audioPath = normalizedAudioPath)
        }
        return message
    }

    private suspend fun ensureHistoryTranscript(
        message: LlamaMessageEntity,
        server: LlamaServerEntity
    ): LlamaMessageEntity {
        val originalAudioPath = message.audioPath?.takeIf { it.isNotBlank() } ?: return message
        if (message.role != "user" || hasEmbeddedAudioTranscript(message.content)) {
            return message
        }

        val transcript = transcribeAudioAttachment(server, originalAudioPath).getOrThrow().text
        val mergedContent = mergeUserTextWithAudioTranscript(message.content, transcript)
        repository.updateMessage(message.id, mergedContent)
        return message.copy(content = mergedContent)
    }

    private suspend fun prepareAssistantMessage(
        chatId: Long,
        history: List<LlamaMessageEntity>,
        isContinuation: Boolean
    ): AssistantMessagePreparation {
        if (isContinuation && history.isNotEmpty() && history.last().role == "assistant") {
            val lastAssistantMsg = history.last()
            val existingThinking = lastAssistantMsg.thinking ?: ""
            val rawContent = lastAssistantMsg.content
            val lastWsIndex = rawContent.indexOfLast { it == ' ' || it == '\n' || it == '\t' }
            val existingContent = if (lastWsIndex > 0 && rawContent.isNotEmpty() && !rawContent.last().isWhitespace()) {
                rawContent.substring(0, lastWsIndex + 1)
            } else {
                rawContent
            }

            if (existingContent != rawContent) {
                repository.updateMessage(lastAssistantMsg.id, existingContent)
            }

            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = existingContent,
                    thinking = existingThinking.takeIf { it.isNotBlank() }
                )
            )

            DebugLog.log("LlamaClientService: Continuing from existing assistant message (${existingContent.length} chars)")
            return AssistantMessagePreparation(
                history = history.dropLast(1) + lastAssistantMsg.copy(content = existingContent),
                assistantMsgId = lastAssistantMsg.id,
                content = existingContent,
                thinking = existingThinking
            )
        }

        return AssistantMessagePreparation(
            history = history,
            assistantMsgId = repository.addMessage(chatId, "assistant", ""),
            content = "",
            thinking = ""
        )
    }

    private fun parseChatParams(apiParams: String?): Map<String, Any> {
        if (apiParams.isNullOrBlank()) return emptyMap()
        return try {
            Gson().fromJson(apiParams, Map::class.java) as? Map<String, Any> ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun streamLlamaServerResponse(
        chatId: Long,
        taskId: Int,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        server: LlamaServerEntity,
        history: List<LlamaMessageEntity>,
        assistantMsgId: Long,
        isContinuation: Boolean,
        params: Map<String, Any>,
        progress: StreamingProgress
    ) {
        val baseUrl = server.baseUrl()
        val apiMessages = mutableListOf<LlamaChatMessage>()
        if (!chat.systemPrompt.isNullOrBlank()) {
            apiMessages += LlamaChatMessage("system", chat.systemPrompt)
        }
        apiMessages += history.map { LlamaChatMessage(it.role, it.toNativeLlamaContent()) }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .build()
                chain.proceed(request)
            }
            .build()

        val api = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlamaApi::class.java)

        val request = LlamaChatRequest(
            model = "default",
            messages = apiMessages,
            stream = true,
            streamOptions = LlamaStreamOptions(includeUsage = true),
            temperature = (params["temperature"] as? Number)?.toFloat(),
            top_p = (params["top_p"] as? Number)?.toFloat(),
            top_k = (params["top_k"] as? Number)?.toInt(),
            min_p = (params["min_p"] as? Number)?.toFloat(),
            seed = (params["seed"] as? Number)?.toInt(),
            repeat_penalty = (params["repeat_penalty"] as? Number)?.toFloat(),
            frequency_penalty = (params["frequency_penalty"] as? Number)?.toFloat(),
            presence_penalty = (params["presence_penalty"] as? Number)?.toFloat(),
            chat_template_kwargs = if (isContinuation || (params["enable_thinking"] as? Boolean) == false) {
                mapOf("enable_thinking" to false)
            } else {
                mapOf("enable_thinking" to true)
            }
        )

        DebugLog.log("LlamaClientService: Sending request to $baseUrl")
        showDebugToast("Connecting to $baseUrl...")

        val call = try {
            api.chatCompletion(request)
        } catch (e: Exception) {
            showDebugToast("Connection Failed: ${e.message}")
            throw Exception("Failed to connect to $baseUrl: ${e.message}")
        }

        val response = call.execute()
        if (!response.isSuccessful) {
            val errorBody = response.errorBody()?.string() ?: ""
            showDebugToast("Server Error ${response.code()}")
            throw Exception("Server Error ${response.code()}: $errorBody")
        }

        showDebugToast("Connected! receiving stream...")

        val source = response.body()?.source() ?: throw Exception("Empty response body from server")
        val reader = BufferedReader(InputStreamReader(source.inputStream()))
        var rawSequence = progress.content
        var lastUpdate = System.currentTimeMillis()
        var firstDeltaReceived = false
        val needsSpaceCheck = isContinuation && progress.content.isNotEmpty() && !progress.content.last().isWhitespace()
        progress.streamStartTimeMs = System.currentTimeMillis()
        progress.tokenCount = 0
        progress.promptTokens = 0
        progress.completionTokens = 0
        progress.isTruncated = false

        while (true) {
            currentCoroutineContext().ensureActive()
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            if (line == "data: [DONE]") break

            if (!line.startsWith("data: ")) continue
            val json = line.substring(6)
            try {
                val chunk = Gson().fromJson(json, LlamaChatResponse::class.java)
                chunk.usage?.let { usage ->
                    progress.promptTokens = usage.promptTokens
                    progress.completionTokens = usage.completionTokens
                }
                chunk.choices.firstOrNull()?.finish_reason?.let { finishReason ->
                    val normalized = finishReason.lowercase()
                    if (normalized == "length" || normalized == "max_tokens") {
                        progress.isTruncated = true
                        DebugLog.log("LlamaClientService: Truncation detected via finish_reason='$normalized'")
                    }
                }

                val deltaObj = chunk.choices.firstOrNull()?.delta
                val delta = deltaObj?.content ?: ""
                val dedicatedReasoning = deltaObj?.reasoningContent ?: deltaObj?.thinking ?: ""
                if (delta.isEmpty() && dedicatedReasoning.isEmpty()) continue

                if (delta.isNotEmpty() && !firstDeltaReceived && needsSpaceCheck && !delta.first().isWhitespace()) {
                    rawSequence += " "
                }
                firstDeltaReceived = true
                rawSequence += delta

                if (!isContinuation) {
                    val extracted = extractThinking(rawSequence, dedicatedReasoning)
                    progress.content = extracted.first
                    progress.thinking = extracted.second
                } else {
                    progress.content = rawSequence
                }

                progress.tokenCount++
                updateStreamingProgress(
                    chatId = chatId,
                    taskId = taskId,
                    assistantMsgId = assistantMsgId,
                    progress = progress,
                    lastUpdateMs = lastUpdate
                ).also { lastUpdate = it }
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun streamOllamaResponse(
        chatId: Long,
        taskId: Int,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        server: LlamaServerEntity,
        history: List<LlamaMessageEntity>,
        assistantMsgId: Long,
        isContinuation: Boolean,
        thinkingEnabled: Boolean,
        progress: StreamingProgress
    ) {
        val modelName = server.modelName?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(getString(R.string.llama_ollama_model_required))

        syncOllamaService(server)

        var rawSequence = progress.content
        var lastUpdate = System.currentTimeMillis()
        var firstDeltaReceived = false
        val needsSpaceCheck = isContinuation && progress.content.isNotEmpty() && !progress.content.last().isWhitespace()
        progress.streamStartTimeMs = System.currentTimeMillis()
        progress.tokenCount = 0
        progress.promptTokens = 0
        progress.completionTokens = 0
        progress.isTruncated = false

        val result = ollamaService.chatWithToolsStreaming(
            model = modelName,
            messages = buildOllamaMessages(chat, history, server),
            thinkingEnabled = thinkingEnabled,
            numCtxOverride = chat.contextSize.takeIf { it > 0 },
            onChunk = { delta, thinkingDelta ->
                val contentDelta = delta.orEmpty()
                val reasoningDelta = thinkingDelta.orEmpty()
                if (contentDelta.isEmpty() && reasoningDelta.isEmpty()) {
                    return@chatWithToolsStreaming
                }

                if (contentDelta.isNotEmpty()) {
                    if (!firstDeltaReceived && needsSpaceCheck && !contentDelta.first().isWhitespace()) {
                        rawSequence += " "
                    }
                    firstDeltaReceived = true
                    rawSequence += contentDelta
                    progress.content = rawSequence
                }
                if (reasoningDelta.isNotEmpty()) {
                    progress.thinking += reasoningDelta
                }

                progress.tokenCount++
                progress.lastTokenAtMs = System.currentTimeMillis()
                runBlocking {
                    lastUpdate = updateStreamingProgress(
                        chatId = chatId,
                        taskId = taskId,
                        assistantMsgId = assistantMsgId,
                        progress = progress,
                        lastUpdateMs = lastUpdate
                    )
                }
            }
        ).getOrElse { throw it }

        progress.promptTokens = result.usage?.promptTokens ?: progress.promptTokens
        result.usage?.completionTokens?.let { completionTokens ->
            progress.completionTokens = completionTokens
            progress.tokenCount = completionTokens
        }
        val usageTps = result.usage?.completionTokens
            ?.takeIf { it > 0 }
            ?.let { completionTokens ->
                result.usage?.evalDurationNs
                    ?.takeIf { it > 0L }
                    ?.let { durationNs -> completionTokens / (durationNs / 1_000_000_000.0) }
            }
        progress.reportedTokensPerSecond = usageTps ?: progress.reportedTokensPerSecond

        if (progress.content.isBlank() && result.message.content.isNotBlank()) {
            progress.content = result.message.content
        }
        if (progress.thinking.isBlank() && !result.message.thinking.isNullOrBlank()) {
            progress.thinking = result.message.thinking.orEmpty()
        }
    }

    private suspend fun streamNativeToolResponse(
        chatId: Long,
        taskId: Int,
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        server: LlamaServerEntity,
        history: List<LlamaMessageEntity>,
        assistantMsgId: Long,
        thinkingEnabled: Boolean,
        params: Map<String, Any>,
        toolConfig: NativeChatToolConfig,
        progress: StreamingProgress
    ) {
        val effectiveToolConfig = nativeChatToolRuntime.configWithOrganizerPermissions(toolConfig)
        val tools = nativeChatToolRuntime.availableTools(effectiveToolConfig)
        if (tools.isEmpty()) {
            if (server.isOllamaEngine()) {
                streamOllamaResponse(
                    chatId = chatId,
                    taskId = taskId,
                    chat = chat,
                    server = server,
                    history = history,
                    assistantMsgId = assistantMsgId,
                    isContinuation = false,
                    thinkingEnabled = thinkingEnabled,
                    progress = progress
                )
            } else {
                streamLlamaServerResponse(
                    chatId = chatId,
                    taskId = taskId,
                    chat = chat,
                    server = server,
                    history = history,
                    assistantMsgId = assistantMsgId,
                    isContinuation = false,
                    params = params,
                    progress = progress
                )
            }
            return
        }

        val modelName = if (server.isOllamaEngine()) {
            server.modelName?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException(getString(R.string.llama_ollama_model_required))
        } else {
            server.modelName?.takeIf { it.isNotBlank() }
        }
        if (server.isOllamaEngine()) {
            syncOllamaService(server)
        }

        val messages = buildOllamaMessages(chat, history, server).toMutableList()
        messages += nativeChatToolAwarenessMessages(effectiveToolConfig)
        val samplingParams = LlamaServerSamplingParams.fromParams(params)
        val numCtx = chat.contextSize.takeIf { it > 0 } ?: 16384

        var rawSequence = progress.content
        var lastUpdate = System.currentTimeMillis()
        progress.streamStartTimeMs = System.currentTimeMillis()
        progress.tokenCount = 0
        progress.promptTokens = 0
        progress.completionTokens = 0
        progress.isTruncated = false
        progress.statusText = null

        suspend fun runModelCall(availableTools: List<AgentTool>): OllamaService.ChatResponse {
            progress.statusText = null
            val chunkHandler: (String?, String?) -> Unit = chunkHandler@{ delta, thinkingDelta ->
                val contentDelta = delta.orEmpty()
                val reasoningDelta = thinkingDelta.orEmpty()
                if (contentDelta.isEmpty() && reasoningDelta.isEmpty()) {
                    return@chunkHandler
                }

                progress.statusText = null
                if (contentDelta.isNotEmpty()) {
                    rawSequence += contentDelta
                    progress.content = rawSequence
                }
                if (reasoningDelta.isNotEmpty()) {
                    progress.thinking += reasoningDelta
                }

                progress.tokenCount++
                runBlocking {
                    lastUpdate = updateStreamingProgress(
                        chatId = chatId,
                        taskId = taskId,
                        assistantMsgId = assistantMsgId,
                        progress = progress,
                        lastUpdateMs = lastUpdate
                    )
                }
            }

            return if (server.isOllamaEngine()) {
                ollamaService.chatWithToolsStreaming(
                    model = modelName ?: "",
                    messages = messages,
                    tools = availableTools,
                    thinkingEnabled = thinkingEnabled,
                    numCtxOverride = chat.contextSize.takeIf { it > 0 },
                    onChunk = chunkHandler
                ).getOrElse { throw it }
            } else {
                llamaServerChatService.chatWithToolsStreaming(
                    baseUrl = server.baseUrl(),
                    messages = messages,
                    tools = availableTools,
                    modelLabel = modelName,
                    thinkingEnabled = thinkingEnabled,
                    numCtx = numCtx,
                    samplingParams = samplingParams,
                    onChunk = chunkHandler
                ).getOrElse { throw it }
            }
        }

        fun mergeUsage(response: OllamaService.ChatResponse) {
            val usage = response.usage ?: return
            usage.promptTokens?.let { progress.promptTokens += it }
            usage.completionTokens?.let { completionTokens ->
                progress.completionTokens += completionTokens
                progress.tokenCount = progress.completionTokens
            }
            usage.evalDurationNs
                ?.takeIf { it > 0L }
                ?.let { durationNs ->
                    usage.completionTokens
                        ?.takeIf { it > 0 }
                        ?.let { completionTokens ->
                            progress.reportedTokensPerSecond = completionTokens / (durationNs / 1_000_000_000.0)
                        }
                }
        }

        fun appendFallbackContent(response: OllamaService.ChatResponse) {
            val content = response.message.content
            if (content.isNotBlank() && !rawSequence.endsWith(content)) {
                rawSequence += content
                progress.content = rawSequence
            }
            if (progress.thinking.isBlank() && !response.message.thinking.isNullOrBlank()) {
                progress.thinking = response.message.thinking.orEmpty()
            }
        }

        repeat(effectiveToolConfig.maxToolRounds) { round ->
            currentCoroutineContext().ensureActive()
            val visibleContentBeforeModelCall = rawSequence
            val response = runModelCall(tools)
            mergeUsage(response)

            val toolCalls = normalizeToolCalls(response.toolCalls.orEmpty(), round)
            if (toolCalls.isEmpty()) {
                appendFallbackContent(response)
                progress.statusText = null
                return
            }

            rawSequence = visibleContentBeforeModelCall
            progress.content = rawSequence
            lastUpdate = updateStreamingProgress(
                chatId = chatId,
                taskId = taskId,
                assistantMsgId = assistantMsgId,
                progress = progress,
                lastUpdateMs = lastUpdate
            )

            messages += response.message.copy(content = "", toolCalls = toolCalls)
            val roundReviewMessages = mutableListOf<OllamaService.ChatMessage>()
            for (toolCall in toolCalls) {
                currentCoroutineContext().ensureActive()
                val toolActivityBaseId = toolCall.id ?: "tool_${round}_${System.nanoTime()}"
                publishToolStatus(
                    chatId = chatId,
                    taskId = taskId,
                    progress = progress,
                    statusText = statusTextForToolCall(toolCall)
                )
                publishToolActivity(
                    chatId = chatId,
                    taskId = taskId,
                    progress = progress,
                    event = ToolActivityEvent(
                        id = "${toolActivityBaseId}_start",
                        toolName = toolCall.name,
                        status = statusTextForToolCall(toolCall),
                        title = toolCall.arguments["query"] ?: toolCall.arguments["url"] ?: toolCall.arguments["prompt"]
                    )
                )
                val toolResult = try {
                    nativeChatToolRuntime.executeToolCall(
                        toolCall = toolCall,
                        config = effectiveToolConfig,
                        onProgress = { toolProgress ->
                            publishToolActivity(
                                chatId = chatId,
                                taskId = taskId,
                                progress = progress,
                                event = ToolActivityEvent(
                                    id = "${toolActivityBaseId}_${System.nanoTime()}",
                                    toolName = toolCall.name,
                                    status = localizedToolProgressStatus(toolCall, toolProgress),
                                    title = toolProgress.title,
                                    url = toolProgress.url,
                                    outputPreview = toolProgress.outputPreview,
                                    isComplete = toolProgress.isComplete
                                )
                            )
                        },
                        searchSummarizer = { request ->
                            summarizeNativeSearchPageWithBackend(
                                server = server,
                                modelName = modelName,
                                request = request
                            )
                        }
                    )
                        .getOrElse { error ->
                            NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                        }
                } catch (e: CancellationException) {
                    throw e
                } catch (error: Throwable) {
                    NativeChatToolResult("tool_error: ${error.message ?: error::class.java.simpleName}")
                }
                publishToolActivity(
                    chatId = chatId,
                    taskId = taskId,
                    progress = progress,
                    event = ToolActivityEvent(
                        id = "${toolActivityBaseId}_done",
                        toolName = toolCall.name,
                        status = getString(R.string.llama_tool_activity_done),
                        title = toolCall.name,
                        outputPreview = toolResult.content.take(1_000),
                        isComplete = true
                    )
                )
                toolResult.generatedImagePath?.takeIf { it.isNotBlank() }?.let { imagePath ->
                    progress.generatedImagePaths += imagePath
                }
                messages += OllamaService.ChatMessage(
                    role = "tool",
                    content = toolResult.content,
                    toolCallId = toolCall.id
                )
                toolResult.generatedImagePath
                    ?.takeIf { effectiveToolConfig.imageIterationEnabled && it.isNotBlank() }
                    ?.let { imagePath ->
                        buildGeneratedImageReviewMessage(imagePath, server)?.let { reviewMessage ->
                            roundReviewMessages += reviewMessage
                        }
                    }
            }
            messages += roundReviewMessages
        }

        messages += OllamaService.ChatMessage(
            role = "system",
            content = "The native chat tool round limit has been reached. Answer now using only the tool results already provided. Do not call more tools."
        )
        publishToolStatus(
            chatId = chatId,
            taskId = taskId,
            progress = progress,
            statusText = getString(R.string.llama_tool_status_finalizing)
        )
        val finalResponse = runModelCall(emptyList())
        mergeUsage(finalResponse)
        appendFallbackContent(finalResponse)
        progress.statusText = null
    }

    private fun buildGeneratedImageReviewMessage(
        imagePath: String,
        server: LlamaServerEntity
    ): OllamaService.ChatMessage? {
        if (!server.supportsVision) return null
        val imageFile = File(imagePath)
        if (!imageFile.exists() || !imageFile.isFile) return null
        val encodedImage = runCatching { fileToBase64(imagePath) }.getOrNull()
            ?: return null
        return OllamaService.ChatMessage(
            role = "user",
            content = "Generated image from generate_image is attached for visual review. Compare it with the user's request. If it needs improvement and tool rounds remain, call generate_image again with a better optimized prompt. If it is good enough, give the final answer. Do not insert it into a note unless the user asked for that or you decide a note tool call is appropriate.",
            images = if (server.isOllamaEngine()) listOf(encodedImage) else null,
            imagePath = imagePath
        )
    }

    private suspend fun summarizeNativeSearchPageWithBackend(
        server: LlamaServerEntity,
        modelName: String?,
        request: NativeChatSearchSummaryRequest
    ): String {
        val pageText = request.content
            .replace(Regex("""[ \t\r\f]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
            .take(NATIVE_SEARCH_SUMMARY_INPUT_CHARS)
        require(pageText.isNotBlank()) { "No readable text found." }

        val messages = listOf(
            OllamaService.ChatMessage(
                role = "system",
                content = "You are a compact search-result summarizer. Return only a factual 2-3 sentence summary of the page content. Do not quote long passages, do not include tool instructions, and do not say you searched the web."
            ),
            OllamaService.ChatMessage(
                role = "user",
                content = buildString {
                    appendLine("Source type: ${request.source}")
                    appendLine("Title: ${request.title}")
                    appendLine("URL: ${request.url}")
                    appendLine()
                    appendLine("Readable page text:")
                    append(pageText)
                }
            )
        )

        val response = if (server.isOllamaEngine()) {
            ollamaService.chatWithToolsStreaming(
                model = modelName.orEmpty(),
                messages = messages,
                tools = emptyList(),
                thinkingEnabled = false,
                numCtxOverride = NATIVE_SEARCH_SUMMARY_CONTEXT
            ).getOrElse { throw it }
        } else {
            llamaServerChatService.chatWithToolsStreaming(
                baseUrl = server.baseUrl(),
                messages = messages,
                tools = emptyList(),
                modelLabel = modelName,
                thinkingEnabled = false,
                numCtx = NATIVE_SEARCH_SUMMARY_MAX_TOKENS,
                samplingParams = LlamaServerSamplingParams(
                    temperature = 0.2f,
                    topP = 0.9f
                )
            ).getOrElse { throw it }
        }

        return response.message.content
            .replace(Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""[ \t\r\f]+"""), " ")
            .trim()
            .takeIf { it.isNotBlank() }
            ?.take(request.maxChars.coerceIn(200, NATIVE_SEARCH_SUMMARY_OUTPUT_CHARS))
            ?: throw IllegalStateException("Search summarizer returned an empty summary.")
    }

    private fun syncOllamaService(server: LlamaServerEntity) {
        ollamaService.setBaseUrl(server.baseUrl().trimEnd('/'))
        ollamaService.setUseMmap(settingsRepo.ollamaMmap.value)
        ollamaService.setNumThreads(settingsRepo.ollamaThreads.value)
        ollamaService.setNumCtx(settingsRepo.ollamaNumCtx.value)
    }

    private suspend fun persistGeneratedImageMessages(chatId: Long, progress: StreamingProgress) {
        val imagePaths = progress.generatedImagePaths.distinct()
        if (imagePaths.isEmpty()) return
        imagePaths.forEach { imagePath ->
            repository.addMessage(
                chatId = chatId,
                role = "assistant",
                content = getString(R.string.llama_generated_image_message),
                imagePath = imagePath
            )
        }
        progress.generatedImagePaths.clear()
    }

    private fun warnIfBatteryOptimizationMayThrottle(chatId: Long) {
        val powerManager = getSystemService(POWER_SERVICE) as? android.os.PowerManager ?: return
        val exempt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        if (!exempt) {
            val warning = getString(R.string.llama_battery_optimization_warning)
            DebugLog.log("[LlamaClientService] $warning")
            Companion.updateState(
                GenerationState.Generating(
                    chatId = chatId,
                    content = "",
                    statusText = warning
                )
            )
        }
    }

    private fun maybeRecordPowerDiagnostics(progress: StreamingProgress) {
        val now = System.currentTimeMillis()
        if (now - progress.lastPowerDiagnosticMs < POWER_DIAGNOSTIC_INTERVAL_MS) return
        progress.lastPowerDiagnosticMs = now
        val powerManager = getSystemService(POWER_SERVICE) as? android.os.PowerManager
        val batteryExempt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        val stalledMs = (now - progress.lastTokenAtMs).coerceAtLeast(0L)
        DebugLog.log(
            "[LlamaClientService] power wakeLock=${WakeLockManager.isHeld()} " +
                "wifiLock=${WakeLockManager.isWifiHeld()} interactive=${powerManager?.isInteractive} " +
                "powerSave=${powerManager?.isPowerSaveMode} batteryExempt=$batteryExempt " +
                "tokenGapMs=$stalledMs tokens=${progress.tokenCount}"
        )
    }

    private fun buildOllamaMessages(
        chat: com.example.llamadroid.data.model.LlamaChatEntity,
        history: List<LlamaMessageEntity>,
        server: LlamaServerEntity
    ): List<OllamaService.ChatMessage> {
        val messages = mutableListOf<OllamaService.ChatMessage>()
        if (!chat.systemPrompt.isNullOrBlank()) {
            messages += OllamaService.ChatMessage(
                role = "system",
                content = chat.systemPrompt
            )
        }

        history.forEach { message ->
            messages += OllamaService.ChatMessage(
                role = message.role,
                content = message.content,
                images = if (message.role == "user" && server.supportsVision && !message.imagePath.isNullOrBlank()) {
                    listOf(fileToBase64(message.imagePath))
                } else {
                    null
                },
                imagePath = message.imagePath,
                audioPath = message.audioPath,
                thinking = message.thinking
            )
        }
        return messages
    }

    private suspend fun updateStreamingProgress(
        chatId: Long,
        taskId: Int,
        assistantMsgId: Long,
        progress: StreamingProgress,
        lastUpdateMs: Long
    ): Long {
        val elapsed = (System.currentTimeMillis() - progress.streamStartTimeMs) / 1000.0
        val tps = if (elapsed > 0.0) progress.tokenCount / elapsed else 0.0
        maybeRecordPowerDiagnostics(progress)

        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = tps,
                statusText = progress.statusText,
                toolEvents = progress.toolEvents.toList()
            )
        )

        if (System.currentTimeMillis() - lastUpdateMs <= 500) {
            return lastUpdateMs
        }

        val currentElapsedMs = System.currentTimeMillis() - progress.streamStartTimeMs
        repository.updateMessageThinkingAndContent(
            assistantMsgId,
            progress.content,
            progress.thinking.takeIf { it.isNotBlank() },
            promptTokens = progress.promptTokens,
            completionTokens = progress.completionTokens,
            tps = tps,
            generationTimeMs = currentElapsedMs
        )
        val progressContext = if (progress.thinking.isNotBlank() && progress.content.isBlank()) {
            progress.thinking
        } else {
            progress.content
        }
        val words = progressContext.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val lastWords = words.takeLast(7).joinToString(" ")
        val progressText = if (lastWords.isBlank()) {
            "%d tok · %.1f t/s".format(progress.tokenCount, tps)
        } else {
            "%d tok · %.1f t/s | ...$lastWords".format(progress.tokenCount, tps)
        }
        UnifiedNotificationManager.updateProgress(taskId, 0.5f, progressText)
        return System.currentTimeMillis()
    }

    private fun publishToolStatus(
        chatId: Long,
        taskId: Int,
        progress: StreamingProgress,
        statusText: String
    ) {
        progress.statusText = statusText
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = 0.0,
                statusText = statusText,
                toolEvents = progress.toolEvents.toList()
            )
        )
        UnifiedNotificationManager.updateProgress(taskId, 0.45f, statusText)
    }

    private fun publishToolActivity(
        chatId: Long,
        taskId: Int,
        progress: StreamingProgress,
        event: ToolActivityEvent
    ) {
        progress.toolEvents += event
        if (progress.toolEvents.size > MAX_TOOL_ACTIVITY_EVENTS) {
            progress.toolEvents.removeAt(0)
        }
        progress.statusText = event.status
        Companion.updateState(
            GenerationState.Generating(
                chatId = chatId,
                content = progress.content,
                thinking = progress.thinking.takeIf { it.isNotBlank() },
                tokenCount = progress.tokenCount,
                tokensPerSecond = 0.0,
                statusText = event.status,
                toolEvents = progress.toolEvents.toList()
            )
        )
        UnifiedNotificationManager.updateProgress(taskId, 0.45f, event.status)
    }

    private fun statusTextForToolCall(toolCall: OllamaService.ToolCall): String =
        when (toolCall.name) {
            NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                R.string.llama_tool_status_web_search,
                toolCall.arguments["query"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_SEARCH_PAGE -> getString(
                R.string.llama_tool_status_search_page,
                (toolCall.arguments["query"] ?: toolCall.arguments["url"]).orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                R.string.llama_tool_status_kiwix_search,
                toolCall.arguments["query"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_FETCH_URL -> getString(
                R.string.llama_tool_status_fetch_url,
                toolCall.arguments["url"].orEmpty().take(80)
            )
            NativeChatToolRuntime.TOOL_GET_DATETIME -> getString(R.string.llama_tool_status_datetime)
            NativeChatToolRuntime.TOOL_CALCULATOR -> getString(R.string.llama_tool_status_calculator)
            NativeChatToolRuntime.TOOL_LIST_NOTES -> getString(R.string.llama_tool_status_list_notes)
            NativeChatToolRuntime.TOOL_READ_NOTE -> getString(R.string.llama_tool_status_read_note)
            NativeChatToolRuntime.TOOL_CREATE_NOTE -> getString(R.string.llama_tool_status_create_note)
            NativeChatToolRuntime.TOOL_UPDATE_NOTE -> getString(R.string.llama_tool_status_update_note)
            NativeChatToolRuntime.TOOL_REPLACE_NOTE_TEXT -> getString(R.string.llama_tool_status_update_note)
            NativeChatToolRuntime.TOOL_CREATE_TODO_LIST -> getString(R.string.llama_tool_status_create_todo)
            NativeChatToolRuntime.TOOL_ADD_TODO_ITEM,
            NativeChatToolRuntime.TOOL_UPDATE_TODO_ITEM,
            NativeChatToolRuntime.TOOL_REMOVE_TODO_ITEM,
            NativeChatToolRuntime.TOOL_SET_TODO_ITEM_CHECKED -> getString(R.string.llama_tool_status_update_todo)
            NativeChatToolRuntime.TOOL_LIST_CALENDAR_EVENTS,
            NativeChatToolRuntime.TOOL_READ_CALENDAR_EVENT -> getString(R.string.llama_tool_status_calendar)
            NativeChatToolRuntime.TOOL_CREATE_CALENDAR_EVENT,
            NativeChatToolRuntime.TOOL_UPDATE_CALENDAR_EVENT,
            NativeChatToolRuntime.TOOL_DELETE_CALENDAR_EVENT -> getString(R.string.llama_tool_status_update_calendar)
            NativeChatToolRuntime.TOOL_LIST_ALARMS,
            NativeChatToolRuntime.TOOL_READ_ALARM -> getString(R.string.llama_tool_status_alarms)
            NativeChatToolRuntime.TOOL_CREATE_ALARM,
            NativeChatToolRuntime.TOOL_UPDATE_ALARM,
            NativeChatToolRuntime.TOOL_DELETE_ALARM -> getString(R.string.llama_tool_status_update_alarm)
            NativeChatToolRuntime.TOOL_GENERATE_IMAGE -> getString(R.string.llama_tool_status_generate_image)
            else -> getString(R.string.llama_tool_status_running)
        }

    private fun localizedToolProgressStatus(
        toolCall: OllamaService.ToolCall,
        progress: NativeChatToolProgress
    ): String {
        val title = progress.title?.take(80).orEmpty()
        return when (progress.phase) {
            NativeChatToolProgressPhase.SEARCHING -> statusTextForToolCall(toolCall)
            NativeChatToolProgressPhase.FOUND -> when (toolCall.name) {
                NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                    R.string.llama_tool_activity_found_web,
                    progress.count ?: 0
                )
                NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                    R.string.llama_tool_activity_found_kiwix,
                    progress.count ?: 0
                )
                NativeChatToolRuntime.TOOL_SEARCH_PAGE -> getString(
                    R.string.llama_tool_activity_found_page_links,
                    progress.count ?: 0
                )
                else -> progress.status
            }
            NativeChatToolProgressPhase.READING -> when (toolCall.name) {
                NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                    R.string.llama_tool_activity_reading_web,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                    R.string.llama_tool_activity_reading_kiwix,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                else -> progress.status
            }
            NativeChatToolProgressPhase.SUMMARIZED -> when (toolCall.name) {
                NativeChatToolRuntime.TOOL_WEB_SEARCH -> getString(
                    R.string.llama_tool_activity_summarized_web,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                NativeChatToolRuntime.TOOL_KIWIX_SEARCH -> getString(
                    R.string.llama_tool_activity_summarized_kiwix,
                    progress.current ?: 0,
                    progress.total ?: 0,
                    title
                )
                else -> progress.status
            }
            NativeChatToolProgressPhase.FETCHING -> statusTextForToolCall(toolCall)
            NativeChatToolProgressPhase.GENERATING -> statusTextForToolCall(toolCall)
            else -> progress.status
        }
    }

    private fun normalizeToolCalls(
        toolCalls: List<OllamaService.ToolCall>,
        round: Int
    ): List<OllamaService.ToolCall> = toolCalls.mapIndexed { index, toolCall ->
        if (!toolCall.id.isNullOrBlank()) {
            toolCall
        } else {
            toolCall.copy(id = "call_${round}_${index}_${System.nanoTime()}")
        }
    }

    private suspend fun transcribeAudioAttachment(
        server: LlamaServerEntity,
        audioPath: String
    ): Result<WhisperResult> = withWhisperService { whisperService ->
        val whisperModelPath = resolveWhisperModelPath(server)
            ?: return@withWhisperService Result.failure(
                IllegalStateException(getString(R.string.whisper_error_no_model))
            )

        whisperService.transcribe(
            WhisperConfig(
                modelPath = whisperModelPath,
                audioPath = audioPath,
                language = server.whisperLanguage.ifBlank { LlamaServerEntity.DEFAULT_WHISPER_LANGUAGE },
                outputFormats = setOf(WhisperOutputFormat.TXT),
                threads = settingsRepo.whisperThreads.value
            )
        )
    }

    private suspend fun resolveWhisperModelPath(server: LlamaServerEntity): String? {
        server.whisperModelPath
            ?.takeIf { it.isNotBlank() && java.io.File(it).exists() }
            ?.let { return it }
        return database.modelDao()
            .getModelsByTypesSync(listOf(ModelType.WHISPER))
            .firstOrNull()
            ?.path
    }

    private suspend fun <T> withWhisperService(
        block: suspend (WhisperService) -> Result<T>
    ): Result<T> {
        applicationContext.startForegroundService(whisperBindingIntent)
        return suspendCancellableCoroutine { continuation ->
            var isBound = false
            val connection = object : ServiceConnection {
                private fun finish(result: Result<T>) {
                    if (isBound) {
                        runCatching { applicationContext.unbindService(this) }
                        isBound = false
                    }
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = (binder as? WhisperService.WhisperBinder)?.getService()
                    if (service == null) {
                        finish(Result.failure(IllegalStateException(getString(R.string.whisper_error_no_service))))
                        return
                    }
                    serviceScope.launch {
                        val result = runCatching { block(service) }.getOrElse { Result.failure(it) }
                        finish(result)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    finish(Result.failure(IllegalStateException(getString(R.string.whisper_error_no_service))))
                }
            }

            isBound = applicationContext.bindService(
                whisperBindingIntent,
                connection,
                Context.BIND_AUTO_CREATE
            )
            if (!isBound && continuation.isActive) {
                continuation.resume(Result.failure(IllegalStateException(getString(R.string.whisper_error_no_service))))
            }
            continuation.invokeOnCancellation {
                if (isBound) {
                    runCatching { applicationContext.unbindService(connection) }
                }
            }
        }
    }

    private var cumulativeDedicatedReasoning = ""

    private fun extractThinking(text: String, newDedicated: String = ""): Pair<String, String> {
        if (newDedicated.isNotEmpty()) {
            cumulativeDedicatedReasoning += newDedicated
        }
        val thinkTags = listOf("<think>", "<|think|>", "<thought>", "<Thought>", "<Think>")
        val endThinkTags = listOf("</think>", "</|think|>", "</thought>", "</Thought>", "</Think>")
        
        var startTag: String? = null
        var startIndex = -1
        
        for (tag in thinkTags) {
            val idx = text.indexOf(tag, ignoreCase = true)
            if (idx != -1 && (startIndex == -1 || idx < startIndex)) {
                startIndex = idx
                startTag = tag
            }
        }
        if (startIndex == -1) {
            return Pair(text, cumulativeDedicatedReasoning.trim())
        }
        var endTag: String? = null
        var endIndex = -1
        for (tag in endThinkTags) {
            val idx = text.indexOf(tag, startIndex + (startTag?.length ?: 0), ignoreCase = true)
            if (idx != -1 && (endIndex == -1 || idx < endIndex)) {
                endIndex = idx
                endTag = tag
            }
        }
        return if (endIndex != -1) {
            val thinking = text.substring(startIndex + (startTag?.length ?: 0), endIndex)
            val content = text.substring(0, startIndex) + text.substring(endIndex + (endTag?.length ?: 0))
            Pair(content.trim(), (cumulativeDedicatedReasoning + "\n" + thinking).trim())
        } else {
            val thinking = text.substring(startIndex + (startTag?.length ?: 0))
            val content = text.substring(0, startIndex)
            Pair(content.trim(), (cumulativeDedicatedReasoning + "\n" + thinking).trim())
        }
    }

    private fun resetThinking() {
        cumulativeDedicatedReasoning = ""
    }

    private fun stopGeneration() {
        job?.cancel()
        OllamaService.stop()
        llamaServerChatService.stopGeneration()
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        Companion.updateState(GenerationState.Idle)
    }

    private fun showDebugToast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
             android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        OllamaService.stop()
        llamaServerChatService.stopGeneration()
        serviceScope.cancel()
        notificationTaskId?.let { taskId ->
            UnifiedNotificationManager.dismissTask(taskId)
        }
        notificationTaskId = null
    }

    private data class PreparedUserTurn(
        val content: String,
        val imagePath: String?,
        val audioPath: String?,
        val alreadyPersisted: Boolean = false
    ) {
        fun shouldPersist(): Boolean =
            content.isNotBlank() || !imagePath.isNullOrBlank() || !audioPath.isNullOrBlank()
    }

    private data class AssistantMessagePreparation(
        val history: List<LlamaMessageEntity>,
        val assistantMsgId: Long,
        val content: String,
        val thinking: String
    )

    private data class StreamingProgress(
        var content: String = "",
        var thinking: String = "",
        var promptTokens: Int = 0,
        var completionTokens: Int = 0,
        var tokenCount: Int = 0,
        var streamStartTimeMs: Long = System.currentTimeMillis(),
        var isTruncated: Boolean = false,
        var reportedTokensPerSecond: Double? = null,
        var statusText: String? = null,
        val toolEvents: MutableList<ToolActivityEvent> = mutableListOf(),
        val generatedImagePaths: MutableList<String> = mutableListOf(),
        var lastPowerDiagnosticMs: Long = 0L,
        var lastTokenAtMs: Long = System.currentTimeMillis()
    )

    companion object {
        const val ACTION_GENERATE = "GENERATE"
        const val ACTION_STOP = "STOP"
        const val EXTRA_CHAT_ID = "CHAT_ID"
        const val EXTRA_SERVER_ID = "SERVER_ID"
        const val EXTRA_USER_MESSAGE = "USER_MESSAGE"
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"
        const val EXTRA_AUDIO_PATH = "AUDIO_PATH"

        private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState = _generationState.asStateFlow()
        
        fun updateState(state: GenerationState) {
            _generationState.value = state
        }

        private const val POWER_DIAGNOSTIC_INTERVAL_MS = 30_000L
        
        fun resetStateIfIdle() {
            if (_generationState.value !is GenerationState.Generating) {
                _generationState.value = GenerationState.Idle
            }
        }

        private const val MAX_TOOL_ACTIVITY_EVENTS = 40
        private const val NATIVE_SEARCH_SUMMARY_CONTEXT = 4096
        private const val NATIVE_SEARCH_SUMMARY_MAX_TOKENS = 512
        private const val NATIVE_SEARCH_SUMMARY_INPUT_CHARS = 6_000
        private const val NATIVE_SEARCH_SUMMARY_OUTPUT_CHARS = 900
    }

    data class ToolActivityEvent(
        val id: String,
        val toolName: String,
        val status: String,
        val title: String? = null,
        val url: String? = null,
        val outputPreview: String? = null,
        val isComplete: Boolean = false,
        val timestampMs: Long = System.currentTimeMillis()
    )
    
    sealed class GenerationState {
        object Idle : GenerationState()
        data class Generating(
            val chatId: Long = -1L,
            val content: String,
            val thinking: String? = null,
            val tokenCount: Int = 0,
            val tokensPerSecond: Double = 0.0,
            val isTranscribingAudio: Boolean = false,
            val transcribingMessageId: Long? = null,
            val statusText: String? = null,
            val toolEvents: List<ToolActivityEvent> = emptyList()
        ) : GenerationState()
        data class Completed(
            val chatId: Long = -1L,
            val content: String,
            val thinking: String? = null,
            val completionTokens: Int = 0,
            val promptTokens: Int = 0,
            val tokensPerSecond: Double = 0.0
        ) : GenerationState()
        data class Error(val message: String, val chatId: Long = -1L) : GenerationState()
    }
}
