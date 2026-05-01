package com.example.llamadroid.ui.ai.ollama

import com.example.llamadroid.data.db.OllamaServerEntity

internal fun resolveSelectedOllamaServer(
    servers: List<OllamaServerEntity>,
    selectedServerId: Long?
): OllamaServerEntity? {
    if (selectedServerId == null) return null
    return servers.firstOrNull { it.id == selectedServerId }
}
