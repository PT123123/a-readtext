package com.example.areadtext.data

import kotlinx.coroutines.flow.Flow

class TranscriptRepository(private val dao: TranscriptDao) {
    val history: Flow<List<TranscriptEntity>> = dao.observeHistory()
    val main: Flow<List<TranscriptEntity>> = dao.observeMain()

    suspend fun insert(
        text: String,
        wavPath: String?,
        durationMs: Long,
        modelName: String,
        showInMain: Boolean = false
    ): Long = dao.insert(
        TranscriptEntity(
            text = text,
            wavPath = wavPath,
            durationMs = durationMs,
            modelName = modelName,
            showInMain = showInMain
        )
    )

    suspend fun delete(t: TranscriptEntity) = dao.delete(t)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun update(t: TranscriptEntity) = dao.update(t)
    suspend fun get(id: Long): TranscriptEntity? = dao.get(id)
}
