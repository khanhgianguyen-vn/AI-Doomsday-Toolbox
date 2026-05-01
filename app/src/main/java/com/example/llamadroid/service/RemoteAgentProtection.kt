package com.example.llamadroid.service

import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.util.DebugLog
import com.example.llamadroid.util.WakeLockManager
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

internal object RemoteAgentProtection {
    private val refCount = AtomicInteger(0)
    private val externalForegroundCount = AtomicInteger(0)

    fun isRemoteUrl(baseUrl: String): Boolean {
        return try {
            val host = URI(baseUrl.trim()).host?.lowercase().orEmpty()
            host.isNotBlank() &&
                host != "localhost" &&
                host != "127.0.0.1" &&
                host != "::1" &&
                host != "0.0.0.0"
        } catch (_: Exception) {
            false
        }
    }

    suspend fun <T> withProtection(baseUrl: String, status: String, block: suspend () -> T): T {
        if (!isRemoteUrl(baseUrl)) return block()

        acquire(status)
        return try {
            block()
        } finally {
            release()
        }
    }

    suspend fun <T> withExistingForeground(owner: String, block: suspend () -> T): T {
        val retained = externalForegroundCount.incrementAndGet()
        DebugLog.log("[RemoteAgentProtection] existing foreground retained owner=$owner count=$retained")
        recordBreadcrumb("existing_foreground_retained", "owner=$owner count=$retained")
        return try {
            block()
        } finally {
            val remaining = externalForegroundCount.decrementAndGet().coerceAtLeast(0)
            externalForegroundCount.set(remaining)
            DebugLog.log("[RemoteAgentProtection] existing foreground released owner=$owner count=$remaining")
            recordBreadcrumb("existing_foreground_released", "owner=$owner count=$remaining")
        }
    }

    private fun acquire(status: String) {
        val context = LlamaApplication.instance
        if (refCount.incrementAndGet() == 1) {
            if (externalForegroundCount.get() > 0) {
                DebugLog.log("[RemoteAgentProtection] using existing foreground for remote call")
                recordBreadcrumb("using_existing_foreground", "remote=true")
                WakeLockManager.acquire(context, "RemoteAgentProtection")
                WakeLockManager.acquireWifiLock(context, "RemoteAgentProtection")
                return
            }
            AgentForegroundService.start(context, status)
            WakeLockManager.acquire(context, "RemoteAgentProtection")
            WakeLockManager.acquireWifiLock(context, "RemoteAgentProtection")
        } else if (externalForegroundCount.get() == 0) {
            AgentForegroundService.updateStatus(context, status)
        }
    }

    private fun release() {
        val context = LlamaApplication.instance
        val remaining = refCount.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            refCount.set(0)
            WakeLockManager.release("RemoteAgentProtection")
            WakeLockManager.releaseWifiLock("RemoteAgentProtection")
            if (externalForegroundCount.get() == 0 && !AgentService.isLoading.value) {
                AgentForegroundService.stop(context)
            }
        }
    }

    private fun recordBreadcrumb(event: String, details: String) {
        runCatching {
            GenerationDiagnosticsStore.recordBreadcrumb(
                source = "remote_agent_protection",
                event = event,
                details = details
            )
        }
    }
}
