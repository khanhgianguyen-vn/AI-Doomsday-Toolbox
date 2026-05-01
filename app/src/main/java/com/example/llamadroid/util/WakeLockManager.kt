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
                wakeLock?.acquire()  // Infinite - long AI tasks need indefinite locks
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
    
    // ============================================================================================
    // WifiLock Management
    // ============================================================================================
    
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private var wifiRefCount = 0
    private val wifiLockSync = Any()
    
    /**
     * Acquire a high-performance WifiLock to prevent radio from sleeping.
     * Essential for distributed inference where low latency is required.
     */
    fun acquireWifiLock(context: Context, tag: String = "Unknown") {
        synchronized(wifiLockSync) {
            if (wifiLock == null) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

                // Keep the chosen mode explicit in one place instead of branching to identical code paths.
                @Suppress("DEPRECATION")
                wifiLock = wifiManager.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "LlamaDroid:AppWifiLock"
                )
                wifiLock?.setReferenceCounted(false)
            }
            
            wifiRefCount++
            if (wifiRefCount == 1) {
                wifiLock?.acquire()
                DebugLog.log("[WifiLock] Acquired by $tag")
            } else {
                DebugLog.log("[WifiLock] Ref increased by $tag (refCount=$wifiRefCount)")
            }
        }
    }
    
    /**
     * Release the WifiLock.
     */
    fun releaseWifiLock(tag: String = "Unknown") {
        synchronized(wifiLockSync) {
            if (wifiRefCount > 0) {
                wifiRefCount--
                DebugLog.log("[WifiLock] Released by $tag (refCount=$wifiRefCount)")
                
                if (wifiRefCount == 0 && wifiLock?.isHeld == true) {
                    wifiLock?.release()
                    DebugLog.log("[WifiLock] Actually released, no more refs")
                }
            }
        }
    }

    fun isWifiHeld(): Boolean = wifiLock?.isHeld == true
}
