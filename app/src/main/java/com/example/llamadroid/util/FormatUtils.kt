package com.example.llamadroid.util

import android.content.Context
import android.text.format.Formatter
import java.util.Locale

object FormatUtils {
    
    /**
     * Helper for backward compatibility with existing code calling FormatUtils.formatFileSize
     */
    fun formatFileSize(bytes: Long): String {
        return Technical.formatBytes(bytes)
    }
    
    /**
     * For technical output (logs, configs, parsing).
     * Always uses US locale for consistency (decimal points instead of commas).
     */
    object Technical {
        fun formatBytes(bytes: Long): String {
            return when {
                bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
                bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
                bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
                else -> "$bytes B"
            }
        }
        
        fun formatDecimal(value: Double, decimals: Int = 1): String {
            return String.format(Locale.US, "%.${decimals}f", value)
        }
    }
    
    /**
     * For user-facing display.
     * Respects user locale settings.
     */
    object Display {
        fun formatBytes(context: Context, bytes: Long): String {
            return Formatter.formatFileSize(context, bytes)
        }
        
        fun formatDuration(seconds: Double): String {
            val totalSeconds = seconds.toInt()
            val mins = totalSeconds / 60
            val secs = totalSeconds % 60
            return String.format(Locale.getDefault(), "%d:%02d", mins, secs)
        }
    }
}
