package com.example.areadtext.reader.book

import android.content.Context
import java.io.File

/**
 * 图书解析器：把某种格式的文件 → [Book]（章节 → 段 → 句 + 偏移的统一模型）。
 *
 * 每种格式实现此接口，由 [ParserRegistry] 按扩展名分发。解析产出同一 [Book]，
 * 即可无缝接入阅读/朗读管线（ReaderActivity + SyncTimeline + OfflineTtsEngine）。
 */
interface BookParser {
    fun parse(filePath: String, bookId: String): Book?
    fun saveCache(context: Context, book: Book)
    fun loadCache(context: Context, bookId: String): Book?

    /** 缓存文件路径，按 bookId 隔离，避免不同格式间冲突。 */
    fun cacheFile(context: Context, bookId: String): File
}
