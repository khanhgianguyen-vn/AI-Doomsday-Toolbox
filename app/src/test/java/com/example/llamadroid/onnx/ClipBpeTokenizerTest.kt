package com.example.llamadroid.onnx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class ClipBpeTokenizerTest {

    @Test
    fun `tokenizer applies clip word end merges and eos padding`() {
        val workspace = createTempDirectory("clip-tokenizer-test").toFile()
        val tokenizer = createTokenizer(workspace)

        val encoded = tokenizer.encode("hi", maxLength = 6)

        assertArrayEquals(intArrayOf(0, 4, 1, 1, 1, 1), encoded)
    }

    @Test
    fun `tokenizer truncates to max length while preserving eos tail`() {
        val workspace = createTempDirectory("clip-tokenizer-truncation-test").toFile()
        val tokenizer = createTokenizer(workspace)

        val encoded = tokenizer.encode("hi hi hi", maxLength = 4)

        assertEquals(4, encoded.size)
        assertEquals(0, encoded.first())
        assertEquals(4, encoded[1])
        assertEquals(1, encoded.last())
    }

    @Test
    fun `tokenizer normalizes prompt casing and width like sdai`() {
        val workspace = createTempDirectory("clip-tokenizer-normalization-test").toFile()
        val tokenizer = createTokenizer(workspace)

        val lower = tokenizer.tokenize("hi", maxLength = 6)
        val normalized = tokenizer.tokenize("ＨＩ", maxLength = 6)

        assertArrayEquals(lower.tokenIds, normalized.tokenIds)
        assertEquals("hi", normalized.normalizedText)
    }

    @Test
    fun `tokenizer exposes attention mask and truncation metadata`() {
        val workspace = createTempDirectory("clip-tokenizer-attention-test").toFile()
        val tokenizer = createTokenizer(workspace)

        val tokenization = tokenizer.tokenize("hi hi hi", maxLength = 4)

        assertArrayEquals(intArrayOf(1, 1, 1, 1), tokenization.attentionMask)
        assertArrayEquals(intArrayOf(0, 1, 2, 3), tokenization.positionIds)
        assertEquals(4, tokenization.tokenCount)
        assertTrue(tokenization.wasTruncated)
    }

    private fun createTokenizer(workspace: File): ClipBpeTokenizer {
        val vocab = File(workspace, "vocab.json").apply {
            writeText(
                """
                {
                  "<|startoftext|>": 0,
                  "<|endoftext|>": 1,
                  "h": 2,
                  "i</w>": 3,
                  "hi</w>": 4,
                  "Āhi</w>": 5
                }
                """.trimIndent()
            )
        }
        val merges = File(workspace, "merges.txt").apply {
            writeText(
                """
                #version: 0.2
                h i</w>
                Ā h
                Āh i</w>
                """.trimIndent()
            )
        }
        return ClipBpeTokenizer(vocab, merges)
    }
}
