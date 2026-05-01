package com.example.llamadroid.data.backup

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChatNotesBackupSupportTest {
    @Test
    fun `markdown image targets are found and remapped`() {
        val content = """
            Research note
            ![diagram](/storage/emulated/0/diagram.png)
            ![remote](https://example.com/image.png)
        """.trimIndent()

        assertEquals(
            listOf("/storage/emulated/0/diagram.png", "https://example.com/image.png"),
            NativeChatNotesBackupSupport.findMarkdownImageTargets(content)
        )

        val rewritten = NativeChatNotesBackupSupport.rewriteMarkdownImageTargets(
            content = content,
            replacements = mapOf("/storage/emulated/0/diagram.png" to "/data/user/0/app/imported/diagram.png")
        )

        assertTrue(rewritten.contains("![diagram](/data/user/0/app/imported/diagram.png)"))
        assertTrue(rewritten.contains("![remote](https://example.com/image.png)"))
    }

    @Test
    fun `zip entry safety rejects traversal and absolute paths`() {
        assertTrue(NativeChatNotesBackupSupport.isSafeZipEntryName("manifest.json"))
        assertTrue(NativeChatNotesBackupSupport.isSafeZipEntryName("media/chat_images/image.png"))

        assertFalse(NativeChatNotesBackupSupport.isSafeZipEntryName("../manifest.json"))
        assertFalse(NativeChatNotesBackupSupport.isSafeZipEntryName("media/../image.png"))
        assertFalse(NativeChatNotesBackupSupport.isSafeZipEntryName("/media/image.png"))
        assertFalse(NativeChatNotesBackupSupport.isSafeZipEntryName("media\\image.png"))
    }

    @Test
    fun `imported names are made unique without changing the first available name`() {
        val existing = mutableSetOf("Research", "Research (2)")

        assertEquals("Research (3)", NativeChatNotesBackupSupport.uniqueImportedName("Research", existing))
        assertEquals("Notes", NativeChatNotesBackupSupport.uniqueImportedName("Notes", existing))
    }

    @Test
    fun `backup manifest round trips native chat and note media references`() {
        val manifest = NativeChatNotesBackupManifest(
            schemaVersion = 2,
            servers = listOf(
                LlamaServerBackup(oldId = 7, name = "Local", host = "127.0.0.1", port = 11434)
            ),
            chats = listOf(
                LlamaChatBackup(oldId = 3, title = "Research", apiParams = """{"toolsEnabled":true}""")
            ),
            messages = listOf(
                LlamaMessageBackup(
                    oldId = 10,
                    chatOldId = 3,
                    role = "user",
                    content = "see this",
                    imageMediaKey = "chat_image_abc",
                    audioMediaKey = "chat_audio_def"
                )
            ),
            notes = listOf(
                NoteBackup(
                    oldId = 4,
                    title = "Findings",
                    content = "![chart](/old/chart.png)",
                    imageRefs = listOf(NoteImageBackup("/old/chart.png", "note_image_ghi"))
                )
            ),
            organizerSettings = OrganizerSettingsBackup(
                calendarToolsAllowed = true,
                alarmToolsAllowed = true,
                updatedAt = 1000L
            ),
            organizerEvents = listOf(
                OrganizerEventBackup(
                    oldId = 11,
                    title = "Doctor",
                    description = "Bring notes",
                    location = "Clinic",
                    startAtMillis = 2000L,
                    endAtMillis = 2600L,
                    colorArgb = 0xFF3366CC
                )
            ),
            organizerAlarms = listOf(
                OrganizerAlarmBackup(
                    oldId = 12,
                    eventOldId = 11,
                    title = "Leave soon",
                    message = "Take documents",
                    triggerAtMillis = 1800L
                )
            ),
            scheduledTasks = listOf(
                LlamaScheduledTaskBackup(
                    oldId = 21,
                    name = "Tech news",
                    taskPrompt = "Summarize the morning news.",
                    scheduleType = "DAILY",
                    timeOfDayMinutes = 7 * 60,
                    nextRunAtMillis = 3000L
                )
            ),
            scheduledTaskLogs = listOf(
                LlamaScheduledTaskLogBackup(
                    oldId = 22,
                    taskOldId = 21,
                    taskName = "Tech news",
                    scheduledAtMillis = 3000L,
                    status = "SUCCESS",
                    finalOutput = "Created a note."
                )
            ),
            onnxGalleryImages = listOf(
                OnnxGalleryImageBackup(
                    originalPath = "/old/onnx.png",
                    fileName = "onnx.png",
                    imageMediaKey = "onnx_image_xyz"
                )
            ),
            tamaBackup = TamaBackupEntry(
                zipPath = "tama/tama_backup.zip",
                sizeBytes = 42L
            ),
            models = listOf(
                ModelBackup(
                    filename = "local.gguf",
                    path = "/old/local.gguf",
                    repoId = "local-import",
                    modelFileGroupKey = "model_jkl"
                )
            ),
            modelFiles = listOf(
                ModelFileBackupEntry(
                    modelKey = "model_jkl",
                    relativePath = "local.gguf",
                    zipPath = "models/model_jkl/local.gguf",
                    sizeBytes = 123L
                )
            ),
            media = listOf(
                MediaBackupEntry("chat_image_abc", "CHAT_IMAGE", "media/chat_images/image.png"),
                MediaBackupEntry("chat_audio_def", "CHAT_AUDIO", "media/chat_audio/audio.m4a"),
                MediaBackupEntry("note_image_ghi", "NOTE_IMAGE", "media/note_images/chart.png"),
                MediaBackupEntry("onnx_image_xyz", "ONNX_GALLERY_IMAGE", "media/onnx_gallery/onnx.png")
            )
        )

        val parsed = Gson().fromJson(Gson().toJson(manifest), NativeChatNotesBackupManifest::class.java)

        assertEquals(2, parsed.schemaVersion)
        assertEquals("Local", parsed.servers.single().name)
        assertEquals("""{"toolsEnabled":true}""", parsed.chats.single().apiParams)
        assertEquals("chat_image_abc", parsed.messages.single().imageMediaKey)
        assertEquals("note_image_ghi", parsed.notes.single().imageRefs.single().mediaKey)
        assertTrue(parsed.organizerSettings?.calendarToolsAllowed == true)
        assertEquals("Doctor", parsed.organizerEvents.orEmpty().single().title)
        assertEquals(11L, parsed.organizerAlarms.orEmpty().single().eventOldId)
        assertEquals("Tech news", parsed.scheduledTasks.orEmpty().single().name)
        assertEquals(21L, parsed.scheduledTaskLogs.orEmpty().single().taskOldId)
        assertEquals("onnx.png", parsed.onnxGalleryImages.orEmpty().single().fileName)
        assertEquals("tama/tama_backup.zip", parsed.tamaBackup?.zipPath)
        assertEquals("local-import", parsed.models.single().repoId)
        assertEquals("models/model_jkl/local.gguf", parsed.modelFiles.single().zipPath)
        assertEquals(4, parsed.media.size)
    }
}
