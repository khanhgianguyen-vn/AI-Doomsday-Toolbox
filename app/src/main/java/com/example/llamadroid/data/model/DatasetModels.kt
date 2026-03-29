package com.example.llamadroid.data.model

import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Data models for dataset creation (LLM fine-tuning formats)
 * Using simple data classes with Gson for serialization (no proguard issues)
 */

/**
 * Alpaca format - instruction/input/output triplet
 */
data class AlpacaEntry(
    val instruction: String,
    val input: String = "",
    val output: String
)

/**
 * ShareGPT format - conversation with roles
 */
data class ShareGPTEntry(
    val conversations: List<ShareGPTMessage>
)

data class ShareGPTMessage(
    val from: String,  // "human", "gpt", "system"
    val value: String
)

/**
 * Simple JSONL format - just input/output pairs
 */
data class JsonlEntry(
    val prompt: String,
    val completion: String
)

/**
 * Dataset format options
 */
enum class DatasetFormat(val displayName: String, val extension: String) {
    ALPACA("Alpaca (instruction/input/output)", "json"),
    SHAREGPT("ShareGPT (conversations)", "json"),
    JSONL("JSONL (prompt/completion)", "jsonl")
}

/**
 * Source types for dataset entries
 */
enum class DatasetSource(val displayName: String, val emoji: String) {
    TEXT_FILE("Text File", "📄"),
    NOTE("Note", "📝"),
    CHAT_EXPORT("Chat Export", "💬"),
    MANUAL("Manual Entry", "✏️")
}

/**
 * A single dataset entry before export
 */
data class DatasetEntry(
    val id: Long = System.currentTimeMillis(),
    val source: DatasetSource,
    val sourceName: String,
    val instruction: String,
    val input: String = "",
    val output: String,
    val isValid: Boolean = true
)

/**
 * Helper to convert entries to different formats
 * Uses Gson for serialization (more reliable with R8/proguard)
 */
object DatasetExporter {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .create()
    
    fun toAlpaca(entries: List<DatasetEntry>): String {
        val alpacaEntries = entries.filter { it.isValid }.map { entry ->
            AlpacaEntry(
                instruction = entry.instruction,
                input = entry.input,
                output = entry.output
            )
        }
        return gson.toJson(alpacaEntries)
    }
    
    fun toShareGPT(entries: List<DatasetEntry>): String {
        val shareGPTEntries = entries.filter { it.isValid }.map { entry ->
            val messages = mutableListOf<ShareGPTMessage>()
            
            // Add system instruction if present
            if (entry.input.isNotBlank()) {
                messages.add(ShareGPTMessage(from = "system", value = entry.input))
            }
            
            // Add user message (instruction/question)
            messages.add(ShareGPTMessage(from = "human", value = entry.instruction))
            
            // Add assistant response
            messages.add(ShareGPTMessage(from = "gpt", value = entry.output))
            
            ShareGPTEntry(conversations = messages)
        }
        return gson.toJson(shareGPTEntries)
    }
    
    fun toJsonl(entries: List<DatasetEntry>): String {
        return entries.filter { it.isValid }.joinToString("\n") { entry ->
            val jsonlEntry = JsonlEntry(
                prompt = if (entry.input.isNotBlank()) {
                    "${entry.input}\n\n${entry.instruction}"
                } else {
                    entry.instruction
                },
                completion = entry.output
            )
            gson.toJson(jsonlEntry)
        }
    }
    
    /**
     * Export to specified format
     */
    fun export(entries: List<DatasetEntry>, format: DatasetFormat): String {
        return when (format) {
            DatasetFormat.ALPACA -> toAlpaca(entries)
            DatasetFormat.SHAREGPT -> toShareGPT(entries)
            DatasetFormat.JSONL -> toJsonl(entries)
        }
    }
}
