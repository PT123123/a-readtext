package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import com.example.areadtext.reader.TextSegmenter
import com.example.areadtext.reader.text.TextNormalizer
import java.io.File

/**
 * PDF 解析器：用 MuPDF（AGPL-3.0）逐页结构化提取文本 → [Book]（按目录分章）。
 *
 * 为什么从 pdfbox-android 迁移到 MuPDF：
 *  pdfbox-android 移植版在部分 PDF（OCR 生成、子集化 TrueType 字体，如
 *  "扫描→OCR→Word→PDF" 产物）上提取出"一页只有几个字"的错误结果；
 *  同一份文件用官方 PDFBox / PyMuPDF（MuPDF 内核）实测 100% 完整提取。
 *  MuPDF 自带 structured text（块/行/字符级）与 outline（目录）能力，
 *  同时解决了"多栏/表格丢结构"和"整本只有一个章节"两个历史痛点。
 *
 * 提取策略：
 *  - 逐页 toStructuredText().asText() 取整页文本（与 PyMuPDF get_text 同内核）。
 *  - 有目录（loadOutline）时按目标页把连续页切分成章节；无目录退化为整本单章。
 *  - 每章文本经 normalize 后交给 [TextSegmenter] 分段切句，产出统一模型。
 *  - 扫描版 PDF（无文本层）提取结果为空，返回 null 由上层提示导入失败。
 *
 * 健壮性增强：
 *  - 文本质量校验：用"非空白字符数/总字符数"判断提取质量，识别"半坏"PDF。
 *  - 失败原因标记：[lastFailureReason] 让上层给用户精确提示（扫描版/低质量/无页面）。
 *  - TOC 容错：目录无法解析时退化为正则切章（第X章/Chapter X）。
 *  - RTL 检测：检测希伯来/阿拉伯文本，标记 [Book.isRtl] 让阅读器设置文字方向。
 */
object PdfBookParser : BookParser {

    private const val TAG = "PdfBookParser"

    /**
     * 最近一次解析失败的原因。上层可据此给用户精确提示。
     * - "scanned" — 扫描版（无文本层）
     * - "low_quality" — 提取质量差（可能是扫描版或特殊编码）
     * - "no_pages" — PDF 无页面
     * - "empty" — 有页面但无文本
     * - null — 解析成功或未解析
     */
    @Volatile
    var lastFailureReason: String? = null
        private set

    /** 最近一次解析的文本质量指标（解析成功后可用）。 */
    @Volatile
    var lastQuality: PdfQuality? = null
        private set

    data class PdfQuality(
        val pageCount: Int,
        val totalChars: Int,
        val nonWhitespaceChars: Int,
        val avgCharsPerPage: Float,
        val textDensity: Float,  // 非空白字符占比
        val isRtl: Boolean,
    )

