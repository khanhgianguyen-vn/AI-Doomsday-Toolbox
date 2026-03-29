package com.example.llamadroid.service

import com.example.llamadroid.LlamaApplication
import com.example.llamadroid.util.WakeLockManager
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

internal object RemoteAgentProtection {
    private val refCount = AtomicInteger(0)

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

    private fun acquire(status: String) {
        val context = LlamaApplication.instance
        if (refCount.incrementAndGet() == 1) {
            AgentForegroundService.start(context, status)
            WakeLockManager.acquire(context, "RemoteAgentProtection")
            WakeLockManager.acquireWifiLock(context, "RemoteAgentProtection")
        } else {
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
            if (!AgentService.isLoading.value) {
                AgentForegroundService.stop(context)
            }
        }
    }
}
