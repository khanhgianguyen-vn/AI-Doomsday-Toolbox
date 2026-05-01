package com.example.llamadroid.service

import com.example.llamadroid.data.db.AiRuntimeJobEntity
import org.json.JSONArray
import org.json.JSONObject

internal const val DATASET_RUNTIME_JOB_KEY = "dataset_pipeline_queue"
internal const val DATASET_RUNTIME_JOB_ID = "dataset_pipeline_queue"

private const val JOB_TYPE_REGEN_ANSWER = "regen_answer"
private const val JOB_TYPE_REGEN_RATING = "regen_rating"
private const val JOB_TYPE_REGEN_ANSWERS = "regen_answers"
private const val JOB_TYPE_REGEN_RATINGS = "regen_ratings"
private const val JOB_TYPE_REGEN_QUESTIONS = "regen_questions"
private const val JOB_TYPE_REGEN_CLEAN = "regen_clean"
private const val JOB_TYPE_CLEAN = "clean"
private const val JOB_TYPE_QUESTIONS = "questions"
private const val JOB_TYPE_ANSWERS = "answers"
private const val JOB_TYPE_RATING = "rating"
private const val JOB_TYPE_IMPORT_PDF = "import_pdf"
private const val JOB_TYPE_IMPORT_TXT = "import_txt"

internal data class PersistedDatasetRuntimeSnapshot(
    val activeJob: PersistedDatasetJob? = null,
    val queuedJobs: List<PersistedDatasetJob> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("activeJob", activeJob?.toJson())
        put(
            "queuedJobs",
            JSONArray().apply {
                queuedJobs.forEach { put(it.toJson()) }
            }
        )
    }

    fun firstProjectId(): Long? = activeJob?.projectId ?: queuedJobs.firstOrNull()?.projectId

    fun toRuntimeQueue(): List<DatasetProcessor.Job> {
        val restored = mutableListOf<DatasetProcessor.Job>()
        activeJob?.toRuntimeJob()?.let(restored::add)
        queuedJobs.mapNotNullTo(restored) { it.toRuntimeJob() }
        return restored
    }

    companion object {
        fun fromJson(rawJson: String): PersistedDatasetRuntimeSnapshot =
            fromJson(JSONObject(rawJson))

        fun fromJson(json: JSONObject): PersistedDatasetRuntimeSnapshot =
            PersistedDatasetRuntimeSnapshot(
                activeJob = json.optJSONObject("activeJob")?.let(PersistedDatasetJob::fromJson),
                queuedJobs = buildList {
                    val jobs = json.optJSONArray("queuedJobs") ?: JSONArray()
                    for (index in 0 until jobs.length()) {
                        val item = jobs.optJSONObject(index) ?: continue
                        add(PersistedDatasetJob.fromJson(item))
                    }
                }
            )
    }
}

internal data class PersistedDatasetJob(
    val type: String,
    val name: String,
    val projectId: Long,
    val prompt: String? = null,
    val qaId: Long? = null,
    val chunkId: Long? = null,
    val sourceUri: String? = null,
    val sourceName: String? = null,
    val qaIds: List<Long> = emptyList(),
    val chunkIds: List<Long> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("name", name)
        put("projectId", projectId)
        put("prompt", prompt)
        put("qaId", qaId)
        put("chunkId", chunkId)
        put("sourceUri", sourceUri)
        put("sourceName", sourceName)
        put("qaIds", JSONArray().apply { qaIds.forEach(::put) })
        put("chunkIds", JSONArray().apply { chunkIds.forEach(::put) })
    }

    fun toRuntimeJob(): DatasetProcessor.Job? {
        return when (type) {
            JOB_TYPE_REGEN_ANSWER -> qaId?.let { DatasetProcessor.Job.RegenAnswer(it, projectId, prompt.orEmpty(), name) }
            JOB_TYPE_REGEN_RATING -> qaId?.let { DatasetProcessor.Job.RegenRating(it, projectId, prompt.orEmpty(), name) }
            JOB_TYPE_REGEN_ANSWERS -> DatasetProcessor.Job.RegenAnswers(qaIds.toSet(), projectId, prompt.orEmpty(), name)
            JOB_TYPE_REGEN_RATINGS -> DatasetProcessor.Job.RegenRatings(qaIds.toSet(), projectId, prompt.orEmpty(), name)
            JOB_TYPE_REGEN_QUESTIONS -> DatasetProcessor.Job.RegenQuestions(chunkIds.toSet(), projectId, prompt.orEmpty(), name)
            JOB_TYPE_REGEN_CLEAN -> chunkId?.let { DatasetProcessor.Job.RegenClean(it, projectId, prompt.orEmpty(), name) }
            JOB_TYPE_CLEAN -> DatasetProcessor.Job.Clean(projectId, prompt.orEmpty(), name)
            JOB_TYPE_QUESTIONS -> DatasetProcessor.Job.Questions(projectId, prompt.orEmpty(), name)
            JOB_TYPE_ANSWERS -> DatasetProcessor.Job.Answers(projectId, prompt.orEmpty(), name)
            JOB_TYPE_RATING -> DatasetProcessor.Job.Rating(projectId, prompt.orEmpty(), name)
            JOB_TYPE_IMPORT_PDF -> sourceUri?.let {
                DatasetProcessor.Job.ImportPdf(projectId, it, sourceName.orEmpty(), name)
            }
            JOB_TYPE_IMPORT_TXT -> sourceUri?.let {
                DatasetProcessor.Job.ImportTxt(projectId, it, sourceName.orEmpty(), name)
            }
            else -> null
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PersistedDatasetJob =
            PersistedDatasetJob(
                type = json.optString("type"),
                name = json.optString("name"),
                projectId = json.optLong("projectId"),
                prompt = json.optString("prompt").ifBlank { null },
                qaId = json.optLong("qaId").takeIf { json.has("qaId") && !json.isNull("qaId") },
                chunkId = json.optLong("chunkId").takeIf { json.has("chunkId") && !json.isNull("chunkId") },
                sourceUri = json.optString("sourceUri").ifBlank { null },
                sourceName = json.optString("sourceName").ifBlank { null },
                qaIds = json.optJSONArray("qaIds").toLongList(),
                chunkIds = json.optJSONArray("chunkIds").toLongList()
            )
    }
}

