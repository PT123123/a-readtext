package com.example.areadtext.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 书架上的书（导入的本地 EPUB）。 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val bookId: String,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String? = null,
    val chapterCount: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
)

/** 朗读进度（MoRealm/Lector 的"读到哪了"持久化，粒度到句）。 */
@Entity(tableName = "reading_progress")
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)