    override fun parse(filePath: String, bookId: String): Book? {
        lastFailureReason = null
        lastQuality = null

        // 用 Throwable 而非 Exception：防御 NoClassDefFoundError / 原生库加载失败等 Error 冒泡闪退。
        return try {
            val doc = Document.openDocument(filePath)
            try {
                val pageCount = doc.countPages()
                if (pageCount <= 0) {
                    Log.w(TAG, "PDF 无页面: $filePath")
                    lastFailureReason = "no_pages"
                    return null
                }

                // 逐页提取文本
                val pageTexts = ArrayList<String>(pageCount)
                for (i in 0 until pageCount) {
                    pageTexts.add(extractPageText(doc, i))
                }

                // ── 文本质量校验 ──
                val totalChars = pageTexts.sumOf { it.length }
                val nonWhitespaceChars = pageTexts.sumOf { it.count { c -> !c.isWhitespace() } }
                val textDensity = if (totalChars > 0) nonWhitespaceChars.toFloat() / totalChars else 0f
                val avgCharsPerPage = totalChars.toFloat() / pageCount
                val isRtl = detectRtl(pageTexts)

                lastQuality = PdfQuality(
                    pageCount = pageCount,
                    totalChars = totalChars,
                    nonWhitespaceChars = nonWhitespaceChars,
                    avgCharsPerPage = avgCharsPerPage,
                    textDensity = textDensity,
                    isRtl = isRtl,
                )

                // 扫描版检测：非空白字符极少（每页平均 < 5 个非空白字符）
                if (nonWhitespaceChars < pageCount * 5 && totalChars < pageCount * 20) {
                    Log.w(
                        TAG,
                        "PDF 可能是扫描版（非空白字符过少）: nonWs=$nonWhitespaceChars " +
                            "pages=$pageCount total=$totalChars"
                    )
                    lastFailureReason = "scanned"
                    return null
                }

                // 完全空白
                if (totalChars == 0) {
                    Log.w(TAG, "PDF 无文本内容（全空白）: $filePath")
                    lastFailureReason = "empty"
                    return null
                }

                // 低质量提取警告（不阻止导入，但记录日志供调试）
                if (textDensity < 0.3f && totalChars > 100) {
                    Log.w(
                        TAG,
                        "PDF 提取质量可能较差: density=$textDensity avgPerPage=$avgCharsPerPage"
                    )
                    lastFailureReason = "low_quality"
                    // 低质量不阻止导入——用户可以决定是否删除
                }

                val title = File(filePath).nameWithoutExtension
                val chapters = buildChapters(doc, pageTexts, title, pageCount)
                Log.i(
                    TAG,
                    "parse OK: pages=$pageCount chars=$totalChars nonWs=$nonWhitespaceChars " +
                        "density=$textDensity chapters=${chapters.size} " +
                        "paras=${chapters.sumOf { it.paragraphs.size }} rtl=$isRtl bookId=$bookId"
                )
                Book(
                    bookId = bookId,
                    title = title,
                    author = "",
                    filePath = filePath,
                    chapters = chapters,
                    isRtl = isRtl,
                )
            } finally {
                doc.destroy()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "PDF parse failed: ${e.message}", e)
            lastFailureReason = "parse_error"
            null
        }
    }

    /**
     * 检测是否为 RTL（希伯来/阿拉伯）文本。
     * 统计 Unicode 希伯来(0590-05FF)和阿拉伯(0600-06FF)区块字符占比。
     */
    private fun detectRtl(pageTexts: List<String>): Boolean {
        var total = 0
        var rtl = 0
        for (text in pageTexts) {
            for (ch in text) {
                total++
                val code = ch.code
                if (code in 0x0590..0x06FF) rtl++
            }
        }
        return total > 0 && rtl.toFloat() / total > 0.3f
    }

    /** 提取单页文本；结构化对象用完即 destroy，失败返回空串（不中断整本）。 */
    private fun extractPageText(doc: Document, pageIndex: Int): String {
        val page = try {
            doc.loadPage(pageIndex)
        } catch (e: Throwable) {
            Log.w(TAG, "loadPage($pageIndex) failed: ${e.message}")
            return ""
        }
        return try {
            val st = page.toStructuredText()
            try {
                st.asText() ?: ""
            } finally {
                st.destroy()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "page $pageIndex extract failed: ${e.message}")
            ""
        } finally {
            page.destroy()
        }
    }

    /**
     * 按目录把逐页文本切成章节；无目录 / 解析失败时退化为正则切章 → 最终退化整本单章。
     * 每个 Outline 条目解析出 0-based 目标页，章节范围 = [本条目页, 下一条目页)。
     * 目录覆盖不到的开头页（封面/序言）并入第一个章节，避免丢内容。
     */
    private fun buildChapters(
        doc: Document,
        pageTexts: List<String>,
        title: String,
        pageCount: Int,
    ): List<Chapter> {
        val toc = collectToc(doc)
        if (toc.isNotEmpty()) {
            val chapters = ArrayList<Chapter>()
            var prevStart = 0
            for ((idx, entry) in toc.withIndex()) {
                val (entryTitle, startPage) = entry
                val endPage = if (idx + 1 < toc.size) toc[idx + 1].second else pageTexts.size
                if (endPage <= startPage) continue  // 空章跳过
                val text = normalize(pageTexts.subList(prevStart, endPage).joinToString("\n"))
                if (text.isBlank()) continue
                chapters.add(
                    Chapter(
                        id = "ch-$idx",
                        title = entryTitle,
                        text = text,
                        paragraphs = TextSegmenter.paragraphsOf(text),
                    )
                )
                prevStart = endPage
            }
            if (chapters.isNotEmpty()) return chapters
            // 目录存在但切不出章节 → 退化
            Log.w(TAG, "目录存在但无法切出有效章节，退化为正则切章")
        }

        // 退化 1：正则切章
        val allText = normalize(pageTexts.joinToString("\n"))
        val regexChapters = splitByChapterMarkers(allText, pageTexts, pageCount)
        if (regexChapters.isNotEmpty()) return regexChapters

        // 退化 2：整本单章
        if (allText.isBlank()) return emptyList()
        return listOf(
            Chapter(
                id = "pdf-root",
                title = title,
                text = allText,
                paragraphs = TextSegmenter.paragraphsOf(allText),
            )
        )
    }

