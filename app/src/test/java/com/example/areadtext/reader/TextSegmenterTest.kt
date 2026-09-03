package com.example.areadtext.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSegmenterTest {

    @Test
    fun `段落切分保留偏移`() {
        val text = "第一段。第二句。\n第二段！"
        val paras = TextSegmenter.paragraphsOf(text)
        assertEquals(2, paras.size)
        assertEquals("第一段。第二句。", paras[0].text)
        assertEquals(0, paras[0].offset)
        assertEquals("第二段！", paras[1].text)
        assertEquals(9, paras[1].offset) // "第一段。第二句。" 8 字符 + 换行 1
        assertEquals(2, paras[0].sentences.size)
    }

    @Test
    fun `句子切分保留句末标点`() {
        val sents = TextSegmenter.sentencesOf("你好！世界。")
        assertEquals(2, sents.size)
        assertEquals("你好！", sents[0].text)
        assertEquals(0, sents[0].start)
        assertEquals("世界。", sents[1].text)
        assertEquals(3, sents[1].start)
    }

    @Test
    fun `空行不产生段落但偏移正确`() {
        val text = "A\n\nB"
        val paras = TextSegmenter.paragraphsOf(text)
        assertEquals(2, paras.size)
        assertEquals("A", paras[0].text)
        assertEquals("B", paras[1].text)
        assertEquals(3, paras[1].offset) // "A\n\n" = 3 字符
    }

    @Test
    fun `英文句末标点也能切句`() {
        val sents = TextSegmenter.sentencesOf("Hello world. Next one?")
        assertEquals(2, sents.size)
        assertEquals("Hello world.", sents[0].text)
        assertEquals(0, sents[0].start)
        assertEquals("Next one?", sents[1].text)
        assertEquals(12, sents[1].start)
    }

    @Test
    fun `小数点与缩写不被切开`() {
        val sents = TextSegmenter.sentencesOf("π≈3.14。Mr. Smith 说好。")
        assertEquals(2, sents.size)
        assertEquals("π≈3.14。", sents[0].text)
        assertEquals("Mr. Smith 说好。", sents[1].text)
    }

    @Test
    fun `纯符号装饰段保留位置`() {
        val paras = TextSegmenter.paragraphsOf("正文\n---\n后文")
        assertEquals(3, paras.size)
        assertEquals("---", paras[1].text)
        assertEquals(3, paras[1].offset)
    }
}
