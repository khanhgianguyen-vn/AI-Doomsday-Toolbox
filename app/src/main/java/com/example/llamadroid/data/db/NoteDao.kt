package com.example.llamadroid.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for notes operations
 */
@Dao
interface NoteDao {
    
    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    suspend fun getAllNotesOnce(): List<NoteEntity>
    
    @Query("SELECT * FROM notes WHERE type = :type ORDER BY updatedAt DESC")
    fun getNotesByType(type: NoteType): Flow<List<NoteEntity>>
    
    @Query("SELECT * FROM notes WHERE title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun searchNotes(query: String): Flow<List<NoteEntity>>
    
    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Int): NoteEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: NoteEntity): Long
    
    @Update
    suspend fun update(note: NoteEntity)
    
    @Delete
    suspend fun delete(note: NoteEntity)
    
    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM notes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("UPDATE notes SET isLlmWhitelisted = :allowed WHERE id IN (:ids)")
    suspend fun setLlmWhitelisted(ids: List<Int>, allowed: Boolean)
    
    @Query("SELECT COUNT(*) FROM notes")
    fun getNoteCount(): Flow<Int>
    
    @Query("SELECT COUNT(*) FROM notes WHERE type = :type")
    fun getNoteCountByType(type: NoteType): Flow<Int>
}
