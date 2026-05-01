package com.example.llamadroid.tama.data

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaPicGenDefaultsTest {

    @Test
    fun `dream prompt pool keeps ten variants and starts with the original prompt`() {
        assertEquals(10, TamaPicGenDefaults.DREAM_POSITIVE_PROMPTS.size)
        assertEquals(
            TamaPicGenDefaults.DREAM_POSITIVE_PROMPT,
            TamaPicGenDefaults.DREAM_POSITIVE_PROMPTS.first()
        )
    }

    @Test
    fun `painting prompt pool keeps ten variants and starts with the original prompt`() {
        assertEquals(10, TamaPicGenDefaults.PAINTING_POSITIVE_PROMPTS.size)
        assertEquals(
            TamaPicGenDefaults.PAINTING_POSITIVE_PROMPT,
            TamaPicGenDefaults.PAINTING_POSITIVE_PROMPTS.first()
        )
    }

    @Test
    fun `random dream prompt always comes from the pool`() {
        repeat(25) {
            val prompt = TamaPicGenDefaults.randomDreamPositivePrompt(Random(it))
            assertTrue(prompt in TamaPicGenDefaults.DREAM_POSITIVE_PROMPTS)
        }
    }

    @Test
    fun `random painting prompt always comes from the pool`() {
        repeat(25) {
            val prompt = TamaPicGenDefaults.randomPaintingPositivePrompt(Random(it))
            assertTrue(prompt in TamaPicGenDefaults.PAINTING_POSITIVE_PROMPTS)
        }
    }
}
