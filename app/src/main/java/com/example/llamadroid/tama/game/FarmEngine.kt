package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.json.JSONArray
import org.json.JSONObject

class FarmEngine(
    private val repository: FarmRepository
) {
    companion object {
        private const val EIGHT_HOURS = 8 * 3600000L
    }

    /**
     * Update all farm elements based on current time.
     * This handles "offline" progress.
     */
    suspend fun updateFarm(petId: String, now: Long = System.currentTimeMillis()) {
        val tiles = repository.getTiles(petId)
        val updatedTiles = tiles.map { updateTile(it, now) }
        // Compare each updated tile with its original using zip, not by ID index
        tiles.zip(updatedTiles).forEach { (original, updated) ->
            if (updated != original) repository.saveTile(petId, updated)
        }

        val upgrades = repository.getUpgrades(petId)
        upgrades.forEach { upgrade ->
            val updated = updateUpgrade(upgrade, now)
            if (updated != upgrade) repository.saveUpgrade(updated)
        }
    }

    private fun updateTile(tile: FarmTile, now: Long): FarmTile {
        val crop = tile.crop ?: return tile
        if (crop.isDecayed) return tile

        val definitions = CropDefinitions.CROPS[crop.type] ?: return tile
        var currentStage = crop.stage
        var lastUpdate = crop.lastStageUpdateTime
        var updated = false
        var decayed = false

        while (currentStage < 3) {
            val baseTime = definitions.stageTimes[currentStage]
            val timeToNext = if (crop.isFertilized) baseTime / 2 else baseTime
            
            if (now >= lastUpdate + timeToNext) {
                lastUpdate += timeToNext
                currentStage++
                updated = true
            } else {
                break
            }
        }

        // Check for decay if final stage
        if (currentStage == 3) {
            if (now >= lastUpdate + EIGHT_HOURS) {
                decayed = true
                updated = true
            }
        }

        return if (updated) {
            tile.copy(crop = crop.copy(
                stage = currentStage,
                lastStageUpdateTime = lastUpdate,
                isDecayed = decayed
            ))
        } else {
            tile
        }
    }

    private fun updateUpgrade(upgrade: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        return when (upgrade.type) {
            "well" -> updateWell(upgrade, now)
            "composter" -> updateComposter(upgrade, now)
            else -> upgrade
        }
    }

    private fun updateWell(well: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        if (!well.isPurchased) return well
        val timePassed = now - well.lastProductionTime
        val unitsProduced = (timePassed / EIGHT_HOURS).toInt()
        
        if (unitsProduced > 0) {
            val newOutput = minOf(5, well.storedOutput + unitsProduced)
            return well.copy(
                storedOutput = newOutput,
                lastProductionTime = well.lastProductionTime + (unitsProduced * EIGHT_HOURS)
            )
        }
        return well
    }

    private fun updateComposter(composter: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        if (!composter.isPurchased) return composter
        
        // extraDataJson stores a list of start times for the 5 slots
        val slots = try { 
            Json.decodeFromString<List<Long?>>(composter.extraDataJson ?: "[]") 
        } catch(e: Exception) { listOf(null, null, null, null, null) }
        
        var producedFertilizers = 0
        val updatedSlots = slots.map { startTime ->
            if (startTime != null && now >= startTime + EIGHT_HOURS) {
                producedFertilizers++
                null
            } else {
                startTime
            }
        }

        return if (producedFertilizers > 0) {
            composter.copy(
                storedOutput = composter.storedOutput + producedFertilizers,
                extraDataJson = Json.encodeToString(updatedSlots)
            )
        } else {
            composter
        }
    }
}
