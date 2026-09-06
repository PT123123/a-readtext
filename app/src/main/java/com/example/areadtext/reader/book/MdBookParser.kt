package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import com.example.areadtext.reader.TextSegmenter
import com.example.areadtext.reader.text.TextNormalizer
import java.io.File

/**
 * Markdown 解析器：剥离 Markdown 标记后按纯文本分段 → [Book]。
 *
 * 增强：
 *  - YAML front matter 剥离
 *  - 图片保留 alt 文本（![alt](url) → alt）
 *  - 表格竖线 → 空格，分隔线行 → 删除
 *  - 脚注引用 [^n] 删除，脚注定义行保留
 *  - 引用块保留内容（去掉 > 前缀）
 */
object MdBookParser : BookParser {

    private const val TAG = "MdBookParser"

    private val MARKDOWN_PATTERNS = listOf(
        // YAML front matter（必须在最前，整体剥离）
        Regex("""^---\s*\r?\n[\s\S]*?\r?\n---\s*\r?\n?""", RegexOption.MULTILINE),
        // 代码块（先于行内代码处理）
        Regex("""```[\s\S]*?```"""),
        // 图片 → alt 文本（![alt](url) → alt）
        Regex("""!\[([^\]]*)\]"""),
        // 链接 → 文本
        Regex("""\[([^\]]+)\]\([^)]+\)"""),
        // 标题符
        Regex("""^#{1,6}\s+""", RegexOption.MULTILINE),
        // 加粗斜体（*** → ** → *）
        Regex("""\*{1,3}(.+?)\*{1,3}"""),
        Regex("""_{1,3}(.+?)_{1,3}"""),
        // 删除线
        Regex("""~~(.+?)~~"""),
        // 列表符
        Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE),
        Regex("""^\s*\d+\.\s+""", RegexOption.MULTILINE),
        // 引用块前缀
        Regex("""^>\s?""", RegexOption.MULTILINE),
        // 行内代码
        Regex("""`([^`]+)`"""),
        // 表格分隔线行（|---|---|）
        Regex("""^\|[-:| ]+\|\s*$""", RegexOption.MULTILINE),
        // 表格竖线 → 空格
        Regex("""\|"""),
        // 脚注引用
        Regex("""\[\^\d+\]"""),
        // HTML 标签
        Regex("""<[^>]+>"""),
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
        // 按顺序应用（顺序很重要：先处理代码块，再处理行内标记）
        result = Regex("""^---\s*\r?\n[\s\S]*?\r?\n---\s*\r?\n?""", RegexOption.MULTILINE).replace(result, "")
        result = Regex("""```[\s\S]*?```""").replace(result, "")
        result = Regex("""!\[([^\]]*)\]""").replace(result, "$1")
        result = Regex("""\[([^\]]+)\]\([^)]+\)""").replace(result, "$1")
        result = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE).replace(result, "")
        result = Regex("""\*{1,3}(.+?)\*{1,3}""").replace(result, "$1")
        result = Regex("""_{1,3}(.+?)_{1,3}""").replace(result, "$1")
        result = Regex("""~~(.+?)~~""").replace(result, "$1")
        result = Regex("""^\s*[-*+]\s+""", RegexOption.MULTILINE).replace(result, "")
        result = Regex("""^\s*\d+\.\s+""", RegexOption.MULTILINE).replace(result, "")
        result = Regex("""^>\s?""", RegexOption.MULTILINE).replace(result, "")
        result = Regex("""`([^`]+)`""").replace(result, "$1")
        result = Regex("""^\|[-:| ]+\|\s*$""", RegexOption.MULTILINE).replace(result, "")
        result = Regex("""\|""").replace(result, " ")
        result = Regex("""\[\^\d+\]""").replace(result, "")
        result = Regex("""<[^>]+>""").replace(result, "")
        result = Regex("""^={3,}\s*$""", RegexOption.MULTILINE).replace(result, "")
        result = Regex("""^-{3,}\s*$""", RegexOption.MULTILINE).replace(result, "")
        // 通用规范化
        return TextNormalizer.normalizeLight(result.trim())
    }

    private fun extractTitle(file: File, raw: String): String {
        val m = Regex("""^#\s+(.+)""", RegexOption.MULTILINE).find(raw)
        return m?.groupValues?.get(1)?.trim()?.take(60) ?: file.nameWithoutExtension
    }

    private fun normalize(text: String): String = text
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\n\\s*"), "\n")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)
    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)
    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)
}
