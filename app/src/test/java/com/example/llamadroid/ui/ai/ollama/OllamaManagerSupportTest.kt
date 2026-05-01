package com.example.llamadroid.ui.ai.ollama

import com.example.llamadroid.data.db.OllamaServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OllamaManagerSupportTest {
    @Test
    fun `selected server resolves from latest saved rows`() {
        val servers = listOf(
            OllamaServerEntity(id = 1, name = "Old", url = "http://old"),
            OllamaServerEntity(id = 2, name = "Prod", url = "http://new")
        )

        val resolved = resolveSelectedOllamaServer(servers, 2)

        assertEquals("http://new", resolved?.url)
        assertEquals(2L, resolved?.id)
    }

    @Test
    fun `missing selected server id clears selection`() {
        val resolved = resolveSelectedOllamaServer(
            servers = listOf(OllamaServerEntity(id = 1, name = "Only", url = "http://one")),
            selectedServerId = 9
        )

        assertNull(resolved)
    }
}
