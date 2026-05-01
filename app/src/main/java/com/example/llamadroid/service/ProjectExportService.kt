package com.example.llamadroid.service

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Service for exporting and importing project data
 */
class ProjectExportService(
    private val context: Context,
    private val agentService: AgentService
) {

    private data class ImportedProjectBundle(
        val metadata: JSONObject,
        val messages: List<AgentService.Companion.ChatMessage>,
        val files: Map<String, ByteArray>
    )
    
    /**
     * Export project to a ZIP file
     * Returns the path to the exported file
     */
    suspend fun exportProject(
        projectFolder: String,
        conversationId: Long,
        outputUri: Uri
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(context)
            
            // Get conversation data
            val conversation = db.agentChatDao().getConversation(conversationId)
                ?: return@withContext Result.failure(Exception("Conversation not found"))

            val messages = resolveMessagesForExport(db, conversationId)
            val activeAgent = if (AgentService.activeConversationId.value == conversationId) {
                AgentService.currentAgent.value.name
            } else {
                conversation.lastAgentRole ?: AgentService.Companion.AgentRole.ORCHESTRATOR.name
            }
            val activeTask = if (AgentService.activeConversationId.value == conversationId) {
                AgentService.currentTask.value
            } else {
                conversation.lastTask
            }
            val selectedModel = if (AgentService.activeConversationId.value == conversationId) {
                AgentService.selectedModel.value
            } else {
                null
            }
            
            // Create ZIP
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    
                    // 1. Export conversation metadata
                    val metadata = JSONObject().apply {
                        put("projectFolder", projectFolder)
                        put("title", conversation.title)
                        put("createdAt", conversation.createdAt)
                        put("updatedAt", conversation.updatedAt)
                        put("lastAgentRole", activeAgent)
                        put("lastTask", activeTask)
                        put("selectedModel", selectedModel)
                        put("exportedAt", System.currentTimeMillis())
                        put("version", "0.931")
                    }
                    addJsonToZip(zipOut, "metadata.json", metadata)
                    
                    // 2. Export messages
                    val messagesArray = JSONArray()
                    messages.forEach { msg ->
                        messagesArray.put(AgentService.chatMessageToJson(msg))
                    }
                    addJsonToZip(zipOut, "messages.json", JSONObject().put("messages", messagesArray))
                    
                    // 3. Export raw project files
                    try {
                        agentService.connect()
                        val safeFolder = sanitizeProjectFolder(projectFolder)
                        val projectRoot = "${AgentService.WORKSPACE_PATH}/$safeFolder"
                        val fileListResult = agentService.runCommand("find '$projectRoot' -type f 2>/dev/null | sort")
                        if (fileListResult.isSuccess) {
                            val files = fileListResult.getOrThrow().lines().filter { it.isNotBlank() }
                            
                            for (filePath in files) {
                                val relPath = filePath.removePrefix("$projectRoot/")
                                val contentResult = agentService.readFileBytes(filePath)
                                if (contentResult.isSuccess) {
                                    val content = contentResult.getOrThrow()
                                    zipOut.putNextEntry(ZipEntry("files/$relPath"))
                                    zipOut.write(content)
                                    zipOut.closeEntry()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Files export failed, but continue with conversation data
                        AgentService.addDebugLog("⚠️ Could not export files: ${e.message}")
                    }
                }
            }
            
            Result.success("Export completed successfully")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Import project from a ZIP file
     */
    suspend fun importProject(
        targetProjectFolder: String,
        targetConversationId: Long,
        inputUri: Uri
    ): Result<Long> = withContext(Dispatchers.IO) {
        try {
            val db = com.example.llamadroid.data.db.AppDatabase.getDatabase(context)
            val bundle = readProjectBundle(inputUri)
            val metadata = bundle.metadata
            val importedMessages = bundle.messages
                .sortedBy { it.sequenceNumber }
                .map { message ->
                    message.copy(id = java.util.UUID.randomUUID().toString(), isStreaming = false)
                }

            val targetFolder = sanitizeProjectFolder(targetProjectFolder)
            val existingConversation = db.agentChatDao().getConversation(targetConversationId)
                ?: return@withContext Result.failure(Exception("Conversation not found"))

            agentService.connect().getOrThrow()
            AgentService.setCurrentProjectFolder(targetFolder)
            AgentService.setActiveConversationId(targetConversationId)
            replaceProjectFiles(targetFolder, bundle.files)

            StagedFileCache.clear()
            db.agentChatDao().deleteAllMessagesInConversation(targetConversationId)
            db.agentChatDao().insertMessages(
                importedMessages.map { msg ->
                    AgentService.chatMessageToEntity(msg, targetConversationId)
                }
            )

            val importedTitle = metadata.optString("title", existingConversation.title)
            val importedRole = metadata.optString("lastAgentRole", AgentService.Companion.AgentRole.ORCHESTRATOR.name)
            val importedTask = metadata.optString("lastTask").takeIf { it.isNotBlank() }
            val importedModel = metadata.optString("selectedModel").takeIf { it.isNotBlank() }

            db.agentChatDao().updateConversation(
                existingConversation.copy(
                    title = importedTitle,
                    projectFolder = targetFolder,
                    lastAgentRole = importedRole,
                    lastTask = importedTask,
                    updatedAt = System.currentTimeMillis()
                )
            )

            AgentService.clearTransientConversationState()
            AgentService.clearAllSessions()
            AgentService.setActiveConversationId(targetConversationId)
            AgentService.setCurrentProjectFolder(targetFolder)
            AgentService.setCurrentAgent(
                runCatching { AgentService.Companion.AgentRole.valueOf(importedRole) }
                    .getOrDefault(AgentService.Companion.AgentRole.ORCHESTRATOR)
            )
            AgentService.setCurrentTask(importedTask)
            importedModel?.let { AgentService.setSelectedModel(it) }
            AgentService.resetMessageCounter(importedMessages.maxOfOrNull { it.sequenceNumber } ?: 0)
            AgentService.setMessages(importedMessages)
            AgentService.clearMemoryDirty("Project import replaced workspace and conversation state.")
            AgentService.addDebugLog("✅ Imported project into /workspace/$targetFolder")

            Result.success(targetConversationId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun resolveMessagesForExport(
        db: com.example.llamadroid.data.db.AppDatabase,
        conversationId: Long
    ): List<AgentService.Companion.ChatMessage> {
        if (AgentService.activeConversationId.value == conversationId) {
            return AgentService.messages.value.sortedBy { it.sequenceNumber }
        }
        return db.agentChatDao()
            .getMessagesForConversationSync(conversationId)
            .map { AgentService.chatMessageFromEntity(it) }
    }

    private suspend fun readProjectBundle(inputUri: Uri): ImportedProjectBundle {
        var metadata: JSONObject? = null
        val messages = mutableListOf<AgentService.Companion.ChatMessage>()
        val files = linkedMapOf<String, ByteArray>()

        context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val content = zipIn.readBytes()
                        when {
                            entry.name == "metadata.json" -> metadata = JSONObject(String(content))
                            entry.name == "messages.json" -> {
                                val array = JSONObject(String(content)).optJSONArray("messages") ?: JSONArray()
                                for (i in 0 until array.length()) {
                                    messages += AgentService.chatMessageFromJson(array.getJSONObject(i))
                                }
                            }
                            entry.name.startsWith("files/") -> {
                                val relativePath = sanitizeArchiveRelativePath(entry.name.removePrefix("files/"))
                                files[relativePath] = content
                            }
                        }
                    }
                    entry = zipIn.nextEntry
                }
            }
        }

        val finalMetadata = metadata ?: throw IllegalArgumentException("Invalid export file: missing metadata")
        return ImportedProjectBundle(
            metadata = finalMetadata,
            messages = messages,
            files = files
        )
    }

    private suspend fun replaceProjectFiles(projectFolder: String, files: Map<String, ByteArray>) {
        val projectRoot = "${AgentService.WORKSPACE_PATH}/$projectFolder"
        agentService.runCommand("mkdir -p '$projectRoot'").getOrThrow()
        agentService.runCommand("find '$projectRoot' -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +").getOrThrow()

        for ((relativePath, content) in files) {
            val fullPath = "$projectRoot/$relativePath"
            agentService.writeFileBytes(fullPath, content, trackChange = false).getOrThrow()
        }
    }

    private fun sanitizeArchiveRelativePath(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank()) { "Archive contains an empty file path" }
        require(!normalized.split('/').any { it == ".." || it.isBlank() }) { "Archive contains an unsafe file path: $path" }
        return normalized
    }

    private fun sanitizeProjectFolder(projectFolder: String): String {
        return projectFolder.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    }
    
    private fun addJsonToZip(zipOut: ZipOutputStream, name: String, json: JSONObject) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(json.toString(2).toByteArray())
        zipOut.closeEntry()
    }
    
    companion object {
        fun getExportFileName(projectName: String): String {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            return "llamadroid_${projectName}_$timestamp.zip"
        }
    }
}
