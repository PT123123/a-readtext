package com.example.areadtext.reader

import com.example.areadtext.R

/**
 * 阅读器主题调色板。
 *
 * 每个主题包含完整的配色方案：背景、文字、表面、强调色、高亮、段落高亮。
 * 主题切换即时生效（不重启 Activity）。
 */
data class ReaderTheme(
    val id: String,
    val nameRes: Int,
    val bgColor: Int,
    val textColor: Int,
    val surfaceColor: Int,
    val accentColor: Int,
    val highlightColor: Int,       // 当前朗读句高亮
    val paraHighlightColor: Int,   // 当前段落背景
    val isDark: Boolean,
    val swatchColor: Int,          // UI 色块预览色
) {
    /** 用于 toolbar/TTS bar 的文字色（surface 上的对比色）。 */
    val onSurfaceColor: Int get() = textColor

    /** 次要文字色（章节标题、进度等）。 */
    val secondaryTextColor: Int get() = (textColor and 0x00FFFFFF) or 0x99000000.toInt()
}

object ThemeCatalog {

    // ── 核心主题（必做）──
    // 注意：所有颜色必须带 0xFF alpha 前缀，否则 toInt() 后 alpha=0 导致完全透明。
    val PAPER = ReaderTheme(
        id = "paper", nameRes = R.string.theme_paper,
        bgColor = 0xFFFAF6EC.toInt(), textColor = 0xFF2E2A26.toInt(),
        surfaceColor = 0xFFFFFFFF.toInt(), accentColor = 0xFF1565C0.toInt(),
        highlightColor = 0xFFFFE082.toInt(), paraHighlightColor = 0xFFF5EEDE.toInt(),
        isDark = false, swatchColor = 0xFFFAF6EC.toInt(),
    )
    val SEPIA = ReaderTheme(
        id = "sepia", nameRes = R.string.theme_sepia,
        bgColor = 0xFFF4ECD8.toInt(), textColor = 0xFF5B4636.toInt(),
        surfaceColor = 0xFFFAF5E8.toInt(), accentColor = 0xFF8D6E63.toInt(),
        highlightColor = 0xFFD3B36C.toInt(), paraHighlightColor = 0xFFEEE3CB.toInt(),
        isDark = false, swatchColor = 0xFFF4ECD8.toInt(),
    )
    val WHITE = ReaderTheme(
        id = "white", nameRes = R.string.theme_white,
        bgColor = 0xFFFFFFFF.toInt(), textColor = 0xFF333333.toInt(),
        surfaceColor = 0xFFFAFAFA.toInt(), accentColor = 0xFF1565C0.toInt(),
        highlightColor = 0xFFFFE082.toInt(), paraHighlightColor = 0xFFF5F5F5.toInt(),
        isDark = false, swatchColor = 0xFFFFFFFF.toInt(),
    )
    val FOREST = ReaderTheme(
        id = "forest", nameRes = R.string.theme_forest,
        bgColor = 0xFFC7EDCC.toInt(), textColor = 0xFF2E4A2E.toInt(),
        surfaceColor = 0xFFE8F5E9.toInt(), accentColor = 0xFF2E7D32.toInt(),
        highlightColor = 0xFFA5D6A7.toInt(), paraHighlightColor = 0xFFD6EBD6.toInt(),
        isDark = false, swatchColor = 0xFFC7EDCC.toInt(),
    )
    val NIGHT = ReaderTheme(
        id = "night", nameRes = R.string.theme_night,
        bgColor = 0xFF121212.toInt(), textColor = 0xFFC8C8C8.toInt(),
        surfaceColor = 0xFF1E1E1E.toInt(), accentColor = 0xFF90CAF9.toInt(),
        highlightColor = 0xFF3A3A3A.toInt(), paraHighlightColor = 0xFF202020.toInt(),
        isDark = true, swatchColor = 0xFF121212.toInt(),
    )
    val OCEAN = ReaderTheme(
        id = "ocean", nameRes = R.string.theme_ocean,
        bgColor = 0xFF0D1B2A.toInt(), textColor = 0xFFA0B4C8.toInt(),
        surfaceColor = 0xFF1B2838.toInt(), accentColor = 0xFF4FC3F7.toInt(),
        highlightColor = 0xFF1A3A4A.toInt(), paraHighlightColor = 0xFF162535.toInt(),
        isDark = true, swatchColor = 0xFF0D1B2A.toInt(),
    )

    // ── 扩展主题（可选）──
    val ROSE = ReaderTheme(
        id = "rose", nameRes = R.string.theme_rose,
        bgColor = 0xFFF5E6E8.toInt(), textColor = 0xFF6B4E57.toInt(),
        surfaceColor = 0xFFFAF0F2.toInt(), accentColor = 0xFFAD1457.toInt(),
        highlightColor = 0xFFF8BBD0.toInt(), paraHighlightColor = 0xFFF0DDDE.toInt(),
        isDark = false, swatchColor = 0xFFF5E6E8.toInt(),
    )
    val AMBER = ReaderTheme(
        id = "amber", nameRes = R.string.theme_amber,
        bgColor = 0xFF1A140E.toInt(), textColor = 0xFFFFD699.toInt(),
        surfaceColor = 0xFF2A2218.toInt(), accentColor = 0xFFFFFF00.toInt(),
        highlightColor = 0xFF4A3A1A.toInt(), paraHighlightColor = 0xFF2A2218.toInt(),
        isDark = true, swatchColor = 0xFF1A140E.toInt(),
    )

    /** 所有主题（按显示顺序）。 */
    val themes: List<ReaderTheme> = listOf(PAPER, SEPIA, WHITE, FOREST, NIGHT, OCEAN, ROSE, AMBER)

    /** 默认主题。 */
    val DEFAULT = PAPER

    fun byId(id: String?): ReaderTheme = themes.find { it.id == id } ?: DEFAULT

    /** 主题数量。 */
    val size: Int get() = themes.size
}
