package com.example.areadtext.reader.book

/** 一句话。[start] 为该句在所属 [Paragraph.text] 内的起始下标（含），end 用 start+len。 */
data class Sentence(
    val text: String,
    val start: Int,
) {
    val end: Int get() = start + text.length
    val isEmpty: Boolean get() = text.isBlank()
}

/** 一段。[offset] 为该段在 [Chapter.text] 内的起始下标。 */
data class Paragraph(
    val text: String,
    val offset: Int,
    val sentences: List<Sentence> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isBlank()
}

data class Chapter(
    val id: String,
    val title: String,
    val text: String,
    val paragraphs: List<Paragraph>,
) {
    val isReadable: Boolean get() = paragraphs.any { !it.isEmpty }
}

data class Book(
    val bookId: String,
    val title: String,
    val author: String,
    val filePath: String,
    val coverPath: String? = null,
    val chapters: List<Chapter>,
) {
    val totalChapters: Int get() = chapters.size
}
