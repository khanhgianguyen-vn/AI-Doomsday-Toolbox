package com.example.llamadroid.tama.ui

import com.example.llamadroid.tama.data.PlantedCrop
import org.junit.Assert.assertEquals
import org.junit.Test

class FarmTileScaleTest {

    @Test
    fun `farm tile scales get larger as crops mature`() {
        assertEquals(0.68f, farmTileScaleForCrop(crop(stage = 0)), 0.0001f)
        assertEquals(0.80f, farmTileScaleForCrop(crop(stage = 1)), 0.0001f)
        assertEquals(0.94f, farmTileScaleForCrop(crop(stage = 2)), 0.0001f)
        assertEquals(1.04f, farmTileScaleForCrop(crop(stage = 3)), 0.0001f)
    }

    @Test
    fun `decayed crops use the dedicated decay scale`() {
        assertEquals(
            1.02f,
            farmTileScaleForCrop(crop(stage = 3, isDecayed = true)),
            0.0001f
        )
    }

    private fun crop(stage: Int, isDecayed: Boolean = false): PlantedCrop {
        return PlantedCrop(
            type = "wheat",
            stage = stage,
            plantedTime = 1_000L,
            lastStageUpdateTime = 1_000L,
            isDecayed = isDecayed
        )
    }
}
