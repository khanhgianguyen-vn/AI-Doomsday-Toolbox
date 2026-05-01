package com.example.llamadroid.tama.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FarmUtilityConfigTest {
    @Test
    fun `well capacity ladder matches requested prices`() {
        assertEquals(200, wellCapacityUpgradeCostForLevel(1))
        assertEquals(300, wellCapacityUpgradeCostForLevel(2))
        assertEquals(400, wellCapacityUpgradeCostForLevel(3))
        assertEquals(500, wellCapacityUpgradeCostForLevel(4))
        assertEquals(600, wellCapacityUpgradeCostForLevel(5))
        assertEquals(700, wellCapacityUpgradeCostForLevel(6))
        assertEquals(800, wellCapacityUpgradeCostForLevel(7))
        assertEquals(900, wellCapacityUpgradeCostForLevel(8))
    }

    @Test
    fun `composter capacity ladder is double the well ladder`() {
        assertEquals(400, composterCapacityUpgradeCostForLevel(1))
        assertEquals(600, composterCapacityUpgradeCostForLevel(2))
        assertEquals(800, composterCapacityUpgradeCostForLevel(3))
        assertEquals(1000, composterCapacityUpgradeCostForLevel(4))
        assertEquals(1200, composterCapacityUpgradeCostForLevel(5))
        assertEquals(1400, composterCapacityUpgradeCostForLevel(6))
        assertEquals(1600, composterCapacityUpgradeCostForLevel(7))
        assertEquals(1800, composterCapacityUpgradeCostForLevel(8))
    }

    @Test
    fun `well speed ladder matches requested prices and intervals`() {
        assertEquals(500, wellSpeedUpgradeCostForLevel(0))
        assertEquals(1000, wellSpeedUpgradeCostForLevel(1))
        assertEquals(1500, wellSpeedUpgradeCostForLevel(2))
        assertEquals(2000, wellSpeedUpgradeCostForLevel(3))
        assertNull(wellSpeedUpgradeCostForLevel(4))

        assertEquals(8, wellIntervalHoursForSpeedLevel(0))
        assertEquals(7, wellIntervalHoursForSpeedLevel(1))
        assertEquals(6, wellIntervalHoursForSpeedLevel(2))
        assertEquals(5, wellIntervalHoursForSpeedLevel(3))
        assertEquals(4, wellIntervalHoursForSpeedLevel(4))
    }
}
