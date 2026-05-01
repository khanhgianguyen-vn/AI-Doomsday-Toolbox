package com.example.llamadroid.tama.game

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import com.example.llamadroid.R
import com.example.llamadroid.service.TamaArtworkGenerationService
import com.example.llamadroid.tama.data.TamaArtworkStatus
import com.example.llamadroid.tama.db.TamaArtworkEntity
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.tama.db.TamaDeepDreamRunEntity
import java.util.UUID

enum class TamaDeepDreamRunClaimAction {
    STARTED_NEW,
    RESUMED_WAITING_APP_OPEN,
    RESTARTED_STALE,
    DEDUPED_ACTIVE,
    ALREADY_COMPLETED
}

data class TamaDeepDreamRunClaimResult(
    val run: TamaDeepDreamRunEntity,
    val action: TamaDeepDreamRunClaimAction
)

object TamaDeepDreamRunCoordinator {
    const val STATUS_PLANNING = "PLANNING"
    const val STATUS_WAITING_APP_OPEN = "WAITING_APP_OPEN"
    const val STATUS_QUEUED = "QUEUED"
    const val STATUS_ARTWORK_RUNNING = "ARTWORK_RUNNING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"
    const val STATUS_CANCELLED = "CANCELLED"

    const val STAGE_PREPARING = "PREPARING"
    const val STAGE_WAITING_APP_OPEN = "WAITING_APP_OPEN"
    const val STAGE_LOCAL_LLAMA = "LOCAL_LLAMA"
    const val STAGE_RESOLVE_MODEL = "RESOLVE_MODEL"
    const val STAGE_FETCH_METADATA = "FETCH_METADATA"
    const val STAGE_SUMMARY = "SUMMARY"
    const val STAGE_CLOSING = "CLOSING"
    const val STAGE_QUEUEING_ALBUM = "QUEUEING_ALBUM"
    const val STAGE_QUEUED = "QUEUED"
    const val STAGE_ARTWORK = "ARTWORK"
    const val STAGE_COMPLETED = "COMPLETED"
    const val STAGE_FAILED = "FAILED"
    const val STAGE_CANCELLED = "CANCELLED"

    private const val WAITING_APP_OPEN_STALE_MS = 24L * 60L * 60L * 1000L
    private const val PLANNING_STALE_MS = 90L * 60L * 1000L
    private const val ARTWORK_STALE_MS = 6L * 60L * 60L * 1000L

    val ACTIVE_STATUSES: List<String> = listOf(
        STATUS_PLANNING,
        STATUS_WAITING_APP_OPEN,
        STATUS_QUEUED,
        STATUS_ARTWORK_RUNNING
    )

    private fun isTerminalStatus(status: String): Boolean {
        return status == STATUS_COMPLETED || status == STATUS_FAILED || status == STATUS_CANCELLED
    }

    fun isRunStale(run: TamaDeepDreamRunEntity, now: Long = System.currentTimeMillis()): Boolean {
        val heartbeatAt = maxOf(run.updatedAt, run.lastHeartbeatAt)
        val threshold = when (run.status) {
            STATUS_PLANNING -> PLANNING_STALE_MS
            STATUS_WAITING_APP_OPEN -> WAITING_APP_OPEN_STALE_MS
            STATUS_QUEUED, STATUS_ARTWORK_RUNNING -> ARTWORK_STALE_MS
            else -> return false
        }
        return now - heartbeatAt > threshold
    }

    fun shouldSuppressAlert(
        run: TamaDeepDreamRunEntity?,
        signature: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (run == null || run.signature != signature) return false
        if (run.status == STATUS_COMPLETED) return true
        return !isTerminalStatus(run.status) && !isRunStale(run, now)
    }

