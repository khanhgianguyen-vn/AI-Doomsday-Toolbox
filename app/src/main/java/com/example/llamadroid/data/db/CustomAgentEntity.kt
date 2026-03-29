package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * CustomAgentEntity - User-defined agents for the AI agent system
 * 
 * Custom agents can be called by the Orchestrator just like built-in agents.
 * They have their own system prompt, model preference, and allowed tools.
 */
@Entity(tableName = "custom_agents")
data class CustomAgentEntity(
    @PrimaryKey val name: String, // e.g., "DEBUGGER"
    val displayName: String, // e.g., "Debugger"
    val emoji: String = "🤖", // Displayed in UI
    val systemPrompt: String, // The agent's core instructions
    val model: String? = null, // Optional override model (null = use default)
    val allowedToolsJson: String = "[]", // JSON array of tool names this agent can use
    val exampleUsage: String = "", // Example of how to call this agent
    val canDelegateToOthers: Boolean = false, // Can this agent call other agents?
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
