package com.example.llamadroid.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.util.DebugLog

/**
 * Database migrations for AppDatabase.
 * 
 * IMPORTANT: When changing the database schema:
 * 1. Increment the version number in AppDatabase
 * 2. Add a new migration object here (e.g., MIGRATION_27_28)
 * 3. Add the migration to ALL_MIGRATIONS list
 * 4. Test the migration thoroughly before release
 * 
 * Never use fallbackToDestructiveMigration() in production as it causes data loss.
 */
object Migrations {
    
    /**
     * All migrations that should be applied.
     * Add new migrations to this list.
     */
    
    /**
     * Versions where destructive migration is allowed.
     * These are early development versions before production release.
     * Once app is released to users, remove versions from this list.
     */
    val DESTRUCTIVE_FALLBACK_VERSIONS: IntArray = intArrayOf(
        // Early development versions (1-26) can use destructive migration
        // since they were pre-release
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
        11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
        21, 22, 23, 24, 25, 26
    )
    
    // ========== EXAMPLE MIGRATION TEMPLATE ==========
    // Uncomment and modify when you need version 28:
    val MIGRATION_27_28 = object : Migration(27, 28) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 27 -> 28")
            
            // Add isSuspicious column to agent_messages table
            if (columnExists(database, "agent_messages", "isSuspicious")) {
                 DebugLog.log("[DB] Column isSuspicious already exists in agent_messages")
            } else {
                database.execSQL("ALTER TABLE agent_messages ADD COLUMN isSuspicious INTEGER NOT NULL DEFAULT 0")
            }
            
