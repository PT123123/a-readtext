package com.example.areadtext

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.example.areadtext.utils.StorageUtils

/**
 * 设置界面
 * 提供应用设置、存储管理、关于信息等功能
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            setupStoragePreferences()
            setupAboutPreferences()
            setupDeveloperPreferences()
        }

        private fun setupStoragePreferences() {
            // 存储信息
            val storageInfoPref = findPreference<Preference>("storage_info")
            storageInfoPref?.setOnPreferenceClickListener {
                updateStorageInfo()
                true
            }

            // 清理缓存
            val clearCachePref = findPreference<Preference>("clear_cache")
            clearCachePref?.setOnPreferenceClickListener {
                clearAppCache()
                true
            }

            // 管理模型
            val manageModelsPref = findPreference<Preference>("manage_models")
            manageModelsPref?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ModelManagerActivity::class.java))
                true
            }

            // 批量粘贴自定义模型（运行时添加，无需重新编译）
            val pasteCustomPref = findPreference<Preference>("paste_custom_models")
            pasteCustomPref?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), CustomModelActivity::class.java))
                true
            }

            // 管理录音
            val manageRecordingsPref = findPreference<Preference>("manage_recordings")
            manageRecordingsPref?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), HistoryActivity::class.java))
                true
            }

            // 初始化存储信息
            updateStorageInfo()
        }

        private fun setupAboutPreferences() {
            // 版本信息
            val versionPref = findPreference<Preference>("version")
            versionPref?.summary = try {
                val packageInfo = requireContext().packageManager.getPackageInfo(
                    requireContext().packageName, 0
                )
                "${packageInfo.versionName} (${packageInfo.longVersionCode})"
            } catch (e: Exception) {
                "未知版本"
            }

            // 关于
            val aboutPref = findPreference<Preference>("about")
            aboutPref?.setOnPreferenceClickListener {
                showAboutDialog()
                true
            }

            // GitHub
            val githubPref = findPreference<Preference>("github")
            githubPref?.setOnPreferenceClickListener {
                openGitHub()
                true
            }

            // 许可证
            val licensePref = findPreference<Preference>("license")
            licensePref?.setOnPreferenceClickListener {
                showLicenseDialog()
                true
            }
        }

        private fun setupDeveloperPreferences() {
            // 调试模式
            val debugModePref = findPreference<SwitchPreference>("debug_mode")
            debugModePref?.setOnPreferenceChangeListener { _, newValue ->
                val isEnabled = newValue as Boolean
                // 这里可以实现调试模式的切换逻辑
                true
            }

            // 导出日志
            val exportLogsPref = findPreference<Preference>("export_logs")
            exportLogsPref?.setOnPreferenceClickListener {
                exportLogs()
                true
            }

            // 设备信息
            val deviceInfoPref = findPreference<Preference>("device_info")
            deviceInfoPref?.setOnPreferenceClickListener {
                showDeviceInfo()
                true
            }
        }

        private fun updateStorageInfo() {
            val context = requireContext()
            val modelSize = StorageUtils.getModelStorageSize(context)
            val recordingsSize = StorageUtils.getRecordingsStorageSize(context)
            val cacheSize = StorageUtils.getAppStorageSize(context) - modelSize - recordingsSize

            val storageInfo = buildString {
                append("模型占用: ${Formatter.formatFileSize(context, modelSize)}\n")
                append("录音占用: ${Formatter.formatFileSize(context, recordingsSize)}\n")
                append("缓存占用: ${Formatter.formatFileSize(context, cacheSize)}\n")
                append("总占用: ${Formatter.formatFileSize(context, modelSize + recordingsSize + cacheSize)}\n\n")
                append("可用空间: ${Formatter.formatFileSize(context, StorageUtils.getAvailableInternalStorage(context))}")
            }

            findPreference<Preference>("storage_info")?.summary = storageInfo
        }

        private fun clearAppCache() {
            val context = requireContext()
            try {
                val freedSize = StorageUtils.clearAppCache(context)
                updateStorageInfo()
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.storage_cleared, Formatter.formatFileSize(context, freedSize)),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.storage_clean_failed),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun showAboutDialog() {
            val context = requireContext()
            val aboutText = """
                |实时语音转写应用
                |
                |基于sherpa-onnx流式Paraformer引擎实现离线语音识别功能。
                |
                |主要特性:
                |• 实时语音转写
                |• 离线工作
                |• 中英双语支持
                |• 模型自定义
                |• 历史记录管理
                |
                |技术架构:
                |• AudioRecord音频采集
                |• sherpa-onnx ASR引擎
                |• Room数据库
                |• WorkManager后台任务
            """.trimMargin("|")

            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.settings_about)
                .setMessage(aboutText)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun openGitHub() {
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/ted/aphone-s2t"))
                startActivity(intent)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    requireContext(),
                    "无法打开GitHub链接",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        private fun showLicenseDialog() {
            val licenseText = """
                |Apache License
                |Version 2.0, January 2004
                |http://www.apache.org/licenses/
                |
                |TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION
                |
                |1. Definitions.
                |
                |"License" shall mean the terms and conditions for use, reproduction,
                |and distribution as defined by Sections 1 through 9 of this document.
                |
                |"Licensor" shall mean the copyright owner or entity authorized by
                |the copyright owner that is granting the License.
                |
                |"Legal Entity" shall mean the union of the acting entity and all
                |other entities that control, are controlled by, or are under common
                |control with that entity. For the purposes of this definition,
                |"control" means (i) the power, direct or indirect, to cause the
                |direction or management of such entity, whether by contract or
                |otherwise, or (ii) ownership of fifty percent (50%) or more of the
                |outstanding shares, or (iii) beneficial ownership of such entity.
                |
                |"You" (or "Your") shall mean an individual or Legal Entity
                |exercising permissions granted by this License.
                |
                |"Source" form shall mean the preferred form for making modifications,
                |including but not limited to software source code, documentation
                |source, and configuration files.
                |
                |"Object" form shall mean any form resulting from mechanical
                |transformation or translation of a Source form, including but
                |not limited to compiled object code, generated documentation,
                |and conversions to other media types.
                |
                |"Work" shall mean the work of authorship, whether in Source or
                |Object form, made available under the License, as indicated by a
                |copyright notice that is included in or attached to the work
                |(an example is provided in the Appendix below).
                |
                |"Derivative Works" shall mean any work, whether in Source or Object
                |form, that is based on (or derived from) the Work and for which the
                |editorial revisions, annotations, elaborations, or other modifications
                |represent, as a whole, an original work of authorship. For the purposes
                |of this License, Derivative Works shall not include works that remain
                |separable from, or merely link (or bind by name) to the interfaces of,
                |the Work and Derivative Works thereof.
                |
                |"Contribution" shall mean any work of authorship, including
                |the original version of the Work and any modifications or additions
                |to that Work or Derivative Works thereof, that is intentionally
                |submitted to Licensor for inclusion in the Work by the copyright owner
                |or by an individual or Legal Entity authorized to submit on behalf of
                |the copyright owner. For the purposes of this definition, "submitted"
                |means any form of electronic, verbal, or written communication sent
                |to the Licensor or its representatives, including but not limited to
                |communication on electronic mailing lists, source code control systems,
                |and issue tracking systems that are managed by, or on behalf of, the
                |Licensor for the purpose of discussing and improving the Work, but
                |excluding communication that is conspicuously marked or otherwise
                |designated in writing by the copyright owner as "Not a Contribution."
                |
                |"Contributor" shall mean Licensor and any individual or Legal Entity
                |on behalf of whom a Contribution has been received by Licensor and
                |subsequently incorporated within the Work.
            """.trimMargin("|")

            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_license)
                .setMessage(licenseText)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        private fun exportLogs() {
            android.widget.Toast.makeText(
                requireContext(),
                "日志导出功能开发中",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        private fun showDeviceInfo() {
            val context = requireContext()
            val deviceInfo = buildString {
                append("设备信息:\n\n")
                append("Android版本: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
                append("设备型号: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                append("处理器: ${android.os.Build.HARDWARE}\n")
                append("系统架构: ${android.os.Build.SUPPORTED_ABIS.joinToString()}\n")
                append("应用版本: ")
                try {
                    val packageInfo = context.packageManager.getPackageInfo(
                        context.packageName, 0
                    )
                    append("${packageInfo.versionName} (${packageInfo.longVersionCode})")
                } catch (e: Exception) {
                    append("未知")
                }
                append("\n\n存储信息:\n")
                append(StorageUtils.getStorageInfoString(context))
            }

            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(R.string.settings_device_info)
                .setMessage(deviceInfo)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}