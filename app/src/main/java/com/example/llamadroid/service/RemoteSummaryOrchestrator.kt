package com.example.llamadroid.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

data class RemoteSummaryPlanEstimate(
    val chunkCount: Int,
    val tokenCountMode: TokenCountMode
)

data class RemoteSummaryProgress(
    val phase: RemoteSummaryPhase,
    val currentChunk: Int,
    val totalChunks: Int,
    val mergeBatch: Int = 0,
    val mergeBatchCount: Int = 0,
    val partialSummaries: List<String> = emptyList()
)

enum class RemoteSummaryPhase {
    CHUNK_SUMMARY,
    MERGE_SUMMARY,
    SINGLE_SUMMARY
}

data class RemoteSummaryExecutionResult(
    val summary: String? = null,
    val partialSummaries: List<String> = emptyList(),
    val errorMessage: String? = null,
    val cancelled: Boolean = false
)

class RemoteSummaryOrchestrator(
    private val client: RemoteSummaryClient
) {
    private data class CallResult(
        val output: String? = null,
        val errorMessage: String? = null,
        val cancelled: Boolean = false,
        val timedOut: Boolean = false,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null
    )

    companion object {
        private const val MAX_ATTEMPTS = 2
    }

    suspend fun estimateChunkPlan(
        summaryPrompt: String,
        text: String,
        chunkContext: Int,
        chunkMaxTokens: Int,
        targetLanguage: String
    ): RemoteSummaryPlanEstimate {
        val budget = computeBudget(
            systemPrompt = summaryPrompt,
            exampleUserPrompt = PDFSummaryLogic.buildChunkPrompt("", 99, 99, targetLanguage),
            contextTokens = chunkContext,
            requestedOutputTokens = chunkMaxTokens
        )
        val tokenCount = client.countRenderedPromptTokens(
            summaryPrompt,
            PDFSummaryLogic.buildChunkPrompt("", 99, 99, targetLanguage)
        )
        val chunks = splitForBudget(
            systemPrompt = summaryPrompt,
            text = text,
            inputBudget = budget.inputTokens,
            targetLanguage = targetLanguage
        )
        return RemoteSummaryPlanEstimate(
            chunkCount = chunks.size,
            tokenCountMode = tokenCount.mode
        )
    }

    suspend fun summarize(
        sourceText: String,
        summaryPrompt: String,
        mergePrompt: String,
        chunkContext: Int,
        chunkMaxTokens: Int,
        mergeContext: Int,
        mergeMaxTokens: Int,
        temperature: Float,
        thinkingEnabled: Boolean,
        targetLanguage: String,
        isCancelled: () -> Boolean,
        onProgress: (RemoteSummaryProgress) -> Unit
    ): RemoteSummaryExecutionResult {
        val normalizedText = sourceText.trim()
        if (normalizedText.isBlank()) {
            return RemoteSummaryExecutionResult(errorMessage = "No text available to summarize")
        }

        val chunks = splitForBudget(
            systemPrompt = summaryPrompt,
            text = normalizedText,
            inputBudget = computeBudget(
                systemPrompt = summaryPrompt,
                exampleUserPrompt = PDFSummaryLogic.buildChunkPrompt("", 99, 99, targetLanguage),
                contextTokens = chunkContext,
                requestedOutputTokens = chunkMaxTokens
            ).inputTokens,
            targetLanguage = targetLanguage
        )

        if (chunks.isEmpty()) {
            return RemoteSummaryExecutionResult(errorMessage = "No text available to summarize")
        }

        val partials = mutableListOf<String>()
        for ((index, chunk) in chunks.withIndex()) {
            if (isCancelled()) {
                return RemoteSummaryExecutionResult(
                    partialSummaries = partials.toList(),
                    cancelled = true,
                    errorMessage = "Cancelled"
                )
            }

            val currentChunk = index + 1
            onProgress(
                RemoteSummaryProgress(
                    phase = if (chunks.size == 1) RemoteSummaryPhase.SINGLE_SUMMARY else RemoteSummaryPhase.CHUNK_SUMMARY,
                    currentChunk = currentChunk,
                    totalChunks = chunks.size,
                    partialSummaries = partials.toList()
                )
            )

            val result = runWithRetry(
                request = RemoteSummaryRequest(
                    systemPrompt = summaryPrompt,
                    userPrompt = PDFSummaryLogic.buildChunkPrompt(chunk, currentChunk, chunks.size, targetLanguage),
                    contextSize = chunkContext,
                    maxTokens = chunkMaxTokens,
                    temperature = temperature,
                    thinkingEnabled = thinkingEnabled
                ),
                isCancelled = isCancelled
            )

            if (result.output == null) {
                return RemoteSummaryExecutionResult(
                    partialSummaries = partials.toList(),
                    cancelled = result.cancelled,
                    errorMessage = result.errorMessage ?: "Chunk summary failed"
                )
            }

            partials += result.output
        }

        val merged = unifyRecursively(
            summaries = partials,
            mergePrompt = mergePrompt,
            mergeContext = mergeContext,
            mergeMaxTokens = mergeMaxTokens,
            temperature = temperature,
            thinkingEnabled = thinkingEnabled,
            targetLanguage = targetLanguage,
            depth = 0,
            isCancelled = isCancelled,
            onProgress = { progress ->
                onProgress(
                    RemoteSummaryProgress(
                        phase = progress.phase,
                        currentChunk = 0,
                        totalChunks = chunks.size,
                        mergeBatch = progress.mergeBatch,
                        mergeBatchCount = progress.mergeBatchCount,
                        partialSummaries = partials.toList()
                    )
                )
            }
        )

        return merged.copy(partialSummaries = partials.toList())
    }

    private suspend fun unifyRecursively(
        summaries: List<String>,
        mergePrompt: String,
        mergeContext: Int,
        mergeMaxTokens: Int,
        temperature: Float,
        thinkingEnabled: Boolean,
        targetLanguage: String,
        depth: Int,
        isCancelled: () -> Boolean,
        onProgress: (RemoteSummaryProgress) -> Unit
    ): RemoteSummaryExecutionResult {
        if (summaries.isEmpty()) {
            return RemoteSummaryExecutionResult(errorMessage = "No summaries available to merge")
        }
        if (summaries.size == 1 && depth > 0) {
            return RemoteSummaryExecutionResult(summary = summaries.first())
        }

        val budget = computeBudget(
            systemPrompt = mergePrompt,
            exampleUserPrompt = PDFSummaryLogic.buildUnificationPrompt(
                summaries = listOf("Example A", "Example B"),
                batchIndex = 99,
                totalBatches = 99,
                depth = depth,
                targetLanguage = targetLanguage
            ),
            contextTokens = mergeContext,
            requestedOutputTokens = mergeMaxTokens
        )

        val batches = PDFSummaryLogic.batchTextsForBudget(summaries, budget.inputTokens) { candidate ->
            val promptTokens = runBlocking {
                client.countRenderedPromptTokens(
                    mergePrompt,
                    PDFSummaryLogic.buildUnificationPrompt(
                        summaries = candidate,
                        batchIndex = 99,
                        totalBatches = 99,
                        depth = depth,
                        targetLanguage = targetLanguage
                    )
                ).totalTokens
            }
            promptTokens + budget.outputTokens <= budget.contextTokens
        }

        if (batches.size <= 1) {
            onProgress(
                RemoteSummaryProgress(
                    phase = RemoteSummaryPhase.MERGE_SUMMARY,
                    currentChunk = 0,
                    totalChunks = summaries.size
                )
            )
            val single = runWithRetry(
                request = RemoteSummaryRequest(
                    systemPrompt = mergePrompt,
                    userPrompt = PDFSummaryLogic.buildUnificationPrompt(
                        summaries = summaries,
                        batchIndex = 1,
                        totalBatches = 1,
                        depth = depth,
                        targetLanguage = targetLanguage
                    ),
                    contextSize = mergeContext,
                    maxTokens = mergeMaxTokens,
                    temperature = temperature,
                    thinkingEnabled = thinkingEnabled
                ),
                isCancelled = isCancelled
            )
            return RemoteSummaryExecutionResult(
                summary = single.output,
                errorMessage = single.errorMessage,
                cancelled = single.cancelled
            )
        }

        val mergedBatches = mutableListOf<String>()
        for ((index, batch) in batches.withIndex()) {
            if (isCancelled()) {
                return RemoteSummaryExecutionResult(
                    partialSummaries = mergedBatches.toList(),
                    cancelled = true,
                    errorMessage = "Cancelled"
                )
            }
            onProgress(
                RemoteSummaryProgress(
                    phase = RemoteSummaryPhase.MERGE_SUMMARY,
                    currentChunk = 0,
                    totalChunks = summaries.size,
                    mergeBatch = index + 1,
                    mergeBatchCount = batches.size
                )
            )
            val result = runWithRetry(
                request = RemoteSummaryRequest(
                    systemPrompt = mergePrompt,
                    userPrompt = PDFSummaryLogic.buildUnificationPrompt(
                        summaries = batch,
                        batchIndex = index + 1,
                        totalBatches = batches.size,
                        depth = depth,
                        targetLanguage = targetLanguage
                    ),
                    contextSize = mergeContext,
                    maxTokens = mergeMaxTokens,
                    temperature = temperature,
                    thinkingEnabled = thinkingEnabled
                ),
                isCancelled = isCancelled
            )
            if (result.output == null) {
                return RemoteSummaryExecutionResult(
                    partialSummaries = mergedBatches.toList(),
                    cancelled = result.cancelled,
                    errorMessage = result.errorMessage ?: "Merge failed"
                )
            }
            mergedBatches += result.output
        }

        return unifyRecursively(
            summaries = mergedBatches,
            mergePrompt = mergePrompt,
            mergeContext = mergeContext,
            mergeMaxTokens = mergeMaxTokens,
            temperature = temperature,
            thinkingEnabled = thinkingEnabled,
            targetLanguage = targetLanguage,
            depth = depth + 1,
            isCancelled = isCancelled,
            onProgress = onProgress
        )
    }

    private suspend fun computeBudget(
        systemPrompt: String,
        exampleUserPrompt: String,
        contextTokens: Int,
        requestedOutputTokens: Int
    ): PdfTokenBudget {
        val promptTokens = client.countRenderedPromptTokens(systemPrompt, exampleUserPrompt).totalTokens
        return PDFSummaryLogic.computeBudget(
            contextTokens = contextTokens,
            requestedOutputTokens = requestedOutputTokens,
            promptOverheadTokens = promptTokens
        )
    }

    private suspend fun splitForBudget(
        systemPrompt: String,
        text: String,
        inputBudget: Int,
        targetLanguage: String
    ): List<String> {
        return PDFSummaryLogic.splitIntoChunks(text, inputBudget) { candidate ->
            val promptTokens = runBlocking {
                client.countRenderedPromptTokens(
                    systemPrompt,
                    PDFSummaryLogic.buildChunkPrompt(candidate, 99, 99, targetLanguage)
                ).totalTokens
            }
            promptTokens <= inputBudget
        }
    }

    private suspend fun runWithRetry(
        request: RemoteSummaryRequest,
        isCancelled: () -> Boolean
    ): CallResult {
        var lastResult = CallResult(errorMessage = "Summary request failed")

        repeat(MAX_ATTEMPTS) { attemptIndex ->
            val result = runOnce(request, isCancelled)
            if (result.output != null || result.cancelled) {
                return result
            }
            lastResult = result
            if (!PDFSummaryLogic.shouldRetryFailure(
                    attemptNumber = attemptIndex + 1,
                    maxAttempts = MAX_ATTEMPTS,
                    isCancelled = result.cancelled,
                    timedOut = result.timedOut,
                    output = result.output,
                    errorMessage = result.errorMessage
                )
            ) {
                return result
            }
        }

        return lastResult
    }

    private suspend fun runOnce(
        request: RemoteSummaryRequest,
        isCancelled: () -> Boolean
    ): CallResult {
        return try {
            val response = client.summarize(request)
            if (isCancelled()) {
                CallResult(cancelled = true, errorMessage = "Cancelled")
            } else {
                CallResult(
                    output = response.output,
                    promptTokens = response.promptTokens,
                    completionTokens = response.completionTokens
                )
            }
        } catch (e: CancellationException) {
            CallResult(cancelled = true, errorMessage = "Cancelled")
        } catch (e: Exception) {
            CallResult(
                errorMessage = e.message ?: "Summary request failed",
                cancelled = isCancelled(),
                timedOut = e.message?.contains("timeout", ignoreCase = true) == true
            )
        }
    }
}
