package com.example.llamadroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * StagedFileCache - Holds pending file writes until user approves
 * 
 * When agent calls write_file, content is staged here instead of
 * being written to disk. The agent can read staged files as if they
 * were already written. On approval, content is flushed to disk.
 */
object StagedFileCache {
    
    data class StagedFile(
        val id: String = UUID.randomUUID().toString(),
        val path: String,
        val content: String,
        val originalContent: String?, // For diff display (null = new file)
        val timestamp: Long = System.currentTimeMillis(),
        val agentRole: String? = null
    )
    
    private val _stagedFiles = MutableStateFlow<Map<String, StagedFile>>(emptyMap())
    val stagedFiles: StateFlow<Map<String, StagedFile>> = _stagedFiles
    
    /**
     * Stage a file write for later approval
     * @return The staged file id
     */
    fun stage(path: String, content: String, originalContent: String?, agentRole: String?): StagedFile {
        val staged = StagedFile(
            path = path,
            content = content,
            originalContent = originalContent,
            agentRole = agentRole
        )
        _stagedFiles.value = _stagedFiles.value + (path to staged)
        return staged
    }
    
    /**
     * Get staged content for a path (returns null if not staged)
     */
    fun getStagedContent(path: String): String? {
        return _stagedFiles.value[path]?.content
    }
    
    /**
     * Check if a file is staged
     */
    fun isStaged(path: String): Boolean {
        return _stagedFiles.value.containsKey(path)
    }
    
    /**
     * Get all pending approvals
     */
    fun getAllPending(): List<StagedFile> {
        return _stagedFiles.value.values.sortedBy { it.timestamp }
    }
    
    /**
     * Get count of pending approvals
     */
    fun pendingCount(): Int = _stagedFiles.value.size
    
    /**
     * Approve a staged file (removes from cache, returns content to write)
     */
    fun approve(path: String): StagedFile? {
        val staged = _stagedFiles.value[path]
        if (staged != null) {
            _stagedFiles.value = _stagedFiles.value - path
        }
        return staged
    }
    
    /**
     * Deny a staged file (removes from cache)
     */
    fun deny(path: String): StagedFile? {
        val staged = _stagedFiles.value[path]
        if (staged != null) {
            _stagedFiles.value = _stagedFiles.value - path
        }
        return staged
    }
    
    /**
     * Approve all pending files
     */
    fun approveAll(): List<StagedFile> {
        val all = _stagedFiles.value.values.toList()
        _stagedFiles.value = emptyMap()
        return all
    }
    
    /**
     * Deny all pending files
     */
    fun denyAll(): List<StagedFile> {
        val all = _stagedFiles.value.values.toList()
        _stagedFiles.value = emptyMap()
        return all
    }
    
    /**
     * Clear all staged files (e.g., on new conversation)
     */
    fun clear() {
        _stagedFiles.value = emptyMap()
    }
}
