package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import com.example.areadtext.reader.TextSegmenter
import java.io.File

/**
 * TXT 解析器：纯文本文件 → [Book]。
 *
 * 按空行分段 → [TextSegmenter] 切句，整本视为一个章节。
 * 支持 UTF-8 / GBK 自动检测（Android 原生 CharsetDetector）。
 */
object TxtBookParser : BookParser {

    private const val TAG = "TxtBookParser"

    override fun parse(filePath: String, bookId: String): Book? {
        return try {
            val file = File(filePath)
            val text = readText(file)
            if (text.isBlank()) {
                Log.w(TAG, "TXT 无文本内容: $filePath")
                return null
            }
            val title = file.nameWithoutExtension
            val chapter = Chapter(
                id = "txt-root",
                title = title,
                text = text,
                paragraphs = TextSegmenter.paragraphsOf(text),
            )
            Book(
                bookId = bookId,
                title = title,
                author = "",
                filePath = filePath,
                chapters = listOf(chapter),
            )
        } catch (e: Exception) {
            Log.e(TAG, "TXT parse failed: ${e.message}", e)
            null
        }
    }

    private fun readText(file: File): String {
        val raw = file.readBytes()
        // 尝试 UTF-8 解码；若乱码率高则回退 GBK
        val utf8 = String(raw, Charsets.UTF_8)
        return if (isValidChinese(utf8)) utf8 else String(raw, java.nio.charset.Charset.forName("GBK"))
    }

    /** 如果 UTF-8 解码后出现大量替换字符，说明实际是 GBK。 */
    private fun isValidChinese(text: String): Boolean {
        val total = text.length.coerceAtLeast(1)
        val bad = text.count { it == '�' }
        return bad * 10 < total // 替换字符 < 10% 视为合法 UTF-8
    }

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)
    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)
    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)
}
