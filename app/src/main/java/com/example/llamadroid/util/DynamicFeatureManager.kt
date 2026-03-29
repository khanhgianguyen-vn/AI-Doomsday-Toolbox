package com.example.llamadroid.util

import android.content.Context
import android.util.Log
import com.google.android.play.core.splitinstall.SplitInstallManager
import com.google.android.play.core.splitinstall.SplitInstallManagerFactory
import com.google.android.play.core.splitinstall.SplitInstallRequest
import com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener
import com.google.android.play.core.splitinstall.model.SplitInstallSessionStatus
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Manages Dynamic Feature Modules.
 * Handles checking status and requesting installation for 'fast-follow' or 'on-demand' modules.
 */
object DynamicFeatureManager {
    private const val TAG = "DynamicFeatureManager"
    
    // Feature Upscaler (Shared)
    const val MODULE_UPSCALER = "feature_upscaler"

    // CPU-Specific Module Prefixes
    private const val MODULE_LLM_PREFIX = "feature_llm_"
    private const val MODULE_KIWIX_PREFIX = "feature_kiwix_"
    private const val MODULE_MEDIA_PREFIX = "feature_media_"

    /**
     * Get the list of expected modules for this device based on CPU tier.
     */
    fun getExpectedModules(): List<String> {
        // CpuFeatures.getBestTier() returns "armv9", "dotprod", or "baseline"
        // But we need to be careful - this is a native call. Ensure specific native lib is loaded?
        // CpuFeatures loads its own lib.
        val tier = try {
            CpuFeatures.getBestTier() // e.g. "armv9", "baseline"
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native lib not loaded yet, defaulting to baseline", e)
            "baseline"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting tier, defaulting to baseline", e)
            "baseline"
        }

        return listOf(
            "${MODULE_LLM_PREFIX}$tier",
            "${MODULE_KIWIX_PREFIX}$tier",
            "${MODULE_MEDIA_PREFIX}$tier",
            MODULE_UPSCALER
        )
    }

    /**
     * Check if a dynamic feature module is installed.
     */
    fun isModuleInstalled(context: Context, moduleName: String): Boolean {
        val manager = SplitInstallManagerFactory.create(context)
        return manager.installedModules.contains(moduleName)
    }

    /**
     * Check if the Native Libs module is ready.
     * Checks if ALL critical features for THIS DEVICE are installed.
     */
    fun isNativeLibsReady(context: Context): Boolean {
        val expected = getExpectedModules()
        val allInstalled = expected.all { isModuleInstalled(context, it) }
        
        if (!allInstalled) {
            // Log what's missing
            val manager = SplitInstallManagerFactory.create(context)
            val installed = manager.installedModules
            val missing = expected.filter { !installed.contains(it) }
            Log.d(TAG, "Missing modules: $missing (Expected: $expected, Installed: $installed)")
        }
        
        return allInstalled
    }

    /**
     * Monitor installation progress of a module.
     * For fast-follow, this might just confirm it's installed or waiting.
     */
    fun monitorModuleInstallation(context: Context, moduleName: String): Flow<Int> = callbackFlow {
        val manager = SplitInstallManagerFactory.create(context)

        // If already installed, emit success immediately
        if (manager.installedModules.contains(moduleName)) {
            trySend(SplitInstallSessionStatus.INSTALLED)
            close()
            return@callbackFlow
        }

        val listener = SplitInstallStateUpdatedListener { state ->
            if (state.moduleNames().contains(moduleName)) {
                trySend(state.status())
                
                if (state.status() == SplitInstallSessionStatus.INSTALLED || 
                    state.status() == SplitInstallSessionStatus.FAILED ||
                    state.status() == SplitInstallSessionStatus.CANCELED) {
                    close()
                }
            }
        }

        manager.registerListener(listener)
        
        // Also explicitly request install just in case (idempotent for fast-follow usually)
        val request = SplitInstallRequest.newBuilder()
            .addModule(moduleName)
            .build()
            
        manager.startInstall(request)
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to start install for $moduleName", e)
                // Don't close flow here, let listener handle or user retry
            }

        awaitClose {
            manager.unregisterListener(listener)
        }
    }

    fun installAllFeatures(context: Context) {
        val manager = SplitInstallManagerFactory.create(context)
        val modulesToInstall = mutableListOf<String>()
        val expectedModules = getExpectedModules()
        
        expectedModules.forEach { module ->
            if (!manager.installedModules.contains(module)) {
                modulesToInstall.add(module)
            }
        }
        
        if (modulesToInstall.isEmpty()) {
            Log.i(TAG, "All expected modules are already installed.")
            return
        }
        
        val requestBuilder = SplitInstallRequest.newBuilder()
        modulesToInstall.forEach { requestBuilder.addModule(it) }
        
        Log.i(TAG, "Requesting installation for: $modulesToInstall")
        
        manager.startInstall(requestBuilder.build())
            .addOnSuccessListener { session -> Log.i(TAG, "Installation session started: $session") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to request modules", e) }
    }
}
