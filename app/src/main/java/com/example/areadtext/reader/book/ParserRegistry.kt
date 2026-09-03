package com.example.areadtext.reader.book

import java.io.File

/**
 * 按文件扩展名分发到对应的 [BookParser]。
 *
 * 新增格式只需在此 object 加一条 when 分支 + 实现 BookParser 接口，
 * 导入管线（ShelfActivity.importBook）即可自动支持。
 */
object ParserRegistry {

    private val parsers: Map<String, BookParser> = mapOf(
        "epub" to EpubBookParser,
        "pdf" to PdfBookParser,
        "txt" to TxtBookParser,
        "md" to MdBookParser,
        "markdown" to MdBookParser,
    )

    fun forFile(file: File): BookParser? = parsers[file.extension.lowercase()]

    fun isSupported(file: File): Boolean = forFile(file) != null

    fun supportedExtensions(): List<String> = parsers.keys.toList()
}
