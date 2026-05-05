package com.llmnode.gemmaserver.server

import android.content.Context
import com.llmnode.gemmaserver.engine.ChatMessage
import com.llmnode.gemmaserver.engine.LiteRtInferenceEngine
import com.llmnode.gemmaserver.security.ApiKeyManager
import com.llmnode.gemmaserver.service.GemmaServerService
import com.llmnode.gemmaserver.util.ServerLogger
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class IncomingTask(
    val timestamp: Long = System.currentTimeMillis(),
    val prompt: String = "",
    val id: String = ""
)

data class StreamToken(
    val token: String = "",
    val done: Boolean = false,
    val tokensPerSec: Double = 0.0
)

data class SimulationTask(
    val personaName: String = "",
    val personaTitle: String = "",
    val eventName: String = "",
    val eventDescription: String = "",
    val nodes: List<SimNode> = emptyList(),
    val taskId: String = "sim-${System.currentTimeMillis()}"
)

data class SimNode(
    val name: String = "",
    val relation: String = "ALLY" // ALLY, RIVAL, HOSTILE
)

data class SimulationResult(
    val taskId: String = "",
    val rawJson: String = "",
    val streamingTokens: List<String> = emptyList()
)

class ApiServer(
    private val context: Context,
    private val port: Int = 8080,
    val taskFlow: MutableSharedFlow<IncomingTask> = MutableSharedFlow(extraBufferCapacity = 10),
    val tokenFlow: MutableSharedFlow<StreamToken> = MutableSharedFlow(extraBufferCapacity = 100),
    val simulationFlow: MutableSharedFlow<SimulationTask> = MutableSharedFlow(extraBufferCapacity = 5),
    val simulationResultFlow: MutableSharedFlow<SimulationResult> = MutableSharedFlow(extraBufferCapacity = 5)
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "ApiServer"
        private const val MAX_QUEUE = 3
    }

    private val activeRequests = AtomicInteger(0)
    val totalRequests = AtomicLong(0)
    val totalBytesIn = AtomicLong(0)
    val totalBytesOut = AtomicLong(0)

    var modelLoaded: Boolean = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        ServerLogger.i(TAG, "${method.name} $uri")

        return when {
            uri == "/health" && method == Method.GET -> handleHealth()
            uri == "/v1/chat/completions" && method == Method.POST -> handleChatCompletions(session)
            uri == "/v1/simulate" && method == Method.POST -> handleSimulate(session)
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found"
            )
        }
    }

    private fun handleHealth(): Response {
        val busy = activeRequests.get() > 0
        val json = """{"status":"ok","model":"gemma-4-E2B","busy":$busy,"loaded":$modelLoaded,"engine":"litert-lm"}"""
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        // Auth check
        val authHeader = session.headers["authorization"] ?: ""
        val token = authHeader.removePrefix("Bearer ").trim()
        ServerLogger.i(TAG, "Auth header raw: '$authHeader'")
        ServerLogger.i(TAG, "Token extracted: '$token' (len=${token.length})")
        if (!ApiKeyManager.validateKey(context, token)) {
            val storedKey = ApiKeyManager.getOrCreateApiKey(context)
            ServerLogger.i(TAG, "Stored key: '$storedKey' (len=${storedKey.length})")
            ServerLogger.i(TAG, "Match: ${token == storedKey}")
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                """{"error":{"message":"Invalid API key","type":"authentication_error","code":"invalid_api_key"}}"""
            )
        }

        // Model loaded check
        if (!modelLoaded) {
            return newFixedLengthResponse(
                Response.Status.lookup(503), "application/json",
                """{"error":{"message":"Model not loaded yet","type":"server_error","code":"model_not_loaded"}}"""
            )
        }

        // Queue check
        val current = activeRequests.incrementAndGet()
        if (current > MAX_QUEUE) {
            activeRequests.decrementAndGet()
            return newFixedLengthResponse(
                Response.Status.lookup(429), "application/json",
                """{"error":{"message":"Too many requests. Queue full ($MAX_QUEUE max).","type":"rate_limit_error","code":"rate_limit"}}"""
            )
        }

        try {
            // Read request body
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: ""
            totalBytesIn.addAndGet(body.length.toLong())

            ServerLogger.i(TAG, "Request body length: ${body.length}")

            // Extract prompt for UI display
            val prompt = extractPromptFromBody(body)
            val requestId = "req-${System.currentTimeMillis()}"
            taskFlow.tryEmit(IncomingTask(timestamp = System.currentTimeMillis(), prompt = prompt, id = requestId))

            // Parse messages from body
            val messages = parseMessages(body)

            // Parse parameters
            val temperature = extractFloat(body, "temperature") ?: 0.7f
            val topP = extractFloat(body, "top_p") ?: 0.95f
            val topK = extractInt(body, "top_k") ?: 40

            // Check if streaming is requested
            val isStreaming = body.contains("\"stream\"") && body.contains("true")

            val engine = GemmaServerService.inferenceEngine
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":{"message":"Inference engine not available","type":"server_error"}}"""
                )

            return if (isStreaming) {
                handleStreamingInference(engine, messages, requestId, temperature, topK, topP)
            } else {
                handleBlockingInference(engine, messages, requestId, temperature, topK, topP)
            }
        } catch (e: Exception) {
            ServerLogger.e(TAG, "Error handling request: ${e.message}")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":{"message":"Internal server error: ${e.message}","type":"server_error"}}"""
            )
        } finally {
            activeRequests.decrementAndGet()
            totalRequests.incrementAndGet()
        }
    }

    private fun handleBlockingInference(
        engine: LiteRtInferenceEngine,
        messages: List<ChatMessage>,
        requestId: String,
        temperature: Float,
        topK: Int,
        topP: Float
    ): Response {
        // Use streaming internally so the UI shows tokens in real-time
        val fullResponse = StringBuilder()
        var tokenCount = 0
        val startTime = System.currentTimeMillis()
        runBlocking {
            engine.generateResponseStream(messages, temperature, topK, topP).collect { chunk ->
                fullResponse.append(chunk)
                tokenCount++
                val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val tps = if (elapsed > 0) tokenCount / elapsed else 0.0
                tokenFlow.tryEmit(StreamToken(token = chunk, done = false, tokensPerSec = tps))
            }
        }
        val totalElapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val finalTps = if (totalElapsed > 0) tokenCount / totalElapsed else 0.0
        tokenFlow.tryEmit(StreamToken(token = "", done = true, tokensPerSec = finalTps))

        val responseText = fullResponse.toString()

        // Build OpenAI-format response
        val escapedContent = escapeJson(responseText)
        val responseJson = """{
            "id": "$requestId",
            "object": "chat.completion",
            "created": ${System.currentTimeMillis() / 1000},
            "model": "gemma-4-E2B",
            "choices": [{
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": "$escapedContent"
                },
                "finish_reason": "stop"
            }],
            "usage": {
                "prompt_tokens": 0,
                "completion_tokens": 0,
                "total_tokens": 0
            }
        }""".trimIndent()

        totalBytesOut.addAndGet(responseJson.length.toLong())
        return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)
    }

    private fun handleStreamingInference(
        engine: LiteRtInferenceEngine,
        messages: List<ChatMessage>,
        requestId: String,
        temperature: Float,
        topK: Int,
        topP: Float
    ): Response {
        val pipedOut = PipedOutputStream()
        val pipedIn = PipedInputStream(pipedOut, 64 * 1024)

        scope.launch {
            try {
                val writer = BufferedWriter(OutputStreamWriter(pipedOut))
                var tokenCount = 0
                val startTime = System.currentTimeMillis()

                engine.generateResponseStream(messages, temperature, topK, topP).collect { chunk ->
                    tokenCount++
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    val tps = if (elapsed > 0) tokenCount / elapsed else 0.0

                    val escapedChunk = escapeJson(chunk)
                    val sseData = """data: {"id":"$requestId","object":"chat.completion.chunk","created":${System.currentTimeMillis() / 1000},"model":"gemma-4-E2B","choices":[{"index":0,"delta":{"content":"$escapedChunk"},"finish_reason":null}]}"""

                    writer.write(sseData)
                    writer.write("\n\n")
                    writer.flush()
                    totalBytesOut.addAndGet(sseData.length.toLong() + 2)

                    tokenFlow.tryEmit(StreamToken(token = chunk, done = false, tokensPerSec = tps))
                }

                // Send [DONE]
                val totalElapsed = (System.currentTimeMillis() - startTime) / 1000.0
                val finalTps = if (totalElapsed > 0) tokenCount / totalElapsed else 0.0
                writer.write("data: [DONE]\n\n")
                writer.flush()
                tokenFlow.tryEmit(StreamToken(token = "", done = true, tokensPerSec = finalTps))
                writer.close()
            } catch (e: Exception) {
                ServerLogger.e(TAG, "Stream inference error: ${e.message}")
            } finally {
                try { pipedOut.close() } catch (_: Exception) { }
            }
        }

        return newChunkedResponse(Response.Status.OK, "text/event-stream", pipedIn).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("Connection", "keep-alive")
        }
    }

    /**
     * Parse messages array from OpenAI-format JSON body.
     */
    private fun parseMessages(body: String): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        // Match each message object in the messages array
        val msgRegex = """\{\s*"role"\s*:\s*"([^"]+)"\s*,\s*"content"\s*:\s*"([^"]*(?:\\.[^"]*)*)"\s*\}""".toRegex()
        val matches = msgRegex.findAll(body)

        for (match in matches) {
            val role = match.groupValues[1]
            val content = match.groupValues[2]
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\/", "/")
            messages.add(ChatMessage(role = role, content = content))
        }

        if (messages.isEmpty()) {
            // Fallback: try to extract just the content
            val prompt = extractPromptFromBody(body)
            messages.add(ChatMessage(role = "user", content = prompt))
        }

        return messages
    }

    private fun extractPromptFromBody(body: String): String {
        try {
            val contentRegex = """"content"\s*:\s*"([^"]*(?:\\.[^"]*)*)"""".toRegex()
            val matches = contentRegex.findAll(body).toList()
            if (matches.isNotEmpty()) {
                val raw = matches.last().groupValues[1]
                return raw.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
            }
        } catch (_: Exception) { }
        return "(unable to parse prompt)"
    }

    private fun extractFloat(body: String, key: String): Float? {
        val regex = """"$key"\s*:\s*([0-9]*\.?[0-9]+)""".toRegex()
        return regex.find(body)?.groupValues?.get(1)?.toFloatOrNull()
    }

    private fun extractInt(body: String, key: String): Int? {
        val regex = """"$key"\s*:\s*([0-9]+)""".toRegex()
        return regex.find(body)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun escapeJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun handleSimulate(session: IHTTPSession): Response {
        // Auth check
        val authHeader = session.headers["authorization"] ?: ""
        val token = authHeader.removePrefix("Bearer ").trim()
        if (!ApiKeyManager.validateKey(context, token)) {
            return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED, "application/json",
                """{"error":{"message":"Invalid API key","type":"authentication_error"}}"""
            )
        }

        if (!modelLoaded) {
            return newFixedLengthResponse(
                Response.Status.lookup(503), "application/json",
                """{"error":{"message":"Model not loaded","type":"server_error"}}"""
            )
        }

        try {
            val bodyMap = HashMap<String, String>()
            session.parseBody(bodyMap)
            val body = bodyMap["postData"] ?: ""

            // Parse simulation task fields
            val personaName = extractString(body, "persona_name") ?: "Unknown"
            val personaTitle = extractString(body, "persona_title") ?: ""
            val eventName = extractString(body, "event_name") ?: ""
            val eventDesc = extractString(body, "event_description") ?: ""
            val taskId = "sim-${System.currentTimeMillis()}"

            // Parse nodes array
            val nodes = mutableListOf<SimNode>()
            val nodeRegex = """\{\s*"name"\s*:\s*"([^"]*)"\s*,\s*"relation"\s*:\s*"([^"]*)"""".toRegex()
            nodeRegex.findAll(body).forEach { match ->
                nodes.add(SimNode(name = match.groupValues[1], relation = match.groupValues[2]))
            }

            val simTask = SimulationTask(
                personaName = personaName,
                personaTitle = personaTitle,
                eventName = eventName,
                eventDescription = eventDesc,
                nodes = nodes,
                taskId = taskId
            )

            // Emit task to UI (triggers persona overlay)
            simulationFlow.tryEmit(simTask)
            taskFlow.tryEmit(IncomingTask(prompt = "Simulate: $personaName", id = taskId))

            // Build the AI prompt
            val nodesDesc = nodes.joinToString(", ") { "${it.name} (${it.relation})" }
            val systemPrompt = """You are a political/social simulation engine. Analyze the given person and event, then output ONLY valid JSON (no markdown, no explanation, just raw JSON).

Output this exact JSON structure:
{
  "tags": ["TAG1", "TAG2", "TAG3"],
  "quote": "A 1-2 sentence psychological core description of this person",
  "radar": {"power":0-100, "security":0-100, "conformity":0-100, "tradition":0-100, "benevolence":0-100, "universalism":0-100, "selfDirection":0-100, "stimulation":0-100, "hedonism":0-100, "achievement":0-100},
  "powerBases": {"coercive":0-100, "reward":0-100, "legitimate":0-100, "expert":0-100, "referent":0-100},
  "behavioral": {"riskTolerance":0-100, "adaptability":0-100, "transparency":0-100, "decisiveness":0-100, "cooperativeness":0-100},
  "shadow": {"bias": "description", "breakPoint": "description"},
  "identity": {"archetype": "THE X", "self": "description", "coreResource": "description", "leverage": "description", "fear": "description"},
  "connections": [{"name":"entity","type":"INFLUENCE|COOPERATION","direction":"outgoing|incoming","weight":0-100}],
  "reaction": {
    "quote": "What this person would say in response to the event (in their voice)",
    "actions": [{"nodeName":"name","relation":"ALLY|RIVAL|HOSTILE","actionType":"COORDINATE|ATTACK|DISCREDIT|DEFEND","description":"what they would do","intensity":"LOW|MEDIUM|HIGH|EXTREME"}]
  }
}"""

            val userPrompt = """Person: $personaName${if (personaTitle.isNotEmpty()) " ($personaTitle)" else ""}
Event: ${if (eventName.isNotEmpty()) "$eventName - $eventDesc" else "General analysis"}
Related nodes: ${if (nodesDesc.isNotEmpty()) nodesDesc else "None specified"}

Generate the complete JSON analysis now."""

            val engine = GemmaServerService.inferenceEngine
                ?: return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":{"message":"Inference engine not available"}}"""
                )

            // Run inference and collect full response
            val messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            )

            val fullResponse = StringBuilder()
            val streamTokens = mutableListOf<String>()
            var tokenCount = 0
            val startTime = System.currentTimeMillis()

            runBlocking {
                engine.generateResponseStream(messages, temperature = 0.7f, topK = 40, topP = 0.95f).collect { chunk ->
                    fullResponse.append(chunk)
                    streamTokens.add(chunk)
                    tokenCount++
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
                    val tps = if (elapsed > 0) tokenCount / elapsed else 0.0
                    tokenFlow.tryEmit(StreamToken(token = chunk, done = false, tokensPerSec = tps))
                }
            }

            val totalElapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val finalTps = if (totalElapsed > 0) tokenCount / totalElapsed else 0.0
            tokenFlow.tryEmit(StreamToken(token = "", done = true, tokensPerSec = finalTps))

            val rawOutput = fullResponse.toString()

            // Emit simulation result to UI
            simulationResultFlow.tryEmit(SimulationResult(
                taskId = taskId,
                rawJson = rawOutput,
                streamingTokens = streamTokens
            ))

            totalRequests.incrementAndGet()

            // Return the raw AI output to Postman too
            val responseJson = """{"task_id":"$taskId","persona":"$personaName","status":"completed","tokens":$tokenCount,"tps":${String.format("%.1f", finalTps)},"result":${escapeJson(rawOutput)}}"""
            return newFixedLengthResponse(Response.Status.OK, "application/json", responseJson)

        } catch (e: Exception) {
            ServerLogger.e(TAG, "Simulation error: ${e.message}")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":{"message":"Simulation failed: ${e.message}"}}"""
            )
        }
    }

    private fun extractString(body: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]*(?:\\.[^"]*)*)"""".toRegex()
        return regex.find(body)?.groupValues?.get(1)
    }

    fun stopServer() {
        scope.cancel()
        stop()
    }
}
