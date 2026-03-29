package com.example.llamadroid.service

import android.content.Context
import com.example.llamadroid.data.api.CompletionRequest
import com.example.llamadroid.data.api.CompletionResponse
import com.example.llamadroid.data.api.LlamaServerApi
import com.example.llamadroid.data.db.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.example.llamadroid.R

/**
 * Processes dataset chunks through LLM pipeline:
 * Clean → Question (5x) → Answer → Rate
 * 
 * Uses companion object for static state so processing survives navigation.
 */
class DatasetProcessor(
    private val context: Context,
    private val dao: DatasetDao
) {
    
    // Progress state
    data class Progress(
        val stage: String,
        val current: Int,
        val total: Int,
        val currentItem: String = "",
        val projectId: Long = 0
    )
    
    // Job types for queue
    sealed class Job(val name: String, val projectId: Long) {
        class RegenAnswer(val qaId: Long, projectId: Long, val answerPrompt: String, name: String) : Job(name, projectId)
        class RegenRating(val qaId: Long, projectId: Long, val reviewPrompt: String, name: String) : Job(name, projectId)
        class RegenAnswers(val qaIds: Set<Long>, projectId: Long, val answerPrompt: String, name: String) : Job(name, projectId)
        class RegenRatings(val qaIds: Set<Long>, projectId: Long, val reviewPrompt: String, name: String) : Job(name, projectId)
        class RegenQuestions(val chunkIds: Set<Long>, projectId: Long, val questionPrompt: String, name: String) : Job(name, projectId)
        class RegenClean(val chunkId: Long, projectId: Long, val cleanPrompt: String, name: String) : Job(name, projectId)
        class Clean(projectId: Long, val cleanPrompt: String, name: String) : Job(name, projectId)
        class Questions(projectId: Long, val questionPrompt: String, name: String) : Job(name, projectId)
        class Answers(projectId: Long, val answerPrompt: String, name: String) : Job(name, projectId)
        class Rating(projectId: Long, val reviewPrompt: String, name: String) : Job(name, projectId)
    }
    
    companion object {
        private const val CHANNEL_ID = "dataset_processing"
        private const val NOTIFICATION_ID = 9999
        
        // Static state that survives navigation
        private val _progress = MutableStateFlow<Progress?>(null)
        val progress = _progress.asStateFlow()
        
        private val _isProcessing = MutableStateFlow(false)
        val isProcessing = _isProcessing.asStateFlow()
        
        // Job queue
        private val _jobQueue = MutableStateFlow<List<Job>>(emptyList())
        val jobQueue = _jobQueue.asStateFlow()
        
        private var processingJob: kotlinx.coroutines.Job? = null
        private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        
        // Application context for notifications (set from Application or Activity)
        private var appContext: android.content.Context? = null
        private var processorInstance: DatasetProcessor? = null
        
        fun setApplicationContext(context: android.content.Context) {
            appContext = context.applicationContext
        }
        
        fun setProcessor(processor: DatasetProcessor) {
            processorInstance = processor
        }
        
        fun queueJob(job: Job) {
            _jobQueue.value = _jobQueue.value + job
            // Start processing if not already running
            if (!_isProcessing.value) {
                processNextJob()
            }
        }
        
        fun removeJob(index: Int) {
            val current = _jobQueue.value.toMutableList()
            if (index in current.indices) {
                current.removeAt(index)
                _jobQueue.value = current
            }
        }
        
        fun moveJobUp(index: Int) {
            if (index > 0) {
                val current = _jobQueue.value.toMutableList()
                val item = current.removeAt(index)
                current.add(index - 1, item)
                _jobQueue.value = current
            }
        }
        
        fun moveJobDown(index: Int) {
            val current = _jobQueue.value.toMutableList()
            if (index < current.size - 1) {
                val item = current.removeAt(index)
                current.add(index + 1, item)
                _jobQueue.value = current
            }
        }
        
        private fun processNextJob() {
            val queue = _jobQueue.value
            if (queue.isEmpty()) return
            
            val job = queue.first()
            _jobQueue.value = queue.drop(1)
            
            processorInstance?.executeJob(job)
        }
        
        fun onJobComplete() {
            if (_jobQueue.value.isNotEmpty()) {
                processNextJob()
            }
        }
        
        fun cancel() {
            processingJob?.cancel()
            _isProcessing.value = false
            updateProgressInternal(null)
        }
        
        fun cancelAll() {
            processingJob?.cancel()
            _jobQueue.value = emptyList()
            _isProcessing.value = false
            updateProgressInternal(null)
        }
        
        // Internal method for updating progress and showing notification
        private fun updateProgressInternal(progress: Progress?) {
            _progress.value = progress
            
            // Show or cancel notification globally
            appContext?.let { ctx ->
                if (progress != null) {
                    showNotification(ctx, progress)
                } else {
                    cancelNotification(ctx)
                }
            }
        }
        
        private fun showNotification(context: android.content.Context, progress: Progress) {
            // Create channel (required for Android 8+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.dataset_processing_notif),
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                manager.createNotificationChannel(channel)
            }
            
            val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_sort_by_size)
                .setContentTitle("${progress.stage}: ${progress.current}/${progress.total}")
                .setContentText(progress.currentItem)
                .setProgress(progress.total, progress.current, false)
                .setOngoing(true)
                .setSilent(true)
                .build()
            
            try {
                androidx.core.app.NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
                // Permission not granted
            }
        }
        
        private fun cancelNotification(context: android.content.Context) {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }
    
    // Instance method that updates static state and triggers notification
    private fun updateProgress(progress: Progress?) {
        updateProgressInternal(progress)
    }
    
    private fun setProcessing(value: Boolean) {
        _isProcessing.value = value
    }
    
    // Create Retrofit with custom server URL
    private fun createApi(serverUrl: String): LlamaServerApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(1200, TimeUnit.SECONDS)  // 20 min for slow LLM on mobile
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
        
        // Ensure URL ends with /
        val baseUrl = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
            
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LlamaServerApi::class.java)
    }
    
    // ========== Chunking ==========
    
    fun chunkText(text: String, chunkSize: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + chunkSize, text.length)
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }
    
    // Build context (50% of prev and next chunks)
    fun buildContext(chunks: List<String>, index: Int): Triple<String, String, String> {
        val prevChunk = if (index > 0) {
            val prev = chunks[index - 1]
            prev.takeLast(prev.length / 2)
        } else ""
        
        val nextChunk = if (index < chunks.size - 1) {
            val next = chunks[index + 1]
            next.take(next.length / 2)
        } else ""
        
        return Triple(prevChunk, chunks[index], nextChunk)
    }
    
    // ========== LLM Calls ==========
    
    private suspend fun callLLM(
        api: LlamaServerApi,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        stop: List<String> = emptyList()
    ): String {
        return try {
            val response = api.completion(CompletionRequest(
                prompt = prompt,
                n_predict = maxTokens,
                temperature = temperature,
                stop = stop
            ))
            response.content.trim()
        } catch (e: Exception) {
            throw e
        }
    }
    
    suspend fun cleanChunk(
        api: LlamaServerApi,
        chunk: String,
        prevContext: String,
        nextContext: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        val formattedPrompt = prompt
            .replace("{prev_chunk_50%}", prevContext)
            .replace("{chunk}", chunk)
            .replace("{next_chunk_50%}", nextContext)
        
        return callLLM(api, formattedPrompt, maxTokens, temperature)
    }
    
    suspend fun generateQuestion(
        api: LlamaServerApi,
        chunk: String,
        prevContext: String,
        nextContext: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        val formattedPrompt = prompt
            .replace("{prev_chunk_50%}", prevContext)
            .replace("{chunk}", chunk)
            .replace("{next_chunk_50%}", nextContext)
        
        return callLLM(api, formattedPrompt, maxTokens, temperature, listOf("\n"))
    }
    
    suspend fun generateAnswer(
        api: LlamaServerApi,
        question: String,
        context: String,
        prompt: String,
        useCoT: Boolean,
        maxTokens: Int,
        temperature: Float
    ): String {
        var formattedPrompt = prompt.replace("{question}", question)
            .replace("{context}", context)
        
        if (useCoT) {
            formattedPrompt = formattedPrompt.replace("{if CoT: Think step by step before answering.}", 
                "Think step by step before answering.")
        } else {
            formattedPrompt = formattedPrompt.replace("{if CoT: Think step by step before answering.}", "")
        }
        
        return callLLM(api, formattedPrompt, maxTokens, temperature)
    }
    
    suspend fun rateQA(
        api: LlamaServerApi,
        question: String,
        answer: String,
        context: String,
        prompt: String,
        maxTokens: Int,
        temperature: Float
    ): Pair<Int, String> {
        val formattedPrompt = prompt
            .replace("{question}", question)
            .replace("{answer}", answer)
            .replace("{context}", context)
        
        // Allow more tokens for justification
        val response = callLLM(api, formattedPrompt, 250, 0.1f)
        
        // Extract FINAL score from response - look for specific patterns in priority order
        val score = extractFinalScore(response)
        
        return Pair(score, response.trim())
    }
    
    /**
     * Extract the final/total score from LLM response.
     * Priority: FINAL_SCORE: X > Total Score: X > Total: X/5 > last digit
     */
    private fun extractFinalScore(response: String): Int {
        // Try FINAL_SCORE: X pattern (most strict)
        val finalScoreMatch = Regex("FINAL_SCORE:\\s*(\\d)").find(response)
        if (finalScoreMatch != null) {
            return finalScoreMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        // Try Total Score: X pattern
        val totalScoreMatch = Regex("(?i)total\\s*score:?\\s*(\\d)").find(response)
        if (totalScoreMatch != null) {
            return totalScoreMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        // Try Total: X/5 or Total: X pattern
        val totalMatch = Regex("(?i)total:?\\s*(\\d)/?\\d?").find(response)
        if (totalMatch != null) {
            return totalMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        // Fallback: find the LAST digit 0-5 in response (likely the final score)
        val allDigits = Regex("[0-5]").findAll(response).toList()
        if (allDigits.isNotEmpty()) {
            return allDigits.last().value.toIntOrNull()?.coerceIn(0, 5) ?: 3
        }
        
        return 3 // Default
    }
    
    // ========== Pipeline ==========
    
    fun runCleaningStage(
        project: DatasetProjectEntity,
        cleanPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            com.example.llamadroid.util.DebugLog.log("[Dataset] Starting cleaning stage for project ${project.id}")
            
            try {
                val api = createApi(project.serverUrl)
                val pendingChunks = dao.getChunksByStatus(project.id, ChunkStatus.PENDING)
                val total = pendingChunks.size
                com.example.llamadroid.util.DebugLog.log("[Dataset] Found $total pending chunks to clean")
                
                if (total == 0) {
                    updateProgress(Progress(context.getString(R.string.dataset_stage_done), 0, 0, context.getString(R.string.dataset_no_chunks_clean), project.id))
                    com.example.llamadroid.util.DebugLog.log("[Dataset] No chunks to clean, done")
                    return@launch
                }
                
                pendingChunks.forEachIndexed { index, chunk ->
                    ensureActive()
                    com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning chunk ${index + 1}/$total (id=${chunk.id})")
                    
                    updateProgress(Progress(context.getString(R.string.dataset_stage_cleaning), index + 1, total, context.getString(R.string.dataset_chunk_param, chunk.chunkIndex), project.id))
                    
                    try {
                        // Get all chunks for context
                        val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.PENDING) + 
                            dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                        val sortedChunks = allChunks.sortedBy { it.chunkIndex }
                        val chunkTexts = sortedChunks.map { it.cleanedText ?: it.originalText }
                        val currentIndex = sortedChunks.indexOfFirst { it.id == chunk.id }
                        
                        val (prev, _, next) = buildContext(chunkTexts, currentIndex)
                        
                        com.example.llamadroid.util.DebugLog.log("[Dataset] Calling LLM for chunk ${chunk.id}...")
                        val cleanedText = cleanChunk(
                            api, chunk.originalText, prev, next,
                            cleanPrompt, project.maxTokens, project.temperature
                        )
                        com.example.llamadroid.util.DebugLog.log("[Dataset] LLM response received, length=${cleanedText.length}")
                        
                        // Update chunk with cleaned text
                        dao.updateChunk(chunk.copy(
                            cleanedText = cleanedText,
                            status = ChunkStatus.CLEANED
                        ))
                        com.example.llamadroid.util.DebugLog.log("[Dataset] Chunk ${chunk.id} updated to CLEANED")
                    } catch (e: Exception) {
                        com.example.llamadroid.util.DebugLog.log("[Dataset] ERROR cleaning chunk ${chunk.id}: ${e.message}")
                        updateProgress(Progress(context.getString(R.string.dataset_stage_error), index + 1, total, context.getString(R.string.dataset_error_param, e.message), project.id))
                        throw e
                    }
                }
                com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning stage COMPLETED for all $total chunks")
            } catch (e: Exception) {
                com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning stage ERROR: ${e.message}")
                updateProgress(Progress(context.getString(R.string.dataset_stage_error), 0, 0, context.getString(R.string.dataset_server_error_param, e.message?.take(50)), project.id))
                // Don't throw - just show error in progress
                kotlinx.coroutines.delay(3000)
            } finally {
                com.example.llamadroid.util.DebugLog.log("[Dataset] Cleaning stage FINISHED, resetting state")
                setProcessing(false)
                updateProgress(null)
            }
        }
    }
    
    fun runQuestionStage(
        project: DatasetProjectEntity,
        questionPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            
            try {
                val api = createApi(project.serverUrl)
                val cleanedChunks = dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                val total = cleanedChunks.size * project.questionsPerChunk
                var current = 0
                
                cleanedChunks.forEach { chunk ->
                    ensureActive()
                    
                    val cleanedText = chunk.cleanedText ?: return@forEach
                    
                    // Get context
                    val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                    val sortedChunks = allChunks.sortedBy { it.chunkIndex }
                    val chunkTexts = sortedChunks.map { it.cleanedText ?: it.originalText }
                    val currentIndex = sortedChunks.indexOfFirst { it.id == chunk.id }
                    val (prev, _, next) = buildContext(chunkTexts, currentIndex)
                    
                    // Generate N questions per chunk
                    val generatedQuestions = mutableSetOf<String>()
                    repeat(project.questionsPerChunk) { i ->
                        ensureActive()
                        current++
                        updateProgress(Progress(context.getString(R.string.dataset_stage_questions), current, total, context.getString(R.string.dataset_chunk_q_param, chunk.chunkIndex, i+1), project.id))
                        
                        val question = generateQuestion(
                            api, cleanedText, prev, next,
                            questionPrompt, project.maxTokens, project.temperature
                        )
                        
                        // Only add unique questions
                        if (question.isNotBlank() && question !in generatedQuestions) {
                            generatedQuestions.add(question)
                            dao.insertQA(DatasetQAEntity(
                                projectId = project.id,
                                chunkId = chunk.id,
                                question = question,
                                status = QAStatus.QUESTIONED
                            ))
                        }
                    }
                }
            } finally {
                setProcessing(false)
                updateProgress(null)
            }
        }
    }
    
    fun runAnswerStage(
        project: DatasetProjectEntity,
        answerPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            
            try {
                val api = createApi(project.serverUrl)
                val unanswered = dao.getQAByStatus(project.id, QAStatus.QUESTIONED)
                val total = unanswered.size
                
                unanswered.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_stage_answers), index + 1, total, qa.question.take(30) + "...", project.id))
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val answer = generateAnswer(
                        api, qa.question, context,
                        answerPrompt, project.useCoT, project.maxTokens, project.temperature
                    )
                    
                    dao.updateQA(qa.copy(
                        answer = answer,
                        status = QAStatus.ANSWERED
                    ))
                }
            } finally {
                setProcessing(false)
                updateProgress(null)
            }
        }
    }
    
    fun runRatingStage(
        project: DatasetProjectEntity,
        reviewPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            
            try {
                val api = createApi(project.serverUrl)
                val answered = dao.getQAByStatus(project.id, QAStatus.ANSWERED)
                val total = answered.size
                
                answered.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_stage_rating), index + 1, total, qa.question.take(30) + "...", project.id))
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val (score, justification) = rateQA(
                        api, qa.question, qa.answer ?: "", context,
                        reviewPrompt, project.maxTokens, project.temperature
                    )
                    
                    dao.updateQA(qa.copy(
                        score = score,
                        scoreJustification = justification,
                        status = QAStatus.REVIEWED
                    ))
                }
            } finally {
                setProcessing(false)
                updateProgress(null)
            }
        }
    }
    
    // ========== Regeneration Stages (for batch regen with progress) ==========
    
    fun runRegenAnswers(
        project: DatasetProjectEntity,
        qaIds: Set<Long>,
        answerPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting regenerate answers for ${qaIds.size} items")
            
            try {
                val api = createApi(project.serverUrl)
                val qaList = qaIds.mapNotNull { id -> dao.getQA(id) }
                val total = qaList.size
                
                qaList.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_job_regen_answers_param, total), index + 1, total, qa.question.take(30) + "...", project.id))
                    com.example.llamadroid.util.DebugLog.log("[Regen] Regenerating answer ${index + 1}/$total")
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val newAnswer = generateAnswer(
                        api, qa.question, context, answerPrompt,
                        project.useCoT, project.maxTokens, project.temperature
                    )
                    
                    dao.updateQA(qa.copy(
                        answer = newAnswer,
                        score = null,
                        scoreJustification = null,
                        status = QAStatus.ANSWERED
                    ))
                }
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed regenerate answers")
            } finally {
                setProcessing(false)
                updateProgress(null)
                onJobComplete()
            }
        }
    }
    
    fun runRegenRatings(
        project: DatasetProjectEntity,
        qaIds: Set<Long>,
        reviewPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting regenerate ratings for ${qaIds.size} items")
            
            try {
                val api = createApi(project.serverUrl)
                val qaList = qaIds.mapNotNull { id -> dao.getQA(id) }.filter { it.answer != null }
                val total = qaList.size
                
                qaList.forEachIndexed { index, qa ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_job_regen_ratings_param, total), index + 1, total, qa.question.take(30) + "...", project.id))
                    com.example.llamadroid.util.DebugLog.log("[Regen] Regenerating rating ${index + 1}/$total")
                    
                    val chunk = dao.getChunk(qa.chunkId) ?: return@forEachIndexed
                    val context = chunk.cleanedText ?: chunk.originalText
                    
                    val (score, justification) = rateQA(
                        api, qa.question, qa.answer ?: "", context,
                        reviewPrompt, project.maxTokens, project.temperature
                    )
                    
                    dao.updateQA(qa.copy(
                        score = score,
                        scoreJustification = justification,
                        status = QAStatus.REVIEWED
                    ))
                }
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed regenerate ratings")
            } finally {
                setProcessing(false)
                updateProgress(null)
                onJobComplete()
            }
        }
    }
    
    fun runRegenQuestions(
        project: DatasetProjectEntity,
        chunkIds: Set<Long>,
        questionPrompt: String
    ) {
        processingJob = processingScope.launch {
            setProcessing(true)
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting regenerate questions for ${chunkIds.size} chunks")
            
            try {
                val api = createApi(project.serverUrl)
                val chunks = chunkIds.mapNotNull { id -> dao.getChunk(id) }
                val total = chunks.size
                
                chunks.forEachIndexed { index, chunk ->
                    ensureActive()
                    updateProgress(Progress(context.getString(R.string.dataset_job_regen_questions_param, total), index + 1, total, chunk.originalText.take(30) + "...", project.id))
                    com.example.llamadroid.util.DebugLog.log("[Regen] Regenerating questions for chunk ${index + 1}/$total")
                    
                    // Delete existing QAs for this chunk
                    dao.deleteQAForChunk(chunk.id)
                    
                    // Get context from surrounding chunks
                    val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
                    val chunkIdx = allChunks.indexOfFirst { c -> c.id == chunk.id }
                    val prevContext = if (chunkIdx > 0) allChunks[chunkIdx - 1].originalText.takeLast(500) else ""
                    val nextContext = if (chunkIdx < allChunks.size - 1) allChunks[chunkIdx + 1].originalText.take(500) else ""
                    val text = chunk.cleanedText ?: chunk.originalText
                    
                    // Generate new questions
                    repeat(5) {
                        ensureActive()
                        val question = generateQuestion(api, text, prevContext, nextContext, questionPrompt, project.maxTokens, project.temperature)
                        if (question.isNotBlank()) {
                            dao.insertQA(DatasetQAEntity(
                                projectId = project.id,
                                chunkId = chunk.id,
                                question = question.trim(),
                                status = QAStatus.QUESTIONED
                            ))
                        }
                    }
                }
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed regenerate questions")
            } finally {
                setProcessing(false)
                updateProgress(null)
                onJobComplete()
            }
        }
    }
    
    // ========== Single Item Regeneration ==========
    
    fun runRegenSingleAnswer(projectId: Long, qaId: Long, answerPrompt: String) {
        processingJob = processingScope.launch {
            setProcessing(true)
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting single answer regen for QA $qaId")
            
            try {
                val project = dao.getProject(projectId) ?: return@launch
                val qa = dao.getQA(qaId) ?: return@launch
                val api = createApi(project.serverUrl)
                
                updateProgress(Progress(context.getString(R.string.dataset_job_regen_answer), 1, 1, qa.question.take(30) + "...", projectId))
                
                val chunk = dao.getChunk(qa.chunkId) ?: return@launch
                val context = chunk.cleanedText ?: chunk.originalText
                
                val newAnswer = generateAnswer(
                    api, qa.question, context, answerPrompt,
                    project.useCoT, project.maxTokens, project.temperature
                )
                
                dao.updateQA(qa.copy(
                    answer = newAnswer,
                    score = null,
                    scoreJustification = null,
                    status = QAStatus.ANSWERED
                ))
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed single answer regen")
            } finally {
                setProcessing(false)
                updateProgress(null)
                onJobComplete()
            }
        }
    }
    
    fun runRegenSingleRating(projectId: Long, qaId: Long, reviewPrompt: String) {
        processingJob = processingScope.launch {
            setProcessing(true)
            com.example.llamadroid.util.DebugLog.log("[Regen] Starting single rating regen for QA $qaId")
            
            try {
                val project = dao.getProject(projectId) ?: return@launch
                val qa = dao.getQA(qaId) ?: return@launch
                if (qa.answer == null) return@launch
                val api = createApi(project.serverUrl)
                
                updateProgress(Progress(context.getString(R.string.dataset_job_regen_rating), 1, 1, qa.question.take(30) + "...", projectId))
                
                val chunk = dao.getChunk(qa.chunkId) ?: return@launch
                val context = chunk.cleanedText ?: chunk.originalText
                
                val (score, justification) = rateQA(
                    api, qa.question, qa.answer ?: "", context,
                    reviewPrompt, project.maxTokens, project.temperature
                )
                
                dao.updateQA(qa.copy(
                    score = score,
                    scoreJustification = justification,
                    status = QAStatus.REVIEWED
                ))
                com.example.llamadroid.util.DebugLog.log("[Regen] Completed single rating regen")
            } finally {
                setProcessing(false)
                updateProgress(null)
                onJobComplete()
            }
        }
    }
    
    // ========== Job Dispatcher ==========
    
    fun executeJob(job: Job) {
        processingScope.launch {
            val project = dao.getProject(job.projectId) ?: run {
                onJobComplete()
                return@launch
            }
            
            when (job) {
                is Job.RegenAnswer -> runRegenSingleAnswer(job.projectId, job.qaId, job.answerPrompt)
                is Job.RegenRating -> runRegenSingleRating(job.projectId, job.qaId, job.reviewPrompt)
                is Job.RegenAnswers -> runRegenAnswers(project, job.qaIds, job.answerPrompt)
                is Job.RegenRatings -> runRegenRatings(project, job.qaIds, job.reviewPrompt)
                is Job.RegenQuestions -> runRegenQuestions(project, job.chunkIds, job.questionPrompt)
                is Job.RegenClean -> runRegenSingleClean(project, job.chunkId, job.cleanPrompt)
                is Job.Clean -> runCleaningStage(project, job.cleanPrompt)
                is Job.Questions -> runQuestionStage(project, job.questionPrompt)
                is Job.Answers -> runAnswerStage(project, job.answerPrompt)
                is Job.Rating -> runRatingStage(project, job.reviewPrompt)
            }
        }
    }
    
    private suspend fun runRegenSingleClean(project: DatasetProjectEntity, chunkId: Long, cleanPrompt: String) {
        try {
            _isProcessing.value = true
            val chunk = dao.getChunk(chunkId) ?: return
            _progress.value = Progress(context.getString(R.string.dataset_job_regen_clean), 1, 1, chunk.originalText.take(50) + "...", project.id)
            
            val api = createApi(project.serverUrl)
            
            // Get all chunks for context
            val allChunks = dao.getChunksByStatus(project.id, ChunkStatus.PENDING) + 
                dao.getChunksByStatus(project.id, ChunkStatus.CLEANED)
            val sortedChunks = allChunks.sortedBy { it.chunkIndex }
            val chunkTexts = sortedChunks.map { it.cleanedText ?: it.originalText }
            val currentIndex = sortedChunks.indexOfFirst { it.id == chunk.id }
            
            val (prev, _, next) = buildContext(chunkTexts, if (currentIndex >= 0) currentIndex else 0)
            
            // Use default clean prompt if not provided
            val prompt = if (cleanPrompt.isBlank()) """Clean this text by removing formatting artifacts, OCR errors, and noise.

Previous context: {prev_chunk_50%}
Text to clean: {chunk}
Next context: {next_chunk_50%}

Cleaned text:""" else cleanPrompt
            
            val cleanedText = cleanChunk(api, chunk.originalText, prev, next, prompt, project.maxTokens, project.temperature)
            
            if (cleanedText.isNotBlank()) {
                dao.updateChunk(chunk.copy(cleanedText = cleanedText.trim(), status = ChunkStatus.CLEANED))
            }
        } catch (e: Exception) {
            com.example.llamadroid.util.DebugLog.log("[Dataset] ERROR in runRegenSingleClean: ${e.message}")
        } finally {
            _isProcessing.value = false
            _progress.value = null
            onJobComplete()
        }
    }
}
