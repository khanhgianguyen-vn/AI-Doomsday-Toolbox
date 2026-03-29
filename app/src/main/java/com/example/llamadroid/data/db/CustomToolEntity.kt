package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * CustomToolEntity - User-defined tools for the AI agent
 * 
 * These tools can execute shell commands with parameter substitution.
 * Example usage is provided to help the LLM understand how to call the tool.
 */
@Entity(tableName = "custom_tools")
data class CustomToolEntity(
    @PrimaryKey val name: String,
    val description: String,
    val parametersJson: String, // JSON: {"param1": "description", "param2": "description"}
    val requiredParamsJson: String, // JSON array: ["param1"]
    val commandTemplate: String, // e.g., "curl -X POST {url} -d '{body}'"
    val exampleUsage: String, // e.g., "tool_call=my_tool(url=\"https://api.com\", body=\"{\\\"key\\\": \\\"value\\\"}\")"
    val workingDirectory: String = "/workspace", // Default working directory
    val needsApproval: Boolean = true, // Whether to require user approval
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
