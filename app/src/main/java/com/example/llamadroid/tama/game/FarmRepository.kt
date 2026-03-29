package com.example.llamadroid.tama.game

import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class FarmRepository(private val farmDao: FarmDao) {

    fun observeTiles(petId: String): Flow<List<FarmTile>> {
        return farmDao.observeTiles(petId).map { entities ->
            entities.map { entityToTile(it) }
        }
    }

    suspend fun getTiles(petId: String): List<FarmTile> {
        return farmDao.getTiles(petId).map { entityToTile(it) }
    }

    fun observeUpgrades(petId: String): Flow<List<FarmUpgradeEntity>> {
        return farmDao.observeUpgrades(petId)
    }

    suspend fun saveTile(petId: String, tile: FarmTile) {
        farmDao.saveTile(tileToEntity(petId, tile))
    }

    suspend fun getUpgrades(petId: String): List<FarmUpgradeEntity> {
        return farmDao.getUpgrades(petId)
    }

    suspend fun getUpgrade(petId: String, type: String): FarmUpgradeEntity? {
        return farmDao.getUpgrade(petId, type)
    }

    suspend fun saveUpgrade(upgrade: FarmUpgradeEntity) {
        farmDao.saveUpgrade(upgrade)
    }

    suspend fun buyUpgrade(petId: String, type: String, price: Int) {
        val existing = farmDao.getUpgrade(petId, type)
        if (existing == null) {
            farmDao.saveUpgrade(FarmUpgradeEntity(type = type, petId = petId, isPurchased = true))
        } else {
            farmDao.saveUpgrade(existing.copy(isPurchased = true))
        }
    }

    suspend fun consumeWater(petId: String): Boolean {
        val well = farmDao.getUpgrade(petId, "well") ?: return false
        if (!well.isPurchased || well.storedOutput <= 0) return false
        
        farmDao.saveUpgrade(well.copy(storedOutput = well.storedOutput - 1))
        return true
    }

    private fun entityToTile(entity: FarmTileEntity): FarmTile {
        return FarmTile(
            id = entity.id,
            status = TileStatus.valueOf(entity.status),
            crop = entity.cropJson?.let { Json.decodeFromString<PlantedCrop>(it) },
            lastWateredTime = entity.lastWateredTime
        )
    }

    private fun tileToEntity(petId: String, tile: FarmTile): FarmTileEntity {
        return FarmTileEntity(
            id = tile.id,
            petId = petId,
            status = tile.status.name,
            cropJson = tile.crop?.let { Json.encodeToString(it) },
            lastWateredTime = tile.lastWateredTime
        )
    }
}
