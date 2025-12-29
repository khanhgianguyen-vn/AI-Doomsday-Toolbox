package com.example.llamadroid.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a ZIM file for Kiwix offline content.
 * ZIM files are stored in the user-chosen folder (no copying on import).
 */
@Entity(tableName = "zim_files")
data class ZimEntity(
    @PrimaryKey val id: String,           // ZIM UUID from metadata
    val filename: String,                  // e.g., "wikipedia_en_all_mini_2024-01.zim"
    val path: String,                      // Full path to ZIM file
    val title: String,                     // e.g., "Wikipedia (English)"
    val description: String = "",          // Content description
    val language: String = "en",           // ISO 639-1 language code
    val sizeBytes: Long,                   // File size in bytes
    val articleCount: Long = 0,            // Number of articles
    val mediaCount: Long = 0,              // Number of media items
    val date: String = "",                 // Publication date (YYYY-MM-DD)
    val creator: String = "",              // Content creator (e.g., "Kiwix")
    val publisher: String = "",            // Publisher (e.g., "Kiwix")
    val favicon: String? = null,           // Base64 favicon or path
    val tags: String = "",                 // Comma-separated tags
    val downloadUrl: String? = null,       // For re-download capability
    val catalogEntryId: String? = null,     // ID from Kiwix catalog for updates
    val sourceUri: String? = null           // Original SAF URI for re-verification
)
