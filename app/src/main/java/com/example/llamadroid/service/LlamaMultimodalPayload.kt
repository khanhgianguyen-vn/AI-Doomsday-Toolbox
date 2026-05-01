package com.example.llamadroid.service

import java.io.File
import java.util.Base64
import com.example.llamadroid.data.model.LlamaMessageEntity
import com.example.llamadroid.data.model.shouldEmbedAudioAttachment

internal fun buildNativeLlamaUserContent(
    userMessage: String,
    imagePath: String? = null,
    audioPath: String? = null
): Any {
    val hasAttachments = !imagePath.isNullOrBlank() || !audioPath.isNullOrBlank()
    if (!hasAttachments) {
        return userMessage
    }

    val parts = mutableListOf<Map<String, Any>>()
    if (userMessage.isNotBlank()) {
        parts += mapOf("type" to "text", "text" to userMessage)
    }

    imagePath
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            parts += mapOf(
                "type" to "image_url",
                "image_url" to mapOf("url" to fileToDataUrl(path, inferImageMimeType(path)))
            )
        }

    audioPath
        ?.takeIf { it.isNotBlank() }
        ?.let { path ->
            parts += mapOf(
                "type" to "input_audio",
                "input_audio" to mapOf(
                    "data" to fileToBase64(path),
                    "format" to inferLlamaInputAudioFormat(path)
                )
            )
        }

    return parts
}

internal fun LlamaMessageEntity.toNativeLlamaContent(): Any {
    return buildNativeLlamaUserContent(
        userMessage = content,
        imagePath = imagePath,
        audioPath = audioPath.takeIf { shouldEmbedAudioAttachment(content, audioPath) }
    )
}

internal fun fileToDataUrl(filePath: String, mimeType: String): String {
    return "data:$mimeType;base64,${fileToBase64(filePath)}"
}

internal fun fileToBase64(filePath: String): String =
    Base64.getEncoder().encodeToString(File(filePath).readBytes())

internal fun inferImageMimeType(filePath: String): String =
    when (File(filePath).extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "gif" -> "image/gif"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "image/jpeg"
    }

internal fun inferLlamaInputAudioFormat(filePath: String): String =
    when (File(filePath).extension.lowercase()) {
        "wav" -> "wav"
        else -> "mp3"
    }
