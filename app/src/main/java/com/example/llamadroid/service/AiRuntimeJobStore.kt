package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.db.AiRuntimeJobEntity
import com.example.llamadroid.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AiRuntimeJobStore {
    const val TYPE_AGENT_CHAT = "AGENT_CHAT"
    const val TYPE_OLLAMA_PULL = "OLLAMA_PULL"
    const val TYPE_OLLAMA_CREATE_REMOTE = "OLLAMA_CREATE_REMOTE"
    const val TYPE_OLLAMA_CREATE_LOCAL = "OLLAMA_CREATE_LOCAL"

    const val STATUS_RUNNING = "RUNNING"
    const val STATUS_RECOVERING = "RECOVERING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_CANCELLED = "CANCELLED"

    suspend fun upsert(context: Context, job: AiRuntimeJobEntity) = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().upsert(job)
        pruneOldTerminalJobs(context)
    }

    suspend fun markState(
        context: Context,
        jobId: String,
        status: String,
        checkpointJson: String? = null,
        progressText: String? = null,
        errorMessage: String? = null
    ) = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().updateState(
            jobId = jobId,
            status = status,
            checkpointJson = checkpointJson,
            progressText = progressText,
            errorMessage = errorMessage,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun getByJobKey(context: Context, jobKey: String): AiRuntimeJobEntity? = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().getByJobKey(jobKey)
    }

    suspend fun getActiveJobs(context: Context): List<AiRuntimeJobEntity> = withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).aiRuntimeJobDao().getActiveJobs()
    }

    private suspend fun pruneOldTerminalJobs(context: Context) {
        val olderThan = System.currentTimeMillis() - 7L * 24L * 60L * 60L * 1000L
        AppDatabase.getDatabase(context).aiRuntimeJobDao().deleteTerminalJobsOlderThan(olderThan)
    }
}
