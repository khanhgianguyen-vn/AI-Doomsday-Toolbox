package com.example.llamadroid.service

import com.example.llamadroid.util.WakeLockManager
import java.util.concurrent.atomic.AtomicInteger

internal object RemoteSummaryProtection {
    private const val WAKE_TAG = "RemoteSummaryProtection"
    private val refCount = AtomicInteger(0)

    fun acquire(context: android.content.Context) {
        if (refCount.incrementAndGet() == 1) {
            WakeLockManager.acquire(context, WAKE_TAG)
            WakeLockManager.acquireWifiLock(context, WAKE_TAG)
        }
    }

    fun release() {
        val remaining = refCount.decrementAndGet().coerceAtLeast(0)
        if (remaining == 0) {
            refCount.set(0)
            WakeLockManager.release(WAKE_TAG)
            WakeLockManager.releaseWifiLock(WAKE_TAG)
        }
    }
}
