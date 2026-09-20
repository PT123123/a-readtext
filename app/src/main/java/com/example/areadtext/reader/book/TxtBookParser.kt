package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import com.example.areadtext.reader.TextSegmenter
import com.example.areadtext.reader.text.TextNormalizer
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * TXT 解析器：纯文本文件 → [Book]。
 *
 * 特性：
 *  - 编码自动检测：只采样文件头部 8KB（BOM → UTF-8 严格 → GB18030 → Big5 → UTF-8 宽松），
 *    随后 BufferedReader 逐行解码，避免整文件字节数组 + 多份全文 String 拷贝。
 *  - 自动分章：按"第X章/Chapter X"等标记切章（至少匹配 3 处才启用），首标记前的
 *    引子文本并入第一章。
 *  - 大文本切割：单章超过 [MAX_CHAPTER_CHARS] 时按段落边界硬切分为"（第N部分）"，
 *    逐章构建后即释放；超过 [MAX_FILE_BYTES] 直接拒绝导入以防 OOM。
 *  - 规范化：逐行 normalize（ligature、零宽字符、特殊空格、控制字符）。
 */
object TxtBookParser : BookParser {

    private const val TAG = "TxtBookParser"

    /** 导入上限：32MB 中文 TXT 约千万字，Book 模型（文本+段+句）峰值约为其数倍，超限拒绝。 */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /** 单章最大字符数，超出按换行（段落）边界硬切分。 */
    private const val MAX_CHAPTER_CHARS = 30_000

    private const val SAMPLE_BYTES = 8192

    /** 最近一次 parse 失败原因（too_large / empty / read_error），成功时为 null。 */
    var lastFailureReason: String? = null
        private set

    /** 常见章节标题正则（对单行 trim 后 matchEntire）。 */
    private val CHAPTER_PATTERNS = listOf(
        Regex("""第[一二三四五六七八九十百千零两〇\d]+[章回节卷篇].*"""),
        Regex("""Chapter\s+\d+.*""", RegexOption.IGNORE_CASE),
        Regex("""Prologue""", RegexOption.IGNORE_CASE),
        Regex("""Epilogue""", RegexOption.IGNORE_CASE),
        Regex("""后记"""),
        Regex("""序[章言]"""),
    )

    override fun parse(filePath: String, bookId: String): Book? {
        lastFailureReason = null
        val file = File(filePath)
        return try {
            if (!file.isFile) {
                lastFailureReason = "read_error"
                return null
            }
            if (file.length() > MAX_FILE_BYTES) {
                Log.w(TAG, "TXT 文件过大 (${file.length() / 1024 / 1024}MB): $filePath")
                lastFailureReason = "too_large"
                return null
            }

            val (charset, bomLen) = detectEncoding(file)
            val (all, markers) = readAllLines(file, charset, bomLen)
            if (all.isBlank()) {
                Log.w(TAG, "TXT 无文本内容: $filePath")
                lastFailureReason = "empty"
                return null
            }

            val title = file.nameWithoutExtension
            val pieces = chapterPieces(all, markers, title)
            // 先把各章节文本复制为独立字符串，再释放全文缓冲
            val texts = pieces.map { all.substring(it.first, it.second) }
            all.setLength(0)

            val chapters = ArrayList<Chapter>(pieces.size)
            for ((i, piece) in pieces.withIndex()) {
                val text = texts[i]
                if (text.isBlank()) continue
                chapters.add(
                    Chapter(
                        id = "txt-ch-$i",
                        title = piece.third,
                        text = text,
                        paragraphs = TextSegmenter.paragraphsOf(text),
                    )
                )
            }
            if (chapters.isEmpty()) {
                lastFailureReason = "empty"
                return null
            }
            val book = Book(
                bookId = bookId,
                title = title,
                author = "",
                filePath = filePath,
                chapters = chapters,
            )
            Log.i(TAG, "parse OK: chapters=${book.totalChapters} paras=${book.chapters.sumOf { it.paragraphs.size }} bookId=$bookId")
            book
        } catch (e: Exception) {
            Log.e(TAG, "TXT parse failed: ${e.message}", e)
            lastFailureReason = "read_error"
            null
        }
    }

