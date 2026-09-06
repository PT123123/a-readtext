package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import com.example.areadtext.reader.TextSegmenter
import com.example.areadtext.reader.text.TextNormalizer
import java.io.File
import java.nio.charset.Charset

/**
 * TXT 解析器：纯文本文件 → [Book]。
 *
 * 特性：
 *  - 编码自动检测：BOM 优先 → UTF-8 严格 → GB18030 → Big5 → UTF-8 宽松。
 *  - 自动分章：按"第X章/Chapter X"等标记切章（至少匹配 3 处才启用）。
 *  - 规范化：ligature、零宽字符、特殊空格。
 */
object TxtBookParser : BookParser {

    private const val TAG = "TxtBookParser"

    /** 常见章节标题正则（按优先级）。 */
    private val CHAPTER_PATTERNS = listOf(
        Regex("""^第[一二三四五六七八九十百千零两〇\d]+[章回节卷篇].*$""", RegexOption.MULTILINE),
        Regex("""^Chapter\s+\d+.*$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
        Regex("""^Prologue$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
        Regex("""^Epilogue$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)),
        Regex("""^后记$""", RegexOption.MULTILINE),
        Regex("""^序[章言]$""", RegexOption.MULTILINE),
    )

    override fun parse(filePath: String, bookId: String): Book? {
        return try {
            val file = File(filePath)
            val text = readText(file)
            if (text.isBlank()) {
                Log.w(TAG, "TXT 无文本内容: $filePath")
                return null
            }
            val title = file.nameWithoutExtension

            // 尝试自动分章
            val chapters = splitChapters(text)
            val book = if (chapters.size > 1) {
                // 多章
                val chapterList = ArrayList<Chapter>(chapters.size)
                for ((i, ch) in chapters.withIndex()) {
                    chapterList.add(
                        Chapter(
                            id = "txt-ch-$i",
                            title = ch.first,
                            text = ch.second,
                            paragraphs = TextSegmenter.paragraphsOf(ch.second),
                        )
                    )
                }
                Book(
                    bookId = bookId,
                    title = title,
                    author = "",
                    filePath = filePath,
                    chapters = chapterList,
                )
            } else {
                // 单章
                Book(
                    bookId = bookId,
                    title = title,
                    author = "",
                    filePath = filePath,
                    chapters = listOf(
                        Chapter(
                            id = "txt-root",
                            title = title,
                            text = text,
                            paragraphs = TextSegmenter.paragraphsOf(text),
                        )
                    ),
                )
            }
            Log.i(TAG, "parse OK: chapters=${book.totalChapters} paras=${book.chapters.sumOf { it.paragraphs.size }} bookId=$bookId")
            book
        } catch (e: Exception) {
            Log.e(TAG, "TXT parse failed: ${e.message}", e)
            null
        }
    }

    /**
     * 编码自动检测：
     * 1. BOM 检测（最权威）
     * 2. UTF-8 严格解码（无乱码字符）
     * 3. GB18030 → Big5 回退（按替换字符比例选最佳）
     */
    private fun readText(file: File): String {
        val raw = file.readBytes()

        // 1. BOM 检测
        val afterBom = stripBom(raw)

        // 2. 采样检测编码（前 8KB 用于判断）
        val sample = if (afterBom.size > 8192) afterBom.copyOf(8192) else afterBom

        val utf8Text = String(sample, Charsets.UTF_8)
        val badCount = utf8Text.count { it == '�' }

        if (badCount == 0) {
            // UTF-8 解码无替换字符，大概率是 UTF-8
            return TextNormalizer.normalize(String(afterBom, Charsets.UTF_8))
        }

        // 3. 回退 GB18030
        val gbkText = String(afterBom, Charset.forName("GB18030"))
        val gbkBad = gbkText.count { it == '�' }
        if (gbkBad < badCount) {
            // GB18030 更好；检查是否 Big5 更优（对繁体中文）
            val big5Text = String(afterBom, Charset.forName("Big5"))
            val big5Bad = big5Text.count { it == '�' }
            return if (big5Bad < gbkBad) {
                TextNormalizer.normalize(big5Text)
            } else {
                TextNormalizer.normalize(gbkText)
            }
        }

        // 4. 回退 Big5
        val big5Text = String(afterBom, Charset.forName("Big5"))
        val big5Bad = big5Text.count { it == '�' }
        return if (big5Bad < badCount) {
            TextNormalizer.normalize(big5Text)
        } else {
            TextNormalizer.normalize(String(afterBom, Charsets.UTF_8))
        }
    }

    /** 剥离 BOM（UTF-8/UTF-16LE/UTF-16BE）并返回剥离后的字节。 */
    private fun stripBom(raw: ByteArray): ByteArray {
        return when {
            raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte() ->
                raw.copyOfRange(3, raw.size)  // UTF-8 BOM
            raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte() ->
                raw.copyOfRange(2, raw.size)  // UTF-16 LE BOM
            raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte() ->
                raw.copyOfRange(2, raw.size)  // UTF-16 BE BOM
            else -> raw
        }
    }

    /**
     * 按常见章节标记自动切章。
     * 至少匹配 3 处才启用自动切章，避免误匹配。
     */
    private fun splitChapters(text: String): List<Pair<String, String>> {
        val matches = ArrayList<Pair<Int, String>>()

        for (pattern in CHAPTER_PATTERNS) {
            for (m in pattern.findAll(text)) {
                matches.add(m.range.first to m.value.trim())
            }
        }

        if (matches.size < 3) return listOf("" to text)  // 不切章

        // 按位置排序，去重（同位置保留首个）
        val sorted = matches.sortedBy { it.first }.distinctBy { it.first }

        val chapters = ArrayList<Pair<String, String>>()
        for ((i, match) in sorted.withIndex()) {
            val start = match.first
            val title = match.second
            val end = if (i + 1 < sorted.size) sorted[i + 1].first else text.length
            if (end <= start) continue
            val body = text.substring(start, end).trim()
            if (body.isBlank()) continue
            chapters.add(title to body)
        }
        return if (chapters.isNotEmpty()) chapters else listOf("" to text)
    }

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)
    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)
    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)
}
