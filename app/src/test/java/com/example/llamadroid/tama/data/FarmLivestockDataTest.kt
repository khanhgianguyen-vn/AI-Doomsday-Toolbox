package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmLivestockDataTest {
    @Test
    fun `livestock slot becomes hungry after twenty four hours`() {
        val slot = FarmLivestockSlot(
            occupied = true,
            storedOutput = 0,
            lastProductionTime = 1_000L,
            lastFedAt = 1_000L
        )

        assertFalse(livestockNeedsFeed(slot, now = 1_000L + LIVESTOCK_FEED_INTERVAL_MS - 1))
        assertTrue(livestockNeedsFeed(slot, now = 1_000L + LIVESTOCK_FEED_INTERVAL_MS))
    }

    @Test
    fun `hungry livestock count only includes occupied hungry animals`() {
        val slots = listOf(
            FarmLivestockSlot(occupied = true, lastProductionTime = 0L, lastFedAt = 0L),
            FarmLivestockSlot(occupied = true, lastProductionTime = 0L, lastFedAt = LIVESTOCK_FEED_INTERVAL_MS),
            FarmLivestockSlot(occupied = false, lastProductionTime = null, lastFedAt = null)
        )

        assertEquals(1, hungryLivestockCount(slots, now = LIVESTOCK_FEED_INTERVAL_MS))
    }

    @Test
    fun `next feed due uses earliest occupied animal`() {
        val slots = listOf(
            FarmLivestockSlot(occupied = true, lastProductionTime = 0L, lastFedAt = 20_000L),
            FarmLivestockSlot(occupied = true, lastProductionTime = 0L, lastFedAt = 5_000L)
        )

        assertEquals(5_000L + LIVESTOCK_FEED_INTERVAL_MS, nextLivestockFeedDueAt(slots))
    }

    @Test
    fun `composter accepts rotten crops and normal crop trade items`() {
        assertTrue(FarmTradeItemCatalog.isCompostableCropItem("crop_wheat"))
        assertTrue(FarmTradeItemCatalog.isCompostableCropItem("crop_rose"))
        assertTrue(FarmTradeItemCatalog.isCompostableCropItem("rotten_crop"))

        assertFalse(FarmTradeItemCatalog.isCompostableCropItem("crop_rotten_crop"))
        assertFalse(FarmTradeItemCatalog.isCompostableCropItem("produce_milk"))
        assertFalse(FarmTradeItemCatalog.isCompostableCropItem("produce_egg"))
        assertFalse(FarmTradeItemCatalog.isCompostableCropItem("water"))
        assertFalse(FarmTradeItemCatalog.isCompostableCropItem("fertilizer"))
    }

    @Test
    fun `composter asset path resolves rotten crop sprite`() {
        assertEquals("farm/Others/rotten_crop.png", FarmTradeItemCatalog.compostableAssetPath("rotten_crop"))
        assertEquals("farm/Crops/stage_final/wheat.png", FarmTradeItemCatalog.compostableAssetPath("crop_wheat"))
    }
}
