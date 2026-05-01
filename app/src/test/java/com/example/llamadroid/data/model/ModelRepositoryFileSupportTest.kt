package com.example.llamadroid.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRepositoryFileSupportTest {

    @Test
    fun `accepts supported media model extensions`() {
        assertTrue(ModelRepository.isSupportedMediaModelFile("model.gguf"))
        assertTrue(ModelRepository.isSupportedMediaModelFile("model.safetensors"))
        assertTrue(ModelRepository.isSupportedMediaModelFile("model.onnx"))
        assertTrue(ModelRepository.isSupportedMediaModelFile("model.ort"))
    }

    @Test
    fun `rejects unsupported media model extensions`() {
        assertFalse(ModelRepository.isSupportedMediaModelFile("readme.md"))
        assertFalse(ModelRepository.isSupportedMediaModelFile("archive.zip"))
        assertFalse(ModelRepository.isSupportedMediaModelFile("weights.bin"))
    }
}
