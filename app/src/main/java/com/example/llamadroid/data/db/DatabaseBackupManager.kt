package com.example.llamadroid.data.db

import android.content.Context
import android.net.Uri
import com.example.llamadroid.tama.db.TamaDatabase
import com.example.llamadroid.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Manages backup and restore of both Room databases (AppDatabase + TamaDatabase).
 * 
 * Backups are ZIP files containing the raw .db, -wal, and -shm files for both databases.
 * Uses SAF (Storage Access Framework) URIs for file I/O so the user picks the location.
 */
object DatabaseBackupManager {
    
    private const val TAG = "[DB-Backup]"
    
    // Database file names (must match the names in AppDatabase/TamaDatabase builders)
    private const val APP_DB_NAME = "llama_droid_db"
    private const val TAMA_DB_NAME = "tama_database"
    
    // Journal file suffixes
    private val DB_SUFFIXES = listOf("", "-wal", "-shm")
    
    /**
     * Generate a backup filename with current timestamp.
     */
    fun generateBackupFilename(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "aidoomsday_backup_${dateFormat.format(Date())}.zip"
    }
    
    /**
     * Create a backup ZIP of both databases and write it to the given SAF URI.
     * 
     * @param context Application context
     * @param destinationUri SAF URI where the ZIP file will be written
     * @return Result with the backup filename on success, or error message on failure
     */
    suspend fun createBackup(context: Context, destinationUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("$TAG Starting backup...")
            
            // Checkpoint WAL to ensure all data is flushed to main DB files
            checkpointDatabases(context)
            
            val dbDir = context.getDatabasePath(APP_DB_NAME).parentFile
                ?: return@withContext Result.failure(Exception("Cannot locate database directory"))
            
            val filesToBackup = mutableListOf<File>()
            
            // Collect all database files
            for (dbName in listOf(APP_DB_NAME, TAMA_DB_NAME)) {
                for (suffix in DB_SUFFIXES) {
                    val file = File(dbDir, "$dbName$suffix")
                    if (file.exists()) {
                        filesToBackup.add(file)
                        DebugLog.log("$TAG Including: ${file.name} (${file.length()} bytes)")
                    }
                }
            }
            
            if (filesToBackup.isEmpty()) {
                return@withContext Result.failure(Exception("No database files found"))
            }
            
            // Write ZIP to the SAF URI
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                ZipOutputStream(outputStream).use { zipOut ->
                    for (file in filesToBackup) {
                        val entry = ZipEntry(file.name)
                        zipOut.putNextEntry(entry)
                        FileInputStream(file).use { fis ->
                            fis.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
            } ?: return@withContext Result.failure(Exception("Cannot open output stream"))
            
            DebugLog.log("$TAG Backup completed: ${filesToBackup.size} files")
            Result.success("${filesToBackup.size} files backed up")
            
        } catch (e: Exception) {
            DebugLog.log("$TAG Backup failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Restore databases from a backup ZIP file.
     * 
     * IMPORTANT: After calling this, the app MUST be restarted for changes to take effect,
     * because Room caches the database connection.
     * 
     * @param context Application context
     * @param sourceUri SAF URI of the backup ZIP file
     * @return Result with success message or error
     */
    suspend fun restoreBackup(context: Context, sourceUri: Uri): Result<String> = withContext(Dispatchers.IO) {
        try {
            DebugLog.log("$TAG Starting restore...")
            
            val dbDir = context.getDatabasePath(APP_DB_NAME).parentFile
                ?: return@withContext Result.failure(Exception("Cannot locate database directory"))
            
            // Close existing database instances so we can overwrite files
            closeDatabases()
            
            var restoredCount = 0
            val validFileNames = buildSet {
                for (dbName in listOf(APP_DB_NAME, TAMA_DB_NAME)) {
                    for (suffix in DB_SUFFIXES) {
                        add("$dbName$suffix")
                    }
                }
            }
            
            // Read the ZIP and extract database files
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val fileName = entry.name
                        
                        // Only restore known database files (security: prevent path traversal)
                        if (fileName in validFileNames) {
                            val targetFile = File(dbDir, fileName)
                            FileOutputStream(targetFile).use { fos ->
                                zipIn.copyTo(fos)
                            }
                            restoredCount++
                            DebugLog.log("$TAG Restored: $fileName (${targetFile.length()} bytes)")
                        } else {
                            DebugLog.log("$TAG Skipped unknown file: $fileName")
                        }
                        
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(Exception("Cannot open backup file"))
            
            if (restoredCount == 0) {
                return@withContext Result.failure(Exception("No valid database files found in backup"))
            }
            
            DebugLog.log("$TAG Restore completed: $restoredCount files")
            Result.success("$restoredCount files restored")
            
        } catch (e: Exception) {
            DebugLog.log("$TAG Restore failed: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Checkpoint WAL to flush pending writes to the main database files.
     */
    private fun checkpointDatabases(context: Context) {
        try {
            val appDb = AppDatabase.getDatabase(context)
            appDb.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            DebugLog.log("$TAG AppDatabase WAL checkpointed")
        } catch (e: Exception) {
            DebugLog.log("$TAG AppDatabase checkpoint failed: ${e.message}")
        }
        
        try {
            val tamaDb = TamaDatabase.getInstance(context)
            tamaDb.openHelper.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            DebugLog.log("$TAG TamaDatabase WAL checkpointed")
        } catch (e: Exception) {
            DebugLog.log("$TAG TamaDatabase checkpoint failed: ${e.message}")
        }
    }
    
    /**
     * Close both database instances so files can be safely overwritten during restore.
     */
    private fun closeDatabases() {
        try {
            AppDatabase.closeInstance()
            DebugLog.log("$TAG AppDatabase closed")
        } catch (e: Exception) {
            DebugLog.log("$TAG AppDatabase close failed: ${e.message}")
        }
        
        try {
            TamaDatabase.closeInstance()
            DebugLog.log("$TAG TamaDatabase closed")
        } catch (e: Exception) {
            DebugLog.log("$TAG TamaDatabase close failed: ${e.message}")
        }
    }
}
