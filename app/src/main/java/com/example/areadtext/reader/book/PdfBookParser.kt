package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Outline
import com.example.areadtext.reader.TextSegmenter
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
 */
object PdfBookParser : BookParser {

    private const val TAG = "PdfBookParser"

    override fun parse(filePath: String, bookId: String): Book? {
        // 用 Throwable 而非 Exception：防御 NoClassDefFoundError / 原生库加载失败等 Error 冒泡闪退。
        return try {
            val doc = Document.openDocument(filePath)
            try {
                val pageCount = doc.countPages()
                if (pageCount <= 0) {
                    Log.w(TAG, "PDF 无页面: $filePath")
                    return null
                }

                // 逐页提取文本
                val pageTexts = ArrayList<String>(pageCount)
                for (i in 0 until pageCount) {
                    pageTexts.add(extractPageText(doc, i))
                }
                val totalChars = pageTexts.sumOf { it.length }
                if (totalChars == 0) {
                    Log.w(TAG, "PDF 无文本内容（可能是扫描版）: $filePath")
                    return null
                }

                val title = File(filePath).nameWithoutExtension
                val chapters = buildChapters(doc, pageTexts, title)
                Log.i(
                    TAG,
                    "parse OK: pages=$pageCount chars=$totalChars chapters=${chapters.size} " +
                        "paras=${chapters.sumOf { it.paragraphs.size }} bookId=$bookId"
                )
                Book(
                    bookId = bookId,
                    title = title,
                    author = "",
                    filePath = filePath,
                    chapters = chapters,
                )
            } finally {
                doc.destroy()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "PDF parse failed: ${e.message}", e)
            null
        }
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
     * 按目录把逐页文本切成章节；无目录 / 解析失败时退化为整本单章。
     * 每个 Outline 条目解析出 0-based 目标页，章节范围 = [本条目页, 下一条目页)。
     * 目录覆盖不到的开头页（封面/序言）并入第一个章节，避免丢内容。
     */
    private fun buildChapters(doc: Document, pageTexts: List<String>, title: String): List<Chapter> {
        val toc = collectToc(doc)
        if (toc.isNotEmpty()) {
            val chapters = ArrayList<Chapter>()
            var prevStart = 0
            for ((idx, entry) in toc.withIndex()) {
                val (entryTitle, startPage) = entry
                val endPage = if (idx + 1 < toc.size) toc[idx + 1].second else pageTexts.size
                if (endPage <= startPage) continue
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
        }

        // 退化：整本单章
        val all = normalize(pageTexts.joinToString("\n"))
        if (all.isBlank()) return emptyList()
        return listOf(
            Chapter(
                id = "pdf-root",
                title = title,
                text = all,
                paragraphs = TextSegmenter.paragraphsOf(all),
            )
        )
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
        fun walk(items: Array<Outline>?) {
            if (items == null) return
            for (item in items) {
                val page = resolvePage(doc, item.uri)
                if (page != null && page >= 0) result.add(item.title to page)
                walk(item.down)
            }
        }
        walk(outline)

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

    private fun normalize(text: String): String = text
        .replace(Regex("[ \\t\\u00a0]+"), " ")
        .replace(Regex("\\s*\n\\s*"), "\n")
        .replace(Regex("\n{2,}"), "\n")
        .trim()

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)
    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)
    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)
}
