package com.example.areadtext.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TranscriptEntity::class, BookEntity::class, ProgressEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun bookDao(): BookDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 -> v2: 新增 show_in_main 列，老数据默认 0（留在历史记录）。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transcripts ADD COLUMN show_in_main INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 -> v3: 新增书架 books 表 + 朗读进度 reading_progress 表。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `books` (
                        `bookId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `coverPath` TEXT,
                        `chapterCount` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`bookId`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `reading_progress` (
                        `bookId` TEXT NOT NULL,
                        `chapterIndex` INTEGER NOT NULL,
                        `paragraphIndex` INTEGER NOT NULL,
                        `sentenceIndex` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`bookId`)
                    )"""
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "areadtext.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build().also { INSTANCE = it }
            }
    }
}
