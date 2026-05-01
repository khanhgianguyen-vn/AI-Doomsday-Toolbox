package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.GrowthStage
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.data.canWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamaGrowthTimingTest {
    @Test
    fun `growth stages use explicit per stage durations`() {
        assertEquals(60_000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.EGG))
        assertEquals(60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.BABY))
        assertEquals(48L * 60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.CHILD))
        assertEquals(48L * 60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.TEEN))
        assertEquals(48L * 60L * 60L * 1000L, GrowthStage.durationUntilNextStageMillis(GrowthStage.ADULT))
        assertNull(GrowthStage.durationUntilNextStageMillis(GrowthStage.SENIOR))
    }

    @Test
    fun `pet mapper preserves lifetime timer stage timer and growth lock pause state`() {
        val pet = TamaPet(
            id = "pet-1",
            name = "Peque",
            birthTimestamp = 1_000L,
            stageProgressStartTime = 9_000L,
            growthLocked = true,
            growthLockStartedAt = 12_000L,
            stage = GrowthStage.TEEN
        )

        val roundTrip = PetMapper.toDomain(PetMapper.toEntity(pet))

        assertEquals(1_000L, roundTrip.birthTimestamp)
        assertEquals(9_000L, roundTrip.stageProgressStartTime)
        assertEquals(12_000L, roundTrip.growthLockStartedAt)
        assertEquals(GrowthStage.TEEN, roundTrip.stage)
    }

    @Test
    fun `senior pets can work while earlier stages stay blocked`() {
        assertFalse(GrowthStage.EGG.canWork())
        assertFalse(GrowthStage.BABY.canWork())
        assertFalse(GrowthStage.CHILD.canWork())
        assertTrue(GrowthStage.TEEN.canWork())
        assertTrue(GrowthStage.ADULT.canWork())
        assertTrue(GrowthStage.SENIOR.canWork())
    }
}
