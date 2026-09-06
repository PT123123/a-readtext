package com.example.areadtext.reader

import android.content.Context
import android.content.SharedPreferences

/** 阅读器用户设置（legado 风格阅读偏好：字号/行距/主题/语速/音色）。 */
object ReaderPreferences {

    private const val PREFS = "areadtext_reader"
    private const val KEY_FONT_SP = "font_sp"
    private const val KEY_LINE_SPACING = "line_spacing"
    private const val KEY_THEME = "theme"
    private const val KEY_SPEED = "speed"
    private const val KEY_SID = "sid"

    // 旧主题 int → 新 theme id 迁移映射
    private val LEGACY_THEME_MAP = mapOf(
        0 to "paper",
        1 to "sepia",
        2 to "night",
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fontSp(context: Context): Float =
        prefs(context).getFloat(KEY_FONT_SP, 19f).coerceIn(12f, 32f)

    fun setFontSp(context: Context, v: Float) =
        prefs(context).edit().putFloat(KEY_FONT_SP, v.coerceIn(12f, 32f)).apply()

    fun lineSpacing(context: Context): Float =
        prefs(context).getFloat(KEY_LINE_SPACING, 1.5f).coerceIn(1.0f, 2.2f)

    fun setLineSpacing(context: Context, v: Float) =
        prefs(context).edit().putFloat(KEY_LINE_SPACING, v.coerceIn(1.0f, 2.2f)).apply()

    /**
     * 获取当前主题 id。
     * 自动迁移旧版 int 主题到新版 String id（首次读取时）。
     */
    fun themeId(context: Context): String {
        val p = prefs(context)
        // 新版：直接读 String
        val str = p.getString(KEY_THEME, null)
        if (str != null) return str

        // 旧版：读 int，迁移
        if (p.contains(KEY_THEME + "_int")) {
            val legacy = p.getInt(KEY_THEME + "_int", 0)
            val id = LEGACY_THEME_MAP[legacy] ?: "paper"
            // 写入新版
            p.edit().putString(KEY_THEME, id).remove(KEY_THEME + "_int").apply()
            return id
        }

        // 默认
        return "paper"
    }

    /** 向后兼容：旧代码 getInt 主题（返回 int，仅内部用）。 */
    @Suppress("unused")
    private fun themeInt(context: Context): Int = when (themeId(context)) {
        "paper" -> 0
        "sepia" -> 1
        "night" -> 2
        else -> 0
    }

    fun setTheme(context: Context, themeId: String) =
        prefs(context).edit().putString(KEY_THEME, themeId).apply()

    /** 直接设置主题（接受 [ReaderTheme]）。 */
    fun setTheme(context: Context, theme: ReaderTheme) =
        prefs(context).edit().putString(KEY_THEME, theme.id).apply()

    fun speed(context: Context): Float =
        prefs(context).getFloat(KEY_SPEED, 1f).coerceIn(0.3f, 4f)

    fun setSpeed(context: Context, v: Float) =
        prefs(context).edit().putFloat(KEY_SPEED, v.coerceIn(0.3f, 4f)).apply()

    fun sid(context: Context): Int = prefs(context).getInt(KEY_SID, 0)

    fun setSid(context: Context, sid: Int) =
        prefs(context).edit().putInt(KEY_SID, sid.coerceAtLeast(0)).apply()
}
