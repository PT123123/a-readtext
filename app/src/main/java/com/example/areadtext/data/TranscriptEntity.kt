package com.example.areadtext.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "wav_path") val wavPath: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "model_name") val modelName: String = "",
    // true = 主窗口「录音列表」显示（本次录音产生）；false = 仅出现在历史记录（导入等）
    @ColumnInfo(name = "show_in_main") val showInMain: Boolean = false
)
