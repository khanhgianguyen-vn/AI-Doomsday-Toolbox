package com.example.llamadroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryBackendTest {
    @Test
    fun `agent remote backend aliases normalize to llama-server`() {
        val aliases = listOf(
            "llama-server",
            "llama_server",
            "llamacpp",
            "llama.cpp",
            "llama-cpp",
            " LLAMA_SERVER "
        )

        aliases.forEach { backend ->
            assertEquals(
                SettingsRepository.PDF_BACKEND_LLAMA_SERVER,
                SettingsRepository.normalizeOllamaOrLlamaBackend(backend)
            )
            assertTrue(SettingsRepository.isLlamaServerBackend(backend))
        }
    }

    @Test
    fun `unknown remote backend values fall back to ollama`() {
        assertEquals(
            SettingsRepository.PDF_BACKEND_OLLAMA,
            SettingsRepository.normalizeOllamaOrLlamaBackend(null)
        )
        assertEquals(
            SettingsRepository.PDF_BACKEND_OLLAMA,
            SettingsRepository.normalizeOllamaOrLlamaBackend("something-else")
        )
        assertFalse(SettingsRepository.isLlamaServerBackend("something-else"))
    }
}
