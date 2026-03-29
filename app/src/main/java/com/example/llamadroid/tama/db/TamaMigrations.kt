package com.example.llamadroid.tama.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.llamadroid.util.DebugLog

/**
 * Database migrations for TamaDatabase.
 * 
 * IMPORTANT: When changing the Tama database schema:
 * 1. Increment the version number in TamaDatabase
 * 2. Add a new migration object here
 * 3. Add the migration to ALL_MIGRATIONS list
 * 4. Test the migration thoroughly before release
 */
object TamaMigrations {
    
    /**
     * Versions where destructive migration is allowed.
     * These are early development versions before the migration system was added.
     * From v13 onwards, proper migrations should be used.
     */
    val DESTRUCTIVE_FALLBACK_VERSIONS: IntArray = intArrayOf(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12
    )
    
    // ========== MIGRATIONS ==========
    
    // Migration from v13 to v14: Add adventure tables, remove RPG stats columns
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            DebugLog.log("[TamaDB] Running migration 13 -> 14: Adventure system")
            
            // Create adventure_sessions table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS adventure_sessions (
                    id TEXT PRIMARY KEY NOT NULL,
                    petId TEXT NOT NULL,
                    dungeonType TEXT NOT NULL,
                    schematicJson TEXT NOT NULL,
                    currentStage INTEGER NOT NULL,
                    isCompleted INTEGER NOT NULL,
                    cumulativeSummary TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    lastPlayedAt INTEGER NOT NULL
                )
            """)
            
            // Create adventure_stages table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS adventure_stages (
                    id TEXT PRIMARY KEY NOT NULL,
                    sessionId TEXT NOT NULL,
                    stageNumber INTEGER NOT NULL,
                    storyContent TEXT NOT NULL,
                    userResponse TEXT,
                    stageSummary TEXT,
                    timestamp INTEGER NOT NULL
                )
            """)
            
            // Create dungeon_progress table
            database.execSQL("""
                CREATE TABLE IF NOT EXISTS dungeon_progress (
                    petId TEXT PRIMARY KEY NOT NULL,
                    completedDungeonCount INTEGER NOT NULL DEFAULT 0,
                    lastCompletedDungeonType TEXT
                )
            """)
            
            // Note: RPG columns (level, experience, strength, etc.) are left in the table
            // for backwards compatibility but ignored by the app. They can be removed
            // in a future migration with table recreation.
        }
    }
    
    /**
     * All migrations that should be applied.
     * Add new migrations to this list when schema changes.
     */
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(
        MIGRATION_13_14
    )
    
    // ========== HELPER FUNCTIONS ==========
    
    /**
     * Check if a column exists in a table.
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
}
