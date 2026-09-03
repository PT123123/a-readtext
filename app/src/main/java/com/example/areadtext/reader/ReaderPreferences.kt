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

    const val THEME_PAPER = 0
    const val THEME_SEPIA = 1
    const val THEME_NIGHT = 2

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

    fun theme(context: Context): Int =
        prefs(context).getInt(KEY_THEME, THEME_PAPER)

    fun setTheme(context: Context, t: Int) =
        prefs(context).edit().putInt(KEY_THEME, t).apply()

    fun speed(context: Context): Float =
        prefs(context).getFloat(KEY_SPEED, 1f).coerceIn(0.3f, 4f)

    fun setSpeed(context: Context, v: Float) =
        prefs(context).edit().putFloat(KEY_SPEED, v.coerceIn(0.3f, 4f)).apply()

    fun sid(context: Context): Int = prefs(context).getInt(KEY_SID, 0)

    fun setSid(context: Context, sid: Int) =
        prefs(context).edit().putInt(KEY_SID, sid.coerceAtLeast(0)).apply()
}
