package com.example.llamadroid.service

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.llamadroid.data.db.AppDatabase
import com.example.llamadroid.data.db.ZimEntity
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * BroadcastReceiver that handles completed ZIM file downloads.
 * Registers the ZIM in the database and moves it to the SAF folder if configured.
 */
class ZimDownloadReceiver : BroadcastReceiver() {
    
    companion object {
        // Track pending downloads: downloadId -> ZimMetadata
        private val pendingDownloads = ConcurrentHashMap<Long, PendingZimDownload>()
        
        data class PendingZimDownload(
            val id: String,
            val title: String,
            val description: String,
            val language: String,
            val sizeBytes: Long,
            val articleCount: Long,
            val mediaCount: Long,
            val date: String,
            val creator: String,
            val publisher: String,
            val downloadUrl: String,
            val filename: String
        )
        
        /**
         * Register a pending download to be tracked
         */
        fun registerPendingDownload(downloadId: Long, metadata: PendingZimDownload) {
            pendingDownloads[downloadId] = metadata
            DebugLog.log("[ZIM] Registered pending download ID=$downloadId for ${metadata.title}")
        }
        
        /**
         * Get and remove pending download metadata
         */
        fun consumePendingDownload(downloadId: Long): PendingZimDownload? {
            return pendingDownloads.remove(downloadId)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        DebugLog.log("[ZIM] BroadcastReceiver onReceive called! Action: ${intent.action}")
        
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
            DebugLog.log("[ZIM] Ignoring action: ${intent.action}")
            return
        }
        
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        DebugLog.log("[ZIM] Download complete broadcast received for ID=$downloadId")
        
        if (downloadId == -1L) {
            DebugLog.log("[ZIM] Invalid download ID -1, returning")
            return
        }
        
        val pending = consumePendingDownload(downloadId)
        if (pending == null) {
            DebugLog.log("[ZIM] No pending metadata for download ID=$downloadId (not a ZIM download)")
            return
        }
        
        DebugLog.log("[ZIM] Download complete ID=$downloadId for ${pending.title}")
        
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        
        // Query download status
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val status = cursor.getInt(statusIndex)
                
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                    val localUri = cursor.getString(uriIndex)
                    
                    DebugLog.log("[ZIM] Download successful, URI=$localUri")
                    
                    // Handle the completed download with goAsync to ensure it finishes
                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            handleCompletedDownload(context, pending, localUri)
                        } finally {
                            pendingResult.finish()
                        }
                    }
                } else {
                    DebugLog.log("[ZIM] Download failed with status=$status")
                }
            }
        }
    }
    
    private suspend fun handleCompletedDownload(
        context: Context,
        metadata: PendingZimDownload,
        localUri: String
    ) {
        try {
            val sourceFile = File(Uri.parse(localUri).path!!)
            val finalPath = sourceFile.absolutePath
            val actualSize = sourceFile.length()
            
            DebugLog.log("[ZIM] Downloaded file at: $finalPath (${actualSize} bytes)")
            
            // NOTE: We keep files in app storage because kiwix-serve (native binary)
            // cannot access content:// URIs from SAF. The file needs a real path.
            // The SAF folder selection is used for display purposes only.
            
            // Register in database
            val db = AppDatabase.getDatabase(context)
            val zimEntity = ZimEntity(
                id = metadata.id.ifBlank { java.util.UUID.randomUUID().toString() },
                filename = metadata.filename,
                path = finalPath,
                title = metadata.title,
                description = metadata.description,
                language = metadata.language,
                sizeBytes = if (actualSize > 0) actualSize else metadata.sizeBytes,
                articleCount = metadata.articleCount,
                mediaCount = metadata.mediaCount,
                date = metadata.date,
                creator = metadata.creator,
                publisher = metadata.publisher,
                downloadUrl = metadata.downloadUrl,
                catalogEntryId = metadata.id
            )
            
            db.zimDao().insertZim(zimEntity)
            DebugLog.log("[ZIM] Registered in database: ${metadata.title}")
            
        } catch (e: Exception) {
            DebugLog.log("[ZIM] Error handling completed download: ${e.message}")
            e.printStackTrace()
        }
    }
}
