package com.example.llamadroid.ui.ai

import com.example.llamadroid.data.db.ModelEntity
import com.example.llamadroid.data.db.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenModeSupportTest {

    private val diffusionModel = ModelEntity(
        filename = "dreamshaper.safetensors",
        path = "/models/dreamshaper.safetensors",
        sizeBytes = 1,
        type = ModelType.SD_CHECKPOINT,
        repoId = "dreamshaper"
    )
    private val upscalerModel = ModelEntity(
        filename = "realesrgan-x4.bin",
        path = "/models/realesrgan-x4.bin",
        sizeBytes = 1,
        type = ModelType.SD_UPSCALER,
        repoId = "realesrgan"
    )

    @Test
    fun `initial mode defaults to txt2img`() {
        assertEquals(0, resolveInitialImageGenMode(targetScreen = null))
        assertEquals(0, resolveInitialImageGenMode(targetScreen = "something_else"))
    }

    @Test
    fun `initial mode resolves img2img route before first composition`() {
        assertEquals(1, resolveInitialImageGenMode(targetScreen = "imagegen_img2img"))
    }

    @Test
    fun `initial mode resolves upscale route before first composition`() {
        assertEquals(2, resolveInitialImageGenMode(targetScreen = "imagegen_upscale"))
    }

    @Test
    fun `upscale mode never resolves a diffusion main model`() {
        val selected = resolveImageGenSelectedMainModel(
            selectedMode = 2,
            selectedModelPath = diffusionModel.path,
            generationModels = listOf(diffusionModel)
        )

        assertNull(selected)
    }

    @Test
    fun `active models switch to upscalers in upscale mode`() {
        val active = resolveImageGenActiveModels(
            selectedMode = 2,
            generationModels = listOf(diffusionModel),
            upscalerModels = listOf(upscalerModel)
        )

        assertEquals(listOf(upscalerModel), active)
    }

    @Test
    fun `selection validation rejects missing upscaler path when switching modes`() {
        val valid = hasValidImageGenSelection(
            selectedMode = 2,
            selectedModelPath = diffusionModel.path,
            generationModels = listOf(diffusionModel),
            upscalerModels = emptyList()
        )

        assertFalse(valid)
    }

    @Test
    fun `selection validation accepts installed upscaler path in upscale mode`() {
        val valid = hasValidImageGenSelection(
            selectedMode = 2,
            selectedModelPath = upscalerModel.path,
            generationModels = listOf(diffusionModel),
            upscalerModels = listOf(upscalerModel)
        )

        assertTrue(valid)
    }

    @Test
    fun `selection validation rejects diffusion path in upscale mode even when upscaler exists`() {
        val valid = hasValidImageGenSelection(
            selectedMode = 2,
            selectedModelPath = diffusionModel.path,
            generationModels = listOf(diffusionModel),
            upscalerModels = listOf(upscalerModel)
        )

        assertFalse(valid)
    }

    @Test
    fun `mode normalization clears stale diffusion selection when switching to upscale`() {
        val normalized = normalizeImageGenSelectionForMode(
            targetMode = 2,
            currentSelectedModelPath = diffusionModel.path,
            generationModels = listOf(diffusionModel),
            upscalerModels = listOf(upscalerModel)
        )

        assertNull(normalized)
    }

    @Test
    fun `mode normalization keeps installed upscaler selection when staying in upscale`() {
        val normalized = normalizeImageGenSelectionForMode(
            targetMode = 2,
            currentSelectedModelPath = upscalerModel.path,
            generationModels = listOf(diffusionModel),
            upscalerModels = listOf(upscalerModel)
        )

        assertEquals(upscalerModel.path, normalized)
    }

    @Test
    fun `repeated upscale normalization remains stable with an installed upscaler`() {
        val generationModels = listOf(diffusionModel)
        val upscalerModels = listOf(upscalerModel)

        val firstPass = normalizeImageGenSelectionForMode(
            targetMode = 2,
            currentSelectedModelPath = diffusionModel.path,
            generationModels = generationModels,
            upscalerModels = upscalerModels
        )
        val secondPass = normalizeImageGenSelectionForMode(
            targetMode = 2,
            currentSelectedModelPath = firstPass ?: upscalerModel.path,
            generationModels = generationModels,
            upscalerModels = upscalerModels
        )

        assertNull(firstPass)
        assertEquals(upscalerModel.path, secondPass)
    }

    @Test
    fun `txt2img normalization rejects stale upscaler selection`() {
        val normalized = normalizeImageGenSelectionForMode(
            targetMode = 0,
            currentSelectedModelPath = upscalerModel.path,
            generationModels = listOf(diffusionModel),
            upscalerModels = listOf(upscalerModel)
        )

        assertNull(normalized)
    }
}
