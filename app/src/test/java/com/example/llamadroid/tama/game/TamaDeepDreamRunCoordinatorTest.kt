package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.db.TamaDeepDreamRunEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaDeepDreamRunCoordinatorTest {
    private fun run(
        status: String,
        heartbeatAt: Long,
        signature: String = "deep:2026-01-10:12345"
    ): TamaDeepDreamRunEntity {
        return TamaDeepDreamRunEntity(
            id = "run-1",
            petId = "pet-1",
            signature = signature,
            dreamDate = "2026-01-10",
            status = status,
            stage = TamaDeepDreamRunCoordinator.STAGE_PREPARING,
            albumId = null,
            ownsLocalLlama = false,
            startedAt = heartbeatAt,
            updatedAt = heartbeatAt,
            lastHeartbeatAt = heartbeatAt,
            errorMessage = null
        )
    }

    @Test
    fun `planning run becomes stale after ninety minutes without heartbeat`() {
        val now = 10_000_000L
        val staleRun = run(
            status = TamaDeepDreamRunCoordinator.STATUS_PLANNING,
            heartbeatAt = now - (91L * 60L * 1000L)
        )

        assertTrue(TamaDeepDreamRunCoordinator.isRunStale(staleRun, now))
    }

    @Test
    fun `artwork run becomes stale after six hours without heartbeat`() {
        val now = 20_000_000L
        val staleRun = run(
            status = TamaDeepDreamRunCoordinator.STATUS_ARTWORK_RUNNING,
            heartbeatAt = now - (6L * 60L * 60L * 1000L) - 1L
        )

        assertTrue(TamaDeepDreamRunCoordinator.isRunStale(staleRun, now))
    }

    @Test
    fun `waiting app open run becomes stale after twenty four hours without heartbeat`() {
        val now = 30_000_000L
        val staleRun = run(
            status = TamaDeepDreamRunCoordinator.STATUS_WAITING_APP_OPEN,
            heartbeatAt = now - (24L * 60L * 60L * 1000L) - 1L
        )

        assertTrue(TamaDeepDreamRunCoordinator.isRunStale(staleRun, now))
    }

    @Test
    fun `completed run suppresses alert for same signature`() {
        val run = run(
            status = TamaDeepDreamRunCoordinator.STATUS_COMPLETED,
            heartbeatAt = 1_000L
        )

        assertTrue(TamaDeepDreamRunCoordinator.shouldSuppressAlert(run, run.signature, now = 2_000L))
    }

    @Test
    fun `failed run does not suppress alert for same signature`() {
        val run = run(
            status = TamaDeepDreamRunCoordinator.STATUS_FAILED,
            heartbeatAt = 1_000L
        )

        assertFalse(TamaDeepDreamRunCoordinator.shouldSuppressAlert(run, run.signature, now = 2_000L))
    }
}
