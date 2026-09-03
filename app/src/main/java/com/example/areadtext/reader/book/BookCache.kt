package com.example.areadtext.reader.book

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 跨格式的通用图书缓存读写。
 *
 * 所有 [BookParser] 都把同一 JSON 结构写到 book_cache/<bookId>/book_vN.json，
 * 因此阅读器加载缓存时无需关心原格式——TTS 调度主机直接调 [loadCache] 即可。
 */
object BookCache {

    private const val TAG = "BookCache"
    private const val CACHE_DIR = "book_cache"
    private const val CACHE_VERSION = 1

    /** 默认缓存文件位置（所有格式共用同一命名，避免跨格式冲突）。 */
    fun file(context: Context, bookId: String): File {
        val dir = File(context.filesDir, "$CACHE_DIR/$bookId").apply { if (!exists()) mkdirs() }
        return File(dir, "book_v$CACHE_VERSION.json")
    }

    fun save(context: Context, book: Book) {
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
            file(context, book.bookId).writeText(root.toString())
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    fun load(context: Context, bookId: String): Book? {
        val f = file(context, bookId)
        if (!f.exists()) return null
        return try {
            val root = JSONObject(f.readText())
            val chaptersArr = root.getJSONArray("chapters")
            val chapters = ArrayList<Chapter>(chaptersArr.length())
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
                    Chapter(
                        id = c.getString("id"),
                        title = c.optString("title", ""),
                        text = c.getString("text"),
                        paragraphs = paras,
                    )
                )
            }
            Book(
                bookId = root.getString("bookId"),
                title = root.optString("title", ""),
                author = root.optString("author", ""),
                filePath = root.optString("filePath", ""),
                coverPath = root.optString("cover", "").takeIf { it.isNotBlank() },
                chapters = chapters,
            )
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            null
        }
    }
}