internal data class DatasetResumeAction(
    val shouldRecover: Boolean,
    val snapshot: PersistedDatasetRuntimeSnapshot? = null
)

internal fun resolveDatasetResumeAction(runtimeJob: AiRuntimeJobEntity?): DatasetResumeAction {
    if (runtimeJob == null || runtimeJob.type != AiRuntimeJobStore.TYPE_DATASET_PIPELINE) {
        return DatasetResumeAction(shouldRecover = false)
    }
    val snapshot = runCatching { PersistedDatasetRuntimeSnapshot.fromJson(runtimeJob.payloadJson) }
        .getOrNull()
        ?: return DatasetResumeAction(shouldRecover = false)
    if (snapshot.activeJob == null && snapshot.queuedJobs.isEmpty()) {
        return DatasetResumeAction(shouldRecover = false)
    }
    return DatasetResumeAction(
        shouldRecover = true,
        snapshot = snapshot
    )
}

internal fun DatasetProcessor.Job.toPersistedDatasetJob(): PersistedDatasetJob {
    return when (this) {
        is DatasetProcessor.Job.RegenAnswer -> PersistedDatasetJob(
            type = JOB_TYPE_REGEN_ANSWER,
            name = name,
            projectId = projectId,
            prompt = answerPrompt,
            qaId = qaId
        )

        is DatasetProcessor.Job.RegenRating -> PersistedDatasetJob(
            type = JOB_TYPE_REGEN_RATING,
            name = name,
            projectId = projectId,
            prompt = reviewPrompt,
            qaId = qaId
        )

        is DatasetProcessor.Job.RegenAnswers -> PersistedDatasetJob(
            type = JOB_TYPE_REGEN_ANSWERS,
            name = name,
            projectId = projectId,
            prompt = answerPrompt,
            qaIds = qaIds.sorted()
        )

        is DatasetProcessor.Job.RegenRatings -> PersistedDatasetJob(
            type = JOB_TYPE_REGEN_RATINGS,
            name = name,
            projectId = projectId,
            prompt = reviewPrompt,
            qaIds = qaIds.sorted()
        )

        is DatasetProcessor.Job.RegenQuestions -> PersistedDatasetJob(
            type = JOB_TYPE_REGEN_QUESTIONS,
            name = name,
            projectId = projectId,
            prompt = questionPrompt,
            chunkIds = chunkIds.sorted()
        )

        is DatasetProcessor.Job.RegenClean -> PersistedDatasetJob(
            type = JOB_TYPE_REGEN_CLEAN,
            name = name,
            projectId = projectId,
            prompt = cleanPrompt,
            chunkId = chunkId
        )

        is DatasetProcessor.Job.Clean -> PersistedDatasetJob(
            type = JOB_TYPE_CLEAN,
            name = name,
            projectId = projectId,
            prompt = cleanPrompt
        )

        is DatasetProcessor.Job.Questions -> PersistedDatasetJob(
            type = JOB_TYPE_QUESTIONS,
            name = name,
            projectId = projectId,
            prompt = questionPrompt
        )

        is DatasetProcessor.Job.Answers -> PersistedDatasetJob(
            type = JOB_TYPE_ANSWERS,
            name = name,
            projectId = projectId,
            prompt = answerPrompt
        )

        is DatasetProcessor.Job.Rating -> PersistedDatasetJob(
            type = JOB_TYPE_RATING,
            name = name,
            projectId = projectId,
            prompt = reviewPrompt
        )

        is DatasetProcessor.Job.ImportPdf -> PersistedDatasetJob(
            type = JOB_TYPE_IMPORT_PDF,
            name = name,
            projectId = projectId,
            sourceUri = sourceUri,
            sourceName = sourceName
        )

        is DatasetProcessor.Job.ImportTxt -> PersistedDatasetJob(
            type = JOB_TYPE_IMPORT_TXT,
            name = name,
            projectId = projectId,
            sourceUri = sourceUri,
            sourceName = sourceName
        )
    }
}

private fun JSONArray?.toLongList(): List<Long> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(optLong(index))
        }
    }
}
