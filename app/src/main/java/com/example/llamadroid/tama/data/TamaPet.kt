package com.example.llamadroid.tama.data

import java.util.UUID
import kotlinx.serialization.Serializable
import com.example.llamadroid.tama.data.InventoryItem

/**
 * Core Tama pet data model.
 * Represents a virtual pet with stats, relationships, and genetic traits.
 */
@Serializable
data class TamaPet(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val species: String = "creature",
    val birthTimestamp: Long = System.currentTimeMillis(),
    val lastDecayTime: Long = System.currentTimeMillis(),  // For stat decay
    val stage: GrowthStage = GrowthStage.EGG,
    val stats: PetStats = PetStats(),
    val mood: Mood = Mood.HAPPY,
    val personality: Personality = Personality.random(),
    val genetics: GeneticTraits = GeneticTraits.random(),
    val relationships: Map<String, Int> = emptyMap(),
    val ownerBondLevel: Float = 50f,
    val educationLevel: Float = 0f,
    val currentLocationId: String = "home",
    val money: Long = 100,
    val inventory: List<InventoryItem> = emptyList(),
    // Activity states (persist when app closed)
    val currentActivity: ActivityType = ActivityType.NONE,
    val activityStartTime: Long? = null,
    val isSleeping: Boolean = false,
    val sleepStartTime: Long? = null,
    val lastSleepWarningTime: Long? = null,
    val miscareCount: Int = 0,
    val isMad: Boolean = false,
    val discoveredLocationIds: Set<String> = setOf("home")
)

/**
 * Activities that persist when app is closed.
 */
@Serializable
enum class ActivityType {
    NONE,
    WORKING,
    STUDYING,
    RELAXING  // At park
}

/**
 * Growth stages based on real time since birth.
 * Transitions specified by user:
 * Egg: 1 minute
 * Baby: 1 hour
 * Child: 24 hours
 * Teen: 24 hours
 * Adult: 24 hours
 * Senior: 1 week
 */
@Serializable
enum class GrowthStage(val displayName: String, val minAgeHours: Float) {
    EGG("Egg", 0f),
    BABY("Baby", 1f/60f),    // 1 minute
    CHILD("Child", 1f),      // 1 hour (evolution to Child happens after 1 hour of Baby stage)
    TEEN("Teen", 25f),       // 24h as Child
    ADULT("Adult", 49f),     // 24h as Teen
    SENIOR("Senior", 73f);   // 24h as Adult (Wait, user said 1 week for senior? "Senior: 1 week" might mean it stays senior for a week or evolves AFTER a week. Re-reading: "Egg: 1m, Baby: 1h, Child: 24h, Teen: 24h, Adult: 24h, Senior: 1 week". 
                             // I will assume it evolves TO Senior after another 24h, and stays Senior for a week.)
    
    companion object {
        fun fromAgeHours(ageHours: Float): GrowthStage {
            return GrowthStage.entries.reversed().firstOrNull { ageHours >= it.minAgeHours } ?: EGG
        }
    }
}

/**
 * Core pet stats (0-100 scale).
 */
@Serializable
data class PetStats(
    val hunger: Float = 100f,       // 100 = full, 0 = starving
    val happiness: Float = 100f,
    val health: Float = 100f,
    val energy: Float = 100f,
    val hygiene: Float = 100f
) {
    fun needsAttention(): Boolean = hunger < 30f || happiness < 30f || health < 30f || hygiene < 30f
    fun critical(): Boolean = hunger < 10f || health < 10f
}


/**
 * Pet mood affects behavior and responses.
 */
@Serializable
enum class Mood(val emoji: String) {
    ECSTATIC("😄"),
    HAPPY("🙂"),
    NEUTRAL("😐"),
    SAD("😢"),
    ANGRY("😠"),
    SICK("🤒"),
    SLEEPING("😴")
}

/**
 * Pet personality affects responses and interactions.
 */
@Serializable
enum class Personality(val description: String) {
    CHEERFUL("Always optimistic and friendly"),
    SHY("Quiet and reserved"),
    PLAYFUL("Loves games and adventures"),
    LAZY("Prefers rest and relaxation"),
    CURIOUS("Wants to explore everything"),
    BRAVE("Eager for challenges");
    
    companion object {
        fun random(): Personality = Personality.entries.toTypedArray().random()
    }
}

/**
 * Genetic traits for sprite generation.
 * Each trait is inherited and combined to create unique appearances.
 */
@Serializable
data class GeneticTraits(
    val eyeStyle: Int = 0,      // Index into eyes folder
    val earStyle: Int = 0,
    val mouthStyle: Int = 0,
    val headShape: Int = 0,
    val bodyStyle: Int = 0,
    val armStyle: Int = 0,
    val legStyle: Int = 0,
    val colorTint: Int = 0,     // Grayscale variation
    val accessories: List<Int> = emptyList()
) {
    companion object {
        fun random(): GeneticTraits = GeneticTraits(
            eyeStyle = (0..9).random(),
            earStyle = (0..9).random(),
            mouthStyle = (0..9).random(),
            headShape = (0..5).random(),
            bodyStyle = (0..5).random(),
            armStyle = (0..5).random(),
            legStyle = (0..5).random(),
            colorTint = (0..3).random()
        )
        
        /**
         * Combine two parents' genetics for a child.
         */
        fun inherit(parent1: GeneticTraits, parent2: GeneticTraits): GeneticTraits {
            return GeneticTraits(
                eyeStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.eyeStyle else parent2.eyeStyle,
                earStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.earStyle else parent2.earStyle,
                mouthStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.mouthStyle else parent2.mouthStyle,
                headShape = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.headShape else parent2.headShape,
                bodyStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.bodyStyle else parent2.bodyStyle,
                armStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.armStyle else parent2.armStyle,
                legStyle = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.legStyle else parent2.legStyle,
                colorTint = if (kotlin.random.Random.nextFloat() > 0.5f) parent1.colorTint else parent2.colorTint
            )
        }
    }
}
