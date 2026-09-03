package com.example.areadtext.reader

/**
 * Reader 域模型（纯 Kotlin，可单测）。
 *
 * 结构对齐 MoRealm 的 "paragraph offsets 单一数据源" 思路：
 * 每个 [EpubChapter] 持有干净的纯文本正文 [text]（HTML 已剥除、实体已解码），
 * [paragraphs] 里的每个 [Paragraph] 通过 [offset] 指向 [EpubChapter.text] 中的
 * 起始下标 —— 渲染层与 TTS 朗读层共享同一坐标系，杜绝"两套分段器对不齐"导致的
 * 高亮卡在第一段的问题（MoRealm TtsHost.paragraphsFromPositions 就是为了解决
 * 同样的问题而生的）。
 */

/** 一句话。 [start] 为该句在所属 [Paragraph.text] 内的起始下标（含），end 用 start+len。 */
data class Sentence(
    val text: String,
    val start: Int,
) {
    val end: Int get() = start + text.length
    val isEmpty: Boolean get() = text.isBlank()
}

/** 一段。 [offset] 为该段在 [EpubChapter.text] 内的起始下标。 */
data class Paragraph(
    val text: String,
    val offset: Int,
    val sentences: List<Sentence> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank()
}

data class EpubChapter(
    val id: String,
    val title: String,
    val text: String,
    val paragraphs: List<Paragraph>,
) {
    val isReadable: Boolean get() = paragraphs.any { !it.isEmpty }
}

data class EpubBook(
    val bookId: String,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String? = null,
    val chapters: List<EpubChapter>,
) {
    val totalChapters: Int get() = chapters.size
}
