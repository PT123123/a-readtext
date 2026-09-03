package com.example.areadtext.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncTimelineTest {

    @Test
    fun `音频时间反查句子`() {
        val tl = SyncTimeline()
        tl.add(0, 0, 0, 4, "你好。", 1000)
        tl.add(0, 1, 4, 9, "世界！", 1500)
        assertEquals(2500, tl.totalAudioMs)

        // FolioReader 式：播放位置 → 当前句子
        assertEquals(0, tl.spanAt(0)!!.sentenceIndex)
        assertEquals(0, tl.spanAt(999)!!.sentenceIndex)
        assertEquals(1, tl.spanAt(1000)!!.sentenceIndex)
        assertEquals(1, tl.spanAt(2499)!!.sentenceIndex)
        assertEquals(1, tl.spanAt(2500)!!.sentenceIndex)
        assertNull(tl.spanAt(-1))
    }

    @Test
    fun `文本区间随句子累积`() {
        val tl = SyncTimeline()
        tl.add(1, 0, 10, 18, "第二段开头。", 500)
        assertEquals(1, tl.spanAt(0)!!.paragraphIndex)
        assertEquals(10, tl.spanAt(0)!!.textStart)
        assertEquals(18, tl.spanAt(0)!!.textEnd)
    }

    @Test
    fun `清空后回到起点`() {
        val tl = SyncTimeline()
        tl.add(0, 0, 0, 3, "abc", 100)
        tl.clear()
        assertEquals(0, tl.totalAudioMs)
        assertNull(tl.spanAt(0))
    }
}
