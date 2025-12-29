package com.example.llamadroid.util

import android.content.Context
import android.os.PowerManager
import com.example.llamadroid.util.DebugLog

/**
 * Centralized wake lock manager for the app.
 * Provides a singleton wake lock that can be acquired/released by any component.
 * Useful for long-running operations like model downloads, ZIM processing, etc.
 */
object WakeLockManager {
    private var wakeLock: PowerManager.WakeLock? = null
    private var refCount = 0
    private val lock = Any()
    
    /**
     * Acquire the app-wide wake lock.
     * Uses reference counting - lock is only released when all acquires are matched with releases.
     * 
     * @param context Application context
     * @param tag Identifier for debugging which component acquired the lock
     */
    fun acquire(context: Context, tag: String = "Unknown") {
        synchronized(lock) {
            if (wakeLock == null) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "LlamaDroid:AppWakeLock"
                )
            }
            
            refCount++
            if (refCount == 1) {
                wakeLock?.acquire()  // No timeout - hold indefinitely for long AI tasks
                DebugLog.log("[WakeLock] Acquired by $tag (refCount=$refCount)")
            } else {
                DebugLog.log("[WakeLock] Ref increased by $tag (refCount=$refCount)")
            }
        }
    }
    
    /**
     * Release the app-wide wake lock.
     * Only actually releases when reference count reaches 0.
     * 
     * @param tag Identifier for debugging which component released the lock
     */
    fun release(tag: String = "Unknown") {
        synchronized(lock) {
            if (refCount > 0) {
                refCount--
                DebugLog.log("[WakeLock] Released by $tag (refCount=$refCount)")
                
                if (refCount == 0 && wakeLock?.isHeld == true) {
                    wakeLock?.release()
                    DebugLog.log("[WakeLock] Actually released, no more refs")
                }
            }
        }
    }
    
    /**
     * Force release the wake lock regardless of reference count.
     * Use only in emergency situations like app termination.
     */
    fun forceRelease() {
        synchronized(lock) {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                DebugLog.log("[WakeLock] Force released")
            }
            refCount = 0
        }
    }
    
    /**
     * Check if wake lock is currently held
     */
    fun isHeld(): Boolean = wakeLock?.isHeld == true
}
