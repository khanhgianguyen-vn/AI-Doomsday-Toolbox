package com.example.llamadroid.data

import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedFileHolderTest {

    @After
    fun tearDown() {
        SharedFileHolder.clear()
    }

    @Test
    fun `consumePendingFile returns current value and clears it`() {
        val uri = mockk<android.net.Uri>()

        SharedFileHolder.setPendingFile(uri, "application/pdf", "pdf")

        val consumed = SharedFileHolder.consumePendingFile()

        requireNotNull(consumed)
        assertEquals(uri, consumed.uri)
        assertEquals("application/pdf", consumed.mimeType)
        assertEquals("pdf", consumed.targetScreen)
        assertNull(SharedFileHolder.pendingFile.value)
        assertNull(SharedFileHolder.consumePendingFile())
    }
}
