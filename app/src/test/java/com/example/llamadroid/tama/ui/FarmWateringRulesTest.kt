package com.example.llamadroid.tama.ui

import com.example.llamadroid.tama.data.FarmTile
import com.example.llamadroid.tama.data.TileStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmWateringRulesTest {

    @Test
    fun `dry farmland can be watered`() {
        assertTrue(canWaterFarmTile(FarmTile(id = 1, status = TileStatus.FARMLAND)))
    }

    @Test
    fun `wet farmland can not be watered again`() {
        assertFalse(canWaterFarmTile(FarmTile(id = 1, status = TileStatus.WET_FARMLAND)))
    }

    @Test
    fun `plain soil can not be watered`() {
        assertFalse(canWaterFarmTile(FarmTile(id = 1, status = TileStatus.SOIL)))
    }
}
