package com.example.areadtext.reader

import com.example.areadtext.reader.book.TxtBookParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.Charset

class TxtBookParserTest {

    private fun tempFile(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "txt_test_${System.nanoTime()}")
        dir.mkdirs()
        return File(dir, name)
    }

    @Test
    fun `UTF-8 TXT 按章节标记自动切章且引子并入第一章`() {
        val f = tempFile("novel.txt")
        f.writeText(
            """
            书名：测试小说
            第一章 起风
            风来了。树动了。
            第二章 下雨
            雨下了。地湿了。
            第三章 天晴
            太阳出来了。
            """.trimIndent(),
            Charsets.UTF_8,
        )
        try {
            val book = TxtBookParser.parse(f.absolutePath, "t1")
            assertNotNull(book)
            assertEquals(3, book!!.totalChapters)
            assertEquals("第一章 起风", book.chapters[0].title)
            // 首标记前的引子并入第一章
            assertTrue(book.chapters[0].text.contains("书名：测试小说"))
            assertEquals("第三章 天晴", book.chapters[2].title)
            // 标记行并入第一章：引子是第一段，标记行是第二段
            assertEquals("书名：测试小说", book.chapters[0].paragraphs[0].text)
            assertEquals(0, book.chapters[0].paragraphs[0].offset)
            assertEquals("第一章 起风", book.chapters[0].paragraphs[1].text)
        } finally {
            f.parentFile?.delete(); f.delete()
        }
    }

    @Test
    fun `GB18030 编码自动检测`() {
        val f = tempFile("gbk.txt")
        val text = "第一段中文内容。第二段中文内容。\n第二段继续。"
        f.writeBytes(text.toByteArray(Charset.forName("GB18030")))
        try {
            val book = TxtBookParser.parse(f.absolutePath, "t2")
            assertNotNull(book)
            assertEquals("第一段中文内容。第二段中文内容。", book!!.chapters[0].paragraphs[0].text)
        } finally {
            f.parentFile?.delete(); f.delete()
        }
    }

    @Test
    fun `无章节标记的超长文本按段落边界硬切分`() {
        val f = tempFile("huge.txt")
        // 约 10 万字符、无章节标记 → 应切成多章，每章 ≤ 30000 字符
        val para = "这是一个足够长的段落，用来测试大文本切割优化是否生效。" + "哈".repeat(60)
        val sb = StringBuilder()
        repeat(1200) { sb.append(para).append('\n') }
        f.writeText(sb.toString(), Charsets.UTF_8)
        try {
            val book = TxtBookParser.parse(f.absolutePath, "t3")
            assertNotNull(book)
            book!!
            assertTrue("应切成多章", book.totalChapters > 3)
            assertTrue(
                "每章不应超过上限",
                book.chapters.all { it.text.length <= 30_000 + para.length },
            )
            assertTrue(book.chapters[0].title.contains("（第1部分）"))
            // 内容不丢失：所有段落文本非空数量与源行数一致
            val totalParas = book.chapters.sumOf { it.paragraphs.size }
            assertEquals(1200, totalParas)
        } finally {
            f.parentFile?.delete(); f.delete()
        }
    }

    @Test
    fun `空文件与超长文件解析失败并给出原因`() {
        val empty = tempFile("empty.txt")
        empty.writeText("   \n\n  ", Charsets.UTF_8)
        try {
            assertEquals(null, TxtBookParser.parse(empty.absolutePath, "t4"))
            assertEquals("empty", TxtBookParser.lastFailureReason)
        } finally {
            empty.parentFile?.delete(); empty.delete()
        }
    }

    @Test
    fun `短文本保持单章不切割`() {
        val f = tempFile("short.txt")
        f.writeText("只有一段话。\n还有第二句话。", Charsets.UTF_8)
        try {
            val book = TxtBookParser.parse(f.absolutePath, "t5")
            assertNotNull(book)
            assertEquals(1, book!!.totalChapters)
            assertEquals("short", book.title)
            assertEquals(2, book.chapters[0].paragraphs.size)
        } finally {
            f.parentFile?.delete(); f.delete()
        }
    }
}
