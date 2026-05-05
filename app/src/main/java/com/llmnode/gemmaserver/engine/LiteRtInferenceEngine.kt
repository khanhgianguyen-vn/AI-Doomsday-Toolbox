package com.llmnode.gemmaserver.engine

import android.content.Context
import com.google.ai.edge.litertlm.*
import com.llmnode.gemmaserver.util.ServerLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Wraps LiteRT-LM Engine and Conversation to provide LLM inference.
 * Uses a mutex to ensure only one session exists at a time (LiteRT-LM constraint).
 */
class LiteRtInferenceEngine(private val context: Context) : Closeable {

    companion object {
        private const val TAG = "LiteRtEngine"
    }

    private var engine: Engine? = null
    private var activeConversation: Conversation? = null

    /** Mutex to serialize requests — LiteRT-LM only supports one session at a time. */
    private val sessionMutex = Mutex()

    @Volatile
    var isInitialized: Boolean = false
        private set

    @Volatile
    var isBusy: Boolean = false
        private set

    /**
     * Initialize the engine with the given model path.
     * This is a heavy operation (~10s) — must be called from a background thread.
     */
    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        ServerLogger.i(TAG, "Initializing LiteRT-LM engine with model: $modelPath")

        try {
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                cacheDir = context.cacheDir.absolutePath
            )

            val eng = Engine(engineConfig)
            eng.initialize()
            engine = eng

            ServerLogger.i(TAG, "Engine initialized successfully with GPU backend")
            isInitialized = true
        } catch (e: Exception) {
            ServerLogger.e(TAG, "GPU backend failed, falling back to CPU: ${e.message}")
            try {
                val cpuConfig = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = context.cacheDir.absolutePath
                )
                val eng = Engine(cpuConfig)
                eng.initialize()
                engine = eng

                ServerLogger.i(TAG, "Engine initialized successfully with CPU backend")
                isInitialized = true
            } catch (e2: Exception) {
                ServerLogger.e(TAG, "Failed to initialize engine: ${e2.message}")
                throw e2
            }
        }
    }

    /**
     * Close any existing active conversation before creating a new one.
     * LiteRT-LM only supports one session at a time.
     */
    private fun closeActiveConversation() {
        try {
            activeConversation?.close()
        } catch (e: Exception) {
            ServerLogger.e(TAG, "Error closing previous conversation: ${e.message}")
        }
        activeConversation = null
    }

    /**
     * Create a new conversation session, closing any existing one first.
     */
    private fun createNewConversation(
        systemInstruction: String? = null,
        messages: List<Pair<String, String>>? = null,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f
    ): Conversation {
        val eng = engine ?: throw IllegalStateException("Engine not initialized")

        // Close existing session first
        closeActiveConversation()

        val config = ConversationConfig(
            systemInstruction = if (systemInstruction != null) Contents.of(systemInstruction) else null,
            initialMessages = messages?.map { (role, content) ->
                if (role == "user") Message.user(content)
                else Message.model(content)
            } ?: emptyList(),
            samplerConfig = SamplerConfig(
                topK = topK,
                topP = topP.toDouble(),
                temperature = temperature.toDouble()
            )
        )

        val conv = eng.createConversation(config)
        activeConversation = conv
        return conv
    }

    /**
     * Generate a complete (non-streaming) response.
     * Acquires the session mutex to prevent concurrent session creation.
     */
    suspend fun generateResponse(
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f
    ): String = sessionMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isInitialized) throw IllegalStateException("Engine not initialized")
            isBusy = true

            try {
                val systemMsg = messages.firstOrNull { it.role == "system" }?.content
                val conversationMessages = messages.filter { it.role != "system" }
                val history = conversationMessages.dropLast(1).map { it.role to it.content }
                val lastMessage = conversationMessages.lastOrNull()?.content
                    ?: throw IllegalArgumentException("No user message found")

                val conv = createNewConversation(
                    systemInstruction = systemMsg,
                    messages = history,
                    temperature = temperature,
                    topK = topK,
                    topP = topP
                )

                val response = conv.sendMessage(lastMessage)
                response.toString()
            } finally {
                closeActiveConversation()
                isBusy = false
            }
        }
    }

    /**
     * Generate a streaming response using Kotlin Flow.
     * Acquires the session mutex to prevent concurrent session creation.
     * Each emitted string is a token/chunk of the response.
     */
    fun generateResponseStream(
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        topK: Int = 40,
        topP: Float = 0.95f
    ): Flow<String> = flow {
        sessionMutex.withLock {
            if (!isInitialized) throw IllegalStateException("Engine not initialized")
            isBusy = true

            try {
                val systemMsg = messages.firstOrNull { it.role == "system" }?.content
                val conversationMessages = messages.filter { it.role != "system" }
                val history = conversationMessages.dropLast(1).map { it.role to it.content }
                val lastMessage = conversationMessages.lastOrNull()?.content
                    ?: throw IllegalArgumentException("No user message found")

                val conv = createNewConversation(
                    systemInstruction = systemMsg,
                    messages = history,
                    temperature = temperature,
                    topK = topK,
                    topP = topP
                )

                conv.sendMessageAsync(lastMessage).collect { message ->
                    emit(message.toString())
                }
            } finally {
                closeActiveConversation()
                isBusy = false
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        closeActiveConversation()
        try {
            engine?.close()
            engine = null
        } catch (e: Exception) {
            ServerLogger.e(TAG, "Error closing engine: ${e.message}")
        }
        isInitialized = false
        ServerLogger.i(TAG, "Engine closed")
    }
}

/**
 * Simple chat message data class for the API layer.
 */
data class ChatMessage(
    val role: String,   // "system", "user", or "assistant"
    val content: String
)
