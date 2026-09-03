package com.example.areadtext.reader

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
 * EPUB 解析器：本地 .epub（ZIP 容器）→ [EpubBook]。
 *
 * 流程：
 *  1. 读 META-INF/container.xml 定位 OPF（package）文档。
 *  2. 读 OPF 拿元数据（书名/作者）+ manifest + spine（阅读顺序）。
 *  3. 按 spine 顺序逐个解析 XHTML → 清洗为纯文本 → [TextSegmenter] 分段。
 *  4. 解析结果缓存为 JSON 到 filesDir，重复打开无需重排大书。
 *
 * 解析只保留阅读/朗读需要的结构（正文纯文本 + 段偏移），不追求完整排版还原，
 * 定位是"本地 EPUB 作为朗读内容源"（对标 MoRealm 的章节正文管线）。
 */
object EpubParser {

    private const val TAG = "EpubParser"
    private const val CACHE_DIR = "epub_cache"
    private const val CACHE_VERSION = 1

    /** 解析 .epub 文件。filePath 指向已复制到应用私有目录的 epub。 */
    fun parse(filePath: String, bookId: String): EpubBook? {
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

                val chapters = ArrayList<EpubChapter>()
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
                EpubBook(
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

    /** 把单个 XHTML 清洗为章节文本 + 分段。 */
    private fun htmlToChapter(entryPath: String, html: String): EpubChapter {
        val doc: Document = try {
            Jsoup.parse(html, "UTF-8")
        } catch (e: Exception) {
            Jsoup.parse("<body>${html}</body>")
        }
        // 删除脚本/样式/注释等非正文
        doc.select("script, style, head, title, meta, link, noscript").remove()
        val body = doc.body() ?: doc

        // 块级元素手动遍历（Jsoup.text() 会折叠换行导致分段失效，故手写）
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
        return EpubChapter(
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

    /** 遍历 body 子树，块级元素/br 处换行，输出保留段落结构的纯文本。 */
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

    /** 读 container.xml 定位 OPF 路径。 */
    private fun findOpfPath(zip: ZipFile): String? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = Jsoup.parse(zip.getInputStream(container), "UTF-8", "")
        return doc.select("rootfile").firstOrNull()?.attr("full-path")?.takeIf { it.isNotBlank() }
    }

    /** href 相对 OPF 所在目录解析为 zip 内路径。 */
    private fun resolvePath(baseDir: String, href: String): String {
        val h = href.replace('\\', '/')
        return if (h.startsWith("/")) h.removePrefix("/")
        else if (baseDir.isEmpty()) h
        else "$baseDir/$h"
    }

    private fun isHtml(path: String): Boolean =
        path.endsWith(".html", true) || path.endsWith(".xhtml", true) ||
            path.endsWith(".htm", true) || path.endsWith(".xml", true)

    // ---- JSON 缓存（大书重开不重排）----

    fun cacheFile(context: Context, bookId: String): File {
        val dir = File(context.filesDir, CACHE_DIR).apply { if (!exists()) mkdirs() }
        return File(dir, "${bookId}_v$CACHE_VERSION.json")
    }

    fun saveCache(context: Context, book: EpubBook) {
        try {
            val arr = JSONArray()
            book.chapters.forEach { ch ->
                val paras = JSONArray()
                ch.paragraphs.forEach { p ->
                    val sents = JSONArray()
                    p.sentences.forEach { s -> sents.put(JSONObject().put("t", s.text).put("s", s.start)) }
                    paras.put(JSONObject().put("t", p.text).put("o", p.offset).put("q", sents))
                }
                arr.put(
                    JSONObject()
                        .put("id", ch.id)
                        .put("title", ch.title)
                        .put("text", ch.text)
                        .put("paras", paras)
                )
            }
            val root = JSONObject()
                .put("bookId", book.bookId)
                .put("title", book.title)
                .put("author", book.author)
                .put("filePath", book.filePath)
                .put("cover", book.coverPath)
                .put("chapters", arr)
            cacheFile(context, book.bookId).writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "saveCache failed: ${e.message}")
        }
    }

    fun loadCache(context: Context, bookId: String): EpubBook? {
        val f = cacheFile(context, bookId)
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val chaptersArr = root.getJSONArray("chapters")
            val chapters = ArrayList<EpubChapter>(chaptersArr.length())
            for (i in 0 until chaptersArr.length()) {
                val c = chaptersArr.getJSONObject(i)
                val parasArr = c.getJSONArray("paras")
                val paras = ArrayList<Paragraph>(parasArr.length())
                for (j in 0 until parasArr.length()) {
                    val p = parasArr.getJSONObject(j)
                    val qArr = p.optJSONArray("q") ?: JSONArray()
                    val sents = ArrayList<Sentence>(qArr.length())
                    for (k in 0 until qArr.length()) {
                        val s = qArr.getJSONObject(k)
                        sents.add(Sentence(text = s.getString("t"), start = s.getInt("s")))
                    }
                    paras.add(Paragraph(text = p.getString("t"), offset = p.getInt("o"), sentences = sents))
                }
                chapters.add(
                    EpubChapter(
                        id = c.getString("id"),
                        title = c.optString("title", ""),
                        text = c.getString("text"),
                        paragraphs = paras,
                    )
                )
            }
            EpubBook(
                bookId = root.getString("bookId"),
                title = root.optString("title", ""),
                author = root.optString("author", ""),
                filePath = root.optString("filePath", ""),
                coverPath = root.optString("cover", "").takeIf { it.isNotBlank() },
                chapters = chapters,
            )
        } catch (e: Exception) {
            Log.w(TAG, "loadCache failed: ${e.message}")
            null
        }
    }
}
