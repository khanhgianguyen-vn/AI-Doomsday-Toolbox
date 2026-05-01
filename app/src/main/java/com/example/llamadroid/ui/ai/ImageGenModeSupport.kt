package com.example.llamadroid.ui.ai

import com.example.llamadroid.data.db.ModelEntity

internal fun resolveInitialImageGenMode(targetScreen: String?): Int {
    return when (targetScreen) {
        "imagegen_upscale" -> 2
        "imagegen_img2img" -> 1
        else -> 0
    }
}

internal fun resolveImageGenActiveModels(
    selectedMode: Int,
    generationModels: List<ModelEntity>,
    upscalerModels: List<ModelEntity>
): List<ModelEntity> {
    return if (selectedMode == 2) upscalerModels else generationModels
}

internal fun resolveImageGenSelectedMainModel(
    selectedMode: Int,
    selectedModelPath: String?,
    generationModels: List<ModelEntity>
): ModelEntity? {
    if (selectedMode == 2) return null
    return generationModels.firstOrNull { it.path == selectedModelPath }
}

internal fun hasValidImageGenSelection(
    selectedMode: Int,
    selectedModelPath: String?,
    generationModels: List<ModelEntity>,
    upscalerModels: List<ModelEntity>
): Boolean {
    if (selectedModelPath == null) return false
    return when (selectedMode) {
        2 -> upscalerModels.any { it.path == selectedModelPath }
        else -> generationModels.any { it.path == selectedModelPath }
    }
}

internal fun normalizeImageGenSelectionForMode(
    targetMode: Int,
    currentSelectedModelPath: String?,
    generationModels: List<ModelEntity>,
    upscalerModels: List<ModelEntity>
): String? {
    val targetModels = resolveImageGenActiveModels(
        selectedMode = targetMode,
        generationModels = generationModels,
        upscalerModels = upscalerModels
    )
    return currentSelectedModelPath?.takeIf { path ->
        targetModels.any { it.path == path }
    }
}
