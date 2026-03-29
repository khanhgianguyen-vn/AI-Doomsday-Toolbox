package com.example.llamadroid.tama.data

import java.util.UUID

/**
 * Non-player character that the pet can interact with.
 */
data class TamaNpc(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val species: String = "creature",
    val personality: Personality = Personality.random(),
    val genetics: GeneticTraits = GeneticTraits.random(),
    val homeLocationId: String,
    val currentLocationId: String,
    val job: String? = null,
    val age: Int = 20,  // In "tama years"
    val marriedToPetId: String? = null,
    val childrenIds: List<String> = emptyList(),
    val likes: List<String> = emptyList(),   // Item IDs they like
    val dislikes: List<String> = emptyList()
) {
    /**
     * Generate a greeting based on personality.
     */
    fun getGreeting(): String = when (personality) {
        Personality.CHEERFUL -> "Hi there! So happy to see you! 😄"
        Personality.SHY -> "Oh... h-hello... 👋"
        Personality.PLAYFUL -> "Heyyy! Wanna play? 🎮"
        Personality.LAZY -> "Oh hey... *yawn* 😪"
        Personality.CURIOUS -> "Ooh! What's that you have? 🔍"
        Personality.BRAVE -> "Greetings, friend! Ready for adventure? ⚔️"
    }
    
    /**
     * Get ASCII representation.
     */
    fun getAscii(): String = """
       oo
      (${personality.ordinal % 2 == 0}.${personality.ordinal % 2 == 0})
       <>
      /||\
    """.trimIndent()
}

/**
 * Item that can be owned, bought, sold, or gifted.
 */
data class TamaItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: ItemType,
    val description: String,
    val price: Long,
    val sellPrice: Long = price / 2,
    val effects: Map<String, Int> = emptyMap(),  // Stat changes when used
    val isEquipable: Boolean = false,
    val rpgStats: Map<String, Int>? = null,  // For equipment
    val rarity: Rarity = Rarity.COMMON,
    val ascii: String = "[ ]"
)

enum class Rarity(val color: String, val prefix: String) {
    COMMON("gray", ""),
    UNCOMMON("green", "Fine"),
    RARE("blue", "Rare"),
    EPIC("purple", "Epic"),
    LEGENDARY("gold", "Legendary")
}

/**
 * Seed for farming.
 */
data class TamaSeed(
    val itemId: String,
    val cropName: String,
    val growthTimeHours: Int,
    val sellPrice: Long,
    val wateringsNeeded: Int = 3
)
