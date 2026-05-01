package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ModelType
import com.example.llamadroid.onnx.OnnxGeneratedImageMetadata
import com.example.llamadroid.onnx.OnnxImageGenConfig
import com.example.llamadroid.onnx.OnnxImageGenMode
import com.example.llamadroid.onnx.OnnxRuntimeOptions
import com.example.llamadroid.onnx.OnnxStorage
import com.example.llamadroid.onnx.OnnxTxt2ImgPipeline
import com.example.llamadroid.onnx.isOnnxTxt2ImgBundle
import com.example.llamadroid.onnx.normalizeOnnxCanvasSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class NativeChatOnnxImageGenerator(
    private val context: Context,
    private val database: AppDatabase
) : NativeChatImageGenerator {

    override suspend fun generateImage(
        prompt: String,
        negativePrompt: String,
        config: NativeChatToolConfig
    ): Result<NativeChatGeneratedImage> = withContext(Dispatchers.IO) {
        try {
            val models = database.modelDao()
                .getModelsByTypesSync(listOf(ModelType.ONNX_IMAGE_GEN))
                .filter { it.isOnnxTxt2ImgBundle() }
            val imageParams = config.imageParams
            val selectedModelId = imageParams.model?.trim().orEmpty()
            val model = models.firstOrNull { it.filename == selectedModelId || it.path == selectedModelId }
                ?: models.firstOrNull()
                ?: return@withContext Result.failure(Exception(context.getString(R.string.native_chat_generate_image_model_missing)))
            val requestedWidth = imageParams.width
            val requestedHeight = imageParams.height
            val normalizedCanvas = normalizeOnnxCanvasSize(requestedWidth, requestedHeight)
            val steps = imageParams.steps.coerceIn(NativeChatImageToolParams.MIN_STEPS, NativeChatImageToolParams.MAX_STEPS)
            val cfgScale = imageParams.cfgScale.coerceIn(NativeChatImageToolParams.MIN_CFG, NativeChatImageToolParams.MAX_CFG)
            val seed = imageParams.seed.trim().toLongOrNull() ?: -1L
            val resolvedNegativePrompt = negativePrompt.takeIf { it.isNotBlank() }
                ?: imageParams.negativePrompt
            val outputFile = OnnxStorage.buildOutputFile(context, prefix = "native_chat")
            val startedAt = System.currentTimeMillis()
            val result = OnnxTxt2ImgPipeline().generate(
                config = OnnxImageGenConfig(
                    modelPath = model.path,
                    modelName = model.filename,
                    mode = OnnxImageGenMode.TXT2IMG,
                    prompt = prompt,
                    negativePrompt = resolvedNegativePrompt,
                    width = normalizedCanvas.normalizedWidth,
                    height = normalizedCanvas.normalizedHeight,
                    steps = steps,
                    cfgScale = cfgScale,
                    seed = seed,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                    backend = imageParams.backend,
                    runtimeOptions = OnnxRuntimeOptions(
                        runtimeThreadCount = imageParams.runtimeThreads,
                        graphOptimizationLevel = imageParams.graphOptimizationLevel,
                        unetBackendOverride = imageParams.unetBackendOverride,
                        vaeDecoderBackendOverride = imageParams.vaeDecoderBackendOverride,
                        vaeEncoderBackendOverride = imageParams.vaeEncoderBackendOverride,
                        intraOpThreads = imageParams.intraOpThreads,
                        interOpThreads = imageParams.interOpThreads,
                        executionMode = imageParams.executionMode,
                        memoryPatternOptimization = imageParams.memoryPatternOptimization,
                        cpuArenaAllocator = imageParams.cpuArenaAllocator,
                        nnapiCpuDisabled = imageParams.nnapiCpuDisabled,
                        nnapiUseFp16 = imageParams.nnapiUseFp16
                    ),
                    outputPath = outputFile.absolutePath
                ),
                onProgress = { _, status ->
                    status.takeIf { it.isNotBlank() }?.let {
                        com.example.llamadroid.util.DebugLog.log("[NativeChatImage] $it")
                    }
                }
            )
            val durationMs = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            OnnxStorage.writeMetadata(
                imageFile = result.outputFile,
                metadata = OnnxGeneratedImageMetadata(
                    imagePath = result.outputFile.absolutePath,
                    modelName = model.filename,
                    mode = OnnxImageGenMode.TXT2IMG.name,
                    prompt = prompt,
                    negativePrompt = resolvedNegativePrompt,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                    width = normalizedCanvas.normalizedWidth,
                    height = normalizedCanvas.normalizedHeight,
                    steps = steps,
                    cfgScale = cfgScale,
                    seed = result.seedUsed,
                    backend = imageParams.backend.name,
                    resolvedBackendSummary = result.runtimeSummary,
                    runtimeOptions = OnnxRuntimeOptions(
                        runtimeThreadCount = imageParams.runtimeThreads,
                        graphOptimizationLevel = imageParams.graphOptimizationLevel,
                        unetBackendOverride = imageParams.unetBackendOverride,
                        vaeDecoderBackendOverride = imageParams.vaeDecoderBackendOverride,
                        vaeEncoderBackendOverride = imageParams.vaeEncoderBackendOverride,
                        intraOpThreads = imageParams.intraOpThreads,
                        interOpThreads = imageParams.interOpThreads,
                        executionMode = imageParams.executionMode,
                        memoryPatternOptimization = imageParams.memoryPatternOptimization,
                        cpuArenaAllocator = imageParams.cpuArenaAllocator,
                        nnapiCpuDisabled = imageParams.nnapiCpuDisabled,
                        nnapiUseFp16 = imageParams.nnapiUseFp16
                    ),
                    createdAtEpochMs = System.currentTimeMillis(),
                    warningMessage = result.warningMessage,
                    totalTimeMs = durationMs
                )
            )

            val content = buildString {
                    appendLine("tool: generate_image")
                    appendLine("status: created")
                    appendLine("image_path: ${result.outputFile.absolutePath}")
                    appendLine("note_markdown: ![${prompt.toMarkdownAltText()}](${result.outputFile.absolutePath})")
                    appendLine("model: ${model.filename}")
                    appendLine("requested_resolution: ${requestedWidth}x${requestedHeight}")
                    appendLine("runtime_resolution: ${normalizedCanvas.normalizedWidth}x${normalizedCanvas.normalizedHeight}")
                    appendLine("steps: $steps")
                    appendLine("cfg: ${String.format(Locale.US, "%.1f", cfgScale)}")
                    appendLine("seed: ${result.seedUsed}")
                    appendLine("backend: ${imageParams.backend.name}")
                    result.warningMessage?.takeIf { it.isNotBlank() }?.let { appendLine("warning: $it") }
                }.trimEnd()
            Result.success(
                NativeChatGeneratedImage(
                    content = content,
                    imagePath = result.outputFile.absolutePath
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun String.toMarkdownAltText(): String {
        return trim()
            .replace(Regex("""[\[\]\n\r]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .take(80)
            .ifBlank { "Generated image" }
    }
}
