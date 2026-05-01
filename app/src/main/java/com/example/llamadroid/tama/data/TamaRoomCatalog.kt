package com.example.llamadroid.tama.data

import android.content.Context
import com.example.llamadroid.R

data class TamaRoomDefinition(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val price: Int,
    val assetPath: String
)

object TamaRoomCatalog {
    const val PRINCIPAL_ROOM_ID = "principal_room"
    const val ARCADE_ROOM_ID = "arcade_room"
    const val GARDEN_ROOM_ID = "garden_room"
    const val DREAM_ROOM_ID = "dream_room"
    const val KITCHEN_ROOM_ID = "kitchen_room"
    const val OBSERVATORY_ROOM_ID = "observatory_room"
    const val ART_STUDIO_ROOM_ID = "art_studio_room"
    const val MODERN_LOFT_ROOM_ID = "modern_loft_room"

    val rooms: List<TamaRoomDefinition> = listOf(
        TamaRoomDefinition(
            id = PRINCIPAL_ROOM_ID,
            titleRes = R.string.tama_room_principal_title,
            descriptionRes = R.string.tama_room_principal_desc,
            price = 0,
            assetPath = "tama/backgrounds/principal_room.png"
        ),
        TamaRoomDefinition(
            id = ARCADE_ROOM_ID,
            titleRes = R.string.tama_room_arcade_title,
            descriptionRes = R.string.tama_room_arcade_desc,
            price = 3000,
            assetPath = "tama/backgrounds/arcade_room.png"
        ),
        TamaRoomDefinition(
            id = GARDEN_ROOM_ID,
            titleRes = R.string.tama_room_garden_title,
            descriptionRes = R.string.tama_room_garden_desc,
            price = 3000,
            assetPath = "tama/backgrounds/garden_room.png"
        ),
        TamaRoomDefinition(
            id = DREAM_ROOM_ID,
            titleRes = R.string.tama_room_dream_title,
            descriptionRes = R.string.tama_room_dream_desc,
            price = 3000,
            assetPath = "tama/backgrounds/library_room.png"
        ),
        TamaRoomDefinition(
            id = KITCHEN_ROOM_ID,
            titleRes = R.string.tama_room_kitchen_title,
            descriptionRes = R.string.tama_room_kitchen_desc,
            price = 3000,
            assetPath = "tama/backgrounds/kitchen_room.png"
        ),
        TamaRoomDefinition(
            id = OBSERVATORY_ROOM_ID,
            titleRes = R.string.tama_room_observatory_title,
            descriptionRes = R.string.tama_room_observatory_desc,
            price = 3000,
            assetPath = "tama/backgrounds/observatory_room.png"
        ),
        TamaRoomDefinition(
            id = ART_STUDIO_ROOM_ID,
            titleRes = R.string.tama_room_art_studio_title,
            descriptionRes = R.string.tama_room_art_studio_desc,
            price = 3000,
            assetPath = "tama/backgrounds/art_studio_room.png"
        ),
        TamaRoomDefinition(
            id = MODERN_LOFT_ROOM_ID,
            titleRes = R.string.tama_room_modern_loft_title,
            descriptionRes = R.string.tama_room_modern_loft_desc,
            price = 3000,
            assetPath = "tama/backgrounds/modern_loft_room.png"
        )
    )

    fun roomById(roomId: String?): TamaRoomDefinition? =
        rooms.firstOrNull { it.id.equals(roomId, ignoreCase = true) }

    fun isRoomId(roomId: String?): Boolean = roomById(roomId) != null

    fun homeRoomAssetPath(roomId: String?): String =
        roomById(roomId)?.assetPath ?: rooms.first { it.id == PRINCIPAL_ROOM_ID }.assetPath

    fun roomInventoryItem(context: Context, roomId: String): InventoryItem? {
        val room = roomById(roomId) ?: return null
        return InventoryItem(
            id = room.id,
            name = context.getString(room.titleRes),
            type = ItemType.DECORATION,
            quantity = 1
        )
    }
}
