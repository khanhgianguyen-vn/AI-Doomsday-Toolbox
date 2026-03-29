package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSummaryRemoteClientTest {
    @Test
    fun `buildOllamaSummaryRequestJson maps remote settings to native ollama fields`() {
        val config = RemoteSummaryBackendConfig(
            backend = "ollama",
            baseUrl = "http://localhost:11434",
            model = "qwen3:8b",
            timeoutMinutes = 0
        )
        val request = RemoteSummaryRequest(
            systemPrompt = "system",
            userPrompt = "user",
            contextSize = 8192,
            maxTokens = 1024,
            temperature = 0.2f,
            thinkingEnabled = false
        )

        val payload = buildOllamaSummaryRequestPayload(config, request)

        assertEquals("qwen3:8b", payload["model"])
        assertEquals(false, payload["think"])
        assertEquals(2, (payload["messages"] as List<*>).size)
        val options = payload["options"] as Map<*, *>
        assertEquals(8192, options["num_ctx"])
        assertEquals(1024, options["num_predict"])
    }

    @Test
    fun `buildLlamaServerSummaryRequestJson disables reasoning by default`() {
        val config = RemoteSummaryBackendConfig(
            backend = "llama-server",
            baseUrl = "http://localhost:8080",
            model = null,
            timeoutMinutes = 10
        )
        val request = RemoteSummaryRequest(
            systemPrompt = "system",
            userPrompt = "user",
            contextSize = 4096,
            maxTokens = 768,
            temperature = 0.4f,
            thinkingEnabled = false
        )

        val payload = buildLlamaServerSummaryRequestPayload(config, request)

        assertEquals("local-model", payload["model"])
        assertEquals(768, payload["max_tokens"])
        assertEquals("none", payload["reasoning_effort"])
        assertEquals(false, (payload["chat_template_kwargs"] as Map<*, *>)["enable_thinking"])
    }

    @Test
    fun `parseLlamaServerContextTokens reads props response`() {
        val body = """
            {
              "default_generation_settings": {
                "n_ctx": 16384
              }
            }
        """.trimIndent()

        assertEquals(16384, parseLlamaServerContextTokens(body))
    }

    @Test
    fun `parseLlamaServerContextTokens returns null for invalid props`() {
        assertEquals(null, parseLlamaServerContextTokens("""{"ok":true}"""))
    }

    @Test
    fun `buildLlamaServerSummaryRequestJson keeps thinking enabled when requested`() {
        val config = RemoteSummaryBackendConfig(
            backend = "llama-server",
            baseUrl = "http://localhost:8080",
            model = "served-model",
            timeoutMinutes = 0
        )
        val request = RemoteSummaryRequest(
            systemPrompt = "system",
            userPrompt = "user",
            contextSize = 4096,
            maxTokens = 512,
            temperature = 0.7f,
            thinkingEnabled = true
        )

        val payload = buildLlamaServerSummaryRequestPayload(config, request)

        assertNotNull(payload["chat_template_kwargs"])
        assertEquals(true, (payload["chat_template_kwargs"] as Map<*, *>)["enable_thinking"])
        assertNotNull(payload["messages"])
        assertFalse(payload.containsKey("reasoning_effort"))
    }
}
