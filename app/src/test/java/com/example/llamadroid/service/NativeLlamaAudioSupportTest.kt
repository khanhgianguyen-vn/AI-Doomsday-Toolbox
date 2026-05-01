package com.example.llamadroid.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLlamaAudioSupportTest {

    @Test
    fun `native llama audio conversion is skipped for wav and mp3`() {
        assertFalse(requiresNativeLlamaAudioConversion("/tmp/example.wav"))
        assertFalse(requiresNativeLlamaAudioConversion("/tmp/example.MP3"))
    }

    @Test
    fun `native llama audio conversion is required for android recorder formats`() {
        assertTrue(requiresNativeLlamaAudioConversion("/tmp/example.m4a"))
        assertTrue(requiresNativeLlamaAudioConversion("/tmp/example.aac"))
        assertTrue(requiresNativeLlamaAudioConversion("/tmp/example.ogg"))
    }
}
