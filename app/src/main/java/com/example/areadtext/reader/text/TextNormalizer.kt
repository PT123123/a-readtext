package com.example.areadtext.reader.text

/**
 * 跨格式的通用文本规范化工具。
 *
 * 所有 [com.example.areadtext.reader.book.BookParser] 提取文本后都经过此工具处理，
 * 解决各格式共有的 Unicode 边界问题：
 *
 *  1. **Ligature 展开**：PDF/EPUB 子集化字体常把 "fi" "fl" 等编码为单 glyph，
 *     若字体 ToUnicode CMap 缺失该条目就出现 □/乱码。展开为多字符可恢复可读性
 *     （代价：高亮/搜索按展开后坐标，与原 glyph 数不一致，但阅读体验远优于乱码）。
 *  2. **零宽字符 + BOM**：UTF-16 BOM、零宽空格/连接符、软连字符等不可见字符
 *     会污染文本（尤其 EPUB 转换工具产物），一律删除。
 *  3. **特殊空格归一化**：不换行空格(00A0)、各种 typographic 空格、全角空格(3000)
 *     统一为普通空格，避免段落切分/句子切分把"词　词"误判为两段。
 *  4. **控制字符清理**：除换行/tab 外的 C0/C1 控制字符删除（PDF 流中常见）。
 */
object TextNormalizer {

    /** 常见 Latin ligature → 展开形式。覆盖 Unicode FB00–FB06 + 常见历史 ligature。 */
    private val LIGATURES: Map<Char, String> = buildMap {
        // FB00–FB06 标准 ligature
        put('ﬀ', "ff")   // U+FB00
        put('ﬁ', "fi")   // U+FB01
        put('ﬂ', "fl")   // U+FB02
        put('ﬃ', "ffi")  // U+FB03
        put('ﬄ', "ffl")  // U+FB04
        put('ﬅ', "ſt")   // U+FB05 (long s + t)
        put('ﬆ', "st")   // U+FB06
        // 拉丁连字
        put('Œ', "OE")   // U+0152
        put('œ', "oe")   // U+0153
        put('Ĳ', "IJ")   // U+0132
        put('ĳ', "ij")   // U+0133
        put('ß', "ss")   // U+00DF
        put('ẞ', "SS")   // U+1E9E
        // 拉丁二合字母
        put('Ǳ', "DZ")   // U+01F1
        put('ǲ', "Dz")   // U+01F2
        put('ǳ', "dz")   // U+01F3
        put('Ǆ', "DZ")   // U+01C4
        put('ǅ', "Dz")   // U+01C5
        put('ǆ', "dz")   // U+01C6
        put('Ǉ', "LJ")   // U+01C7
        put('ǈ', "Lj")   // U+01C8
        put('ǉ', "lj")   // U+01C9
        put('Ǌ', "NJ")   // U+01CA
        put('ǋ', "Nj")   // U+01CB
        put('ǌ', "nj")   // U+01CC
        // 其他
        put('ȹ', "qp")   // U+0239
        put('Ꜳ', "AA")   // U+A732
        put('ꜳ', "aa")   // U+A733
        put('Ꜵ', "AO")   // U+A734
        put('ꜵ', "ao")   // U+A735
        put('Ꜷ', "AU")   // U+A736
        put('ꜷ', "au")   // U+A737
        put('Ꜹ', "AV")   // U+A738
        put('ꜹ', "av")   // U+A739
        put('Ꜽ', "AY")   // U+A73C
        put('ꜽ', "ay")   // U+A73D
        put('Ꝏ', "OO")   // U+A74E
        put('ꝏ', "oo")   // U+A74F
    }

    /** 零宽 / 不可见字符集合（这些字符不占宽度，会污染文本处理）。 */
    private val ZERO_WIDTH_CHARS: Set<Char> = buildSet {
        add('​')  // U+200B zero width space
        add('‌')  // U+200C zero width non-joiner
        add('‍')  // U+200D zero width joiner
        add('﻿')  // U+FEFF zero width no-break space (BOM)
        add('⁠')  // U+2060 word joiner
        add('­')  // U+00AD soft hyphen
        add(' ')  // U+2028 line separator
        add(' ')  // U+2029 paragraph separator
        add('‪')  // U+202A LRE
        add('‫')  // U+202B RLE
        add('‬')  // U+202C PDF
        add('‭')  // U+202D LRO
        add('‮')  // U+202E RLO
        add('‏')  // U+200F right-to-left mark
        add('‎')  // U+200E left-to-right mark
    }

    /** 特殊空格 → 普通空格。包括全角空格和各种 typographic 空格。 */
    private val SPECIAL_SPACES = Regex(
        "[  -   　]"
    )

    /**
     * 规范化文本：展开 ligature、删除零宽字符、归一化空格、清理控制字符。
     *
     * @return 规范化后的文本（保证非 null，输入 blank 返回 ""）
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ZERO_WIDTH_CHARS.contains(ch) -> continue
                LIGATURES.containsKey(ch) -> sb.append(LIGATURES[ch])
                else -> sb.append(ch)
            }
        }
        // 特殊空格 → 普通空格
        var result = SPECIAL_SPACES.replace(sb.toString(), " ")
        // 控制字符清理（保留 \n \t）：C0 0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F + DEL 0x7F + C1 0x80-0x9F
        result = result.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F-\\x9F]"), "")
        return result
    }

    /** 轻量版：只处理空格和零宽字符（用于已处理过 ligature 的文本二次清洗）。 */
    fun normalizeLight(text: String): String {
        if (text.isEmpty()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            if (ZERO_WIDTH_CHARS.contains(ch)) continue
            sb.append(ch)
        }
        return SPECIAL_SPACES.replace(sb.toString(), " ")
    }
}
