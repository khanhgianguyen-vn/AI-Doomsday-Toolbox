package com.example.llamadroid.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.llamadroid.util.DebugLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Chat service for llama-server (llama.cpp HTTP server).
 * Uses the OpenAI-compatible /v1/chat/completions endpoint.
 *
 * Key differences from Ollama:
 * - Model is fixed at server launch (not changeable per-request)
 * - Threads are fixed at server launch
 * - Thinking is handled via <think> tags in content (same parsing as Ollama fallback)
 * - Streaming uses SSE format (data: {json}\n\n) instead of JSON lines
 * - Tool calls arrive incrementally across SSE chunks
 */
class LlamaServerChatService {

    companion object {
        private const val TAG = "LlamaServerChat"
    }

    @Volatile
    var shouldStop = false

    fun stopGeneration() {
        shouldStop = true
    }

    /**
     * Send a chat completion request to llama-server with tool support.
     * Returns the same ChatResponse type as OllamaService for seamless integration.
     *
     * @param baseUrl llama-server base URL (e.g., "http://localhost:8080")
     * @param messages conversation history
     * @param tools available tools for function calling
     * @param thinkingEnabled if false, strip <think> tags from output
     * @param numCtx context window size (passed as max_tokens hint)
     * @param onChunk streaming callback: (contentDelta, thinkingDelta)
     */
    suspend fun chatWithToolsStreaming(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool> = emptyList(),
        thinkingEnabled: Boolean = true,
        numCtx: Int = 16384,
        onChunk: (String?, String?) -> Unit = { _, _ -> }
    ): Result<OllamaService.ChatResponse> = withContext(Dispatchers.IO) {
        shouldStop = false
        var sawStreamOutput = false
        val guardedOnChunk: (String?, String?) -> Unit = { chunk, thinkingChunk ->
            if (!chunk.isNullOrBlank() || !thinkingChunk.isNullOrBlank()) {
                sawStreamOutput = true
            }
            onChunk(chunk, thinkingChunk)
        }

        try {
            val chatResponse = RemoteAgentProtection.withProtection(baseUrl, "Running remote llama-server agent…") {
                RemoteBackendResilience.runWithSingleRetry(
                    onRetry = { firstError ->
                        DebugLog.log("[$TAG] Recoverable llama-server chat failure, retrying: ${RemoteBackendResilience.summarize(firstError)}")
                    },
                    shouldRetry = { !sawStreamOutput }
                ) {
                    performChatWithToolsStreaming(baseUrl, messages, tools, thinkingEnabled, numCtx, guardedOnChunk)
                }
            }
            Result.success(chatResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun performChatWithToolsStreaming(
        baseUrl: String,
        messages: List<OllamaService.ChatMessage>,
        tools: List<AgentTool>,
        thinkingEnabled: Boolean,
        numCtx: Int,
        onChunk: (String?, String?) -> Unit
    ): OllamaService.ChatResponse {
        val url = URL("${baseUrl.trimEnd('/')}/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 30000
        conn.readTimeout = 1800000 // 30 minutes for long reasoning

        // Build request in OpenAI format
        val requestJson = JSONObject().apply {
            put("stream", true)
            // Model field is required by the API but llama-server ignores it
            // (it serves whatever model was loaded at startup)
            put("model", "local-model")

            // Messages array
            val messagesArray = JSONArray()
            for (msg in messages) {
                messagesArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                    // Include tool_calls for assistant messages
                    msg.toolCalls?.let { calls ->
                        if (calls.isNotEmpty()) {
                            val tcArray = JSONArray()
                            for (tc in calls) {
                                tcArray.put(JSONObject().apply {
                                    put("id", tc.id ?: "call_${System.nanoTime()}")
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", tc.name)
                                        put("arguments", JSONObject(tc.arguments as Map<*, *>).toString())
                                    })
                                })
                            }
                            put("tool_calls", tcArray)
                        }
                    }
                    // For tool role messages, include tool_call_id
                    if (msg.role == "tool" && msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
                })
            }
            put("messages", messagesArray)

            // Tools in OpenAI format
            if (tools.isNotEmpty()) {
                val toolsArray = JSONArray()
                for (tool in tools) {
                    toolsArray.put(JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    for ((paramName, paramDesc) in tool.parameters) {
                                        put(paramName, JSONObject().apply {
                                            put("type", "string")
                                            put("description", paramDesc)
                                        })
                                    }
                                })
                                put("required", JSONArray(tool.requiredParams))
                            })
                        })
                    })
                }
                put("tools", toolsArray)
                put("tool_choice", "auto")
            }
        }

        // numCtx kept for signature parity / future hinting.
        if (numCtx > 0) {
            requestJson.put("max_tokens", numCtx)
        }

        OutputStreamWriter(conn.outputStream).use { it.write(requestJson.toString()); it.flush() }

        if (conn.responseCode != 200) {
            val errorBody = try {
                conn.errorStream?.bufferedReader()?.readText() ?: "Unknown error"
            } catch (_: Exception) {
                "HTTP ${conn.responseCode}"
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
            throw Exception("llama-server error: $errorBody")
        }

        // Parse SSE streaming response
        val fullContent = StringBuilder()
        val fullThinking = StringBuilder()
        var insideThinkTag = false

        // Tool calls are assembled incrementally across SSE chunks
        // Each chunk may contain partial function name or arguments
        val toolCallBuilders = mutableMapOf<Int, ToolCallBuilder>()

        BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (shouldStop) {
                    DebugLog.log("[$TAG] stop requested, breaking SSE stream")
                    conn.disconnect()
                    throw Exception("Stopped by user")
                }

                val data = line ?: continue

                // SSE format: lines starting with "data: "
                if (!data.startsWith("data: ")) continue
                val jsonStr = data.removePrefix("data: ").trim()

                // Stream end signal
                if (jsonStr == "[DONE]") break

                try {
                    val chunk = JSONObject(jsonStr)
                    val choices = chunk.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue

                    val choice = choices.getJSONObject(0)
                    val delta = choice.optJSONObject("delta") ?: continue

                    // Handle content delta
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        // Parse <think> tags the same way OllamaService does
                        var remaining = content
                        while (remaining.isNotEmpty()) {
                            if (!insideThinkTag) {
                                if (remaining.contains("<think>")) {
                                    val parts = remaining.split("<think>", limit = 2)
                                    if (parts[0].isNotEmpty()) {
                                        fullContent.append(parts[0])
                                        onChunk(parts[0], null)
                                    }
                                    insideThinkTag = true
                                    remaining = if (parts.size > 1) parts[1] else ""
                                } else {
                                    fullContent.append(remaining)
                                    onChunk(remaining, null)
                                    remaining = ""
                                }
                            } else {
                                if (remaining.contains("</think>")) {
                                    val parts = remaining.split("</think>", limit = 2)
                                    if (parts[0].isNotEmpty()) {
                                        fullThinking.append(parts[0])
                                        if (thinkingEnabled) onChunk(null, parts[0])
                                    }
                                    insideThinkTag = false
                                    remaining = if (parts.size > 1) parts[1] else ""
                                } else {
                                    fullThinking.append(remaining)
                                    if (thinkingEnabled) onChunk(null, remaining)
                                    remaining = ""
                                }
                            }
                        }
                    }

                    // Handle reasoning_content field (some models use this instead of <think> tags)
                    val reasoningContent = delta.optString("reasoning_content", "")
                    if (reasoningContent.isNotEmpty()) {
                        fullThinking.append(reasoningContent)
                        if (thinkingEnabled) onChunk(null, reasoningContent)
                    }

                    // Handle incremental tool calls
                    val tcArray = delta.optJSONArray("tool_calls")
                    if (tcArray != null) {
                        for (i in 0 until tcArray.length()) {
                            val tcObj = tcArray.getJSONObject(i)
                            val index = tcObj.optInt("index", i)
                            val id = tcObj.optString("id", "")
                            val funcObj = tcObj.optJSONObject("function")

                            val builder = toolCallBuilders.getOrPut(index) { ToolCallBuilder() }
                            if (id.isNotEmpty()) builder.id = id
                            if (funcObj != null) {
                                val name = funcObj.optString("name", "")
                                val args = funcObj.optString("arguments", "")
                                if (name.isNotEmpty()) builder.name = name
                                builder.arguments.append(args)
                            }
                        }
                    }

                    // Check finish_reason
                    val finishReason = choice.optString("finish_reason", "")
                    if (finishReason == "stop" || finishReason == "tool_calls") break

                } catch (e: Exception) {
                    DebugLog.log("[$TAG] SSE parse error: ${e.message} for line: $jsonStr")
                }
            }
        }

        // Assemble final tool calls from builders
        val toolCalls = if (toolCallBuilders.isNotEmpty()) {
            toolCallBuilders.entries.sortedBy { it.key }.mapNotNull { (_, builder) ->
                if (builder.name.isNotEmpty()) {
                    try {
                        val argsJson = JSONObject(builder.arguments.toString())
                        val args = mutableMapOf<String, String>()
                        argsJson.keys().forEach { key -> args[key] = argsJson.get(key).toString() }
                        DebugLog.log("[$TAG] Assembled tool call: ${builder.name} (id: ${builder.id})")
                        OllamaService.ToolCall(builder.name, args, builder.id)
                    } catch (e: Exception) {
                        DebugLog.log("[$TAG] Failed to parse tool call args: ${e.message}")
                        null
                    }
                } else null
            }
        } else null

        try {
            conn.disconnect()
        } catch (_: Exception) {
        }

        DebugLog.log("[$TAG] Stream finished. ${toolCalls?.size ?: 0} tool calls detected.")

        return OllamaService.ChatResponse(
            message = OllamaService.ChatMessage(
                role = "assistant",
                content = fullContent.toString(),
                toolCalls = toolCalls,
                thinking = fullThinking.toString().ifEmpty { null }
            ),
            done = true,
            toolCalls = toolCalls
        )
    }

    /**
     * Check connection to llama-server by hitting /health endpoint
     */
    suspend fun checkConnection(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            RemoteAgentProtection.withProtection(baseUrl, "Checking remote llama-server connection…") {
                RemoteBackendResilience.runWithSingleRetry(
                    onRetry = { firstError ->
                        DebugLog.log("[$TAG] Recoverable llama-server health failure, retrying: ${RemoteBackendResilience.summarize(firstError)}")
                    }
                ) {
                    val url = URL("${baseUrl.trimEnd('/')}/health")
                    val conn = url.openConnection() as HttpURLConnection
                    try {
                        conn.requestMethod = "GET"
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.responseCode == 200
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Helper class to assemble tool calls from incremental SSE deltas.
     * llama-server sends tool calls piece by piece:
     * - First chunk: id + function name
     * - Subsequent chunks: argument fragments
     */
    private class ToolCallBuilder {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }
}
