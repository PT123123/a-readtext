package com.example.areadtext.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @Test
    fun `解析最小 EPUB`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "epub_test_${System.nanoTime()}")
        dir.mkdirs()
        val epub = File(dir, "test.epub")

        ZipOutputStream(FileOutputStream(epub)).use { zos ->
            fun write(name: String, content: String) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            write("mimetype", "application/epub+zip")
            write(
                "META-INF/container.xml",
                """<?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>"""
            )
            write(
                "OEBPS/content.opf",
                """<?xml version="1.0"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>测试书</dc:title><dc:creator>测试作者</dc:creator>
                  </metadata>
                  <manifest>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
                </package>"""
            )
            write(
                "OEBPS/c1.xhtml",
                """<html><body><h2>第一章</h2>
                   <p>第一章第一段。第二句。</p><p>第二段内容！</p>
                 </body></html>"""
            )
            write(
                "OEBPS/c2.xhtml",
                """<html><body><h2>第二章</h2><p>第二章内容。</p></body></html>"""
            )
        }

        try {
            val book = EpubParser.parse(epub.absolutePath, "test_book")
            assertNotNull("应能解析出书", book)
            assertEquals("测试书", book!!.title)
            assertEquals("测试作者", book.author)
            assertEquals(2, book.totalChapters)
            // 第一章：h2 标题也成一整段（3 段），第一段两句
            val c1 = book.chapters[0]
            assertEquals(3, c1.paragraphs.size)
            assertEquals("第一章", c1.paragraphs[0].text)
            assertEquals(2, c1.paragraphs[1].sentences.size)
            assertEquals("第一章第一段。", c1.paragraphs[1].sentences[0].text)
            // 偏移一致性：段偏移应指向章节文本
            assertEquals(0, c1.paragraphs[0].offset)
            assertTrue(c1.text.length > 0)
        } finally {
            epub.delete()
            dir.delete()
        }
    }

    @Test
    fun `非 EPUB 文件解析失败`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "epub_bad_${System.nanoTime()}")
        dir.mkdirs()
        val f = File(dir, "bad.epub")
        f.writeText("这不是一个 zip")
        try {
            assertEquals(null, EpubParser.parse(f.absolutePath, "bad"))
        } finally {
            f.delete(); dir.delete()
        }
    }
}