            DebugLog.log("[DB] Migration 27 -> 28 complete")
        }
    }

    val MIGRATION_28_29 = object : Migration(28, 29) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 28 -> 29")

            // Create ollama_servers table
            if (!tableExists(database, "ollama_servers")) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `ollama_servers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `lastConnected` INTEGER NOT NULL)")
            }

            DebugLog.log("[DB] Migration 28 -> 29 complete")
        }
    }
    
    // ========== HELPER FUNCTIONS ==========
    
    /**
     * All migrations that should be applied.
     * Add new migrations to this list.
     */
    val MIGRATION_29_30 = object : Migration(29, 30) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 29 -> 30")

            // Create llama_servers table
            if (!tableExists(database, "llama_servers")) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `llama_servers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `host` TEXT NOT NULL, `port` INTEGER NOT NULL, `lastUsed` INTEGER NOT NULL)")
            }

            // Create llama_chats table
            if (!tableExists(database, "llama_chats")) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `llama_chats` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `lastModified` INTEGER NOT NULL, `contextSize` INTEGER NOT NULL, `apiParams` TEXT)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_chats_lastModified` ON `llama_chats` (`lastModified`)")
            }

            // Create llama_messages table
            if (!tableExists(database, "llama_messages")) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `llama_messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` INTEGER NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isError` INTEGER NOT NULL, FOREIGN KEY(`chatId`) REFERENCES `llama_chats`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_llama_messages_chatId` ON `llama_messages` (`chatId`)")
            }

            DebugLog.log("[DB] Migration 29 -> 30 complete")
        }
    }

    val MIGRATION_30_31 = object : Migration(30, 31) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 30 -> 31")

            // Add systemPrompt to llama_chats
            if (!columnExists(database, "llama_chats", "systemPrompt")) {
                database.execSQL("ALTER TABLE `llama_chats` ADD COLUMN `systemPrompt` TEXT DEFAULT NULL")
            }

            // Add supportsVision to llama_servers
            if (!columnExists(database, "llama_servers", "supportsVision")) {
                database.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `supportsVision` INTEGER NOT NULL DEFAULT 0")
            }

            // Add modelName to llama_servers
            if (!columnExists(database, "llama_servers", "modelName")) {
                database.execSQL("ALTER TABLE `llama_servers` ADD COLUMN `modelName` TEXT DEFAULT NULL")
            }

            DebugLog.log("[DB] Migration 30 -> 31 complete")
        }
    }

    val MIGRATION_31_32 = object : Migration(31, 32) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 31 -> 32")

            // Add isTruncated to llama_messages
            if (!columnExists(database, "llama_messages", "isTruncated")) {
                database.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `isTruncated` INTEGER NOT NULL DEFAULT 0")
            }
            
            // Add thinking to llama_messages
            if (!columnExists(database, "llama_messages", "thinking")) {
                database.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `thinking` TEXT DEFAULT NULL")
            }

            DebugLog.log("[DB] Migration 31 -> 32 complete")
        }
    }

    val MIGRATION_32_33 = object : Migration(32, 33) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 32 -> 33")

            if (!columnExists(database, "llama_messages", "promptTokens")) {
                database.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `promptTokens` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(database, "llama_messages", "completionTokens")) {
                database.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `completionTokens` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(database, "llama_messages", "tps")) {
                database.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `tps` REAL NOT NULL DEFAULT 0.0")
            }

            DebugLog.log("[DB] Migration 32 -> 33 complete")
        }
    }

    val MIGRATION_33_34 = object : Migration(33, 34) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 33 -> 34")

            if (!columnExists(database, "llama_messages", "generationTimeMs")) {
                database.execSQL("ALTER TABLE `llama_messages` ADD COLUMN `generationTimeMs` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 33 -> 34 complete")
        }
    }

    val MIGRATION_34_35 = object : Migration(34, 35) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 34 -> 35")

            // Add sequenceNumber column for stable message ordering
            if (!columnExists(database, "agent_messages", "sequenceNumber")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `sequenceNumber` INTEGER NOT NULL DEFAULT 0")
                // Backfill existing messages with their auto-increment id as sequence
                database.execSQL("UPDATE `agent_messages` SET `sequenceNumber` = `id`")
            }

            DebugLog.log("[DB] Migration 34 -> 35 complete")
        }
    }

    val MIGRATION_35_36 = object : Migration(35, 36) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 35 -> 36")

            // Create saved_commands table
            if (!tableExists(database, "saved_commands")) {
                database.execSQL("CREATE TABLE IF NOT EXISTS `saved_commands` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `command` TEXT NOT NULL)")
            }

            DebugLog.log("[DB] Migration 35 -> 36 complete")
        }
    }

    val MIGRATION_36_37 = object : Migration(36, 37) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 36 -> 37")

            // Expand saved_commands table with all master settings fields
            if (!columnExists(database, "saved_commands", "modelPath")) {
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `modelPath` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `contextSize` INTEGER NOT NULL DEFAULT 4096")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `batchSize` INTEGER NOT NULL DEFAULT 512")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `temperature` REAL NOT NULL DEFAULT 0.7")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `threads` INTEGER NOT NULL DEFAULT 4")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `host` TEXT NOT NULL DEFAULT '127.0.0.1'")
                // Speculative decoding
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `speculativeEnabled` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftModelPath` TEXT")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftMax` INTEGER NOT NULL DEFAULT 16")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftMin` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `draftPMin` REAL NOT NULL DEFAULT 0.75")
                // Advanced
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `parallel` INTEGER")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `cacheRam` INTEGER")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `customFlags` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `flashAttention` INTEGER NOT NULL DEFAULT 0")
                // KV Cache
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheEnabled` INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheTypeK` TEXT NOT NULL DEFAULT 'f16'")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheTypeV` TEXT NOT NULL DEFAULT 'f16'")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `kvCacheReuse` INTEGER NOT NULL DEFAULT 0")
                // Master RAM & Workers
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `masterRamMB` INTEGER NOT NULL DEFAULT 4096")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `workersListStr` TEXT NOT NULL DEFAULT ''")
                // Legacy settings
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `lowMemoryMode` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `enableVision` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `mmprojPath` TEXT")
            }

            DebugLog.log("[DB] Migration 36 -> 37 complete")
        }
    }

    val MIGRATION_37_38 = object : Migration(37, 38) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 37 -> 38")

            if (!columnExists(database, "saved_commands", "scope")) {
                database.execSQL("ALTER TABLE `saved_commands` ADD COLUMN `scope` TEXT NOT NULL DEFAULT 'GENERAL'")
            }

            // Existing presets with an assigned worker list came from Master mode.
            database.execSQL(
                """
                UPDATE `saved_commands`
                SET `scope` = CASE
                    WHEN TRIM(`workersListStr`) != '' THEN 'MASTER'
                    ELSE 'GENERAL'
                END
                """.trimIndent()
            )

            DebugLog.log("[DB] Migration 37 -> 38 complete")
        }
    }

    val MIGRATION_38_39 = object : Migration(38, 39) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 38 -> 39")

            if (!tableExists(database, "ai_runtime_jobs")) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_runtime_jobs` (
                        `jobId` TEXT NOT NULL,
                        `jobKey` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `conversationId` INTEGER,
                        `sessionId` TEXT,
                        `projectFolder` TEXT,
                        `backendIdentifier` TEXT,
                        `modelName` TEXT,
                        `payloadJson` TEXT NOT NULL,
                        `checkpointJson` TEXT,
                        `progressText` TEXT,
                        `errorMessage` TEXT,
                        `resumable` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`jobId`)
                    )
                    """.trimIndent()
                )
                database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_ai_runtime_jobs_jobKey` ON `ai_runtime_jobs` (`jobKey`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_ai_runtime_jobs_status` ON `ai_runtime_jobs` (`status`)")
            }

            DebugLog.log("[DB] Migration 38 -> 39 complete")
        }
    }

    val MIGRATION_39_40 = object : Migration(39, 40) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[DB] Running migration 39 -> 40")

            if (!columnExists(database, "agent_messages", "toolCallId")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `toolCallId` TEXT")
            }
            if (!columnExists(database, "agent_messages", "terminalOutput")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `terminalOutput` TEXT")
            }
            if (!columnExists(database, "agent_messages", "isTerminalVisible")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `isTerminalVisible` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(database, "agent_messages", "planModifiedContent")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `planModifiedContent` TEXT")
            }
            if (!columnExists(database, "agent_messages", "isDelegation")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `isDelegation` INTEGER NOT NULL DEFAULT 0")
            }
            if (!columnExists(database, "agent_messages", "customAgentName")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `customAgentName` TEXT")
            }
            if (!columnExists(database, "agent_messages", "pendingToolCall")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `pendingToolCall` TEXT")
            }
            if (!columnExists(database, "agent_messages", "isOutputExpanded")) {
                database.execSQL("ALTER TABLE `agent_messages` ADD COLUMN `isOutputExpanded` INTEGER NOT NULL DEFAULT 0")
            }

            DebugLog.log("[DB] Migration 39 -> 40 complete")
        }
    }

    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_27_28,
        MIGRATION_28_29,
        MIGRATION_29_30,
        MIGRATION_30_31,
        MIGRATION_31_32,
        MIGRATION_32_33,
        MIGRATION_33_34,
        MIGRATION_34_35,
        MIGRATION_35_36,
        MIGRATION_36_37,
        MIGRATION_37_38,
        MIGRATION_38_39,
        MIGRATION_39_40
    )
    /**
     * Check if a column exists in a table.
     * Useful for conditional migrations.
     */
    fun columnExists(database: SupportSQLiteDatabase, tableName: String, columnName: String): Boolean {
        val cursor = database.query("PRAGMA table_info($tableName)")
        val nameIndex = cursor.getColumnIndex("name")
        
        while (cursor.moveToNext()) {
            if (cursor.getString(nameIndex) == columnName) {
                cursor.close()
                return true
            }
        }
        cursor.close()
        return false
    }
    
    /**
     * Check if a table exists in the database.
     */
    fun tableExists(database: SupportSQLiteDatabase, tableName: String): Boolean {
        val cursor = database.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            arrayOf(tableName)
        )
        val exists = cursor.count > 0
        cursor.close()
        return exists
    }
}
