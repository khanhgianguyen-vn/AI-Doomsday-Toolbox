package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.data.repository.OllamaRepository
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max

data class OllamaRuntimeState(
    val progress: Map<String, Float> = emptyMap(),
    val status: Map<String, String> = emptyMap(),
    val activeJobKeys: Map<String, String> = emptyMap()
)

class OllamaRuntimeManager(
    private val appContext: Context,
    private val repository: OllamaRepository,
    private val runtimeScope: CoroutineScope,
    private val sshService: SSHService
) {
    companion object {
        private const val TAG = "OllamaRuntimeManager"
    }

    private val activeJobs = linkedMapOf<String, Job>()
    private val _runtimeState = MutableStateFlow(OllamaRuntimeState())
    val runtimeState: StateFlow<OllamaRuntimeState> = _runtimeState.asStateFlow()

    fun clearStatus(modelName: String) {
        _runtimeState.value = _runtimeState.value.copy(
            progress = _runtimeState.value.progress - modelName,
            status = _runtimeState.value.status - modelName,
            activeJobKeys = _runtimeState.value.activeJobKeys - modelName
        )
    }

    fun cancelOperation(modelName: String) {
        val jobKey = _runtimeState.value.activeJobKeys[modelName] ?: return
        synchronized(activeJobs) {
            activeJobs.remove(jobKey)?.cancel()
        }
    }

    fun cancelAll() {
        val jobs = synchronized(activeJobs) {
            activeJobs.values.toList().also { activeJobs.clear() }
        }
        jobs.forEach { it.cancel() }
        _runtimeState.value = OllamaRuntimeState()
    }

    fun pullModel(serverUrl: String, modelName: String) {
        val normalizedModel = modelName.trim()
        val jobKey = "pull|$serverUrl|$normalizedModel"
        startTrackedJob(
            jobKey = jobKey,
            modelName = normalizedModel,
            type = AiRuntimeJobStore.TYPE_OLLAMA_PULL,
            progressPrefix = appContext.getString(R.string.ollama_runtime_pulling),
            payloadBuilder = {
                put("serverUrl", serverUrl)
                put("modelName", normalizedModel)
            }
        ) { jobId ->
            repository.pullModel(serverUrl, normalizedModel).collect { progress ->
                val percent = if (progress.total != null && progress.completed != null && progress.total > 0) {
                    progress.completed.toFloat() / progress.total.toFloat()
                } else {
                    0f
                }
                updateProgress(jobId, normalizedModel, progress.status, percent)
            }
        }
    }

    fun createModel(serverUrl: String, name: String, fromModel: String, modelfile: String) {
        val normalizedName = repository.normalizeCreateModelName(name)
        val jobKey = "create_remote|$serverUrl|$normalizedName"
        startTrackedJob(
            jobKey = jobKey,
            modelName = normalizedName,
            type = AiRuntimeJobStore.TYPE_OLLAMA_CREATE_REMOTE,
            progressPrefix = appContext.getString(R.string.ollama_runtime_creating),
            payloadBuilder = {
                put("serverUrl", serverUrl)
                put("name", normalizedName)
                put("fromModel", fromModel)
                put("modelfile", modelfile)
            }
        ) { jobId ->
            updateProgress(jobId, normalizedName, "${appContext.getString(R.string.ollama_runtime_creating)} $normalizedName…", 0.08f)
            repository.createModel(serverUrl, normalizedName, fromModel, modelfile).collect { progress ->
                val currentProgress = _runtimeState.value.progress[normalizedName] ?: 0f
                val estimatedProgress = estimateCreateProgress(progress.status, currentProgress)
                updateProgress(jobId, normalizedName, progress.status, estimatedProgress)
            }
        }
    }

    fun createModelLocally(name: String, fromModel: String, modelfile: String) {
        val normalizedName = repository.normalizeCreateModelName(name)
        val jobKey = "create_local|$normalizedName"
        startTrackedJob(
            jobKey = jobKey,
            modelName = normalizedName,
            type = AiRuntimeJobStore.TYPE_OLLAMA_CREATE_LOCAL,
            progressPrefix = appContext.getString(R.string.ollama_runtime_creating_local),
            payloadBuilder = {
                put("name", normalizedName)
                put("fromModel", fromModel)
                put("modelfile", modelfile)
            }
        ) { jobId ->
            updateProgress(jobId, normalizedName, "creating locally", 0.1f)
            repository.createModelLocally(sshService, normalizedName, fromModel, modelfile)
                .onSuccess {
                    updateProgress(jobId, normalizedName, it.ifBlank { "success" }, 1f)
                }
                .onFailure { throw it }
        }
    }

    fun resumePersistedJobs() {
        runtimeScope.launch {
            val jobs = AiRuntimeJobStore.getActiveJobs(appContext)
            jobs.forEach { job ->
                val payload = runCatching { JSONObject(job.payloadJson) }.getOrNull() ?: return@forEach
                when (job.type) {
                    AiRuntimeJobStore.TYPE_OLLAMA_PULL -> {
                        pullModel(
                            serverUrl = payload.optString("serverUrl"),
                            modelName = payload.optString("modelName")
                        )
                    }
                    AiRuntimeJobStore.TYPE_OLLAMA_CREATE_REMOTE -> {
                        createModel(
                            serverUrl = payload.optString("serverUrl"),
                            name = payload.optString("name"),
                            fromModel = payload.optString("fromModel"),
                            modelfile = payload.optString("modelfile")
                        )
                    }
                    AiRuntimeJobStore.TYPE_OLLAMA_CREATE_LOCAL -> {
                        createModelLocally(
                            name = payload.optString("name"),
                            fromModel = payload.optString("fromModel"),
                            modelfile = payload.optString("modelfile")
                        )
                    }
                }
            }
        }
    }

    private fun startTrackedJob(
        jobKey: String,
        modelName: String,
        type: String,
        progressPrefix: String,
        payloadBuilder: JSONObject.() -> Unit,
        block: suspend (jobId: String) -> Unit
    ) {
        synchronized(activeJobs) {
            if (activeJobs[jobKey]?.isActive == true) return
        }

        val payload = JSONObject().apply(payloadBuilder)
        val now = System.currentTimeMillis()
        val jobId = UUID.randomUUID().toString()
        AgentForegroundService.retainRuntime(appContext, "$progressPrefix $modelName…")

        val job = runtimeScope.launch {
            try {
                AiRuntimeJobStore.upsert(
                    appContext,
                    AiRuntimeJobEntity(
                        jobId = jobId,
                        jobKey = jobKey,
                        type = type,
                        status = AiRuntimeJobStore.STATUS_RUNNING,
                        modelName = modelName,
                        payloadJson = payload.toString(),
                        progressText = "$progressPrefix $modelName…",
                        createdAt = now,
                        updatedAt = now
                    )
                )
                setStatus(modelName, "$progressPrefix $modelName…", 0f, jobKey)
                block(jobId)
                AiRuntimeJobStore.markState(
                    appContext,
                    jobId = jobId,
                    status = AiRuntimeJobStore.STATUS_COMPLETED,
                    progressText = appContext.getString(R.string.ollama_runtime_success)
                )
                setStatus(modelName, appContext.getString(R.string.ollama_runtime_success), 1f, null)
            } catch (e: CancellationException) {
                AiRuntimeJobStore.markState(
                    appContext,
                    jobId = jobId,
                    status = AiRuntimeJobStore.STATUS_CANCELLED,
                    progressText = appContext.getString(R.string.ollama_runtime_cancelled),
                    errorMessage = null
                )
                setStatus(modelName, appContext.getString(R.string.ollama_runtime_cancelled), 0f, null)
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                DebugLog.log("[$TAG] Operation $jobKey failed: $message")
                AiRuntimeJobStore.markState(
                    appContext,
                    jobId = jobId,
                    status = AiRuntimeJobStore.STATUS_FAILED,
                    progressText = appContext.getString(R.string.ollama_status_failed_prefix),
                    errorMessage = message
                )
                setStatus(modelName, "${appContext.getString(R.string.ollama_status_failed_prefix)} $message", 0f, null)
            } finally {
                synchronized(activeJobs) {
                    activeJobs.remove(jobKey)
                }
                AgentForegroundService.releaseRuntime(appContext)
            }
        }

        synchronized(activeJobs) {
            activeJobs[jobKey] = job
        }
    }

    private suspend fun updateProgress(jobId: String, modelName: String, status: String, progress: Float) {
        AiRuntimeJobStore.markState(
            appContext,
            jobId = jobId,
            status = AiRuntimeJobStore.STATUS_RUNNING,
            progressText = status
        )
        setStatus(modelName, status, progress, _runtimeState.value.activeJobKeys[modelName])
    }

    private fun setStatus(modelName: String, status: String, progress: Float, activeJobKey: String?) {
        val nextProgress = _runtimeState.value.progress.toMutableMap()
        val nextStatus = _runtimeState.value.status.toMutableMap()
        val nextKeys = _runtimeState.value.activeJobKeys.toMutableMap()

        if (status.equals(appContext.getString(R.string.ollama_runtime_success), ignoreCase = true) ||
            status.equals(appContext.getString(R.string.ollama_runtime_cancelled), ignoreCase = true)
        ) {
            nextProgress.remove(modelName)
            nextKeys.remove(modelName)
            nextStatus[modelName] = status
        } else {
            nextProgress[modelName] = progress
            nextStatus[modelName] = status
            activeJobKey?.let { nextKeys[modelName] = it }
        }

        _runtimeState.value = OllamaRuntimeState(
            progress = nextProgress,
            status = nextStatus,
            activeJobKeys = nextKeys
        )
    }

    private fun estimateCreateProgress(status: String, currentProgress: Float): Float {
        val normalized = status.lowercase()
        val stagedProgress = when {
            normalized.contains("gathering") -> 0.12f
            normalized.contains("reading model metadata") -> 0.2f
            normalized.contains("creating model layer") -> 0.35f
            normalized.contains("creating system layer") -> 0.55f
            normalized.contains("creating template layer") -> 0.7f
            normalized.contains("creating license layer") -> 0.78f
            normalized.contains("creating parameter layer") -> 0.82f
            normalized.contains("using already created layer") -> 0.86f
            normalized.contains("copying") || normalized.contains("transferring") -> 0.9f
            normalized.contains("writing manifest") -> 0.96f
            normalized.contains("success") || normalized.contains("completed") || normalized.contains("done") -> 1f
            else -> max(currentProgress, 0.08f)
        }
        return max(currentProgress, stagedProgress.coerceIn(0f, 1f))
    }
}
