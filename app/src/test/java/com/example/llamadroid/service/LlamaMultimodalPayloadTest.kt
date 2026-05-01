package com.example.llamadroid.service

import com.example.llamadroid.data.model.LlamaMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LlamaMultimodalPayloadTest {

    @Test
    fun `text only content stays a string`() {
        val content = buildNativeLlamaUserContent("hello world")

        assertTrue(content is String)
        assertEquals("hello world", content)
    }

    @Test
    fun `attachment only content serializes without text part`() {
        val audioFile = tempFile(".ogg", byteArrayOf(7, 8, 9))

        val content = buildNativeLlamaUserContent(
            userMessage = "",
            audioPath = audioFile.absolutePath
        )

        val parts = content as List<*>
        assertEquals(1, parts.size)

        val audioPart = parts[0] as Map<*, *>
        assertEquals("input_audio", audioPart["type"])
        val inputAudio = audioPart["input_audio"] as Map<*, *>
        assertEquals("mp3", inputAudio["format"])
        assertEquals("BwgJ", inputAudio["data"])
    }

    @Test
    fun `audio attachment serializes as llama input audio data`() {
        val audioFile = tempFile(".wav", byteArrayOf(1, 2, 3, 4))

        val content = buildNativeLlamaUserContent(
            userMessage = "transcribe this",
            audioPath = audioFile.absolutePath
        )

        val parts = content as List<*>
        assertEquals(2, parts.size)
        assertEquals("text", (parts[0] as Map<*, *>)["type"])

        val audioPart = parts[1] as Map<*, *>
        assertEquals("input_audio", audioPart["type"])
        val inputAudio = audioPart["input_audio"] as Map<*, *>
        assertEquals("wav", inputAudio["format"])
        assertEquals("AQIDBA==", inputAudio["data"])
    }

    @Test
    fun `image and audio attachments preserve order`() {
        val imageFile = tempFile(".jpg", byteArrayOf(9, 8, 7))
        val audioFile = tempFile(".m4a", byteArrayOf(4, 5, 6))

        val content = buildNativeLlamaUserContent(
            userMessage = "look and listen",
            imagePath = imageFile.absolutePath,
            audioPath = audioFile.absolutePath
        )

        val parts = content as List<*>
        assertEquals(3, parts.size)

        assertEquals("text", (parts[0] as Map<*, *>)["type"])
        assertEquals("image_url", (parts[1] as Map<*, *>)["type"])
        assertEquals("input_audio", (parts[2] as Map<*, *>)["type"])

        val imageUrl = ((parts[1] as Map<*, *>)["image_url"] as Map<*, *>)["url"] as String
        val inputAudio = (parts[2] as Map<*, *>)["input_audio"] as Map<*, *>
        assertTrue(imageUrl.startsWith("data:image/jpeg;base64,"))
        assertEquals("mp3", inputAudio["format"])
        assertEquals("BAUG", inputAudio["data"])
    }

    @Test
    fun `transcribed audio message keeps text and skips audio payload`() {
        val message = LlamaMessageEntity(
            id = 1,
            chatId = 99,
            role = "user",
            content = "Question\n\nThis is the transcription of an audio sent by the user: Hello there",
            audioPath = "/tmp/audio.m4a"
        )

        val content = message.toNativeLlamaContent()

        assertTrue(content is String)
        assertEquals(message.content, content)
    }

    private fun tempFile(extension: String, bytes: ByteArray): File {
        val file = File.createTempFile("llama-multimodal", extension)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }
}
