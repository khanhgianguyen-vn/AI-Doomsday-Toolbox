package com.example.llamadroid.data.binary

import org.junit.Assert.assertEquals
import org.junit.Test

class BinaryRepositoryTest {

    @Test
    fun buildBinarySearchTiers_prefersBaselineButFallsBackToInstalledDeviceTier() {
        assertEquals(
            listOf("baseline", "dotprod"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "baseline", deviceTier = "dotprod")
        )
    }

    @Test
    fun buildBinarySearchTiers_keepsArmv9FallbackOrderWhenBaselineMissing() {
        assertEquals(
            listOf("baseline", "armv9", "dotprod"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "baseline", deviceTier = "armv9")
        )
    }

    @Test
    fun buildBinarySearchTiers_preservesNormalDeviceTierFallbackChain() {
        assertEquals(
            listOf("armv9", "dotprod", "baseline"),
            BinaryRepository.buildBinarySearchTiers(selectedTier = "armv9", deviceTier = "armv9")
        )
    }
}
