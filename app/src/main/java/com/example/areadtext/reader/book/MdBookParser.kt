package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Markdown 解析器：剥离 Markdown 标记后按纯文本分段 → [Book]。
 *
 * 去除标题符(#)、加粗斜体(*)、列表符(-*)、链接[text](url)，保留纯文本正文。
 */
object MdBookParser : BookParser {

    private const val TAG = "MdBookParser"

    private val MARKDOWN_PATTERNS = listOf(
        Regex("""^#{1,6}\s+"""),            // # 标题 → 空
        Regex("""(\*\*|__)(.+?)\1"""),       // **加粗** / __加粗__
        Regex("""(\*|_)(.+?)\1"""),          // *斜体* / _斜体_
        Regex("""\[(.+?)\]\(.*?\)"""),       // [text](url) → text
        Regex("""^\s*[-*]\s+"""),            // - 列表 → 空
        Regex("""^\s*\d+\.\s+"""),           // 1. 列表 → 空
        Regex("""```.*?```", RegexOption.DOT_ALL), // 代码块 → 空
        Regex("""`(.+?)`"""),                // `代码`
    )

    override fun parse(filePath: String, bookId: String): Book? {
        return try {
            val file = File(filePath)
            val raw = file.readText(Charsets.UTF_8)
            val text = stripMarkdown(normalize(raw))
            if (text.isBlank()) {
                Log.w(TAG, "MD 无文本内容: $filePath")
                return null
            }
            val title = extractTitle(file, raw)
            val chapter = Chapter(
                id = "md-root",
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
            Log.e(TAG, "MD parse failed: ${e.message}", e)
            null
        }
    }

    private fun stripMarkdown(text: String): String {
        var result = text
        MARKDOWN_PATTERNS.forEach { result = it.replace(result, "") }
        return result.trim()
    }

    private fun extractTitle(file: File, raw: String): String {
        val m = Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(raw)
        return m?.groupValues?.get(1)?.trim()?.take(60) ?: file.nameWithoutExtension
    }

    private fun normalize(text: String): String = text
        .replace(Regex("[ \\t\\u00a0]+"), " ")
        .replace(Regex("\\s*\n\\s*"), "\n")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)
    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)
    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)
}
