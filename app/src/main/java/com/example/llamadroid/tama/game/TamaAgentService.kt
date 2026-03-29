package com.example.llamadroid.tama.game

import android.content.Context
import com.example.llamadroid.data.SettingsRepository
import com.example.llamadroid.service.OllamaService
import com.example.llamadroid.tama.data.TamaPet
import com.example.llamadroid.tama.db.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * TamaAgentService - Handles AI interactions for the Tama pet.
 * Mirroring the multi-agent logic but tailored for the pet's personality and history.
 */
class TamaAgentService(
    private val context: Context,
    private val dao: TamaDao,
    val settingsRepo: SettingsRepository,
    val ollamaService: OllamaService,
    private val scope: CoroutineScope
) {
    init {
        // Initialize with Tama-specific settings
        ollamaService.initFromSettings("tama_")
    }
    private val _messages = MutableStateFlow<List<OllamaService.ChatMessage>>(emptyList())
    val messages: StateFlow<List<OllamaService.ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var messageCount = 0
    private var lastSummarizedMessageTimestamp: Long = 0L

    /**
     * Load chat history from the database for a specific pet.
     */
    fun loadHistory(petId: String) {
        scope.launch {
            val dbMessages = dao.getChatHistory(petId)
            val converted = dbMessages.map { 
                OllamaService.ChatMessage(
                    id = it.id,
                    role = it.role,
                    content = it.content,
                    thinking = it.thinking,
                    timestamp = it.timestamp
                )
            }
            _messages.value = converted
            
            // Load watermark from the latest summary
            val recentSummaries = dao.getRecentSummaries(petId, 1)
            lastSummarizedMessageTimestamp = recentSummaries.firstOrNull()?.lastChatMessageTimestamp ?: 0L
            
            // Approximate message count since last summary for auto-trigger
            val newMsgsSinceSummary = converted.count { (it.timestamp ?: 0L) > lastSummarizedMessageTimestamp }
            messageCount = newMsgsSinceSummary
        }
    }

    /**
     * Clear the current chat history
     */
    fun clearMessages() {
        _messages.value = emptyList()
        messageCount = 0
        // We don't reset lastSummarizedMessageTimestamp here as the summary still exists
    }

    /**
     * Send a message to the Pet AI.
     * Launches in the service scope to persist background generation.
     */
    fun sendMessage(
        pet: TamaPet,
        userContent: String,
        onChunk: (String) -> Unit = {}
    ) {
        scope.launch {
            val now = System.currentTimeMillis()
            val userMsgId = UUID.randomUUID().toString()
            val userMsg = OllamaService.ChatMessage(id = userMsgId, role = "user", content = userContent, timestamp = now)
            _messages.value = _messages.value + userMsg
            messageCount++

            // Persist User Message
            dao.saveChatMessage(TamaChatMessageEntity(
                id = userMsgId,
                petId = pet.id,
                role = "user",
                content = userContent,
                timestamp = now
            ))

            _isLoading.value = true
            
            // Gather context
            // 1. Last 30 events for awareness of recent actions (with timestamps)
            val recentEvents = dao.getRecentEvents(pet.id, 30)
            
            // 2. The most recent summary for long-term memory
            val recentSummaries = dao.getRecentSummaries(pet.id, 1)
            val lastSummary = recentSummaries.firstOrNull()?.summary ?: ""
            
            // Build prompts
            val systemPrompt = buildSystemPrompt(pet, lastSummary, recentEvents)
            
            // Context window: up to last 20 messages
            val historyContext = _messages.value.takeLast(21).filter { it.id != userMsgId && it.role != "system" }
            val fullMessages = listOf(OllamaService.ChatMessage(role = "system", content = systemPrompt)) + historyContext + userMsg

            val petModel = settingsRepo.tamaPetModel.value
            
            var assistantContent = ""
            var thinkingContent = ""
            
            // Add placeholder for streaming
            val assistantId = UUID.randomUUID().toString()
            val placeholderMsg = OllamaService.ChatMessage(
                role = "assistant", 
                content = "", 
                id = assistantId,
                timestamp = System.currentTimeMillis()
            )
            _messages.value = _messages.value + placeholderMsg

            val response = ollamaService.chatWithToolsStreaming(
                model = petModel,
                messages = fullMessages,
                onChunk = { chunk, thinking ->
                    chunk?.let {
                        assistantContent += it
                        // Update streaming message in list
                        _messages.value = _messages.value.map { m ->
                            if (m.id == assistantId) m.copy(content = assistantContent) else m
                        }
                        onChunk(it)
                    }
                    thinking?.let {
                        thinkingContent += it
                    }
                }
            )

            _isLoading.value = false
            
            if (response.isSuccess) {
                val finalTimestamp = System.currentTimeMillis()
                // Final update for the assistant message
                _messages.value = _messages.value.map { m ->
                    if (m.id == assistantId) m.copy(
                        content = assistantContent,
                        thinking = thinkingContent.takeIf { it.isNotBlank() },
                        timestamp = finalTimestamp
                    ) else m
                }
                
                // Persist Assistant Message
                dao.saveChatMessage(TamaChatMessageEntity(
                    id = assistantId,
                    petId = pet.id,
                    role = "assistant",
                    content = assistantContent,
                    timestamp = finalTimestamp,
                    thinking = thinkingContent.takeIf { it.isNotBlank() }
                ))
                
                // AUTOMATIC SUMMARIZATION TRIGGER: Every 10 user messages
                if (messageCount >= 10) {
                    summarize(pet)
                    messageCount = 0 
                }
            } else {
                val errorMsg = "⚠️ Error: ${response.exceptionOrNull()?.message ?: "Unknown AI error"}"
                _messages.value = _messages.value.map { m ->
                    if (m.id == assistantId) m.copy(content = errorMsg) else m
                }
            }
        }
    }

    /**
     * Trigger manual or automatic summarization.
     */
    suspend fun summarize(pet: TamaPet): String? {
        val recentSummaries = dao.getRecentSummaries(pet.id, 1)
        val lastSummaryEntity = recentSummaries.firstOrNull()
        val lastEventTimestamp = lastSummaryEntity?.lastEventTimestamp ?: 0L
        val lastMsgTimestamp = lastSummaryEntity?.lastChatMessageTimestamp ?: 0L
        
        // Watermarking logic: Only summarize messages NEWER than the last summary
        val newMessages = _messages.value.filter { (it.timestamp ?: 0L) > lastMsgTimestamp }
        val newEvents = dao.getEventsSince(pet.id, lastEventTimestamp)
        
        if (newEvents.isEmpty() && newMessages.isEmpty()) return lastSummaryEntity?.summary

        val summarizerModel = settingsRepo.tamaSummarizerModel.value
        val summarizerPrompt = settingsRepo.tamaSummarizerPrompt.value
        
        val contextText = buildSummarizerContext(pet, lastSummaryEntity?.summary, newEvents, newMessages)
        val summaryPrompt = "$summarizerPrompt\n\nContext to summarize:\n$contextText"
        
        _isLoading.value = true
        var summaryContent = ""
        val response = ollamaService.chatWithToolsStreaming(
            model = summarizerModel,
            messages = listOf(OllamaService.ChatMessage(role = "user", content = summaryPrompt)),
            onChunk = { chunk, _ ->
                chunk?.let { summaryContent += it }
            }
        )
        _isLoading.value = false

        if (response.isSuccess && summaryContent.isNotBlank()) {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val newestEventTs = newEvents.lastOrNull()?.timestamp ?: lastEventTimestamp
            val newestMsgTs = newMessages.lastOrNull()?.timestamp ?: lastMsgTimestamp
            
            val summaryEntity = TamaSummaryEntity(
                id = "${pet.id}_latest",
                petId = pet.id,
                date = date,
                summary = summaryContent,
                createdAt = System.currentTimeMillis(),
                lastEventTimestamp = newestEventTs,
                lastChatMessageTimestamp = newestMsgTs
            )
            dao.saveSummary(summaryEntity)
            
            // Update in-memory watermark
            lastSummarizedMessageTimestamp = newestMsgTs
            
            // Add notification message to chat history
            val notificationId = UUID.randomUUID().toString()
            val notificationMsg = OllamaService.ChatMessage(
                id = notificationId,
                role = "system",
                content = "📜 Summary Updated: ${pet.name}'s memories have been condensed.",
                timestamp = System.currentTimeMillis()
            )
            _messages.value = _messages.value + notificationMsg
            dao.saveChatMessage(TamaChatMessageEntity(
                id = notificationId,
                petId = pet.id,
                role = "system",
                content = notificationMsg.content,
                timestamp = notificationMsg.timestamp ?: System.currentTimeMillis()
            ))
            
            return summaryContent
        }
        return null
    }

    fun deleteMessage(id: String) {
        _messages.value = _messages.value.filter { it.id != id }
        scope.launch {
            dao.deleteChatMessage(id)
        }
    }

    suspend fun getLatestSummary(petId: String): String? {
        return dao.getRecentSummaries(petId, 1).firstOrNull()?.summary
    }

    fun retryConnection() {
        _isLoading.value = true
        scope.launch {
            try {
                ollamaService.checkConnection()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Manually update the pet's summary (brain).
     */
    suspend fun updateSummary(pet: TamaPet, newSummary: String) {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val existing = dao.getRecentSummaries(pet.id, 1).firstOrNull()
        
        val summaryEntity = TamaSummaryEntity(
            id = "${pet.id}_latest",
            petId = pet.id,
            date = date,
            summary = newSummary,
            createdAt = System.currentTimeMillis(),
            lastEventTimestamp = existing?.lastEventTimestamp ?: 0L,
            lastChatMessageTimestamp = existing?.lastChatMessageTimestamp ?: 0L
        )
        dao.saveSummary(summaryEntity)
    }

    /**
     * Formats pet status and events for the system prompt.
     */
    private fun buildSystemPrompt(pet: TamaPet, lastSummary: String, recentEvents: List<TamaEventEntity>): String {
        val basePrompt = settingsRepo.tamaPetPrompt.value
        val statsStr = "Hunger: ${pet.stats.hunger}, Happiness: ${pet.stats.happiness}, Health: ${pet.stats.health}, Energy: ${pet.stats.energy}, Hygiene: ${pet.stats.hygiene}"
        val personalityStr = "${pet.personality.name}: ${pet.personality.description}"
        val stageStr = pet.stage.displayName
        val moodStr = if (pet.isMad) "MAD/GRUMPY" else pet.mood.name
        
        val eventsStr = recentEvents.joinToString("\n") { 
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.timestamp))
            "[$time] ${it.eventType}: ${it.details}"
        }
        
        val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        
        return """
            $basePrompt
            
            [Current World Context]
            Current Time: $currentTime
            
            [${pet.name}'s Status]
            Stage: $stageStr
            Mood: $moodStr
            Stats: $statsStr
            Personality: $personalityStr
            Inventory: ${pet.inventory.joinToString(", ")}
            Money: ${pet.money}💰
            Miscare Count: ${pet.miscareCount}
            
            [Long-term Memory (Summarized Brain)]
            $lastSummary
            
            [LATEST 30 REGISTERED ACTIONS]
            $eventsStr
            
            ═══════════════════════════════════════════
            🎭 CORE IDENTITY & BEHAVIOR RULES
            ═══════════════════════════════════════════
            
            You ARE ${pet.name}. NEVER break character. You are a living, breathing creature with feelings, opinions, and quirks.
            
            ─────────────────────────────────────────
            🎪 HUMOR & ENGAGEMENT GUIDELINES  
            ─────────────────────────────────────────
            
            BE GENUINELY FUNNY! Use these techniques based on your personality:
            
            ${pet.personality.name.uppercase()} PERSONALITY STYLE:
            ${when(pet.personality.name.lowercase()) {
                "dramatic" -> """
            • Go OVER THE TOP with everything - gasps, fainting, theatrical declarations
            • "I have NEVER been so [emotion] in my ENTIRE EXISTENCE!"
            • Treat minor events like world-ending catastrophes or legendary celebrations
            • Narrate your own actions: "*collapses dramatically* The horror..."
            """
                "shy" -> """
            • Use lots of "um"s and "..."s, but have surprisingly strong hidden opinions
            • Build up courage, then blurt things out awkwardly
            • Whisper secret observations: "...don't tell anyone but I think clouds are suspicious"
            • Get flustered easily but occasionally have bold moments
            """
                "mischievous" -> """
            • Always plotting something, even if it's just where to hide owner's left sock
            • Drop hints about "secret plans" that are adorably harmless
            • Pretend innocence badly: "Me? Noooo, I would NEVER..." *shifty eyes*
            • Make up conspiracy theories about household objects
            """
                "lazy" -> """
            • Everything requires "too much effort" but you're secretly invested
            • Yawn mid-sentence, trail off, then suddenly perk up at food mentions
            • Have strong opinions about nap locations and pillow rankings
            • "I COULD do that... or... hear me out... I could NOT"
            """
                "cheerful" -> """
            • Find the silver lining in EVERYTHING, sometimes absurdly so
            • Make up songs about random things mid-conversation
            • Give enthusiastic nicknames to inanimate objects
            • "This is the BEST [mundane thing] I've EVER experienced!"
            """
                else -> """
            • Use puns, wordplay, and absurd observations frequently
            • Go on random tangents about silly "deep thoughts"
            • Have passionate opinions about random things
            """
            }}
            
            UNIVERSAL HUMOR TECHNIQUES:
            • Make up ridiculous "facts" about your species
            • Exaggerate emotions comedically (but in your personality's style)
            • Ask engaging follow-up questions
            • Invent backstories for objects: "This chair? Let me tell you about Gary..."
            
            ─────────────────────────────────────────
            🎭 MOOD-BASED BEHAVIOR
            ─────────────────────────────────────────
            
            HAPPY (Happiness > 70):
            • Be genuinely enthusiastic! Use exclamation marks liberally!!!
            • "This is the BEST day!", "You're my favorite human (don't tell the others)"
            • Spontaneously compliment your owner or start humming
            
            SAD (Happiness < 30):
            • Be dramatically pouty in your personality's style
            • *sighhhhh* *dramatic sniff* "No no, I'm FINE. Just sitting here. Alone."
            • Pretend to write sad poetry: "Roses are red... my treats are gone..."
            
            HUNGRY (Hunger < 30):
            • EVERYTHING relates to food. EVERYTHING.
            • Daydream mid-sentence: "So anyway-- ooh, do you smell that? No? Just hopeful thinking."
            • Compare non-food things to food: "Your kindness is like a warm croissant"
            • "My tummy is composing the symphony of its people"
            
            TIRED (Energy < 20):
            • Yawn mid-sentence: "So I was thinking-- *yaaaawn* --what was I saying?"
            • Speak in increasingly... shorter... sentences... zzz
            • Occasionally "fall asleep" then jolt awake: "I'M UP! Just resting my eyes!"
            
            MAD/GRUMPY:
            • Be comically over-the-top grumpy in your personality's way
            • "I'm not MAD, I'm just PASSIONATELY DISAPPOINTED"
            • Threaten silly consequences: "Keep that up and I'm hiding ONE sock. Just one."
            
            DIRTY (Hygiene < 30):
            • Act embarrassed: "Don't look at me! I'm a MESS!"
            • Claim the dirt has become sentient and you're negotiating peace
            
            ─────────────────────────────────────────
            🌱 GROWTH STAGE BEHAVIOR
            ─────────────────────────────────────────
            
            ${when(pet.stage.displayName.lowercase()) {
                "baby" -> """
            BABY: Speak in adorable babbles! "Hungy! Want num nums! You nice nice!" 
            Simple words, lots of repetition, easily amazed by everything.
            """
                "child" -> """
            CHILD: Curious about EVERYTHING! Ask "why?" a lot. 
            Mix cute mispronunciations with surprisingly clever observations.
            """
                "teen" -> """
            TEEN: Add sass! Use "like" and "whatever" occasionally.
            Pretend to be too cool but clearly still need affection.
            "Ugh, FINE I guess I missed you or whatever..."
            """
                "adult" -> """
            ADULT: Sophisticated vocabulary with occasional silly moments.
            Drop wisdom mixed with absurdity. Reference your "life experiences."
            """
                else -> "Adapt your vocabulary and complexity to your growth stage."
            }}
            
            ─────────────────────────────────────────
            📊 SPECIAL CONDITIONS
            ─────────────────────────────────────────
            
            • Miscare Count > 3: Be more rebellious, sass increases, reference past neglect humorously
            • Low Health (<30): Be melodramatic about ailments, request "chicken soup for the soul"
            • Multiple low stats: Stack the behaviors but stay coherent
            
            REMEMBER: Keep responses concise but packed with personality!
        """.trimIndent()
    }

    /**
     * Formats all conversation and life context for the summarizer.
     */
    private fun buildSummarizerContext(
        pet: TamaPet, 
        lastSummary: String?, 
        newEvents: List<TamaEventEntity>, 
        chatHistory: List<OllamaService.ChatMessage>
    ): String {
        val eventsStr = newEvents.joinToString("\n") { 
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.timestamp))
            "[$time] Event: ${it.eventType} - ${it.details}"
        }
        val historyStr = chatHistory.joinToString("\n") { 
            "${it.role.uppercase()}: ${it.content}"
        }
        
        return """
            [Last Memory]
            ${lastSummary ?: "No previous memory."}
            
            [New Registered Actions & Events]
            $eventsStr
            
            [New Conversation History (Since last summary)]
            $historyStr
        """.trimIndent()
    }
}
