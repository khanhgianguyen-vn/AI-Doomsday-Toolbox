package com.example.llamadroid.service

import java.util.UUID

/**
 * AgentSession - Tracks isolated message history for each agent invocation
 * 
 * This ensures sub-agents (CODER, REVIEWER, etc.) only see their own messages
 * and the task given by their parent, not other agents' work.
 */
data class AgentSession(
    val id: String = UUID.randomUUID().toString(),
    val agentType: String,
    val parentSessionId: String? = null,
    val inputFromParent: String? = null,
    val contextFromParent: String? = null,
    val contract: String? = null,
    val messages: MutableList<AgentService.Companion.ChatMessage> = mutableListOf(),
    val startedAt: Long = System.currentTimeMillis()
) {
    /**
     * Add a message to this session's history
     */
    fun addMessage(message: AgentService.Companion.ChatMessage) {
        messages.add(message)
    }
    
    /**
     * Clear messages (for cleanup)
     */
    fun clear() = messages.clear()
}
