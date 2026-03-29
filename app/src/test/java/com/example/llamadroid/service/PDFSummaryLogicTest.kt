package com.example.llamadroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PDFSummaryLogicTest {
    @Test
    fun `computeBudget grows input budget with context`() {
        val smallBudget = PDFSummaryLogic.computeBudget(
            contextTokens = 4096,
            requestedOutputTokens = 1024,
            promptOverheadTokens = 400
        )
        val largeBudget = PDFSummaryLogic.computeBudget(
            contextTokens = 8192,
            requestedOutputTokens = 1024,
            promptOverheadTokens = 400
        )

        assertEquals(1024, smallBudget.outputTokens)
        assertEquals(1024, largeBudget.outputTokens)
        assertTrue(largeBudget.inputCharBudget > smallBudget.inputCharBudget)
    }

    @Test
    fun `splitIntoChunks prefers paragraph boundaries`() {
        val text = buildString {
            append("Paragraph one sentence one. Paragraph one sentence two.")
            append("\n\n")
            append("Paragraph two sentence one. Paragraph two sentence two.")
        }

        val chunks = PDFSummaryLogic.splitIntoChunks(text, chunkTokenBudget = 8)

        assertEquals(2, chunks.size)
        assertTrue(chunks.first().endsWith("two."))
        assertTrue(chunks.last().startsWith("Paragraph two"))
    }

    @Test
    fun `splitIntoChunks respects custom fit predicate`() {
        val text = """
            alpha beta gamma delta

            epsilon zeta eta theta
        """.trimIndent()

        val chunks = PDFSummaryLogic.splitIntoChunks(text, chunkTokenBudget = 100) { candidate ->
            !candidate.contains("epsilon")
        }

        assertEquals(2, chunks.size)
        assertTrue(chunks.first().contains("alpha"))
        assertTrue(chunks.last().contains("epsilon"))
    }

    @Test
    fun `batchTextsForBudget creates multiple groups when combined text is too large`() {
        val summaries = listOf(
            "chunk one ".repeat(20).trim(),
            "chunk two ".repeat(20).trim(),
            "chunk three ".repeat(20).trim()
        )

        val batches = PDFSummaryLogic.batchTextsForBudget(summaries, inputTokenBudget = 20)
        val flattened = batches.flatten().joinToString("\n")

        assertTrue(batches.size > 1)
        assertTrue(flattened.contains("chunk one"))
        assertTrue(flattened.contains("chunk two"))
        assertTrue(flattened.contains("chunk three"))
    }

    @Test
    fun `cleanLlamaOutput removes stats lines and keeps content`() {
        val raw = """
            [ Prompt: 15.8 t/s | Generation: 6.1 t/s ]
            # Document Summary
            
            Main idea.
            sampling time = 10.00 ms
            42.00 tokens per second
        """.trimIndent()

        val cleaned = PDFSummaryLogic.cleanLlamaOutput(raw)

        assertEquals("# Document Summary\n\nMain idea.", cleaned)
    }

    @Test
    fun `cleanLlamaOutput strips thinking blocks`() {
        val raw = """
            [Start thinking]
            Thinking Process:
            1. Analyze request
            2. Plan answer
            [End thinking]

            # Summary
            Final answer.
        """.trimIndent()

        val cleaned = PDFSummaryLogic.cleanLlamaOutput(raw)

        assertEquals("# Summary\nFinal answer.", cleaned)
    }

    @Test
    fun `shouldRetryFailure retries only once and never after cancellation`() {
        assertTrue(
            PDFSummaryLogic.shouldRetryFailure(
                attemptNumber = 1,
                maxAttempts = 2,
                isCancelled = false,
                timedOut = true,
                output = null,
                errorMessage = "timeout"
            )
        )
        assertFalse(
            PDFSummaryLogic.shouldRetryFailure(
                attemptNumber = 2,
                maxAttempts = 2,
                isCancelled = false,
                timedOut = true,
                output = null,
                errorMessage = "timeout"
            )
        )
        assertFalse(
            PDFSummaryLogic.shouldRetryFailure(
                attemptNumber = 1,
                maxAttempts = 2,
                isCancelled = true,
                timedOut = true,
                output = null,
                errorMessage = "timeout"
            )
        )
    }

    @Test
    fun `buildUnificationPrompt includes merge context`() {
        val prompt = PDFSummaryLogic.buildUnificationPrompt(
            summaries = listOf("Chunk A", "Chunk B"),
            batchIndex = 2,
            totalBatches = 3,
            depth = 1
        )

        assertTrue(prompt.contains("Summary merge batch 2 of 3."))
        assertTrue(prompt.contains("intermediate merge step"))
        assertTrue(prompt.contains("Chunk A"))
        assertTrue(prompt.contains("Chunk B"))
    }

    @Test
    fun `chunk and merge prompts include requested target language`() {
        val chunkPrompt = PDFSummaryLogic.buildChunkPrompt(
            chunkText = "Alpha",
            chunkIndex = 1,
            totalChunks = 4,
            targetLanguage = "Spanish"
        )
        val mergePrompt = PDFSummaryLogic.buildUnificationPrompt(
            summaries = listOf("A", "B"),
            batchIndex = 1,
            totalBatches = 2,
            depth = 0,
            targetLanguage = "Spanish"
        )

        assertTrue(chunkPrompt.contains("Write the summary in: Spanish."))
        assertTrue(mergePrompt.contains("Write the merged summary in: Spanish."))
    }

    @Test
    fun `extractRelevantErrorMessage prefers explicit error lines`() {
        val raw = """
            load time = 10.0 ms
            error: prompt does not fit in context
            total time = 20.0 ms
        """.trimIndent()

        val error = PDFSummaryLogic.extractRelevantErrorMessage(raw, exitCode = 1)

        assertEquals("error: prompt does not fit in context", error)
    }
}
