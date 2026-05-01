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
        private const val CROP_DECAY_AFTER_MATURE_MS = 15 * 3600000L
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
            if (updated != original) repository.saveTile(petId, updated, rescheduleNotifications = false)
        }

        val upgrades = repository.getUpgrades(petId)
        upgrades.forEach { upgrade ->
            val updated = updateUpgrade(upgrade, now)
            if (updated != upgrade) repository.saveUpgrade(updated, rescheduleNotifications = false)
        }

        val livestock = repository.getLivestock(petId)
        livestock.forEach { structure ->
            val updated = updateLivestock(structure, now)
            if (updated != structure) repository.saveLivestock(updated, rescheduleNotifications = false)
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
            if (now >= lastUpdate + CROP_DECAY_AFTER_MATURE_MS) {
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
        val state = repository.decodeWellState(well, now)
        val interval = wellProductionIntervalForSpeedLevel(state.speedLevel)
        val updatedSlots = state.slots.map { slot ->
            when {
                slot.hasWater -> slot
                slot.cycleStartedAt == null -> slot.copy(cycleStartedAt = now)
                now >= slot.cycleStartedAt + interval -> slot.copy(hasWater = true)
                else -> slot
            }
        }
        val encodedState = Json.encodeToString(state.copy(slots = updatedSlots))
        val stored = updatedSlots.count { it.hasWater }
        val nextCycleStart = updatedSlots.firstOrNull { !it.hasWater }?.cycleStartedAt ?: well.lastProductionTime
        return if (
            stored != well.storedOutput ||
            encodedState != (well.extraDataJson ?: "") ||
            nextCycleStart != well.lastProductionTime
        ) {
            well.copy(
                lastProductionTime = nextCycleStart,
                storedOutput = stored,
                extraDataJson = encodedState
            )
        } else {
            well
        }
    }

    private fun updateComposter(composter: FarmUpgradeEntity, now: Long): FarmUpgradeEntity {
        if (!composter.isPurchased) return composter
        
        val capacity = composterSlotCapacityForLevel(composter.level)
        val slots = try {
            Json.decodeFromString<List<ComposterSlot>>(composter.extraDataJson ?: "[]")
        } catch (_: Exception) {
            runCatching {
                Json.decodeFromString<List<Long?>>(composter.extraDataJson ?: "[]")
                    .map { startedAt ->
                        if (startedAt == null) {
                            ComposterSlot()
                        } else {
                            ComposterSlot(
                                state = if (now >= startedAt + FARM_COMPOSTER_PROCESS_MS) ComposterSlotState.READY else ComposterSlotState.PROCESSING,
                                startedAt = startedAt,
                                readyAt = (startedAt + FARM_COMPOSTER_PROCESS_MS).takeIf { now >= it },
                                inputItemId = "rotten_crop"
                            )
                        }
                    }
            }.getOrElse {
                List(capacity) { ComposterSlot() }
            }
        }.let { decoded ->
            val trimmed = if (decoded.size >= capacity) decoded.take(capacity) else decoded
            trimmed + List((capacity - trimmed.size).coerceAtLeast(0)) { ComposterSlot() }
        }

        val updatedSlots = slots.map { slot ->
            if (slot.state == ComposterSlotState.PROCESSING && slot.startedAt != null && now >= slot.startedAt + FARM_COMPOSTER_PROCESS_MS) {
                slot.copy(
                    state = ComposterSlotState.READY,
                    readyAt = slot.startedAt + FARM_COMPOSTER_PROCESS_MS
                )
            } else {
                slot
            }
        }
        val readyCount = updatedSlots.count { it.state == ComposterSlotState.READY }
        val encodedSlots = Json.encodeToString(updatedSlots)

        return if (readyCount != composter.storedOutput || encodedSlots != (composter.extraDataJson ?: "")) {
            composter.copy(
                storedOutput = readyCount,
                extraDataJson = encodedSlots
            )
        } else {
            composter
        }
    }

    private fun updateLivestock(
        livestock: FarmLivestockEntity,
        now: Long
    ): FarmLivestockEntity {
        val type = FarmLivestockType.fromId(livestock.type) ?: return livestock
        val slots = repository.decodeLivestockSlots(livestock, type)
        val updatedSlots = slots.map { slot ->
            if (!slot.occupied || slot.lastProductionTime == null) {
                slot
            } else {
                val feedDueAt = (slot.lastFedAt ?: slot.lastProductionTime) + LIVESTOCK_FEED_INTERVAL_MS
                val productionWindowEnd = minOf(now, feedDueAt)
                val timePassed = (productionWindowEnd - slot.lastProductionTime).coerceAtLeast(0L)
                val unitsProduced = (timePassed / type.productionIntervalMs).toInt()
                if (unitsProduced <= 0) {
                    slot
                } else {
                    slot.copy(
                        storedOutput = minOf(type.perAnimalStorageCap, slot.storedOutput + unitsProduced),
                        lastProductionTime = slot.lastProductionTime + (unitsProduced * type.productionIntervalMs)
                    )
                }
            }
        }
        val encoded = Json.encodeToString(updatedSlots)
        return if (encoded != livestock.slotsJson) {
            livestock.copy(slotsJson = encoded)
        } else {
            livestock
        }
    }
}
