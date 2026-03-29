package com.example.llamadroid.util

import android.content.Context
import org.json.JSONObject
import java.io.InputStreamReader

/**
 * Utility to load AI prompts from res/raw/prompts.json
 */
object PromptUtils {
    private var promptsJson: JSONObject? = null

    /**
     * Load prompts from resources if not already loaded
     */
    private fun ensureLoaded(context: Context) {
        if (promptsJson != null) return
        
        try {
            val inputStream = context.resources.openRawResource(
                context.resources.getIdentifier("prompts", "raw", context.packageName)
            )
            val reader = InputStreamReader(inputStream)
            val jsonString = reader.readText()
            promptsJson = JSONObject(jsonString)
            reader.close()
        } catch (e: Exception) {
            DebugLog.log("[PromptUtils] Failed to load prompts: ${e.message}")
            promptsJson = JSONObject() // Empty JSON to avoid repeated failed loads
        }
    }

    /**
     * Get a prompt by key
     */
    fun getPrompt(context: Context, key: String, default: String): String {
        ensureLoaded(context)
        return promptsJson?.optString(key, default) ?: default
    }

    /**
     * Keys Constants
     */
    object Keys {
        const val ORCHESTRATOR = "orchestrator"
        const val CODER = "coder"
        const val REVIEWER = "reviewer"
        const val EXECUTOR = "executor"
        const val SUMMARIZER = "summarizer"
        const val TAMA_PET = "tama_pet"
        const val TAMA_SUMMARIZER = "tama_summarizer"
    }
}
