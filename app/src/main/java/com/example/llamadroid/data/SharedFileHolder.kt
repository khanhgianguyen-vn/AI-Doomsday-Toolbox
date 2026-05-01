package com.example.llamadroid.data

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Global holder for shared file URIs from Intent.ACTION_SEND.
 * Screens can check this and consume the pending file.
 */
object SharedFileHolder {
    
    data class PendingFile(
        val uri: Uri,
        val mimeType: String,
        val targetScreen: String? = null  // Specific tool if user chose from dialog
    )
    
    private val _pendingFile = MutableStateFlow<PendingFile?>(null)
    private val pendingFileLock = Any()
    val pendingFile = _pendingFile.asStateFlow()
    
    fun setPendingFile(uri: Uri, mimeType: String, targetScreen: String? = null) {
        synchronized(pendingFileLock) {
            _pendingFile.value = PendingFile(uri, mimeType, targetScreen)
        }
    }
    
    fun consumePendingFile(): PendingFile? = synchronized(pendingFileLock) {
        _pendingFile.value.also {
            _pendingFile.value = null
        }
    }
    
    fun clear() {
        synchronized(pendingFileLock) {
            _pendingFile.value = null
        }
    }
}
