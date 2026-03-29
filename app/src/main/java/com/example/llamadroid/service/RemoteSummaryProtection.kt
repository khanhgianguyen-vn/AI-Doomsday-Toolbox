package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.util.WakeLockManager
import java.util.concurrent.atomic.AtomicInteger

internal object RemoteSummaryProtection {
    private const val WAKE_TAG = "RemoteSummaryProtection"
    private val refCount = AtomicInteger(0)

    fun acquire(context: Context, status: String, foregroundTaskId: Int? = null) {
        if (refCount.incrementAndGet() == 1) {
            AgentForegroundService.start(context, status, foregroundTaskId)
            WakeLockManager.acquire(context, WAKE_TAG)
            WakeLockManager.acquireWifiLock(context, WAKE_TAG)
        } else {
            AgentForegroundService.updateStatus(context, status)
        }
    }

    fun release(context: Context) {
        val remaining = refCount.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            refCount.set(0)
            WakeLockManager.release(WAKE_TAG)
            WakeLockManager.releaseWifiLock(WAKE_TAG)
            if (!AgentService.isLoading.value) {
                AgentForegroundService.stop(context)
            }
        }
    }
}