    /**
     * 按常见章节标记正则切章。
     * 匹配：第X章/第X回/第X节/第X卷/Chapter X 等。
     */
    private val CHAPTER_MARKERS = listOf(
        Regex("""第[一二三四五六七八九十百千零两〇\d]+[章回节卷篇].*"""),
        Regex("""Chapter\s+\d+.*""", RegexOption.IGNORE_CASE),
        Regex("""^\d+\.\s+\S{2,40}$""", RegexOption.MULTILINE),
    )

    private fun splitByChapterMarkers(
        allText: String,
        pageTexts: List<String>,
        pageCount: Int,
    ): List<Chapter> {
        val matches = ArrayList<Pair<Int, String>>()  // (charOffset, title)

        for (pattern in CHAPTER_MARKERS) {
            for (m in pattern.findAll(allText)) {
                matches.add(m.range.first to m.value.trim())
            }
        }

        // 至少匹配 3 处才启用自动切章，避免误匹配
        if (matches.size < 3) return emptyList()

        // 按位置排序，去重（同位置保留首个）
        val sorted = matches.sortedBy { it.first }.distinctBy { it.first }

        val chapters = ArrayList<Chapter>()
        for ((i, match) in sorted.withIndex()) {
            val start = match.first
            val title = match.second
            val end = if (i + 1 < sorted.size) sorted[i + 1].first else allText.length
            if (end <= start) continue
            val body = allText.substring(start, end).trim()
            if (body.isBlank()) continue
            chapters.add(
                Chapter(
                    id = "regex-ch-$i",
                    title = title,
                    text = body,
                    paragraphs = TextSegmenter.paragraphsOf(body),
                )
            )
        }
        return chapters
    }

    /** 扁平化目录（含子项），解析每条目标页为 0-based 页索引，按页去重保序。 */
    private fun collectToc(doc: Document): List<Pair<String, Int>> {
        val outline = try {
            doc.loadOutline()
        } catch (e: Throwable) {
            Log.w(TAG, "loadOutline failed: ${e.message}")
            null
        } ?: return emptyList()

        val result = ArrayList<Pair<String, Int>>()
        var resolvedCount = 0
        var totalCount = 0
        fun walk(items: Array<Outline>?) {
            if (items == null) return
            for (item in items) {
                totalCount++
                val page = resolvePage(doc, item.uri)
                if (page != null && page >= 0) {
                    result.add(item.title to page)
                    resolvedCount++
                }
                walk(item.down)
            }
        }
        walk(outline)

        // 如果目录条目 > 0 但全部无法解析到页码，返回空（触发正则切章退化）
        if (totalCount > 0 && resolvedCount == 0) {
            Log.w(TAG, "目录存在但全部无法解析到页码: total=$totalCount resolved=0")
            return emptyList()
        }

        // 按页排序，同页去重（保留首个），避免章节范围重叠/空章
        return result
            .sortedBy { it.second }
            .distinctBy { it.second }
    }

    /** 解析 outline uri（如 "#page=5"）为 0-based 页索引；失败返回 null。 */
    private fun resolvePage(doc: Document, uri: String?): Int? {
        if (uri.isNullOrBlank()) return null
        return try {
            doc.resolveLink(uri).page
        } catch (e: Throwable) {
            Log.w(TAG, "resolveLink($uri) failed: ${e.message}")
            null
        }
    }

    private fun normalize(text: String): String = TextNormalizer.normalize(text)
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\n\\s*"), "\n")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)
    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)
    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)
}
