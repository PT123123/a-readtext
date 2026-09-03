package com.example.areadtext

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

/**
 * 应用入口：初始化第三方库所需的 Android 资源。
 *
 *  - pdfbox-android（PDF 文本提取）需要在首次使用前加载字体资源，
 *    否则 PDFTextStripper 在处理含嵌入字体的 PDF 时会抛异常。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }
}
