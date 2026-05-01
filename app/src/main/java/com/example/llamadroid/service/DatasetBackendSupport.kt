package com.example.llamadroid.service

import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.data.api.CompletionRequest
import com.example.llamadroid.data.api.LlamaServerApi
import com.example.llamadroid.data.db.DatasetProjectEntity
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface DatasetLlmBackend {
    suspend fun complete(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stop: List<String> = emptyList()
    ): String

    fun normalizeQuestionOutput(response: String): String = response.trim()
}

internal class LlamaServerDatasetLlmBackend(
    private val api: LlamaServerApi
) : DatasetLlmBackend {
    override suspend fun complete(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stop: List<String>
    ): String {
        return api.completion(
            CompletionRequest(
                prompt = prompt,
                n_predict = maxTokens,
                temperature = temperature,
                stop = stop
            )
        ).content.trim()
    }
}

internal class OllamaDatasetLlmBackend(
    private val project: DatasetProjectEntity
) : DatasetLlmBackend {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun complete(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stop: List<String>
    ): String = suspendCancellableCoroutine { continuation ->
        val baseUrl = project.ollamaUrl.trim().trimEnd('/')

        val payload = buildJsonObject(
            buildDatasetOllamaRequestPayload(
                project = project,
                prompt = prompt,
                maxTokens = maxTokens,
                temperature = temperature,
                stop = stop
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(
                payload.toString().toRequestBody(
                    "application/json; charset=utf-8".toMediaType()
                )
            )
            .build()

        val call = client.newCall(request)
        continuation.invokeOnCancellation {
            call.cancel()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = response.body?.string().orEmpty()

                        if (!response.isSuccessful) {
                            val error = runCatching { JSONObject(body).optString("error") }
                                .getOrNull()
                                .orEmpty()
                                .ifBlank { body.ifBlank { "HTTP ${response.code}" } }
                            throw IllegalStateException(error)
                        }

                        val json = JSONObject(body)
                        if (json.has("error")) {
                            throw IllegalStateException(json.optString("error"))
                        }

                        val content = json.optJSONObject("message")
                            ?.optString("content")
                            ?.trim()
                            .orEmpty()

                        if (!continuation.isCancelled) {
                            continuation.resume(content)
                        }
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
        })
    }

    override fun normalizeQuestionOutput(response: String): String {
        return extractDatasetQuestionLine(response)
    }
}

internal fun normalizeDatasetBackend(backend: String?): String {
    return if (backend == SettingsRepository.PDF_BACKEND_OLLAMA) {
        SettingsRepository.PDF_BACKEND_OLLAMA
    } else {
        SettingsRepository.PDF_BACKEND_LLAMA_SERVER
    }
}

internal fun buildDatasetOllamaRequestPayload(
    project: DatasetProjectEntity,
    prompt: String,
    maxTokens: Int,
    temperature: Float,
    stop: List<String> = emptyList()
): Map<String, Any?> {
    val options = linkedMapOf<String, Any?>(
        "num_ctx" to project.ollamaNumCtx,
        "num_thread" to project.ollamaThreads,
        "use_mmap" to project.ollamaMmap,
        "temperature" to temperature.toDouble(),
        "num_predict" to maxTokens
    )
    if (stop.isNotEmpty()) {
        options["stop"] = stop
    }

    return linkedMapOf(
        "model" to project.ollamaModel,
        "stream" to false,
        "think" to false,
        "messages" to listOf(
            mapOf(
                "role" to "user",
                "content" to prompt
            )
        ),
        "options" to options
    )
}

internal fun extractDatasetQuestionLine(response: String): String {
    return response.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        ?: response.trim()
}
