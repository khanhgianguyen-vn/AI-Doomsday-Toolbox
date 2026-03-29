package com.example.llamadroid.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.example.llamadroid.data.api.LlamaApi
import com.example.llamadroid.data.api.LlamaChatRequest
import com.example.llamadroid.data.api.LlamaChatMessage
import com.example.llamadroid.data.api.LlamaChatResponse
import com.example.llamadroid.data.api.LlamaStreamOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.example.llamadroid.util.DebugLog
import com.google.gson.Gson
import java.io.BufferedReader
import java.io.InputStreamReader
import com.example.llamadroid.data.repository.LlamaRepository
import kotlinx.coroutines.flow.first

class LlamaClientService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    
    // Active generation state
    private var job: Job? = null
    
    // Repository to save messages
    private lateinit var repository: LlamaRepository
    
    override fun onCreate() {
        super.onCreate()
        // Initialize repository (using manual instantiation for now, assuming DI isn't fully set up)
        val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(applicationContext)
        repository = LlamaRepository(db.llamaServerDao(), db.llamaChatDao(), db.llamaMessageDao())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_GENERATE -> {
                val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
                val serverId = intent.getLongExtra(EXTRA_SERVER_ID, -1L)
                val userMessage = intent.getStringExtra(EXTRA_USER_MESSAGE)
                val imagePath = intent.getStringExtra(EXTRA_IMAGE_PATH)
                
                if (chatId != -1L && serverId != -1L) {
                    startGeneration(chatId, serverId, userMessage, imagePath)
                } else {
                    DebugLog.log("LlamaClientService: Missing params for generation")
                    Companion.updateState(GenerationState.Error("Missing parameters for generation"))
                }
            }
            ACTION_STOP -> {
                stopGeneration()
            }
        }
        return START_NOT_STICKY
    }

    private fun startGeneration(chatId: Long, serverId: Long, userMessage: String?, imagePath: String?) {
        if (job?.isActive == true) {
            DebugLog.log("LlamaClientService: Generation already in progress")
            return
        }

        // Start Foreground
        val (taskId, notification) = UnifiedNotificationManager.startTaskForForeground(
            UnifiedNotificationManager.TaskType.LLAMA_CLIENT,
            "Llama Chat"
        )
        startForeground(taskId, notification)
        
        // Acquire WakeLock and WifiLock indefinitely
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "LlamaClientService:Gen_CPU")
        wakeLock?.acquire()

        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifiManager.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "LlamaClientService:Gen_WIFI"
        )
        wifiLock?.acquire()

        resetThinking()
        Companion.updateState(GenerationState.Generating(chatId = chatId, content = ""))

        job = serviceScope.launch {
            var assistantMsgId: Long = -1L
            var promptTokens = 0
            var completionTokens = 0
            var tokenCount = 0
            var streamStartTime = System.currentTimeMillis()
            var fullContent = ""
            var fullThinking = ""
            var rawSequence = ""
            var isContinuation = false // Will be updated after loading history
            
            try {
                // 1. Get Server Details
                val server = repository.getServer(serverId) ?: throw Exception("Server with ID $serverId not found")
                val baseUrl = if (server.host.startsWith("http")) {
                    "${server.host}:${server.port}/"
                } else {
                    "http://${server.host}:${server.port}/"
                }
                
                DebugLog.log("LlamaClientService: Connecting to $baseUrl for Chat ID $chatId")
                
                // 2. Get Chat Details
                val chat = repository.getChat(chatId) ?: throw Exception("Chat with ID $chatId not found")
                
                // 3. Save User Message IF provided
                if (userMessage != null) {
                    repository.addMessage(chatId, "user", userMessage)
                }
                
                // 4. Load History
                val history = repository.getMessages(chatId).first()
                if (history.isEmpty()) throw Exception("Chat history is empty")
                
                isContinuation = userMessage == null && history.isNotEmpty() && history.last().role == "assistant"

                
                // Build message list, prepending system prompt if set
                val apiMessages = mutableListOf<LlamaChatMessage>()
                if (!chat.systemPrompt.isNullOrBlank()) {
                    apiMessages.add(LlamaChatMessage("system", chat.systemPrompt))
                }
                
                // Add all historical messages as standard strings (if image, only sent once in user flow)
                apiMessages.addAll(history.map { LlamaChatMessage(it.role, it.content) })
                
                // If there's an image attached for the CURRENT message, replace the last appended user message
                // with the Vision format (List of Maps)
                if (!imagePath.isNullOrBlank() && userMessage != null && apiMessages.isNotEmpty()) {
                    // Check if previous message is the user message we just appended
                    val lastMsgIndex = apiMessages.lastIndex
                    val lastMsg = apiMessages[lastMsgIndex]
                    if (lastMsg.role == "user" && lastMsg.content == userMessage) {
                        try {
                            val fileContent = java.io.File(imagePath).readBytes()
                            val base64Str = android.util.Base64.encodeToString(fileContent, android.util.Base64.NO_WRAP)
                            val visionContent = listOf(
                                mapOf("type" to "text", "text" to userMessage),
                                mapOf("type" to "image_url", "image_url" to mapOf("url" to "data:image/jpeg;base64,$base64Str"))
                            )
                            apiMessages[lastMsgIndex] = LlamaChatMessage("user", visionContent)
                        } catch (e: Exception) {
                            DebugLog.log("LlamaClientService: Failed to attach image: ${e.message}")
                            showDebugToast("Failed to attach image to message")
                        }
                    }
                }

                // 5. Prepare API
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(0, TimeUnit.SECONDS) // Infinite read for streams
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val original = chain.request()
                        val request = original.newBuilder()
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

                // 6. Create or reuse Assistant Message
                val existingContent: String
                val existingThinking: String
                
                if (isContinuation && history.isNotEmpty() && history.last().role == "assistant") {
                    // Continue mode: reuse the last assistant message
                    val lastAssistantMsg = history.last()
                    assistantMsgId = lastAssistantMsg.id
                    existingThinking = lastAssistantMsg.thinking ?: ""
                    
                    // Trim the last partial word
                    val rawContent = lastAssistantMsg.content
                    val lastWsIndex = rawContent.indexOfLast { it == ' ' || it == '\n' || it == '\t' }
                    existingContent = if (lastWsIndex > 0 && !rawContent.last().isWhitespace()) {
                        rawContent.substring(0, lastWsIndex + 1)
                    } else {
                        rawContent
                    }
                    
                    // Update DB and apiMessages with trimmed content
                    if (existingContent != rawContent) {
                        repository.updateMessage(assistantMsgId, existingContent)
                        val lastApiIdx = apiMessages.lastIndex
                        if (lastApiIdx >= 0 && apiMessages[lastApiIdx].role == "assistant") {
                            apiMessages[lastApiIdx] = LlamaChatMessage("assistant", existingContent)
                        }
                    }

                    // CRITICAL: Update state right away with existing content to prevent blank UI flash
                    Companion.updateState(GenerationState.Generating(
                        chatId = chatId,
                        content = existingContent,
                        thinking = existingThinking.takeIf { it.isNotBlank() }
                    ))

                    DebugLog.log("LlamaClientService: Continuing from existing assistant message (${existingContent.length} chars)")
                } else {
                    // Normal mode: create a new placeholder
                    assistantMsgId = repository.addMessage(chatId, "assistant", "")
                    existingContent = ""
                    existingThinking = ""
                }
                
                // 7. Parse sampling params from chat.apiParams JSON
                val params: Map<String, Any> = if (!chat.apiParams.isNullOrBlank()) {
                    try {
                        Gson().fromJson(chat.apiParams, Map::class.java) as? Map<String, Any> ?: emptyMap()
                    } catch (e: Exception) { emptyMap() }
                } else { emptyMap() }
                
                val paramEnableThinking = (params["enable_thinking"] as? Boolean) ?: true

                // 8. Make Request
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
                    chat_template_kwargs = if (isContinuation || !paramEnableThinking) {
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
                
                // 8. Stream Response
                val response = call.execute()
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: ""
                    showDebugToast("Server Error ${response.code()}")
                    throw Exception("Server Error ${response.code()}: $errorBody")
                }
                
                showDebugToast("Connected! receiving stream...")

                val source = response.body()?.source() ?: throw Exception("Empty response body from server")
                val reader = BufferedReader(InputStreamReader(source.inputStream()))
                
                fullContent = existingContent
                fullThinking = existingThinking
                rawSequence = existingContent
                
                var lastUpdate = System.currentTimeMillis()
                tokenCount = 0
                streamStartTime = System.currentTimeMillis()
                promptTokens = 0
                completionTokens = 0
                var firstDeltaReceived = false
                val needsSpaceCheck = isContinuation && existingContent.isNotEmpty() && !existingContent.last().isWhitespace()
                var isTruncated = false
                
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) continue
                    if (line == "data: [DONE]") break
                    
                    if (line.startsWith("data: ")) {
                        val json = line.substring(6)
                        try {
                            val chunk = Gson().fromJson(json, LlamaChatResponse::class.java)
                            
                            chunk.usage?.let { usage ->
                                promptTokens = usage.promptTokens
                                completionTokens = usage.completionTokens
                            }
                            chunk.choices.firstOrNull()?.let { choice ->
                                if (!choice.finish_reason.isNullOrBlank()) {
                                    val reason = choice.finish_reason.lowercase()
                                    if (reason == "length" || reason == "max_tokens") {
                                        isTruncated = true
                                        DebugLog.log("LlamaClientService: Truncation detected via finish_reason='$reason'")
                                    }
                                }
                            }
                            val deltaObj = chunk.choices.firstOrNull()?.delta
                            val delta = deltaObj?.content ?: ""
                            
                            if (delta.isNotEmpty()) {
                                DebugLog.log("LlamaClientService: Received content delta: $delta")
                            }
                            if (!deltaObj?.reasoningContent.isNullOrEmpty()) {
                                DebugLog.log("LlamaClientService: Received reasoning_content: ${deltaObj?.reasoningContent}")
                            }

                            if (delta.isNotEmpty() || !deltaObj?.reasoningContent.isNullOrEmpty() || !deltaObj?.thinking.isNullOrEmpty()) {
                                if (delta.isNotEmpty() && !firstDeltaReceived && needsSpaceCheck && !delta[0].isWhitespace()) {
                                    rawSequence += " "
                                }
                                firstDeltaReceived = true
                                rawSequence += delta
                                
                                val dedicatedReasoning = deltaObj?.let { 
                                    it.reasoningContent ?: it.thinking 
                                } ?: ""
                                
                                if (!isContinuation) {
                                    val extracted = extractThinking(rawSequence, dedicatedReasoning)
                                    fullContent = extracted.first
                                    fullThinking = extracted.second
                                } else {
                                    fullContent = rawSequence
                                }
                                
                                tokenCount++
                                val elapsed = (System.currentTimeMillis() - streamStartTime) / 1000.0
                                val tps = if (elapsed > 0.0) tokenCount / elapsed else 0.0
                                
                                Companion.updateState(GenerationState.Generating(
                                    chatId = chatId,
                                    content = fullContent,
                                    thinking = fullThinking.takeIf { it.isNotBlank() },
                                    tokenCount = tokenCount,
                                    tokensPerSecond = tps
                                ))
                                
                                if (System.currentTimeMillis() - lastUpdate > 500) {
                                    val currentElapsedMs = System.currentTimeMillis() - streamStartTime
                                    repository.updateMessageThinkingAndContent(
                                        assistantMsgId, 
                                        fullContent, 
                                        fullThinking.takeIf { it.isNotBlank() },
                                        promptTokens = promptTokens,
                                        completionTokens = completionTokens,
                                        tps = tps,
                                        generationTimeMs = currentElapsedMs
                                    )
                                    val progContext = if (fullThinking.isNotEmpty() && fullContent.isBlank()) fullThinking else fullContent
                                    val words = progContext.trim().split(Regex("\\s+"))
                                    val lastWords = words.takeLast(7).joinToString(" ")
                                    val progText = if (lastWords.isBlank()) "%.1f t/s".format(tps) else "%.1f t/s | ...$lastWords".format(tps)
                                    UnifiedNotificationManager.updateProgress(taskId, 0.5f, progText)
                                    lastUpdate = System.currentTimeMillis()
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
                
                val finalElapsed = (System.currentTimeMillis() - streamStartTime) / 1000.0
                val finalTps = if (finalElapsed > 0.0) tokenCount / finalElapsed else 0.0
                if (completionTokens == 0) completionTokens = tokenCount
                
                val finalElapsedMs = System.currentTimeMillis() - streamStartTime
                
                repository.updateMessageTruncatedStatus(assistantMsgId, isTruncated)
                repository.updateMessageThinkingAndContent(
                    assistantMsgId, 
                    fullContent, 
                    fullThinking.takeIf { it.isNotBlank() },
                    promptTokens = promptTokens,
                    completionTokens = completionTokens,
                    tps = finalTps,
                    generationTimeMs = finalElapsedMs
                )

                Companion.updateState(GenerationState.Completed(
                    chatId = chatId,
                    content = fullContent,
                    thinking = fullThinking.takeIf { it.isNotBlank() },
                    completionTokens = completionTokens,
                    promptTokens = promptTokens,
                    tokensPerSecond = finalTps
                ))
            } catch (e: Exception) {
                if (e is CancellationException) {
                     DebugLog.log("LlamaClientService: Generation cancelled")
                     if (assistantMsgId != -1L) {
                         val finalElapsedMs = System.currentTimeMillis() - streamStartTime
                         val finalElapsed = finalElapsedMs / 1000.0
                         val finalTps = if (finalElapsed > 0.0) tokenCount / finalElapsed else 0.0
                         repository.updateMessageThinkingAndContent(
                             assistantMsgId, 
                             fullContent, 
                             fullThinking.takeIf { it.isNotBlank() },
                             promptTokens = promptTokens,
                             completionTokens = if (completionTokens == 0) tokenCount else completionTokens,
                             tps = finalTps,
                             generationTimeMs = finalElapsedMs
                         )
                     }
                } else {
                    DebugLog.log("LlamaClientService: Error ${e.message}")
                    Companion.updateState(GenerationState.Error(e.message ?: "Unknown error"))
                }
            } finally {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                job = null
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
        serviceScope.cancel()
    }

    companion object {
        const val ACTION_GENERATE = "GENERATE"
        const val ACTION_STOP = "STOP"
        const val EXTRA_CHAT_ID = "CHAT_ID"
        const val EXTRA_SERVER_ID = "SERVER_ID"
        const val EXTRA_USER_MESSAGE = "USER_MESSAGE"
        const val EXTRA_IMAGE_PATH = "IMAGE_PATH"

        private val _generationState = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val generationState = _generationState.asStateFlow()
        
        fun updateState(state: GenerationState) {
            _generationState.value = state
        }
        
        fun resetStateIfIdle() {
            if (_generationState.value !is GenerationState.Generating) {
                _generationState.value = GenerationState.Idle
            }
        }
    }
    
    sealed class GenerationState {
        object Idle : GenerationState()
        data class Generating(
            val chatId: Long = -1L,
            val content: String,
            val thinking: String? = null,
            val tokenCount: Int = 0,
            val tokensPerSecond: Double = 0.0
        ) : GenerationState()
        data class Completed(
            val chatId: Long = -1L,
            val content: String,
            val thinking: String? = null,
            val completionTokens: Int = 0,
            val promptTokens: Int = 0,
            val tokensPerSecond: Double = 0.0
        ) : GenerationState()
        data class Error(val message: String) : GenerationState()
    }
}
