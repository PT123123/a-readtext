package com.example.areadtext.reader.book

import android.content.Context
import android.util.JsonReader
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter

/**
 * 跨格式的通用图书缓存读写。
 *
 * 所有 [BookParser] 都把同一 JSON 结构写到 book_cache/<bookId>/book_vN.json，
 * 因此阅读器加载缓存时无需关心原格式——TTS 调度主机直接调 [loadCache] 即可。
 *
 * 大书内存优化：save/load 均为流式（逐段写入 / JsonReader 逐 token 解析），
 * 不再构造整本巨型 JSONObject 树，导入与打开千万字 TXT 时峰值内存与单章同量级。
 * JSON 结构与旧版 org.json 实现完全兼容。
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
            BufferedWriter(FileWriter(file(context, book.bookId)), 64 * 1024).use { w ->
                w.write("{\"bookId\":")
                w.write(jsonString(book.bookId))
                w.write(",\"title\":")
                w.write(jsonString(book.title))
                w.write(",\"author\":")
                w.write(jsonString(book.author))
                w.write(",\"filePath\":")
                w.write(jsonString(book.filePath))
                book.coverPath?.let {
                    w.write(",\"cover\":")
                    w.write(jsonString(it))
                }
                w.write(",\"rtl\":")
                w.write(if (book.isRtl) "true" else "false")
                w.write(",\"chapters\":[")
                book.chapters.forEachIndexed { ci, ch ->
                    if (ci > 0) w.write(",")
                    w.write("{\"id\":")
                    w.write(jsonString(ch.id))
                    w.write(",\"title\":")
                    w.write(jsonString(ch.title))
                    w.write(",\"text\":")
                    w.write(jsonString(ch.text))
                    w.write(",\"paras\":[")
                    ch.paragraphs.forEachIndexed { pi, p ->
                        if (pi > 0) w.write(",")
                        w.write("{\"t\":")
                        w.write(jsonString(p.text))
                        w.write(",\"o\":")
                        w.write(p.offset.toString())
                        w.write(",\"q\":[")
                        p.sentences.forEachIndexed { si, s ->
                            if (si > 0) w.write(",")
                            w.write("{\"t\":")
                            w.write(jsonString(s.text))
                            w.write(",\"s\":")
                            w.write(s.start.toString())
                            w.write("}")
                        }
                        w.write("]}")
                    }
                    w.write("]}")
                }
                w.write("]}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "save failed: ${e.message}")
        }
    }

    fun load(context: Context, bookId: String): Book? {
        val f = file(context, bookId)
        if (!f.exists()) return null
        return try {
            JsonReader(FileReader(f).let { java.io.BufferedReader(it, 64 * 1024) }).use { r ->
                var id = bookId
                var title = ""
                var author = ""
                var path = ""
                var cover: String? = null
                var rtl = false
                val chapters = ArrayList<Chapter>()
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "bookId" -> id = r.nextString()
                        "title" -> title = r.nextString()
                        "author" -> author = r.nextString()
                        "filePath" -> path = r.nextString()
                        "cover" -> cover = r.nextString().takeIf { it.isNotBlank() }
                        "rtl" -> rtl = r.nextBoolean()
                        "chapters" -> {
                            r.beginArray()
                            while (r.hasNext()) chapters.add(readChapter(r))
                            r.endArray()
                        }
                        else -> r.skipValue()
                    }
                }
                r.endObject()
                if (chapters.isEmpty()) return null
                Book(
                    bookId = id,
                    title = title,
                    author = author,
                    filePath = path,
                    coverPath = cover,
                    chapters = chapters,
                    isRtl = rtl,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed: ${e.message}")
            null
        }
    }

    private fun readChapter(r: JsonReader): Chapter {
        var id = ""
        var title = ""
        var text = ""
        val paras = ArrayList<Paragraph>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextString()
                "title" -> title = r.nextString()
                "text" -> text = r.nextString()
                "paras" -> {
                    r.beginArray()
                    while (r.hasNext()) paras.add(readParagraph(r))
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Chapter(id = id, title = title, text = text, paragraphs = paras)
    }

    private fun readParagraph(r: JsonReader): Paragraph {
        var text = ""
        var offset = 0
        val sents = ArrayList<Sentence>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "t" -> text = r.nextString()
                "o" -> offset = r.nextInt()
                "q" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var st = ""
                        var ss = 0
                        r.beginObject()
                        while (r.hasNext()) {
                            when (r.nextName()) {
                                "t" -> st = r.nextString()
                                "s" -> ss = r.nextInt()
                                else -> r.skipValue()
                            }
                        }
                        r.endObject()
                        sents.add(Sentence(text = st, start = ss))
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return Paragraph(text = text, offset = offset, sentences = sents)
    }

    /** 字符串 → JSON 字面量（含引号），控制字符转义。 */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
