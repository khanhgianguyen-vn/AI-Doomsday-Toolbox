package com.example.llamadroid.tama.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetSpeciesLineTest {
    @Test
    fun `new pet species stays unchanged`() {
        assertEquals("dragon", normalizePetSpecies("dragon", legacyBodyStyle = 2))
        assertEquals("unicorn", normalizePetSpecies("unicorn", legacyBodyStyle = 0))
        assertEquals("kitsune", normalizePetSpecies("kitsune", legacyBodyStyle = 1))
    }

    @Test
    fun `legacy creature species maps deterministically from body style`() {
        assertEquals("dragon", normalizePetSpecies("creature", legacyBodyStyle = 0))
        assertEquals("unicorn", normalizePetSpecies("creature", legacyBodyStyle = 1))
        assertEquals("kitsune", normalizePetSpecies("creature", legacyBodyStyle = 2))
        assertEquals("dragon", normalizePetSpecies("creature", legacyBodyStyle = 3))
    }

    @Test
    fun `asset path matches species stage state and frame`() {
        assertEquals(
            "tama/pets/unicorn/teen/walk_1.png",
            resolvePetSpriteAssetPath(
                speciesLine = PetSpeciesLine.UNICORN,
                stage = GrowthStage.TEEN,
                state = PetSpriteState.WALK,
                frameIndex = 1
            )
        )
        assertEquals(
            "tama/pets/kitsune/adult/sleep_0.png",
            resolvePetSpriteAssetPath(
                speciesLine = PetSpeciesLine.KITSUNE,
                stage = GrowthStage.ADULT,
                state = PetSpriteState.SLEEP,
                frameIndex = 9
            )
        )
    }

    @Test
    fun `all current Tama actions resolve to a valid sprite state`() {
        TamaSpriteSupportedActions.forEach { action ->
            val resolved = mapPetActionToSpriteState(action, isSleeping = false)
            assertTrue(
                "Unexpected sprite state for action $action",
                resolved in setOf(PetSpriteState.IDLE, PetSpriteState.WALK, PetSpriteState.SLEEP, PetSpriteState.EAT)
            )
        }
        assertEquals(PetSpriteState.SLEEP, mapPetActionToSpriteState("playing", isSleeping = true))
    }
}
