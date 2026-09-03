package com.example.areadtext.reader

import com.example.areadtext.reader.book.Paragraph
import com.example.areadtext.reader.book.Sentence

/**
 * 文本分段器：把章节正文切成段、把段切成句，并保留各自在原文中的偏移。
 *
 * 语义综合 MoRealm（段落级）+ Lector（逐句级）两套口径：
 *  - 段落以换行 / <br> / <div> 闭合为界（清洗阶段已归一化为 '\n'），空段与
 *    纯装饰段（如 "---"）保留位置但不参与朗读，保证高亮进度与渲染 1:1。
 *  - 句子以 。！？…；；以及英文 .!? 为界，分隔符保留在句尾（对齐 MoRealm
 *    splitIntoSubSentences 的"保留分隔符在前一子句末尾"）。
 */
object TextSegmenter {

    // 中文句末标点 + 英文 !?;（英文句点 . 需"后跟空白/结尾"且非缩写才切，避免拆开 3.14 / Mr.）
    private const val SENTENCE_ENDERS = "。！？…；!?;"

    /** 把一段正文切成 [Sentence] 列表（含各自在段内偏移）。空段返回空列表。 */
    fun sentencesOf(paragraphText: String): List<Sentence> {
        val result = ArrayList<Sentence>()
        val sb = StringBuilder()
        var atStart = true
        var i = 0
        val n = paragraphText.length
        while (i < n) {
            val c = paragraphText[i]
            // 句首空白不并入（保证 "Hello world. Next" → 第二句以 N 开头）
            if (atStart && c.isWhitespace()) { i++; continue }
            atStart = false
            sb.append(c)

            val isEnd = when {
                c in SENTENCE_ENDERS -> true
                c == '.' -> {
                    if (i == n - 1) true
                    else if (!paragraphText[i + 1].isWhitespace()) false
                    else !isAbbreviationDot(paragraphText, i)
                }
                else -> false
            }
            if (isEnd && sb.isNotBlank()) {
                result.add(Sentence(text = sb.toString(), start = result.sumOf { it.text.length }))
                sb.clear()
                atStart = true
            }
            i++
        }
        if (sb.isNotBlank()) result.add(Sentence(text = sb.toString(), start = result.sumOf { it.text.length }))
        return result
    }

    /** 点号前是 1..3 个纯字母（Mr. / Dr. / St. / U.S.…）视为缩写，不切句。 */
    private fun isAbbreviationDot(text: String, dotIndex: Int): Boolean {
        var k = dotIndex - 1
        var len = 0
        while (k >= 0 && text[k].isLetter()) { len++; k-- }
        return len in 1..3
    }

    /**
     * 把章节纯文本切成 [Paragraph] 列表（含每段在章节文本中的偏移）。
     * 空行直接跳过（不产生段落）；全空白/纯符号段保留位置但标记空，供朗读层跳过。
     */
    fun paragraphsOf(chapterText: String): List<Paragraph> {
        val result = ArrayList<Paragraph>()
        var index = 0
        for (raw in chapterText.lines()) {
            val text = raw.trim()
            if (text.isEmpty()) {
                index += raw.length + 1
                continue
            }
            result.add(Paragraph(text = text, offset = index, sentences = sentencesOf(text)))
            index += raw.length + 1
        }
        return result
    }
}
