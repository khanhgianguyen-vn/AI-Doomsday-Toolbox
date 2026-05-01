package com.example.llamadroid.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagedFileCacheTest {

    @After
    fun tearDown() {
        StagedFileCache.clear()
    }

    @Test
    fun `approve removes staged file atomically`() {
        StagedFileCache.stage(
            path = "src/Main.kt",
            content = "println(\"hello\")",
            originalContent = null,
            agentRole = "CODER"
        )

        val approved = StagedFileCache.approve("src/Main.kt")

        requireNotNull(approved)
        assertEquals("src/Main.kt", approved.path)
        assertEquals("println(\"hello\")", approved.content)
        assertFalse(StagedFileCache.isStaged("src/Main.kt"))
        assertNull(StagedFileCache.approve("src/Main.kt"))
    }

    @Test
    fun `approveAll returns pending items and clears cache`() {
        StagedFileCache.stage("a.kt", "A", null, "CODER")
        StagedFileCache.stage("b.kt", "B", "old", "CODER")

        val approved = StagedFileCache.approveAll()

        assertEquals(2, approved.size)
        assertTrue(approved.map { it.path }.containsAll(listOf("a.kt", "b.kt")))
        assertEquals(0, StagedFileCache.pendingCount())
    }
}
