package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R

data class TamaDecorDefinition(
    val id: String,
    val titleRes: Int,
    val price: Int,
    val assetPath: String
)

enum class TamaDecorSlot {
    LEFT,
    RIGHT
}

object TamaDecorCatalog {
    const val BALL_PLUSH_ID = "toy_ball_plush"
    const val BLOCK_TRAIN_ID = "toy_block_train"
    const val MINI_ROBOT_ID = "toy_mini_robot"
    const val ROCKING_HORSE_ID = "toy_rocking_horse"
    const val MUSIC_BOX_ID = "toy_music_box"
    const val TREASURE_CHEST_ID = "toy_treasure_chest"
    const val COZY_BEANBAG_ID = "toy_cozy_beanbag"
    const val MOON_LANTERN_ID = "toy_moon_lantern"
    const val CRYSTAL_TERRARIUM_ID = "toy_crystal_terrarium"
    const val MINI_BOOKSHELF_ID = "toy_mini_bookshelf"
    const val TINY_AQUARIUM_ID = "toy_tiny_aquarium"
    const val ROBOT_VACUUM_ID = "toy_robot_vacuum"

    val toys: List<TamaDecorDefinition> = listOf(
        TamaDecorDefinition(
            id = BALL_PLUSH_ID,
            titleRes = R.string.tama_toy_ball_plush,
            price = 250,
            assetPath = "tama/decor/ball_plush.png"
        ),
        TamaDecorDefinition(
            id = BLOCK_TRAIN_ID,
            titleRes = R.string.tama_toy_block_train,
            price = 400,
            assetPath = "tama/decor/block_train.png"
        ),
        TamaDecorDefinition(
            id = MINI_ROBOT_ID,
            titleRes = R.string.tama_toy_mini_robot,
            price = 550,
            assetPath = "tama/decor/mini_robot.png"
        ),
        TamaDecorDefinition(
            id = ROCKING_HORSE_ID,
            titleRes = R.string.tama_toy_rocking_horse,
            price = 750,
            assetPath = "tama/decor/rocking_horse.png"
        ),
        TamaDecorDefinition(
            id = MUSIC_BOX_ID,
            titleRes = R.string.tama_toy_music_box,
            price = 950,
            assetPath = "tama/decor/music_box.png"
        ),
        TamaDecorDefinition(
            id = TREASURE_CHEST_ID,
            titleRes = R.string.tama_toy_treasure_chest,
            price = 1200,
            assetPath = "tama/decor/treasure_chest.png"
        ),
        TamaDecorDefinition(
            id = COZY_BEANBAG_ID,
            titleRes = R.string.tama_toy_cozy_beanbag,
            price = 650,
            assetPath = "tama/decor/cozy_beanbag.png"
        ),
        TamaDecorDefinition(
            id = MOON_LANTERN_ID,
            titleRes = R.string.tama_toy_moon_lantern,
            price = 800,
            assetPath = "tama/decor/moon_lantern.png"
        ),
        TamaDecorDefinition(
            id = CRYSTAL_TERRARIUM_ID,
            titleRes = R.string.tama_toy_crystal_terrarium,
            price = 950,
            assetPath = "tama/decor/crystal_terrarium.png"
        ),
        TamaDecorDefinition(
            id = MINI_BOOKSHELF_ID,
            titleRes = R.string.tama_toy_mini_bookshelf,
            price = 700,
            assetPath = "tama/decor/mini_bookshelf.png"
        ),
        TamaDecorDefinition(
            id = TINY_AQUARIUM_ID,
            titleRes = R.string.tama_toy_tiny_aquarium,
            price = 1100,
            assetPath = "tama/decor/tiny_aquarium.png"
        ),
        TamaDecorDefinition(
            id = ROBOT_VACUUM_ID,
            titleRes = R.string.tama_toy_robot_vacuum,
            price = 900,
            assetPath = "tama/decor/robot_vacuum.png"
        )
    )

    fun decorById(itemId: String?): TamaDecorDefinition? {
        return toys.firstOrNull { it.id.equals(itemId, ignoreCase = true) }
    }

    fun isDecorId(itemId: String?): Boolean = decorById(itemId) != null

    fun decorInventoryItem(context: Context, decorId: String): InventoryItem? {
        val decor = decorById(decorId) ?: return null
        return InventoryItem(
            id = decor.id,
            name = context.getString(decor.titleRes),
            type = ItemType.TOY,
            quantity = 1
        )
    }
}
