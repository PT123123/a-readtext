package com.example.areadtext.model

import android.content.Context
import android.content.SharedPreferences

/**
 * 下载镜像机制（拓展开源模型资源的落地通道之一）。
 *
 * 内置模型默认走 GitHub 官方 Releases 直链；国内网络下载失败（403 / 超时 / DNS /
 * TLS）时，用户可在失败卡片上点「换镜像重试」轮换下载通道：
 *
 *   0. GitHub 官方直连（默认）
 *   1. 社区加速 · ghproxy.net   （前缀式代理，https://ghproxy.net/<原始URL>）
 *   2. 社区加速 · gh-proxy.com  （前缀式代理，https://gh-proxy.com/<原始URL>）
 *   3. 自定义镜像前缀            （用户粘贴，同样拼在 GitHub 链接前）
 *
 * 每个模型的选择单独持久化（SharedPreferences），下次下载 / 断点续传自动沿用。
 * 注意：社区加速镜像为第三方服务，稳定性不一；内置模型删除后重加仍保留其镜像选择。
 */
object ModelMirror {

    private const val PREFS = "areadtext_mirrors"
    private const val KEY_SELECTED = "selected_%s"       // per model id -> mirror key
    private const val KEY_CUSTOM_PREFIX = "custom_prefix" // 用户自定义前缀（拼在 GitHub URL 前）

    /** 一个下载通道：key 稳定标识；customPrefix 非空时 = 把该前缀拼到原始 URL 前。 */
    data class Mirror(val key: String, val label: String, val customPrefix: String? = null, val isCustom: Boolean = false)

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 内置镜像（确定性重写规则，不依赖运行时状态）。 */
    private val builtinMirrors = listOf(
        Mirror("github", "GitHub 官方直连"),
        Mirror("ghproxy_net", "社区加速 · ghproxy.net", "https://ghproxy.net/"),
        Mirror("gh_proxy", "社区加速 · gh-proxy.com", "https://gh-proxy.com/"),
    )

    /** 全部可用镜像 = 内置 + 用户自定义前缀。 */
    fun allMirrors(c: Context): List<Mirror> = buildList {
        addAll(builtinMirrors)
        val cp = prefs(c).getString(KEY_CUSTOM_PREFIX, null)?.trim().orEmpty()
        if (cp.isNotBlank()) add(Mirror("custom", "自定义镜像", cp, isCustom = true))
    }

    fun selectedKey(c: Context, modelId: String): String =
        prefs(c).getString(String.format(KEY_SELECTED, modelId), "github") ?: "github"

    fun setSelected(c: Context, modelId: String, key: String) {
        prefs(c).edit().putString(String.format(KEY_SELECTED, modelId), key).apply()
    }

    /** 当前模型使用的镜像（默认 GitHub 直连）。 */
    fun current(c: Context, info: LocalModelInfo): Mirror =
        allMirrors(c).firstOrNull { it.key == selectedKey(c, info.id) } ?: builtinMirrors.first()

    /** 轮换到下一个镜像（供「换镜像重试」使用）。 */
    fun next(c: Context, info: LocalModelInfo): Mirror {
        val list = allMirrors(c)
        if (list.isEmpty()) return builtinMirrors.first()
        val cur = selectedKey(c, info.id)
        val idx = list.indexOfFirst { it.key == cur }.coerceAtLeast(0)
        return list[(idx + 1) % list.size]
    }

    /** 解析出本次下载实际使用的 URL 与镜像（worker 与 UI 共用，保证口径一致）。 */
    fun resolve(c: Context, info: LocalModelInfo): Pair<String, Mirror> {
        val mirror = current(c, info)
        val url = mirror.customPrefix?.let { p -> p.trimEnd('/') + "/" + info.archive.url }
            ?: info.archive.url
        return url to mirror
    }

    fun setCustomPrefix(c: Context, prefix: String) {
        prefs(c).edit().putString(KEY_CUSTOM_PREFIX, prefix.trim()).apply()
    }

    fun customPrefix(c: Context): String =
        prefs(c).getString(KEY_CUSTOM_PREFIX, null)?.trim().orEmpty()
}
