package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatToolAwarenessTest {
    @Test
    fun `note tools add reminder to list and read notes before asking for ids`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                noteToolsEnabled = true,
                todoToolsEnabled = true
            )
        )

        assertEquals(1, messages.size)
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("list_notes"))
        assertTrue(messages.first().content.contains("read_note"))
        assertTrue(messages.first().content.contains("create_note"))
        assertTrue(messages.first().content.contains("update_note"))
        assertTrue(messages.first().content.contains("replace_note_text"))
        assertTrue(messages.first().content.contains("recover previous research"))
        assertTrue(messages.first().content.contains("Do not ask the user to provide note IDs"))
        assertTrue(messages.first().content.contains("todo-list tools"))
    }

    @Test
    fun `image and note tool reminders can be combined`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                noteToolsEnabled = true,
                imageGenerationEnabled = true
            )
        )

        assertEquals(2, messages.size)
        assertTrue(messages.any { it.content.contains("list_notes") })
        assertTrue(messages.any { it.content.contains("generate_image") })
    }

    @Test
    fun `organizer tools remind model to list and read ids before editing`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                calendarToolsEnabled = true,
                alarmToolsEnabled = true
            )
        )

        assertEquals(1, messages.size)
        assertEquals("system", messages.first().role)
        assertTrue(messages.first().content.contains("list_calendar_events"))
        assertTrue(messages.first().content.contains("read_calendar_event"))
        assertTrue(messages.first().content.contains("list_alarms"))
        assertTrue(messages.first().content.contains("read_alarm"))
        assertTrue(messages.first().content.contains("do not ask the user for event or alarm IDs"))
    }

    @Test
    fun `web tools remind model to navigate pages before summarizing site sections`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = true,
                webSearchEnabled = true,
                fetchUrlEnabled = true
            )
        )

        assertEquals(1, messages.size)
        assertTrue(messages.first().content.contains("web_search"))
        assertTrue(messages.first().content.contains("search_page"))
        assertTrue(messages.first().content.contains("fetch_url"))
        assertTrue(messages.first().content.contains("latest commits"))
    }

    @Test
    fun `disabled tools do not add reminder messages`() {
        val messages = nativeChatToolAwarenessMessages(
            NativeChatToolConfig(
                toolsEnabled = false,
                noteToolsEnabled = true,
                imageGenerationEnabled = true
            )
        )

        assertTrue(messages.isEmpty())
    }
}
