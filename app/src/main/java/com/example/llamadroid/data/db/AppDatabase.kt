package com.example.llamadroid.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Type converters for Room database
 */
class Converters {
    @TypeConverter
    fun fromNoteType(value: NoteType): String = value.name
    
    @TypeConverter
    fun toNoteType(value: String): NoteType = NoteType.valueOf(value)
    
    @TypeConverter
    fun fromWorkflowType(value: WorkflowType): String = value.name
    
    @TypeConverter
    fun toWorkflowType(value: String): WorkflowType = WorkflowType.valueOf(value)
}

@Database(
    entities = [ModelEntity::class, ChunkEntity::class, KnowledgeBaseEntity::class, ChatMessageEntity::class, SystemPromptEntity::class, NoteEntity::class, ZimEntity::class, com.example.llamadroid.data.model.SavedWorker::class, WorkflowTemplateEntity::class], 
    version = 14,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun modelDao(): ModelDao
    abstract fun ragDao(): RagDao
    abstract fun chatDao(): ChatDao
    abstract fun systemPromptDao(): SystemPromptDao
    abstract fun noteDao(): NoteDao
    abstract fun zimDao(): ZimDao
    abstract fun savedWorkerDao(): SavedWorkerDao
    abstract fun workflowTemplateDao(): WorkflowTemplateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "llama_droid_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
