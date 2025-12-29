package com.example.llamadroid.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Utility for checking and requesting storage permissions,
 * particularly MANAGE_EXTERNAL_STORAGE for Android 11+.
 */
object StoragePermissionHelper {
    
    /**
     * Check if the app has permission to access all files on external storage.
     * This is required for native binaries to read models from /storage/emulated/0.
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // For Android 10 and below, requestLegacyExternalStorage should work
            true
        }
    }
    
    /**
     * Open system settings to grant "All files access" permission.
     * User must manually toggle the permission.
     */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to general all files access settings
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }
    
    /**
     * Check if we need to show a permission request dialog.
     * Returns true on Android 11+ if permission not granted.
     */
    fun shouldRequestAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()
    }
}
