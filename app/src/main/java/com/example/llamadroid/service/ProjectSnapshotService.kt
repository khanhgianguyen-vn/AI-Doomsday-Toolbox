package com.example.llamadroid.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service for managing project snapshots (undo/rollback functionality)
 * Uses git-like commits stored in the /brain/snapshots/ folder
 */
class ProjectSnapshotService(
    private val agentService: AgentService
) {
    
    data class Snapshot(
        val id: String,
        val description: String,
        val timestamp: Long,
        val files: List<String>
    )
    
    private val _snapshots = MutableStateFlow<List<Snapshot>>(emptyList())
    val snapshots: StateFlow<List<Snapshot>> = _snapshots.asStateFlow()
    
    /**
     * Create a new snapshot of the current project state
     */
    suspend fun createSnapshot(
        projectFolder: String,
        description: String
    ): Result<Snapshot> = withContext(Dispatchers.IO) {
        try {
            val timestamp = System.currentTimeMillis()
            val snapshotId = "snap_${timestamp}"
            val snapshotDir = "/workspace/$projectFolder/brain/snapshots/$snapshotId"
            
            // Create snapshot directory
            agentService.runCommand("mkdir -p $snapshotDir")
            
            // Get list of files in project (excluding brain/snapshots)
            val filesResult = agentService.runCommand(
                "find /workspace/$projectFolder -type f ! -path '*/brain/snapshots/*'"
            )
            
            val files = mutableListOf<String>()
            if (filesResult.isSuccess) {
                val fileList = filesResult.getOrThrow().lines().filter { it.isNotBlank() }
                
                // Copy each file to snapshot
                for (filePath in fileList) {
                    val relPath = filePath.removePrefix("/workspace/$projectFolder/")
                    val destPath = "$snapshotDir/files/$relPath"
                    val destDir = destPath.substringBeforeLast("/")
                    
                    agentService.runCommand("mkdir -p '$destDir' && cp '$filePath' '$destPath' 2>/dev/null")
                    files.add(relPath)
                }
            }
            
            // Save snapshot metadata
            val metadata = JSONObject().apply {
                put("id", snapshotId)
                put("description", description)
                put("timestamp", timestamp)
                put("files", JSONArray(files))
            }
            
            agentService.writeFile(
                "$snapshotDir/metadata.json",
                metadata.toString(2)
            )
            
            val snapshot = Snapshot(snapshotId, description, timestamp, files)
            _snapshots.value = listOf(snapshot) + _snapshots.value
            
            AgentService.addDebugLog("📸 Created snapshot: $description (${files.size} files)")
            
            Result.success(snapshot)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Rollback to a specific snapshot
     */
    suspend fun rollbackToSnapshot(
        projectFolder: String,
        snapshotId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val snapshotDir = "/workspace/$projectFolder/brain/snapshots/$snapshotId"
            
            // Read metadata
            val metadataResult = agentService.readFile("$snapshotDir/metadata.json")
            if (metadataResult.isFailure) {
                return@withContext Result.failure(Exception("Snapshot not found: $snapshotId"))
            }
            
            val metadata = JSONObject(metadataResult.getOrThrow())
            val description = metadata.getString("description")
            val filesArray = metadata.getJSONArray("files")
            
            // Build list of files that should exist after rollback
            val snapshotFiles = mutableSetOf<String>()
            for (i in 0 until filesArray.length()) {
                snapshotFiles.add(filesArray.getString(i))
            }
            
            // Step 1: Delete files that weren't in the snapshot (except brain folder)
            val currentFilesResult = agentService.runCommand(
                "find /workspace/$projectFolder -type f ! -path '*/brain/*'"
            )
            if (currentFilesResult.isSuccess) {
                val currentFiles = currentFilesResult.getOrThrow().lines().filter { it.isNotBlank() }
                for (filePath in currentFiles) {
                    val relPath = filePath.removePrefix("/workspace/$projectFolder/")
                    if (relPath !in snapshotFiles) {
                        agentService.runCommand("rm -f '$filePath'")
                    }
                }
            }
            
            // Step 2: Restore each file from snapshot
            var restored = 0
            for (i in 0 until filesArray.length()) {
                val relPath = filesArray.getString(i)
                val srcPath = "$snapshotDir/files/$relPath"
                val destPath = "/workspace/$projectFolder/$relPath"
                val destDir = destPath.substringBeforeLast("/")
                
                agentService.runCommand("mkdir -p '$destDir'")
                
                val contentResult = agentService.readFile(srcPath)
                if (contentResult.isSuccess) {
                    agentService.writeFile(destPath, contentResult.getOrThrow())
                    restored++
                }
            }
            
            AgentService.addDebugLog("⏪ Rolled back to: $description ($restored files restored, extra files removed)")
            
            Result.success("Restored $restored files from snapshot: $description")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * List all snapshots for a project
     */
    suspend fun listSnapshots(
        projectFolder: String
    ): Result<List<Snapshot>> = withContext(Dispatchers.IO) {
        try {
            val snapshotsDir = "/workspace/$projectFolder/brain/snapshots"
            
            val listResult = agentService.runCommand("ls -d $snapshotsDir/*/ 2>/dev/null | head -20")
            if (listResult.isFailure) {
                _snapshots.value = emptyList()
                return@withContext Result.success(emptyList())
            }
            
            val dirs = listResult.getOrThrow().lines().filter { it.isNotBlank() }
            val snapshots = mutableListOf<Snapshot>()
            
            for (dir in dirs) {
                try {
                    val metadataResult = agentService.readFile("${dir.trimEnd('/')}/metadata.json")
                    if (metadataResult.isSuccess) {
                        val meta = JSONObject(metadataResult.getOrThrow())
                        snapshots.add(Snapshot(
                            id = meta.getString("id"),
                            description = meta.getString("description"),
                            timestamp = meta.getLong("timestamp"),
                            files = mutableListOf<String>().also { list ->
                                val arr = meta.getJSONArray("files")
                                for (i in 0 until arr.length()) {
                                    list.add(arr.getString(i))
                                }
                            }
                        ))
                    }
                } catch (e: Exception) {
                    // Skip invalid snapshots
                }
            }
            
            _snapshots.value = snapshots.sortedByDescending { it.timestamp }
            Result.success(_snapshots.value)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Delete a snapshot
     */
    suspend fun deleteSnapshot(
        projectFolder: String,
        snapshotId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val snapshotDir = "/workspace/$projectFolder/brain/snapshots/$snapshotId"
            agentService.runCommand("rm -rf '$snapshotDir'")
            _snapshots.value = _snapshots.value.filter { it.id != snapshotId }
            AgentService.addDebugLog("🗑️ Deleted snapshot: $snapshotId")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    companion object {
        fun formatTimestamp(timestamp: Long): String {
            return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
