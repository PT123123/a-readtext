package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF 解析器：用 Apache PDFBox 3.x 提取文本 → [Book]。
 *
 * 提取策略：
 *  - 整本 PDF 视为一个大章节（PDF 本身没有标准章节结构，故 title 取文件名）。
 *  - 按空行分段 → [TextSegmenter] 切句，产出与其他格式统一的 chapter/paragraph/sentence 模型。
 *  - 表格/多栏排版会丢失结构（PDFBox 的文本提取不保视觉顺序），但满足朗读场景的"正文流"需求。
 */
object PdfBookParser : BookParser {

    private const val TAG = "PdfBookParser"

    override fun parse(filePath: String, bookId: String): Book? {
        return try {
            Loader.loadPDF(File(filePath)).use { doc ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                val rawText = stripper.getText(doc)
                if (rawText.isBlank()) {
                    Log.w(TAG, "PDF 无文本内容: $filePath")
                    return null
                }
                val text = normalize(rawText)
                val title = File(filePath).nameWithoutExtension
                val chapter = Chapter(
                    id = "pdf-root",
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "PDF parse failed: ${e.message}", e)
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
