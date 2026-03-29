package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ollama_servers")
data class OllamaServerEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val lastConnected: Long = 0
)
