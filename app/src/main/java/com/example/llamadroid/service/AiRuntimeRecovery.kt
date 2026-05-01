package com.example.llamadroid.service

import android.content.Context
import android.content.Intent
import com.example.llamadroid.R
import com.example.llamadroid.tama.notifications.TamaNotificationScheduler
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object AiRuntimeRecovery {
    private const val SOURCE = "ai_runtime_boot_receiver"

    fun dispatch(
        context: Context,
        action: String?,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        onFinished: (() -> Unit)? = null,
        recover: suspend (Context) -> Unit = ::performRecovery
    ) {
        if (!isRelevantAction(action)) return

        val appContext = context.applicationContext
        recordBreadcrumbSafely(
            event = "receiver_dispatch_requested",
            phase = action,
            details = "async=true"
        )
        scope.launch {
            try {
                recordBreadcrumbSafely(
                    event = "receiver_started",
                    phase = action,
                    details = "async=true"
                )
                recover(appContext)
                recordBreadcrumbSafely(
                    event = "receiver_finished",
                    phase = action,
                    details = "async=true"
                )
            } catch (throwable: Throwable) {
                val message = "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"
                DebugLog.log("[AiRuntimeRecovery] Receiver failed: $message")
                recordBreadcrumbSafely(
                    event = "receiver_failed",
                    phase = action,
                    details = message
                )
            } finally {
                onFinished?.invoke()
            }
        }
    }

    suspend fun performRecovery(context: Context) {
        val staleJobs = AiRuntimeJobStore.markStaleActiveJobsTerminal(context)
        val recoverableJobs = AiRuntimeJobStore.getRecoverableJobs(context)
        val hasForegroundRuntimeJobs = recoverableJobs.isNotEmpty()
        recordBreadcrumbSafely(
            event = "receiver_recovery_state",
            details = "hasForegroundRuntimeJobs=$hasForegroundRuntimeJobs stalePruned=${staleJobs.size} recoverable=${recoverableJobs.size}"
        )
        TamaNotificationScheduler.scheduleAll(context)
        if (hasForegroundRuntimeJobs) {
            AgentForegroundService.startForRecovery(
                context,
                context.getString(R.string.agent_runtime_recovering_jobs)
            )
        }
    }

    fun isRelevantAction(action: String?): Boolean {
        return action == Intent.ACTION_BOOT_COMPLETED
    }

    private fun recordBreadcrumbSafely(
        event: String,
        phase: String? = null,
        details: String? = null
    ) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = SOURCE,
                mode = null,
                event = event,
                phase = phase,
                details = details
            )
        }
    }
}
