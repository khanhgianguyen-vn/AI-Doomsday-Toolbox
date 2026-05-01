package com.example.llamadroid.tama.notifications

import com.example.llamadroid.tama.db.TamaDeepDreamRunEntity
import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.PetStats
import com.example.llamadroid.tama.data.PlantedCrop
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.game.TamaDeepDreamRunCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class TamaNotificationSchedulerTest {
    private fun deepDreamRun(
        status: String,
        signature: String
    ) = TamaDeepDreamRunEntity(
        id = "run-1",
        petId = "pet-1",
        signature = signature,
        dreamDate = "2026-01-10",
        status = status,
        stage = TamaDeepDreamRunCoordinator.STAGE_PREPARING,
        albumId = null,
        ownsLocalLlama = false,
        startedAt = 0L,
        updatedAt = 0L,
        lastHeartbeatAt = 0L,
        errorMessage = null
    )

    @Test
    fun `healthy pet schedules no immediate actionable alert`() {
        val pet = TamaPet(
            name = "Nori",
            stage = GrowthStage.CHILD,
            lastDecayTime = 1_000L,
            stats = PetStats(hunger = 80f, happiness = 75f, health = 100f, energy = 90f, hygiene = 85f)
        )

        val alert = TamaNotificationScheduler.computePetNeedAlert(pet, now = 1_000L)

        assertNotNull(alert)
        checkNotNull(alert)
        assertEquals("pet:happiness:32401000", alert.signature)
    }

    @Test
    fun `pet near threshold schedules earliest actionable stat`() {
        val pet = TamaPet(
            name = "Nori",
            stage = GrowthStage.CHILD,
            lastDecayTime = 0L,
            stats = PetStats(hunger = 31f, happiness = 50f, health = 100f, energy = 90f, hygiene = 40f)
        )

        val forecast = TamaNotificationScheduler.computePetNeedForecast(pet, now = 0L)

        assertNotNull(forecast)
        checkNotNull(forecast)
        assertEquals("hunger", forecast.statKey)
        assertEquals(720_000L, forecast.dueAtMillis)
    }

    @Test
    fun `egg pet does not schedule pet-need notification`() {
        val pet = TamaPet(name = "Nori", stage = GrowthStage.EGG)
        assertNull(TamaNotificationScheduler.computePetNeedAlert(pet, now = 0L))
    }

    @Test
    fun `crop ready alert uses earliest non decayed final-stage time`() {
        val planted = PlantedCrop(
            type = "wheat",
            stage = 1,
            plantedTime = 0L,
            lastStageUpdateTime = 3_600_000L
        )
        val tiles = listOf(
            FarmTile(id = 0, crop = planted),
            FarmTile(id = 1, crop = PlantedCrop(type = "melon", stage = 0, plantedTime = 0L, lastStageUpdateTime = 0L))
        )

        val alert = TamaNotificationScheduler.computeCropReadyAlert("pet-1", tiles, now = 0L)

        assertNotNull(alert)
        checkNotNull(alert)
        assertEquals(21_600_000L, alert.dueAtMillis)
    }

    @Test
    fun `deep dream alert keeps fixed eligibility due time even when now is later`() {
        val sleepStart = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 10, 22, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val pet = TamaPet(
            name = "Nori",
            stage = GrowthStage.ADULT,
            isSleeping = true,
            sleepStartTime = sleepStart
        )
        val now = sleepStart + (6 * 60 * 60 * 1000L)

        val alert = TamaNotificationScheduler.computeDeepDreamAlert(pet, now = now)

        assertNotNull(alert)
        checkNotNull(alert)
        assertEquals(sleepStart + (4 * 60 * 60 * 1000L), alert.dueAtMillis)
    }

    @Test
    fun `deep dream alert signature stays stable after eligibility time passes`() {
        val sleepStart = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 10, 22, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val pet = TamaPet(
            name = "Nori",
            stage = GrowthStage.ADULT,
            isSleeping = true,
            sleepStartTime = sleepStart
        )
        val alertAtDue = TamaNotificationScheduler.computeDeepDreamAlert(
            pet,
            now = sleepStart + (4 * 60 * 60 * 1000L)
        )
        val alertMuchLater = TamaNotificationScheduler.computeDeepDreamAlert(
            pet,
            now = sleepStart + (8 * 60 * 60 * 1000L)
        )

        assertNotNull(alertAtDue)
        assertNotNull(alertMuchLater)
        checkNotNull(alertAtDue)
        checkNotNull(alertMuchLater)
        assertEquals(alertAtDue.signature, alertMuchLater.signature)
        assertEquals(alertAtDue.dueAtMillis, alertMuchLater.dueAtMillis)
    }

    @Test
    fun `deep dream alert is suppressed when same signature run is completed`() {
        val sleepStart = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 10, 22, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val pet = TamaPet(
            id = "pet-1",
            name = "Nori",
            stage = GrowthStage.ADULT,
            isSleeping = true,
            sleepStartTime = sleepStart
        )
        val signature = "deep:2026-01-10:${sleepStart + (4 * 60 * 60 * 1000L)}"

        val alert = TamaNotificationScheduler.computeDeepDreamAlert(
            pet = pet,
            run = deepDreamRun(TamaDeepDreamRunCoordinator.STATUS_COMPLETED, signature),
            now = sleepStart + (5 * 60 * 60 * 1000L)
        )

        assertNull(alert)
    }

    @Test
    fun `deep dream alert is kept when same signature run failed`() {
        val sleepStart = Calendar.getInstance().apply {
            set(2026, Calendar.JANUARY, 10, 22, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val pet = TamaPet(
            id = "pet-1",
            name = "Nori",
            stage = GrowthStage.ADULT,
            isSleeping = true,
            sleepStartTime = sleepStart
        )
        val signature = "deep:2026-01-10:${sleepStart + (4 * 60 * 60 * 1000L)}"

        val alert = TamaNotificationScheduler.computeDeepDreamAlert(
            pet = pet,
            run = deepDreamRun(TamaDeepDreamRunCoordinator.STATUS_FAILED, signature),
            now = sleepStart + (5 * 60 * 60 * 1000L)
        )

        assertNotNull(alert)
        checkNotNull(alert)
        assertEquals(signature, alert.signature)
    }
}
