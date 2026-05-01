package com.example.llamadroid.util

/**
 * Centralized constants for AI services, ports, and timeouts.
 */
object AIConstants {
    // Port configurations
    object Ports {
        const val OLLAMA = 11434
        const val KIWIX = 8888
        const val FILE_SERVER = 9111
    }

    // Default URLs
    object Urls {
        const val OLLAMA_DEFAULT = "http://localhost:${Ports.OLLAMA}"
    }

    // Timeouts
    object Timeouts {
        const val OLLAMA_MODEL_PULL_MINUTES = 30L
    }

    // Default AI Parameters
    object Defaults {
        const val CONTEXT_SIZE_LLM = 8192
        const val CONTEXT_SIZE_OLLAMA = 4096
        const val CONTEXT_SIZE_PDF = 4096
        const val CONTEXT_SIZE_WORKFLOW = 2048
        
        const val TEMPERATURE_CHAT = 0.7f
        const val TEMPERATURE_PDF = 0.3f
        
        const val THREAD_COUNT = 4
        
        const val MAX_TOKENS_PDF = 1024
        const val MAX_TOKENS_WORKFLOW = 300
    }
}
