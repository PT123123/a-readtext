package com.example.areadtext.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {
    @Insert
    suspend fun insert(t: TranscriptEntity): Long

    @Update
    suspend fun update(t: TranscriptEntity)

    @Query("SELECT * FROM transcripts WHERE show_in_main = 0 ORDER BY created_at DESC")
    fun observeHistory(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE show_in_main = 1 ORDER BY created_at DESC")
    fun observeMain(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts ORDER BY created_at DESC")
    suspend fun getAll(): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    suspend fun get(id: Long): TranscriptEntity?

    @Delete
    suspend fun delete(t: TranscriptEntity)

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
