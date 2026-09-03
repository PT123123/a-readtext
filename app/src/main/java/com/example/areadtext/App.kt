package com.example.areadtext

import android.app.Application

/**
 * 应用入口。
 *
 * 说明：历史版本在这里初始化 pdfbox-android 的字体资源（PDFBoxResourceLoader），
 * 迁移到 MuPDF 后不再需要任何第三方库的 Android 资源预加载。
 */
class App : Application()