    /**
     * 逐行读取 + 规范化，返回（全文缓冲, 章节标记列表 [偏移, 标题]）。
     * 行间以 '\n' 连接，偏移即该标记行在全文缓冲中的起点。
     */
    private fun readAllLines(
        file: File,
        charset: Charset,
        bomLen: Int,
    ): Pair<StringBuilder, List<Pair<Int, String>>> {
        val all = StringBuilder(minOf(file.length() * 2, Int.MAX_VALUE.toLong()).toInt())
        val markers = ArrayList<Pair<Int, String>>()
        FileInputStream(file).use { fis ->
            repeat(bomLen) { fis.read() }  // 跳过 BOM（≤4 字节，冷路径）
            BufferedReader(InputStreamReader(fis, charset), 64 * 1024).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = TextNormalizer.normalize(line).trim()
                    if (trimmed.isNotEmpty() && CHAPTER_PATTERNS.any { it.matchEntire(trimmed) != null }) {
                        markers.add(all.length to trimmed)
                    }
                    if (all.isNotEmpty()) all.append('\n')
                    all.append(trimmed)
                    line = reader.readLine()
                }
            }
        }
        return all to markers
    }

    /**
     * 计算章节文本区间与标题：
     *  - 标记 ≥3 处：按标记切章，首标记前的引子并入第一章；
     *  - 否则整本视为一章；
     *  - 任一章超过 [MAX_CHAPTER_CHARS] 时按段落换行边界继续硬切分。
     */
    private fun chapterPieces(
        all: StringBuilder,
        markers: List<Pair<Int, String>>,
        fallbackTitle: String,
    ): List<Triple<Int, Int, String>> {
        val raw = if (markers.size >= 3) {
            val list = ArrayList<Triple<Int, Int, String>>(markers.size)
            for ((i, m) in markers.withIndex()) {
                val start = if (i == 0) 0 else m.first
                val end = if (i + 1 < markers.size) markers[i + 1].first else all.length
                if (end > start) list.add(Triple(start, end, m.second))
            }
            list.ifEmpty { listOf(Triple(0, all.length, fallbackTitle)) }
        } else {
            listOf(Triple(0, all.length, fallbackTitle))
        }

        val pieces = ArrayList<Triple<Int, Int, String>>(raw.size + 8)
        for ((start, end, title) in raw) {
            val parts = splitOversized(all, start, end)
            if (parts.size <= 1) {
                pieces.add(Triple(start, end, title))
            } else {
                parts.forEachIndexed { idx, range ->
                    pieces.add(Triple(range.first, range.second, "$title（第${idx + 1}部分）"))
                }
            }
        }
        return pieces
    }

    /** 把 [start,end) 区间按段落（换行）边界切成不超过 [MAX_CHAPTER_CHARS] 的片段。 */
    private fun splitOversized(all: StringBuilder, start: Int, end: Int): List<Pair<Int, Int>> {
        val result = ArrayList<Pair<Int, Int>>()
        var s = start
        while (end - s > MAX_CHAPTER_CHARS) {
            val nl = all.indexOf('\n', s + MAX_CHAPTER_CHARS)
            val cut = if (nl in (s + 1) until end) nl + 1 else s + MAX_CHAPTER_CHARS
            result.add(s to cut)
            s = cut
        }
        if (end > s) result.add(s to end)
        return result
    }

    /**
     * 编码自动检测（只读头部 [SAMPLE_BYTES] 采样，绝不整文件解码做对比）：
     * 1. BOM 检测（最权威）；2. UTF-8 严格解码无替换字符 → UTF-8；
     * 3. GB18030 / Big5 按替换字符数取优；4. 兜底宽松 UTF-8。
     */
    private fun detectEncoding(file: File): Pair<Charset, Int> {
        val sample = ByteArray(SAMPLE_BYTES)
        val read = FileInputStream(file).use { it.read(sample) }
        val n = if (read < 0) 0 else read
        return when {
            n >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte() ->
                Charsets.UTF_8 to 3
            n >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte() ->
                StandardCharsets.UTF_16LE to 2
            n >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte() ->
                StandardCharsets.UTF_16BE to 2
            else -> {
                // 去掉尾部 3 字节，避免样本截断多字节序列产生假替换字符
                val safeN = if (n >= 4) n - 3 else n
                val utf8Bad = countBad(String(sample, 0, safeN, Charsets.UTF_8))
                if (utf8Bad == 0) {
                    Charsets.UTF_8 to 0
                } else {
                    val gbkBad = countBad(String(sample, 0, safeN, Charset.forName("GB18030")))
                    val big5Bad = countBad(String(sample, 0, safeN, Charset.forName("Big5")))
                    when {
                        gbkBad < utf8Bad && gbkBad <= big5Bad -> Charset.forName("GB18030") to 0
                        big5Bad < utf8Bad -> Charset.forName("Big5") to 0
                        else -> Charsets.UTF_8 to 0
                    }
                }
            }
        }
    }

    private fun countBad(s: String): Int {
        var count = 0
        for (c in s) if (c == '\uFFFD') count++
        return count
    }

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)
    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)
    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)
}
