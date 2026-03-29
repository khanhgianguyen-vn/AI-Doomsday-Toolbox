package com.example.llamadroid.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * CPU feature detection for loading the optimal binary tier.
 * 
 * Tiers:
 * - baseline: armv8-a (runs on ALL arm64 devices)
 * - dotprod: armv8.2-a+dotprod (most modern phones 2018+)
 * - armv9: armv9-a with SVE2 (newest devices 2021+)
 */
object CpuFeatures {
    private const val TAG = "CpuFeatures"
    
    private var initialized = false
    private var cachedTier: String? = null
    
    init {
        try {
            System.loadLibrary("cpufeatures")
            initialized = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load cpufeatures library: ${e.message}")
            initialized = false
        }
    }
    
    /**
     * Check if CPU supports dot product instructions (armv8.2-a+dotprod)
     */
    external fun hasDotProd(): Boolean
    
    /**
     * Check if CPU supports ARMv9 features (SVE2)
     */
    external fun hasArmV9(): Boolean
    
    /**
     * Check if CPU supports i8mm (int8 matrix multiply)
     */
    external fun hasI8mm(): Boolean
    
    /**
     * Get the best CPU tier for this device: "armv9", "dotprod", or "baseline"
     */
    external fun getBestTier(): String
    
    /**
     * Get the cached best tier, or detect it
     */
    fun getTier(): String {
        if (!initialized) {
            Log.w(TAG, "Native library not loaded, defaulting to baseline")
            return "baseline"
        }
        
        if (cachedTier == null) {
            cachedTier = getBestTier()
            Log.i(TAG, "Detected CPU tier: $cachedTier")
        }
        return cachedTier!!
    }
    
    /**
     * Get the current device architecture (e.g. "arm64-v8a", "armeabi-v7a", "x86_64")
     */
    fun getArch(): String {
        return android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: android.os.Build.CPU_ABI
    }

    /**
     * Log all CPU features for debugging
     */
    fun logFeatures() {
        if (!initialized) {
            Log.w(TAG, "Native library not loaded")
            return
        }
        
        Log.i(TAG, "CPU Features:")
        Log.i(TAG, "  DotProd: ${hasDotProd()}")
        Log.i(TAG, "  ARMv9 (SVE2): ${hasArmV9()}")
        Log.i(TAG, "  I8MM: ${hasI8mm()}")
        Log.i(TAG, "  Best tier: ${getBestTier()}")
    }
}
