package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.R
import com.example.llamadroid.tama.data.*
import com.example.llamadroid.tama.db.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.text.SimpleDateFormat
import java.util.*

/**
 * Core game engine for Tama virtual pet.
 * Manages pet state, real-time updates, and game logic.
 */
class TamaGameEngine(
    private val context: Context,
    private val dao: TamaDao,
    private val farmEngine: FarmEngine
) {
    private val _pet = MutableStateFlow<TamaPet?>(null)
    val pet: StateFlow<TamaPet?> = _pet.asStateFlow()
    
    private val _events = MutableStateFlow<List<TamaEvent>>(emptyList())
    val events: StateFlow<List<TamaEvent>> = _events.asStateFlow()
    
    private val _currentLocation = MutableStateFlow<TamaLocation?>(null)
    val currentLocation: StateFlow<TamaLocation?> = _currentLocation.asStateFlow()
    
    // Time tracking for real-time updates
    private var lastUpdateTime = System.currentTimeMillis()
    private var hasLoggedReturnThisSession = false  // Prevent OWNER_RETURNED spam
    
    companion object {
        const val STAT_DECAY_PER_HOUR = 5  // Stats decrease by this much per hour
        const val MAX_WORK_HOURS_HEALTHY = 8
        const val NEGLECT_THRESHOLD_HOURS = 4  // After this long without care = neglect
    }
    
    // ==================== Pet Creation ====================
    
    /**
     * Create a new pet (starts as egg).
     */
    suspend fun createPet(name: String, species: String = "creature"): TamaPet {
        val pet = TamaPet(
            name = name,
            species = species,
            stage = GrowthStage.EGG,
            genetics = GeneticTraits.random(),
            inventory = listOf(
                InventoryItem(id = "hoe_starter", name = "Hoe", type = ItemType.TOOL, durability = 100, maxDurability = 100),
                InventoryItem(id = "watering_can_starter", name = "Watering Can", type = ItemType.TOOL, durability = 100, maxDurability = 100)
            )
        )
        _pet.value = pet
        savePet(pet)
        logEvent(pet.id, EventType.HATCHED, context.getString(R.string.tama_event_hatched, name, species))
        return pet
    }
    
    /**
     * Completely reset/delete current pet.
     */
    suspend fun resetPet() {
        _pet.value?.let { pet ->
            dao.deletePet(PetMapper.toEntity(pet))
            _pet.value = null
            _events.value = emptyList()
        }
    }
    
    /**
     * Load existing pet from database.
     */
    suspend fun loadPet(): TamaPet? {
        val entity = dao.getActivePet() ?: return null
        val pet = PetMapper.toDomain(entity)
        _pet.value = pet
        
        // Load recent events
        val eventEntities = dao.getRecentEvents(pet.id)
        _events.value = eventEntities.map { entityToEvent(it) }
        
        // Update for time passed while app was closed
        updateForTimePassed()
        
        return pet
    }
    
    // ==================== Time-Based Updates ====================
    
    /**
     * Update pet state based on real time passed.
     * Called on app open and periodically.
     */
    suspend fun updateForTimePassed() {
        val pet = _pet.value ?: return
        val now = System.currentTimeMillis()
        
        // Update farm state (growth, production, decay)
        farmEngine.updateFarm(pet.id, now)
        
        var updatedPet = pet
        val minutesPassed = (now - pet.lastDecayTime) / (1000f * 60)
        val secondsPassed = (now - pet.lastDecayTime) / 1000L
        
        // 1. Check for growth stage evolution - ALWAYS check this when pet is loaded
        val ageHours = (now - pet.birthTimestamp) / (1000f * 60f * 60f)
        val newStage = GrowthStage.fromAgeHours(ageHours)
        if (newStage != pet.stage) {
            updatedPet = updatedPet.copy(stage = newStage)
            logEvent(pet.id, EventType.EVOLVED, context.getString(R.string.tama_event_evolved, pet.name, newStage.displayName))
            // Update _pet immediately so following logic uses correct stage
            _pet.value = updatedPet
        }
        
        if (secondsPassed < 5L) {
            // Check for stage evolution even if no decay
            if (updatedPet != pet) savePet(updatedPet)
            return
        }
        
        // 2. Real-time Decay (Smooth)
        // STAT_DECAY_PER_HOUR (5 by default) points per hour.
        // We calculate the fraction of decay based on the exact seconds passed.
        val decayPerHour = STAT_DECAY_PER_HOUR.toFloat()
        val decayMultiplier = if (pet.stage == GrowthStage.BABY) 5f else 1f
        val decayAmount = (secondsPassed / 3600f) * decayPerHour * decayMultiplier
        
        if (decayAmount > 0.001f) {
            val newStats = updatedPet.stats.copy(
                hunger = (updatedPet.stats.hunger - decayAmount).coerceIn(0f, 100f),
                happiness = (updatedPet.stats.happiness - decayAmount).coerceIn(0f, 100f),
                hygiene = (updatedPet.stats.hygiene - decayAmount).coerceIn(0f, 100f),
                energy = (updatedPet.stats.energy - decayAmount).coerceIn(0f, 100f)
            )
            
            updatedPet = updatedPet.copy(stats = newStats, lastDecayTime = now)
            
            // 4. Check for neglect (only if not sleeping and not an egg)
            if (newStats.needsAttention() && !pet.isSleeping && pet.stage != GrowthStage.EGG) {
                updatedPet = updatedPet.copy(
                    stats = updatedPet.stats.copy(happiness = (updatedPet.stats.happiness - 0.1f).coerceIn(0f, 100f)),
                    ownerBondLevel = (updatedPet.ownerBondLevel - 0.1f).coerceIn(0f, 100f)
                )
                if (minutesPassed > 60) {
                    logEvent(pet.id, EventType.NEGLECTED, context.getString(R.string.tama_event_neglected, pet.name))
                }
            }
        }

        // 5. Sleep Cycle Logic (22:00 bedtime)
        // Baby stage is exempt!
        if (pet.stage != GrowthStage.BABY && pet.stage != GrowthStage.EGG) {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            
            // Bedtime starts at 22:00
            val isBedtime = hour >= 22 || hour < 6
            
            if (isBedtime && !pet.isSleeping) {
                // Not in bed! 
                // Miscare if past midnight (00:00 - 06:00)
                val isMiscareTime = hour < 6 
                
                if (isMiscareTime) {
                    val lastWarning = pet.lastSleepWarningTime ?: 0L
                    val today = calendar.get(Calendar.DAY_OF_YEAR)
                    val warningCalendar = Calendar.getInstance().apply { timeInMillis = lastWarning }
                    if (today != warningCalendar.get(Calendar.DAY_OF_YEAR)) {
                        updatedPet = updatedPet.copy(
                            miscareCount = pet.miscareCount + 1,
                            lastSleepWarningTime = now,
                            isMad = true,
                            stats = updatedPet.stats.copy(happiness = (updatedPet.stats.happiness - 15).coerceIn(0f, 100f))
                        )
                        logEvent(pet.id, EventType.NEGLECTED, context.getString(R.string.tama_event_stayed_up_mad, pet.name))
                    }
                } else {
                    // Just bedtime warning (22:00 - 23:59)
                    updatedPet = updatedPet.copy(isMad = true)
                    if (minutesPassed > 30) {
                        logEvent(pet.id, EventType.NEGLECTED, context.getString(R.string.tama_event_sleepy))
                    }
                }
            }
        }
        
        // 6. Update mood
        val newMood = calculateMood(updatedPet.stats)
        updatedPet = updatedPet.copy(mood = newMood)
        
        // 8. Log return if away a while (only once per session)
        val minutesPassedSinceDecay = (now - pet.lastDecayTime) / (1000f * 60)
        if (minutesPassedSinceDecay > 5 && !hasLoggedReturnThisSession) {
            logEvent(pet.id, EventType.OWNER_RETURNED, context.getString(R.string.tama_event_welcome_back, minutesPassedSinceDecay.toInt()))
            hasLoggedReturnThisSession = true
        }
        
        _pet.value = updatedPet
        savePet(updatedPet)
        lastUpdateTime = now
    }
    
    private fun calculateMood(stats: PetStats): Mood = when {
        stats.health < 20 -> Mood.SICK
        stats.energy < 20 -> Mood.SLEEPING
        stats.critical() -> Mood.SAD
        stats.happiness > 80 && stats.hunger > 60 -> Mood.ECSTATIC
        stats.happiness > 50 -> Mood.HAPPY
        stats.happiness > 30 -> Mood.NEUTRAL
        stats.happiness > 10 -> Mood.SAD
        else -> Mood.ANGRY
    }
    
    // ==================== Action Result ====================
    
    /**
     * Result of an action - indicates if it was performed and any message.
     */
    data class ActionResult(
        val success: Boolean,
        val message: String,
        val action: String = ""
    )
    
    // ==================== Care Actions ====================
    
    suspend fun feed(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.isSleeping) return ActionResult(false, "${pet.name} is sleeping...")
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, "Eggs don't eat!")
        if (pet.stats.hunger >= 100) return ActionResult(false, "${pet.name} is full! 🍖")
        
        val hungerGain = 30f
        val newStats = pet.stats.copy(hunger = (pet.stats.hunger + hungerGain).coerceAtMost(100f))
        val updatedPet = pet.copy(
            stats = newStats,
            ownerBondLevel = (pet.ownerBondLevel + 1f).coerceAtMost(100f)
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.FED, context.getString(R.string.tama_event_fed, pet.name, pet.stats.hunger.toInt(), newStats.hunger.toInt()), 
            statsChange = mapOf("hunger" to hungerGain))
        return ActionResult(true, "${pet.name} ate happily! 🍖", "eating")
    }
    
    suspend fun feedWithFood(foodName: String, hungerGain: Int, happinessGain: Int): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.isSleeping) return ActionResult(false, "${pet.name} is sleeping...")
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, "Eggs don't eat!")
        
        // Lettuce specific override: user requested value 5
        val finalHungerGain = if (foodName == "Lettuce") 5 else hungerGain
        
        // Check if free food or from inventory
        val freeFoods = listOf("Lettuce", "Candy")
        val isFreeFood = freeFoods.contains(foodName)
        
        var newInventory = pet.inventory.toMutableList()
        if (!isFreeFood) {
            val matchingItem = newInventory.find { it.name.equals(foodName, ignoreCase = true) && it.quantity > 0 }
            if (matchingItem == null) {
                return ActionResult(false, "You don't have any $foodName!")
            }
            
            val index = newInventory.indexOf(matchingItem)
            if (matchingItem.quantity == 1) {
                newInventory.removeAt(index)
            } else {
                newInventory[index] = matchingItem.copy(quantity = matchingItem.quantity - 1)
            }
        }
        
        val finalHungerGainFloat = finalHungerGain.toFloat()
        val happinessGainFloat = happinessGain.toFloat()

        val newStats = pet.stats.copy(
            hunger = (pet.stats.hunger + finalHungerGainFloat).coerceAtMost(100f),
            happiness = (pet.stats.happiness + happinessGainFloat).coerceAtMost(100f)
        )
        val updatedPet = pet.copy(
            stats = newStats,
            inventory = newInventory,
            ownerBondLevel = (pet.ownerBondLevel + 1f).coerceAtMost(100f)
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.FED, context.getString(R.string.tama_event_ate_food, pet.name, foodName))
        return ActionResult(true, "${pet.name} ate $foodName! 🍖", "eating")
    }
    
    suspend fun buyItem(itemName: String, price: Int): ActionResult {
        val item = InventoryItem(
            id = itemName.lowercase().replace(" ", "_"),
            name = itemName,
            type = if (itemName.lowercase().contains("seed")) ItemType.SEED else ItemType.FOOD
        )
        return buyItem(item, 1, price)
    }
    
    suspend fun clean(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.isSleeping) return ActionResult(false, "${pet.name} is sleeping...")
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, "Eggs don't need baths!")
        if (pet.stats.hygiene >= 100) return ActionResult(false, "${pet.name} is already clean! 🛁")
        
        val hygieneGain = 40f
        val newStats = pet.stats.copy(hygiene = (pet.stats.hygiene + hygieneGain).coerceAtMost(100f))
        val updatedPet = pet.copy(stats = newStats)
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.CLEANED, context.getString(R.string.tama_event_cleaned, pet.name, pet.stats.hygiene.toInt(), newStats.hygiene.toInt()),
            statsChange = mapOf("hygiene" to hygieneGain))
        return ActionResult(true, "${pet.name} is all clean! 🛁", "cleaning")
    }
    
    suspend fun play(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.isSleeping) return ActionResult(false, "${pet.name} is sleeping...")
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, "Eggs can't play!")
        if (pet.stats.happiness >= 100) return ActionResult(false, "${pet.name} is already super happy! 🎮")
        if (pet.stats.energy < 10) return ActionResult(false, "${pet.name} is too tired to play...")
        
        val happinessGain = 20f
        val energyCost = 10f
        val newStats = pet.stats.copy(
            happiness = (pet.stats.happiness + happinessGain).coerceAtMost(100f),
            energy = (pet.stats.energy - energyCost).coerceAtLeast(0f)
        )
        val updatedPet = pet.copy(
            stats = newStats,
            ownerBondLevel = (pet.ownerBondLevel + 2f).coerceAtMost(100f)
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.PLAYED, context.getString(R.string.tama_event_played, pet.name),
            statsChange = mapOf("happiness" to happinessGain, "energy" to -energyCost))
        return ActionResult(true, "${pet.name} had fun! 🎮", "playing")
    }
    
    /**
     * Put pet to bed - they stay asleep until woken up.
     */
    suspend fun goToBed(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.isSleeping) return ActionResult(false, "${pet.name} is already asleep...")
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, "Eggs don't sleep!")
        if (pet.stats.energy >= 100) return ActionResult(false, "${pet.name} isn't tired! 😄")
        
        val updatedPet = pet.copy(
            isSleeping = true,
            sleepStartTime = System.currentTimeMillis(),
            mood = Mood.SLEEPING
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(pet.id, EventType.SLEPT, context.getString(R.string.tama_event_slept, pet.name))
        return ActionResult(true, "${pet.name} is sleeping... 💤", "sleeping")
    }
    
    /**
     * Wake pet up - restores energy based on time slept.
     */
    suspend fun wakeUp() {
        val currentPet = _pet.value ?: return
        if (!currentPet.isSleeping) return  // Not sleeping
        
        val sleepStart = currentPet.sleepStartTime ?: System.currentTimeMillis()
        val minutesSlept = ((System.currentTimeMillis() - sleepStart) / (1000 * 60)).toInt()
        
        // Energy restored: 5 per minute slept (halved from 10), max +80
        val energyGain = (minutesSlept * 5f).coerceIn(5f, 80f)
        val newStats = currentPet.stats.copy(
            energy = (currentPet.stats.energy + energyGain).coerceAtMost(100f),
            health = (currentPet.stats.health + minutesSlept.toFloat()).coerceAtMost(100f)  // Sleep heals
        )
        
        val updatedPet = currentPet.copy(
            isSleeping = false,
            sleepStartTime = null,
            isMad = false, 
            stats = newStats,
            mood = if (newStats.happiness > 50f) Mood.HAPPY else Mood.NEUTRAL
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        logEvent(updatedPet.id, EventType.WOKE_UP, context.getString(R.string.tama_event_woke_up, updatedPet.name, minutesSlept, energyGain.toInt()),
            statsChange = mapOf("energy" to energyGain))
    }
    
    /**
     * Check if action is allowed (blocked when sleeping).
     */
    fun canDoAction(): Boolean {
        val pet = _pet.value ?: return false
        return !pet.isSleeping && pet.stage != GrowthStage.EGG
    }
    
    // ==================== Activity System ====================
    
    /**
     * Start an activity (working, studying, relaxing).
     */
    suspend fun startActivity(activity: ActivityType): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.isSleeping) return ActionResult(false, "${pet.name} is sleeping...")
        if (pet.currentActivity != ActivityType.NONE) {
            return ActionResult(false, "${pet.name} is already busy!")
        }
        
        // Stage restrictions
        if (activity == ActivityType.WORKING && pet.stage != GrowthStage.ADULT && pet.stage != GrowthStage.TEEN) {
            return ActionResult(false, "Only teens/adults can work!")
        }
        
        val updatedPet = pet.copy(
            currentActivity = activity,
            activityStartTime = System.currentTimeMillis()
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        
        val action = when (activity) {
            ActivityType.WORKING -> "working"
            ActivityType.STUDYING -> "studying"
            ActivityType.RELAXING -> "sunbathing"
            else -> "idle"
        }
        val emoji = when (activity) {
            ActivityType.WORKING -> "💼"
            ActivityType.STUDYING -> "📚"
            ActivityType.RELAXING -> "🌳"
            else -> ""
        }
        logEvent(pet.id, EventType.STARTED_WORK, context.getString(R.string.tama_event_started_activity, pet.name, activity.name.lowercase()))
        return ActionResult(true, "$emoji Started ${activity.name.lowercase()}!", action)
    }
    
    /**
     * Stop current activity and collect rewards.
     */
    suspend fun stopActivity(): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.currentActivity == ActivityType.NONE) {
            return ActionResult(false, "Not doing anything!")
        }
        
        val now = System.currentTimeMillis()
        val hoursActive = ((now - (pet.activityStartTime ?: now)) / (1000 * 60 * 60f)).coerceAtMost(8f)
        val minutesActive = ((now - (pet.activityStartTime ?: now)) / (1000 * 60f)).toInt()
        
        var updatedPet = pet
        var message = ""
        
        when (pet.currentActivity) {
            ActivityType.WORKING -> {
                val earnings = (hoursActive * 10).toLong().coerceAtLeast(if (minutesActive > 0) 1 else 0)
                updatedPet = updatedPet.copy(money = pet.money + earnings)
                message = "💰 Earned $earnings coins!"
                logEvent(pet.id, EventType.GOT_PAID, context.getString(R.string.tama_event_earned, earnings.toInt()))
            }
            ActivityType.STUDYING -> {
                val intGain = (hoursActive * 5f)
                updatedPet = updatedPet.copy(educationLevel = (pet.educationLevel + intGain).coerceAtMost(100f))
                message = "📚 Gained ${intGain.toInt()} education!"
                logEvent(pet.id, EventType.STUDIED, context.getString(R.string.tama_event_studied, intGain.toInt()))
            }
            ActivityType.RELAXING -> {
                val happinessGain = (hoursActive * 40f)  // 40 happiness/hour
                val healthGain = (hoursActive * 2f)
                updatedPet = updatedPet.copy(
                    stats = pet.stats.copy(
                        happiness = (pet.stats.happiness + happinessGain).coerceIn(0f, 100f),
                        health = (pet.stats.health + healthGain).coerceIn(0f, 100f)
                    )
                )
                message = "🌳 +${happinessGain.toInt()} happiness, +${healthGain.toInt()} health!"
                logEvent(pet.id, EventType.RELAXED, context.getString(R.string.tama_event_relaxed))
            }
            else -> {}
        }
        
        updatedPet = updatedPet.copy(
            currentActivity = ActivityType.NONE,
            activityStartTime = null
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        
        return ActionResult(true, message, "idle")
    }
    
    /**
     * Start a job.
     */
    suspend fun startWork(jobName: String, requiredEdu: Int = 0): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.educationLevel < requiredEdu) {
            return ActionResult(false, "Need $requiredEdu education for $jobName!")
        }
        
        // Start activity working (passing job info could be done via a custom activity or meta-data)
        // For now, we'll use the existing ActivityType.WORKING and track the specific pay in finishWork.
        
        return startActivity(ActivityType.WORKING)
    }
    
    suspend fun finishWork(): Long {
        val pet = _pet.value ?: return 0
        if (pet.currentActivity != ActivityType.WORKING) return 0
        val before = pet.money
        stopActivity()
        return (_pet.value?.money ?: before) - before
    }
    
    // ==================== Travel System ====================
    
    /**
     * Travel to a new location (costs energy).
     */
    suspend fun travelTo(location: TamaLocation): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        if (pet.isSleeping) return ActionResult(false, "${pet.name} is sleeping...")
        if (pet.currentActivity != ActivityType.NONE) return ActionResult(false, "${pet.name} is busy!")
        if (pet.stage == GrowthStage.EGG) return ActionResult(false, "Eggs can't travel!")
        
        // Calculate energy cost based on distance (all locations in same city now)
        val currentLoc = _currentLocation.value
        val energyCost = if (currentLoc != null) {
            val distance = kotlin.math.abs(currentLoc.x - location.x) + kotlin.math.abs(currentLoc.y - location.y)
            (distance * 3).coerceAtLeast(3)
        } else {
            5  // First time setting location
        }
        
        if (pet.stats.energy < energyCost) {
            return ActionResult(false, "${pet.name} is too tired to travel (need $energyCost energy)")
        }
        
        // Update pet
        val newDiscovered = pet.discoveredLocationIds + location.id
        val newStats = pet.stats.copy(energy = pet.stats.energy - energyCost)
        val updatedPet = pet.copy(
            stats = newStats,
            currentLocationId = location.id,
            discoveredLocationIds = newDiscovered
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        
        // Update location state
        _currentLocation.value = location
        
        // Log discovery if new
        if (!pet.discoveredLocationIds.contains(location.id)) {
            logEvent(pet.id, EventType.DISCOVERED, context.getString(R.string.tama_event_discovered, location.name, location.type.emoji), 
                locationId = location.id)
        }

        logEvent(pet.id, EventType.TRAVELED, context.getString(R.string.tama_event_traveled, pet.name, location.name),
            locationId = location.id,
            statsChange = mapOf("energy" to -energyCost.toFloat()))
        
        return ActionResult(true, "Arrived at ${location.name}! (-${energyCost.toInt()} energy)", "walking")
    }
    

    suspend fun setCurrentLocation(location: TamaLocation) {
        _currentLocation.value = location
        // Also mark as discovered
        val pet = _pet.value ?: return
        if (!pet.discoveredLocationIds.contains(location.id)) {
            val updatedPet = pet.copy(
                discoveredLocationIds = pet.discoveredLocationIds + location.id,
                currentLocationId = location.id
            )
            _pet.value = updatedPet
            savePet(updatedPet)
        }
    }

    // ==================== Economy System ====================

    suspend fun buyItem(item: InventoryItem, quantity: Int, pricePerUnit: Int): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        val totalCost = pricePerUnit.toLong() * quantity
        if (pet.money < totalCost) {
            return ActionResult(false, "Not enough coins! Need $totalCost, have ${pet.money}")
        }

        val newInventory = pet.inventory.toMutableList()
        // Stacking logic: Tools NEVER stack. Everything else SHOULD stack.
        val existingIndex = newInventory.indexOfFirst { it.id == item.id && it.type != ItemType.TOOL }
        
        if (existingIndex != -1) {
            val existing = newInventory[existingIndex]
            newInventory[existingIndex] = existing.copy(quantity = existing.quantity + quantity)
        } else {
            // Ensure newly added items have correct initial quantity
            newInventory.add(item.copy(quantity = quantity))
        }

        val updatedPet = pet.copy(
            money = pet.money - totalCost,
            inventory = newInventory
        )
        _pet.value = updatedPet
        savePet(updatedPet)
        
        logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_bought, quantity, item.name, totalCost.toInt()))
        return ActionResult(true, "Bought $quantity ${item.name}!")
    }

    /**
     * Consume an item from inventory without logging it as a sale.
     * Used for planting, crafting, etc.
     */
    suspend fun consumeItem(item: InventoryItem, quantity: Int = 1): Boolean {
        val pet = _pet.value ?: return false
        val existing = pet.inventory.find { it.id == item.id } ?: return false
        
        if (existing.quantity < quantity) return false
        
        val newInventory = if (existing.quantity == quantity) {
            pet.inventory.filter { it.id != item.id }
        } else {
            pet.inventory.map {
                if (it.id == item.id) it.copy(quantity = it.quantity - quantity)
                else it
            }
        }
        
        val updatedPet = pet.copy(inventory = newInventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        return true
    }
    
    /**
     * Spend money.
     */
    suspend fun spendMoney(amount: Long): Boolean {
        val pet = _pet.value ?: return false
        if (pet.money < amount) return false
        
        val updatedPet = pet.copy(money = pet.money - amount)
        _pet.value = updatedPet
        savePet(updatedPet)
        return true
    }
    
    suspend fun sellItem(item: InventoryItem, quantity: Int = 1, price: Long): ActionResult {
        val pet = _pet.value ?: return ActionResult(false, "No pet!")
        val totalGain = price * quantity
        
        val newInventory = pet.inventory.toMutableList()
        val existingIndex = newInventory.indexOfFirst { it.id == item.id }
        if (existingIndex == -1 || newInventory[existingIndex].quantity < quantity) {
            return ActionResult(false, "Not enough ${item.name} to sell!")
        }

        val existing = newInventory[existingIndex]
        if (existing.quantity == quantity) {
            newInventory.removeAt(existingIndex)
        } else {
            newInventory[existingIndex] = existing.copy(quantity = existing.quantity - quantity)
        }

        val updatedPet = pet.copy(
            money = pet.money + totalGain,
            inventory = newInventory
        )
        _pet.value = updatedPet
        savePet(updatedPet)

        logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_sold, quantity, item.name, totalGain.toInt()))
        return ActionResult(true, "Sold $quantity ${item.name}!")
    }
    
    // ==================== Event Logging ====================
    
    suspend fun logEvent(
        petId: String,
        eventType: EventType,
        details: String,
        locationId: String? = null,
        npcId: String? = null,
        statsChange: Map<String, Float>? = null
    ) {
        val now = System.currentTimeMillis()
        val lastEvent = _events.value.firstOrNull()
        
        // Improved Condensing Logic: "{pet name} ate 5 lettuce" style
        if (lastEvent != null && lastEvent.eventType == eventType && (now - lastEvent.timestamp) < 15 * 60 * 1000) {
            val baseDetails = details.replace(Regex(" x\\d+$"), "").replace(Regex(" \\(\\d+ total\\)$"), "")
            val lastBaseDetails = lastEvent.details.replace(Regex(" x\\d+$"), "").replace(Regex(" \\(\\d+ total\\)$"), "")
            
            if (baseDetails == lastBaseDetails) {
                val match = Regex(" x(\\d+)$").find(lastEvent.details)
                val count = (match?.groupValues?.get(1)?.toInt() ?: 1) + 1
                
                // Specific condensing for repetitive actions: "ate 5 lettuce", "played 3 times"
                val petName = _pet.value?.name ?: "The pet"
                val newDetails = if (baseDetails.contains("ate", ignoreCase = true)) {
                    val foodMatch = Regex("([Aa]te) ([^!]+)").find(baseDetails)
                    if (foodMatch != null) {
                        val food = foodMatch.groupValues[2].trim()
                        val pluralFood = if (food.endsWith("y")) food.dropLast(1) + "ies" 
                                        else if (food.endsWith("s") || food.endsWith("x") || food.endsWith("ch") || food.endsWith("sh")) food + "es"
                                        else food + "s"
                        "$petName ate $count $pluralFood"
                    } else {
                        "$baseDetails ($count total)"
                    }
                } else if (baseDetails.contains("played", ignoreCase = true)) {
                    "$petName played $count times"
                } else if (baseDetails.contains("cleaned", ignoreCase = true)) {
                    "$petName was cleaned $count times"
                } else {
                    "$baseDetails ($count total)"
                }
                
                val updatedEvent = lastEvent.copy(details = newDetails, timestamp = now, statsChange = null) // Summing stats change is complex, so we null it for condensed events
                _events.value = listOf(updatedEvent) + _events.value.drop(1)
                dao.saveEvent(eventToEntity(updatedEvent))
                return
            }
        }

        val event = TamaEvent(
            petId = petId,
            eventType = eventType,
            details = details,
            locationId = locationId ?: _pet.value?.currentLocationId,
            npcId = npcId,
            statsChange = statsChange,
            timestamp = now
        )
        _events.value = listOf(event) + _events.value.take(99)  // Keep last 100
        dao.saveEvent(eventToEntity(event))
    }
    
    /**
     * Export all pet data to JSON for device transfer.
     */
    suspend fun exportToJson(): String {
        val pet = _pet.value ?: return "{}"
        val events = dao.getRecentEvents(pet.id, 500)
        val summaries = dao.getRecentSummaries(pet.id, 30)
        
        // Use JsonObject builder for dynamic structure
        val json = Json { prettyPrint = true }
        val exportData = buildJsonObject {
            put("version", 1)
            put("exportDate", System.currentTimeMillis())
            put("pet", json.encodeToJsonElement(TamaPet.serializer(), pet))
            putJsonArray("events") {
                events.forEach { event ->
                    addJsonObject {
                        put("id", event.id)
                        put("petId", event.petId)
                        put("eventType", event.eventType)
                        put("details", event.details)
                        put("timestamp", event.timestamp)
                        event.locationId?.let { put("locationId", it) }
                        event.npcId?.let { put("npcId", it) }
                        event.statsChangeJson?.let { put("statsChangeJson", it) }
                    }
                }
            }
            putJsonArray("summaries") {
                summaries.forEach { summary ->
                    addJsonObject {
                        put("id", summary.id)
                        put("petId", summary.petId)
                        put("date", summary.date)
                        put("summary", summary.summary)
                        put("createdAt", summary.createdAt)
                    }
                }
            }
        }
        
        return json.encodeToString(exportData)
    }
    
    /**
     * Import pet data from JSON.
     */
    suspend fun importFromJson(jsonString: String): Boolean {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val data = json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(jsonString)
            
            // Re-decode the components
            val pet = json.decodeFromJsonElement(TamaPet.serializer(), data["pet"]!!)
            
            _pet.value = pet
            savePet(pet)
            
            // Re-load events for UI
            val dbEvents = dao.getRecentEvents(pet.id, 100)
            _events.value = dbEvents.map { entityToEvent(it) }
            
            logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_imported))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==================== Helpers ====================
    
    private suspend fun savePet(pet: TamaPet) {
        dao.savePet(PetMapper.toEntity(pet))
    }
    
    // Simplified engine - legacy mapper methods removed
    
    private fun eventToEntity(event: TamaEvent): TamaEventEntity = TamaEventEntity(
        id = event.id,
        timestamp = event.timestamp,
        petId = event.petId,
        eventType = event.eventType.name,
        details = event.details,
        locationId = event.locationId,
        npcId = event.npcId,
        statsChangeJson = event.statsChange?.let { Json.encodeToString(it) }
    )
    
    private fun entityToEvent(entity: TamaEventEntity): TamaEvent = TamaEvent(
        id = entity.id,
        timestamp = entity.timestamp,
        petId = entity.petId,
        eventType = try { EventType.valueOf(entity.eventType) } catch(e: Exception) { EventType.OTHER },
        details = entity.details,
        locationId = entity.locationId,
        npcId = entity.npcId,
        statsChange = entity.statsChangeJson?.let { jsonObjectToFloatMap(it) }
    )
    
    private fun jsonArrayToStringList(jsonStr: String): List<String> {
        return try { Json.decodeFromString<List<String>>(jsonStr) } catch(e: Exception) { emptyList() }
    }
    
    private fun jsonObjectToFloatMap(jsonStr: String): Map<String, Float> {
        return try { Json.decodeFromString<Map<String, Float>>(jsonStr) } catch(e: Exception) { emptyMap() }
    }

    suspend fun reduceToolDurability(tool: InventoryItem, amount: Int): Boolean {
        val pet = _pet.value ?: return false
        val inventory = pet.inventory.toMutableList()
        val index = inventory.indexOfFirst { it.id == tool.id }
        if (index == -1) return false
        
        val currentTool = inventory[index]
        val currentDurability = currentTool.durability ?: 100
        val newDurability = currentDurability - amount
        
        if (newDurability <= 0) {
            inventory.removeAt(index)
            logEvent(pet.id, EventType.OTHER, context.getString(R.string.tama_event_tool_broke, currentTool.name))
        } else {
            inventory[index] = currentTool.copy(durability = newDurability)
        }
        
        val updatedPet = pet.copy(inventory = inventory)
        _pet.value = updatedPet
        savePet(updatedPet)
        return true
    }
}
