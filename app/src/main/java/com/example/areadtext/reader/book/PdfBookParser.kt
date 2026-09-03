package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import com.example.areadtext.reader.TextSegmenter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/**
 * PDF 解析器：用 pdfbox-android（PDFBox 2.x 的 Android 移植版）提取文本 → [Book]。
 *
 * 为什么不用原版 org.apache.pdfbox:pdfbox 3.x：
 *  原版大量类（PDFStreamEngine / PDFGraphicsStreamEngine 等）引用 java.awt.*，
 *  Android 运行时没有这些类，加载 PDFTextStripper 时即抛 NoClassDefFoundError，
 *  且该 Error 不被 catch(Exception) 捕获，直接导致进程闪退。
 *  pdfbox-android 把 java.awt 依赖替换成了 Android 兼容实现，可在设备上正常运行。
 *
 * 提取策略：
 *  - 整本 PDF 视为一个大章节（PDF 本身没有标准章节结构，故 title 取文件名）。
 *  - 按空行分段 → [TextSegmenter] 切句，产出与其他格式统一的 chapter/paragraph/sentence 模型。
 *  - 表格/多栏排版会丢失结构（PDFBox 的文本提取不保视觉顺序），但满足朗读场景的"正文流"需求。
 *  - 扫描版 PDF（无文本层）提取结果为空，返回 null 由上层提示导入失败。
 */
object PdfBookParser : BookParser {

    private const val TAG = "PdfBookParser"

    override fun parse(filePath: String, bookId: String): Book? {
        // 用 Throwable 而非 Exception：防御 NoClassDefFoundError / StackOverflowError 等 Error
        // 直接冒泡导致进程闪退（PDFBox 类加载失败就是典型案例）。
        return try {
            PDDocument.load(File(filePath)).use { doc ->
                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                val rawText = stripper.getText(doc)
                if (rawText.isBlank()) {
                    Log.w(TAG, "PDF 无文本内容（可能是扫描版）: $filePath")
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
        } catch (e: Throwable) {
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
