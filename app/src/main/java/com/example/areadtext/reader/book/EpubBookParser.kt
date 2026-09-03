package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * EPUB 解析器：本地 .epub（ZIP 容器）→ [Book]。
 *
 * 流程：
 *  1. 读 META-INF/container.xml 定位 OPF（package）文档。
 *  2. 读 OPF 拿元数据（书名/作者）+ manifest + spine（阅读顺序）。
 *  3. 按 spine 顺序逐个解析 XHTML → 清洗为纯文本 → [TextSegmenter] 分段。
 *  4. 解析结果缓存为 JSON 到 filesDir，重复打开无需重排大书。
 */
object EpubBookParser : BookParser {

    private const val TAG = "EpubBookParser"
    private const val CACHE_DIR = "book_cache"
    private const val CACHE_VERSION = 1

    override fun parse(filePath: String, bookId: String): Book? {
        return try {
            ZipFile(File(filePath)).use { zip ->
                val opfPath = findOpfPath(zip) ?: run {
                    Log.w(TAG, "container.xml 中找不到 OPF")
                    return null
                }
                val opfEntry = zip.getEntry(opfPath) ?: run {
                    Log.w(TAG, "OPF 不存在: $opfPath")
                    return null
                }
                val opfDoc = Jsoup.parse(zip.getInputStream(opfEntry), "UTF-8", "")
                val title = opfDoc.select("metadata > dc\\:title").first()?.text()
                    ?: opfDoc.select("metadata title").first()?.text()
                    ?: File(filePath).nameWithoutExtension
                val author = opfDoc.select("metadata > dc\\:creator").first()?.text()
                    ?: opfDoc.select("metadata creator").first()?.text()
                    ?: ""

                val spineItems = opfDoc.select("spine itemref")
                val manifest = HashMap<String, String>() // id -> href
                opfDoc.select("manifest > item").forEach { it ->
                    val id = it.attr("id")
                    val href = it.attr("href")
                    if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
                }
                val baseDir = opfPath.substringBeforeLast('/').let { if (it == opfPath) "" else it }

                val chapters = ArrayList<Chapter>()
                spineItems.forEach { itemref ->
                    val idref = itemref.attr("idref")
                    val href = manifest[idref] ?: return@forEach
                    val entryPath = resolvePath(baseDir, href)
                    val entry = zip.getEntry(entryPath) ?: return@forEach
                    if (isHtml(entryPath)) {
                        val html = zip.getInputStream(entry).readBytes().toString(Charsets.UTF_8)
                        val chapter = htmlToChapter(entryPath, html)
                        if (chapter.isReadable) chapters.add(chapter)
                    }
                }
                if (chapters.isEmpty()) {
                    Log.w(TAG, "spine 中没有可读章节")
                    return null
                }
                Book(
                    bookId = bookId,
                    title = title,
                    author = author,
                    filePath = filePath,
                    chapters = chapters,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}", e)
            null
        }
    }

    private fun htmlToChapter(entryPath: String, html: String): Chapter {
        val doc: Document = try {
            Jsoup.parse(html, "UTF-8")
        } catch (e: Exception) {
            Jsoup.parse("<body>${html}</body>")
        }
        doc.select("script, style, head, title, meta, link, noscript").remove()
        val body = doc.body() ?: doc

        val normalized = buildPlainText(body)
            .replace(Regex("[ \\t\\u00a0]+"), " ")
            .replace(Regex("\\s*\n\\s*"), "\n")
            .replace(Regex("\n{2,}"), "\n")
            .trim()

        val title = run {
            doc.select("h1, h2").firstOrNull()?.text()?.trim()?.take(60)
                ?: entryPath.substringAfterLast('/').removeSuffix(".html").removeSuffix(".xhtml")
                ?: entryPath
        }
        return Chapter(
            id = entryPath,
            title = title,
            text = normalized,
            paragraphs = TextSegmenter.paragraphsOf(normalized),
        )
    }

    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote",
        "tr", "section", "article", "table", "ul", "ol", "header", "footer", "figure",
    )

    private fun buildPlainText(root: Element): String {
        val sb = StringBuilder()

        fun ensureNewline() {
            if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        }

        fun walk(el: Element) {
            val iter = el.childNodes().iterator()
            while (iter.hasNext()) {
                val node = iter.next()
                when (node) {
                    is TextNode -> sb.append(node.text())
                    is Element -> {
                        val tag = node.tagName()
                        if (tag == "br") {
                            ensureNewline()
                        } else {
                            if (tag in BLOCK_TAGS) ensureNewline()
                            walk(node)
                            if (tag in BLOCK_TAGS) ensureNewline()
                        }
                    }
                }
            }
        }

        walk(root)
        return sb.toString()
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = Jsoup.parse(zip.getInputStream(container), "UTF-8", "")
        return doc.select("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val h = href.replace('\\', '/')
        return if (h.startsWith("/")) h.removePrefix("/")
        else if (baseDir.isEmpty()) h
        else "$baseDir/$h"
    }

    private fun isHtml(path: String): Boolean =
        path.endsWith(".html", true) || path.endsWith(".xhtml", true) ||
            path.endsWith(".htm", true) || path.endsWith(".xml", true)

    // ---- JSON 缓存（委托给通用 BookCache，所有格式同一位置/结构）----

    override fun cacheFile(context: Context, bookId: String) = BookCache.file(context, bookId)

    override fun saveCache(context: Context, book: Book) = BookCache.save(context, book)

    override fun loadCache(context: Context, bookId: String): Book? = BookCache.load(context, bookId)

}
