package com.example.llamadroid.tama.adventure

import android.content.Context
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.AdventureForegroundService
import com.example.llamadroid.tama.db.*
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Manages text adventure interactions with the LLM.
 * Handles schematic generation (hidden from user), stage progression, and summarization.
 * Integrates with foreground service for background generation.
 */
class AdventureService(
    private val database: TamaDatabase,
    private val settingsRepository: SettingsRepository,
    private val context: Context? = null
) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
    }
    
    /**
     * Initialize a new adventure or continue an existing one.
     * Schematic is generated and stored internally - never shown to user.
     */
    suspend fun initializeOrContinue(
        petId: String,
        dungeonType: DungeonType
    ): Result<AdventureSession> = withContext(Dispatchers.IO) {
        try {
            context?.let { AdventureForegroundService.start(it, "Starting adventure...") }
            
            // Check for existing active session
            val existingSession = database.tamaDao().getActiveAdventureSession(petId)
            if (existingSession != null && existingSession.dungeonType == dungeonType.name) {
                val stages = database.tamaDao().getAdventureStages(existingSession.id)
                val session = entityToSession(existingSession, stages)
                context?.let { AdventureForegroundService.stop(it) }
                return@withContext Result.success(session)
            }
            
            context?.let { AdventureForegroundService.updateStatus(it, "Generating story structure...") }
            
            // STEP 1: Generate schematic first (hidden from user)
            val schematic = generateSchematic(dungeonType)
            if (schematic == null) {
                context?.let { AdventureForegroundService.stop(it) }
                return@withContext Result.failure(Exception("Failed to generate story schematic"))
            }
            
            DebugLog.log("[Adventure] Schematic generated: ${schematic.totalStages} stages")
            
            val session = AdventureSession(
                id = UUID.randomUUID().toString(),
                petId = petId,
                dungeonType = dungeonType,
                schematic = schematic
            )
            
            context?.let { AdventureForegroundService.updateStatus(it, "Writing opening scene...") }
            
            // STEP 2: Generate first stage (schematic NOT shown to user, just used for guidance)
            val firstStage = generateStage(session, null)
            if (firstStage == null) {
                context?.let { AdventureForegroundService.stop(it) }
                return@withContext Result.failure(Exception("Failed to generate first story stage"))
            }
            
            val sessionWithStage = session.addStage(firstStage)
            saveSession(sessionWithStage)
            
            context?.let { AdventureForegroundService.stop(it) }
            Result.success(sessionWithStage)
        } catch (e: Exception) {
            DebugLog.log("[Adventure] Error: ${e.message}")
            context?.let { AdventureForegroundService.stop(it) }
            Result.failure(e)
        }
    }
    
    /**
     * Submit player choice and generate next stage.
     */
    suspend fun submitChoice(
        petId: String,
        dungeonType: DungeonType,
        playerChoice: String
    ): Result<AdventureSession> = withContext(Dispatchers.IO) {
        try {
            context?.let { AdventureForegroundService.start(it, "Processing your choice...") }
            
            val sessionEntity = database.tamaDao().getActiveAdventureSession(petId)
                ?: return@withContext Result.failure(Exception("No active adventure found"))
            
            val stages = database.tamaDao().getAdventureStages(sessionEntity.id)
            var session = entityToSession(sessionEntity, stages)
            
            // Update last stage with player response
            session = session.updateLastStageWithResponse(playerChoice)
            
            context?.let { AdventureForegroundService.updateStatus(it, "Summarizing...") }
            
            // Summarize the latest stage
            val summary = summarizeStage(session.stages.last())
            if (summary != null) {
                val updatedStages = session.stages.toMutableList()
                updatedStages[updatedStages.lastIndex] = updatedStages.last().copy(summary = summary)
                session = session.copy(
                    stages = updatedStages,
                    cumulativeSummary = session.cumulativeSummary + "\n" + summary
                )
            }
            
            // Check if we've reached the end
            if (session.currentStage >= session.schematic.totalStages) {
                session = session.markCompleted()
                
                // Update dungeon progress
                val progress = database.tamaDao().getDungeonProgress(petId)
                if (progress == null) {
                    database.tamaDao().saveDungeonProgress(
                        DungeonProgressEntity(
                            petId = petId,
                            completedDungeonCount = 1,
                            lastCompletedDungeonType = dungeonType.name
                        )
                    )
                } else if (dungeonType.unlockOrder > progress.completedDungeonCount) {
                    database.tamaDao().updateDungeonProgress(
                        petId,
                        progress.completedDungeonCount + 1,
                        dungeonType.name
                    )
                }
                
                saveSession(session)
                context?.let { AdventureForegroundService.stop(it) }
                return@withContext Result.success(session)
            }
            
            context?.let { 
                AdventureForegroundService.updateStatus(it, "Writing stage ${session.currentStage + 1}...") 
            }
            
            // Generate next stage
            val nextStage = generateStage(session, playerChoice)
            if (nextStage == null) {
                context?.let { AdventureForegroundService.stop(it) }
                return@withContext Result.failure(Exception("Failed to generate next stage"))
            }
            
            session = session.addStage(nextStage)
            saveSession(session)
            
            context?.let { AdventureForegroundService.stop(it) }
            Result.success(session)
        } catch (e: Exception) {
            DebugLog.log("[Adventure] Error submitting choice: ${e.message}")
            context?.let { AdventureForegroundService.stop(it) }
            Result.failure(e)
        }
    }
    
    /**
     * Reset adventure for a dungeon.
     */
    suspend fun resetAdventure(petId: String, dungeonType: DungeonType) = withContext(Dispatchers.IO) {
        val existingSession = database.tamaDao().getActiveAdventureSession(petId)
        if (existingSession != null && existingSession.dungeonType == dungeonType.name) {
            database.tamaDao().deleteAdventureStages(existingSession.id)
            database.tamaDao().deleteAdventureSession(existingSession.id)
        }
    }
    
    // ========== LLM Interaction ==========
    
    /**
     * Generate story schematic - INTERNAL USE ONLY.
     * This is never shown to the user, only used to guide story generation.
     */
    private suspend fun generateSchematic(dungeonType: DungeonType): StorySchematic? {
        val dungeonPrompt = dungeonType.stylePrompt
        
        // Minimal prompt for schematic - just structural info
        val prompt = """You are a story planner. Create a brief structural outline for a text adventure.

DUNGEON THEME: $dungeonPrompt

Generate a story structure with these exact fields:
TOTAL_STAGES: (pick a number between 8 and 20)
STORY_THREAD: (one paragraph describing the main plot arc)
KEY_EVENTS: (list 4-5 major plot points, one per line)
POSSIBLE_ENDINGS: (list 2-3 possible endings, one per line)
TONE: (one word: dark/mysterious/action/horror/epic)
DIFFICULTY: (easy/medium/hard)

Be concise. This is internal planning only.

IMPORTANT: Write EVERYTHING in ${settingsRepository.adventureLanguage.value}. Do not use any other language."""

        DebugLog.log("[Adventure] Generating schematic for ${dungeonType.displayName}...")
        val response = callOllama(prompt)
        if (response == null) {
            DebugLog.log("[Adventure] Schematic generation failed - Ollama returned null. Check URL/model settings.")
            return null
        }
        
        DebugLog.log("[Adventure] Schematic response length: ${response.length}")
        return parseSchematic(response)
    }
    
    /**
     * Generate a story stage.
     * Uses schematic for guidance but NEVER outputs schematic content.
     */
    private suspend fun generateStage(session: AdventureSession, playerChoice: String?): AdventureStage? {
        val systemPrompt = settingsRepository.adventureSystemPrompt.value
        val dungeonPrompt = session.dungeonType.stylePrompt
        
        val contextBuilder = StringBuilder()
        
        // System instructions
        contextBuilder.append("""$systemPrompt

IMPORTANT RULES:
- DO NOT mention or output any story structure, schematic, or planning information
- DO NOT mention stage numbers, total stages, or how many stages remain
- Just write immersive story content as if you are the narrator
- ALWAYS end with 2-4 clear options for the player to choose from
- WRITE EVERYTHING IN ${settingsRepository.adventureLanguage.value} ONLY. Do not switch to any other language.

""")
        
        // Theme context
        contextBuilder.append("SETTING: ${session.dungeonType.displayName}\n")
        contextBuilder.append("THEME: $dungeonPrompt\n\n")
        
        // Story structure (internal guidance only)
        contextBuilder.append("[INTERNAL GUIDANCE - do not output this]\n")
        contextBuilder.append("Story arc: ${session.schematic.storyThread}\n")
        contextBuilder.append("Key events to weave in: ${session.schematic.keyEvents.joinToString(", ")}\n")
        contextBuilder.append("Tone: ${session.schematic.tone}\n")
        contextBuilder.append("[END INTERNAL GUIDANCE]\n\n")
        
        // Previous story summary
        if (session.cumulativeSummary.isNotBlank()) {
            contextBuilder.append("STORY SO FAR:\n${session.cumulativeSummary}\n\n")
        }
        
        // Player's choice
        if (playerChoice != null) {
            contextBuilder.append("THE PLAYER CHOSE: $playerChoice\n\n")
        }
        
        // Stage instruction
        val stageNumber = session.currentStage + 1
        val totalStages = session.schematic.totalStages
        val progressPercent = (stageNumber * 100) / totalStages
        
        contextBuilder.append("Write the next scene of the adventure. ")
        
        when {
            progressPercent < 20 -> contextBuilder.append("This is early in the story - set the scene and introduce the situation.")
            progressPercent < 50 -> contextBuilder.append("Build tension and develop the plot.")
            progressPercent < 80 -> contextBuilder.append("Things should be escalating toward the climax.")
            progressPercent < 95 -> contextBuilder.append("This is near the end - approach the resolution.")
            else -> contextBuilder.append("This is the final scene - bring the story to a satisfying conclusion.")
        }
        
        contextBuilder.append("\n\nRemember: End with clear options for the player like 'What do you do? A) ... B) ... C) ...'")
        
        val response = callOllama(contextBuilder.toString())
        if (response == null) return null
        
        // Clean the response - remove any accidental schematic leakage
        val cleanedResponse = response
            .replace(Regex("\\[INTERNAL.*?\\].*?\\[END INTERNAL.*?\\]", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("TOTAL_STAGES:.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("STORY_THREAD:.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("KEY_EVENTS:.*", RegexOption.IGNORE_CASE), "")
            .trim()
        
        return AdventureStage(
            stageNumber = stageNumber,
            storyContent = cleanedResponse,
            userResponse = null,
            summary = null
        )
    }
    
    private suspend fun summarizeStage(stage: AdventureStage): String? {
        val summarizerPrompt = settingsRepository.adventureSummarizerPrompt.value
        
        val prompt = """$summarizerPrompt

STAGE CONTENT:
${stage.storyContent}

PLAYER RESPONSE: ${stage.userResponse ?: "N/A"}

Provide a 1-2 sentence summary of what happened."""

        return callOllama(prompt, useSummarizer = true)?.trim()
    }
    
    private suspend fun callOllama(prompt: String, useSummarizer: Boolean = false): String? {
        return withContext(Dispatchers.IO) {
            try {
                val baseUrl = settingsRepository.adventureOllamaUrl.value.trimEnd('/')
                val model = if (useSummarizer) {
                    settingsRepository.adventureSummarizerModel.value
                } else {
                    settingsRepository.adventureModel.value
                }
                val numCtx = settingsRepository.adventureOllamaNumCtx.value
                val useMmap = settingsRepository.adventureOllamaMmap.value
                
                val url = URL("$baseUrl/api/generate")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 30000
                connection.readTimeout = 180000 // 3 minutes for longer generations
                
                val requestBody = """
                    {
                        "model": "$model",
                        "prompt": ${json.encodeToString(prompt)},
                        "stream": false,
                        "options": {
                            "num_ctx": $numCtx,
                            "use_mmap": $useMmap
                        }
                    }
                """.trimIndent()
                
                OutputStreamWriter(connection.outputStream).use { it.write(requestBody) }
                
                if (connection.responseCode == 200) {
                    val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                    
                    // Parse response using proper JSON parsing
                    try {
                        val jsonResponse = org.json.JSONObject(response)
                        val content = jsonResponse.optString("response", null)
                        if (content != null) {
                            content
                        } else {
                            DebugLog.log("[Adventure] No 'response' field in Ollama response")
                            null
                        }
                    } catch (e: Exception) {
                        DebugLog.log("[Adventure] Could not parse Ollama JSON response: ${e.message}")
                        null
                    }
                } else {
                    DebugLog.log("[Adventure] Ollama error: ${connection.responseCode}")
                    null
                }
            } catch (e: Exception) {
                DebugLog.log("[Adventure] Ollama call failed: ${e.message}")
                null
            }
        }
    }
    
    private fun parseSchematic(response: String): StorySchematic {
        // Parse TOTAL_STAGES with multiple patterns
        val totalStages = listOf(
            Regex("TOTAL_STAGES:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("total.?stages.?:?\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+)\\s*stages", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { regex ->
            regex.find(response)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 10
        
        val storyThread = Regex("STORY_THREAD:\\s*(.+?)(?=KEY_EVENTS|POSSIBLE_ENDINGS|TONE|DIFFICULTY|$)", 
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(response)?.groupValues?.get(1)?.trim() ?: response.take(300)
        
        val keyEvents = Regex("KEY_EVENTS:\\s*(.+?)(?=POSSIBLE_ENDINGS|TONE|DIFFICULTY|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(response)?.groupValues?.get(1)?.trim()?.split(Regex("[\\n•\\-\\d.]+"))
            ?.map { it.trim() }?.filter { it.isNotBlank() && it.length > 3 } ?: listOf("Adventure begins")
        
        val possibleEndings = Regex("POSSIBLE_ENDINGS:\\s*(.+?)(?=TONE|DIFFICULTY|$)",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(response)?.groupValues?.get(1)?.trim()?.split(Regex("[\\n•\\-\\d.]+"))
            ?.map { it.trim() }?.filter { it.isNotBlank() && it.length > 3 } ?: listOf("Victory", "Defeat")
        
        val tone = Regex("TONE:\\s*(.+?)(?=DIFFICULTY|$)", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.trim()?.take(50) ?: "Dark Fantasy"
        
        val difficulty = Regex("DIFFICULTY:\\s*(easy|medium|hard)", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.lowercase() ?: "medium"
        
        DebugLog.log("[Adventure] Parsed schematic: totalStages=$totalStages")
        
        return StorySchematic(
            totalStages = totalStages.coerceIn(5, 30),
            storyThread = storyThread,
            keyEvents = keyEvents.take(5),
            possibleEndings = possibleEndings.take(3),
            tone = tone,
            difficulty = difficulty
        )
    }
    
    // ========== Database Helpers ==========
    
    private suspend fun saveSession(session: AdventureSession) {
        val entity = AdventureSessionEntity(
            id = session.id,
            petId = session.petId,
            dungeonType = session.dungeonType.name,
            schematicJson = json.encodeToString(session.schematic),
            currentStage = session.currentStage,
            isCompleted = session.isCompleted,
            cumulativeSummary = session.cumulativeSummary,
            createdAt = session.createdAt,
            lastPlayedAt = session.lastPlayedAt
        )
        database.tamaDao().saveAdventureSession(entity)
        
        // Save all stages
        session.stages.forEach { stage ->
            database.tamaDao().saveAdventureStage(
                AdventureStageEntity(
                    id = "${session.id}_${stage.stageNumber}",
                    sessionId = session.id,
                    stageNumber = stage.stageNumber,
                    storyContent = stage.storyContent,
                    userResponse = stage.userResponse,
                    stageSummary = stage.summary,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }
    
    private fun entityToSession(
        entity: AdventureSessionEntity,
        stageEntities: List<AdventureStageEntity>
    ): AdventureSession {
        val schematic = try {
            json.decodeFromString<StorySchematic>(entity.schematicJson)
        } catch (e: Exception) {
            StorySchematic(
                totalStages = 10,
                storyThread = "Adventure continues...",
                keyEvents = listOf(),
                possibleEndings = listOf("Victory", "Defeat"),
                tone = "dark fantasy",
                difficulty = "medium"
            )
        }
        
        val dungeonType = try {
            DungeonType.valueOf(entity.dungeonType)
        } catch (e: Exception) {
            DungeonType.CHAOS_REALM
        }
        
        val stages = stageEntities.map { stageEntity ->
            AdventureStage(
                stageNumber = stageEntity.stageNumber,
                storyContent = stageEntity.storyContent,
                userResponse = stageEntity.userResponse,
                summary = stageEntity.stageSummary
            )
        }
        
        return AdventureSession(
            id = entity.id,
            petId = entity.petId,
            dungeonType = dungeonType,
            schematic = schematic,
            stages = stages,
            currentStage = entity.currentStage,
            isCompleted = entity.isCompleted,
            cumulativeSummary = entity.cumulativeSummary,
            createdAt = entity.createdAt,
            lastPlayedAt = entity.lastPlayedAt
        )
    }
}