    suspend fun claimPlanningRun(
        database: TamaDatabase,
        petId: String,
        signature: String,
        dreamDate: String,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunClaimResult = database.withTransaction {
        val dao = database.tamaDao()
        val existing = dao.getDeepDreamRunBySignature(petId, signature)
        val activeOtherRuns = dao.getDeepDreamRunsForPetByStatuses(petId, ACTIVE_STATUSES)
            .filter { it.signature != signature }
            .map { other ->
                other.copy(
                    status = STATUS_CANCELLED,
                    stage = STAGE_CANCELLED,
                    updatedAt = now,
                    lastHeartbeatAt = now,
                    errorMessage = other.errorMessage
                )
            }
        if (activeOtherRuns.isNotEmpty()) {
            activeOtherRuns.forEach { clearPendingAlbumIfNeeded(dao, it.petId, it.albumId) }
            dao.saveDeepDreamRuns(activeOtherRuns)
        }

        when {
            existing == null -> {
                val run = TamaDeepDreamRunEntity(
                    id = UUID.randomUUID().toString(),
                    petId = petId,
                    signature = signature,
                    dreamDate = dreamDate,
                    status = STATUS_PLANNING,
                    stage = STAGE_PREPARING,
                    albumId = null,
                    ownsLocalLlama = false,
                    startedAt = now,
                    updatedAt = now,
                    lastHeartbeatAt = now,
                    errorMessage = null
                )
                dao.saveDeepDreamRun(run)
                TamaDeepDreamRunClaimResult(run, TamaDeepDreamRunClaimAction.STARTED_NEW)
            }

            existing.status == STATUS_COMPLETED -> {
                TamaDeepDreamRunClaimResult(existing, TamaDeepDreamRunClaimAction.ALREADY_COMPLETED)
            }

            existing.status == STATUS_WAITING_APP_OPEN && !isRunStale(existing, now) -> {
                val resumed = existing.copy(
                    status = STATUS_PLANNING,
                    stage = STAGE_PREPARING,
                    updatedAt = now,
                    lastHeartbeatAt = now,
                    errorMessage = null
                )
                dao.saveDeepDreamRun(resumed)
                TamaDeepDreamRunClaimResult(resumed, TamaDeepDreamRunClaimAction.RESUMED_WAITING_APP_OPEN)
            }

            !isTerminalStatus(existing.status) && !isRunStale(existing, now) -> {
                TamaDeepDreamRunClaimResult(existing, TamaDeepDreamRunClaimAction.DEDUPED_ACTIVE)
            }

            else -> {
                val restarted = existing.copy(
                    dreamDate = dreamDate,
                    status = STATUS_PLANNING,
                    stage = STAGE_PREPARING,
                    albumId = null,
                    ownsLocalLlama = false,
                    startedAt = now,
                    updatedAt = now,
                    lastHeartbeatAt = now,
                    errorMessage = null
                )
                dao.saveDeepDreamRun(restarted)
                TamaDeepDreamRunClaimResult(restarted, TamaDeepDreamRunClaimAction.RESTARTED_STALE)
            }
        }
    }

    suspend fun markWaitingAppOpen(
        database: TamaDatabase,
        runId: String,
        errorMessage: String?,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRun(runId) ?: return null
        val updated = run.copy(
            status = STATUS_WAITING_APP_OPEN,
            stage = STAGE_WAITING_APP_OPEN,
            updatedAt = now,
            lastHeartbeatAt = now,
            errorMessage = errorMessage
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun updatePlanningStage(
        database: TamaDatabase,
        runId: String,
        stage: String,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRun(runId) ?: return null
        val updated = run.copy(
            status = STATUS_PLANNING,
            stage = stage,
            updatedAt = now,
            lastHeartbeatAt = now
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun updateOwnsLocalLlama(
        database: TamaDatabase,
        runId: String,
        ownsLocalLlama: Boolean,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRun(runId) ?: return null
        val updated = run.copy(
            ownsLocalLlama = ownsLocalLlama,
            updatedAt = now,
            lastHeartbeatAt = now
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun markQueued(
        database: TamaDatabase,
        runId: String,
        albumId: String,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRun(runId) ?: return null
        val updated = run.copy(
            status = STATUS_QUEUED,
            stage = STAGE_QUEUED,
            albumId = albumId,
            updatedAt = now,
            lastHeartbeatAt = now,
            errorMessage = null
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun markArtworkRunningForAlbum(
        database: TamaDatabase,
        albumId: String,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRunByAlbumId(albumId) ?: return null
        if (run.status == STATUS_COMPLETED || run.status == STATUS_FAILED || run.status == STATUS_CANCELLED) {
            return run
        }
        val updated = run.copy(
            status = STATUS_ARTWORK_RUNNING,
            stage = STAGE_ARTWORK,
            updatedAt = now,
            lastHeartbeatAt = now
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun markCompleted(
        database: TamaDatabase,
        runId: String,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRun(runId) ?: return null
        val updated = run.copy(
            status = STATUS_COMPLETED,
            stage = STAGE_COMPLETED,
            ownsLocalLlama = false,
            updatedAt = now,
            lastHeartbeatAt = now,
            errorMessage = null
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun markCancelled(
        database: TamaDatabase,
        runId: String,
        errorMessage: String?,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRun(runId) ?: return null
        clearPendingAlbumIfNeeded(dao, run.petId, run.albumId)
        val updated = run.copy(
            status = STATUS_CANCELLED,
            stage = STAGE_CANCELLED,
            ownsLocalLlama = false,
            updatedAt = now,
            lastHeartbeatAt = now,
            errorMessage = errorMessage
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun markFailed(
        database: TamaDatabase,
        runId: String,
        errorMessage: String?,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val run = dao.getDeepDreamRun(runId) ?: return null
        clearPendingAlbumIfNeeded(dao, run.petId, run.albumId)
        val updated = run.copy(
            status = STATUS_FAILED,
            stage = STAGE_FAILED,
            ownsLocalLlama = false,
            updatedAt = now,
            lastHeartbeatAt = now,
            errorMessage = errorMessage
        )
        dao.saveDeepDreamRun(updated)
        return updated
    }

    suspend fun getWaitingAppOpenRunForPet(
        database: TamaDatabase,
        petId: String,
        now: Long = System.currentTimeMillis()
    ): TamaDeepDreamRunEntity? {
        val waitingRuns = database.tamaDao()
            .getDeepDreamRunsForPetByStatuses(petId, listOf(STATUS_WAITING_APP_OPEN))
        return waitingRuns.firstOrNull { !isRunStale(it, now) }
    }

    suspend fun getRunBySignature(
        database: TamaDatabase,
        petId: String,
        signature: String
    ): TamaDeepDreamRunEntity? {
        return database.tamaDao().getDeepDreamRunBySignature(petId, signature)
    }

    suspend fun reconcileRunsForPet(
        context: Context,
        database: TamaDatabase,
        petId: String,
        now: Long = System.currentTimeMillis(),
        startArtworkIfNeeded: Boolean = false
    ): List<TamaDeepDreamRunEntity> {
        val dao = database.tamaDao()
        val runs = dao.getDeepDreamRunsForPetByStatuses(petId, ACTIVE_STATUSES)
        val reconciled = mutableListOf<TamaDeepDreamRunEntity>()
        runs.forEach { run ->
            val updated = when (run.status) {
                STATUS_QUEUED, STATUS_ARTWORK_RUNNING -> reconcileAlbumState(
                    context = context,
                    database = database,
                    run = run,
                    now = now,
                    startArtworkIfNeeded = startArtworkIfNeeded
                )
                STATUS_WAITING_APP_OPEN -> {
                    if (isRunStale(run, now)) {
                        markCancelled(
                            database,
                            run.id,
                            context.getString(R.string.tama_deep_dream_error_retry_expired),
                            now
                        )
                    } else {
                        run
                    }
                }
                STATUS_PLANNING -> {
                    if (isRunStale(run, now)) {
                        markFailed(
                            database,
                            run.id,
                            context.getString(R.string.tama_deep_dream_error_planning_stalled),
                            now
                        )
                    } else {
                        run
                    }
                }
                else -> run
            } ?: run
            reconciled += updated
        }
        return reconciled
    }

    suspend fun reconcileAlbumState(
        context: Context,
        database: TamaDatabase,
        run: TamaDeepDreamRunEntity,
        now: Long = System.currentTimeMillis(),
        startArtworkIfNeeded: Boolean = false
    ): TamaDeepDreamRunEntity? {
        val dao = database.tamaDao()
        val albumId = run.albumId
        if (albumId.isNullOrBlank()) {
            return if (isRunStale(run, now)) {
                markFailed(
                    database,
                    run.id,
                    context.getString(R.string.tama_deep_dream_error_album_not_queued),
                    now
                )
            } else {
                run
            }
        }

        val albumArtworks = dao.getArtworks(run.petId).filter { it.albumId == albumId }
        if (albumArtworks.isEmpty()) {
            return if (isRunStale(run, now)) {
                markFailed(
                    database,
                    run.id,
                    context.getString(R.string.tama_deep_dream_error_album_missing_slides),
                    now
                )
            } else {
                run
            }
        }

        if (albumArtworks.any { it.status == TamaArtworkStatus.FAILED.name }) {
            return markFailed(
                database,
                run.id,
                albumArtworks.firstOrNull { it.status == TamaArtworkStatus.FAILED.name }?.errorMessage
                    ?: context.getString(R.string.tama_deep_dream_error_slide_failed),
                now
            )
        }

        val completedCount = albumArtworks.count { it.status == TamaArtworkStatus.COMPLETED.name }
        if (completedCount >= 4 && albumArtworks.all { it.status == TamaArtworkStatus.COMPLETED.name }) {
            return markCompleted(database, run.id, now)
        }

        val hasPendingArtwork = albumArtworks.any {
            it.status == TamaArtworkStatus.QUEUED.name || it.status == TamaArtworkStatus.GENERATING.name
        }
        if (hasPendingArtwork) {
            val desiredStatus = if (albumArtworks.any { it.status == TamaArtworkStatus.GENERATING.name }) {
                STATUS_ARTWORK_RUNNING
            } else {
                STATUS_QUEUED
            }
            val updated = run.copy(
                status = desiredStatus,
                stage = STAGE_ARTWORK,
                updatedAt = now,
                lastHeartbeatAt = now
            )
            dao.saveDeepDreamRun(updated)
            if (startArtworkIfNeeded) {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    TamaArtworkGenerationService.createProcessQueueIntent(context.applicationContext)
                )
            }
            return updated
        }

        return if (isRunStale(run, now)) {
            markFailed(
                database,
                run.id,
                context.getString(R.string.tama_deep_dream_error_artwork_stalled),
                now
            )
        } else {
            run
        }
    }

    private suspend fun clearPendingAlbumIfNeeded(
        dao: com.example.llamadroid.tama.db.TamaDao,
        petId: String,
        albumId: String?
    ) {
        if (albumId.isNullOrBlank()) return
        val pet = dao.getPet(petId)?.let(PetMapper::toDomain) ?: return
        if (pet.pendingDreamAlbumId != albumId) return
        dao.savePet(PetMapper.toEntity(pet.copy(pendingDreamAlbumId = null)))
    }
}
