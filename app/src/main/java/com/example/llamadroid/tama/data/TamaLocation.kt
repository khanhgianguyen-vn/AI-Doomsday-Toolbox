package com.example.llamadroid.tama.data

import java.util.UUID

/**
 * Location in the Tama world.
 */
data class TamaLocation(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: LocationType,
    val description: String,
    val cityId: String,  // Which city this location belongs to
    val x: Int = 0,      // Position in city grid
    val y: Int = 0,
    val isDiscovered: Boolean = false,
    val npcIds: List<String> = emptyList(),  // NPCs at this location
    val shopInventory: List<String>? = null,  // Item IDs if it's a shop
    val jobs: List<Job>? = null  // Available jobs if workplace
)

enum class LocationType(val emoji: String, val displayName: String, val description: String, val asciiArt: String) {
    HOME("🏠", "Home", "Your cozy home. Rest and recover here.", """
     /\
    /  \
   /____\
   |[][]|
   |____|
    """.trimIndent()),
    
    SCHOOL("🏫", "School", "Study to increase intelligence.", """
     ___
    |SCH|
    |OOL|
    |___|
   /|   |\
    """.trimIndent()),
    
    WORKPLACE("🏢", "Office", "Work to earn money.", """
    _____
   |WORK|
   |    |
   |    |
   |____|
    """.trimIndent()),
    
    SHOP("🏪", "Shop", "Buy food and items.", """
     ____
    |SHOP|
    | $$ |
    |____|
    """.trimIndent()),
    
    PARK("🌳", "Park", "Relax and meet new friends.", """
      ^
     /|\
    / | \
     _|_
    |___|
    """.trimIndent()),
    
    HOSPITAL("🏥", "Hospital", "Heal sickness and injuries.", """
     _+_
    |   |
    | + |
    |___|
    """.trimIndent()),
    
    FARM("🌾", "Farm", "Plant and harvest crops.", """
    ~ ~ ~
   ~~~~~~
   |FARM|
   |____|
    """.trimIndent()),
    
    DUNGEON("🏚️", "Dungeon", "Dangerous adventures await!", """
     ___
    |DNG|
    | ? |
    |___|
    """.trimIndent())
}

/**
 * Job available at a workplace.
 */
data class Job(
    val id: String,
    val title: String,
    val requiredEducation: Int,  // Min education level
    val hourlyPay: Long,
    val energyCostPerHour: Int,
    val maxHoursPerDay: Int = 8
)

/**
 * City in the procedurally generated world.
 */
data class TamaCity(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val seed: Long,  // For procedural generation
    val x: Int,      // World position
    val y: Int,
    val isDiscovered: Boolean = false,
    val locationIds: List<String> = emptyList()
)
