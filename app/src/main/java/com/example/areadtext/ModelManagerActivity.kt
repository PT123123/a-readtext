package com.example.areadtext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.areadtext.databinding.ActivityModelManagerBinding
import com.example.areadtext.databinding.ItemModelBinding
import com.example.areadtext.dialog.AddCustomModelDialog
import com.example.areadtext.model.LocalModelInfo
import com.example.areadtext.model.ModelCatalog
import com.example.areadtext.model.ModelDownloadWorker
import com.example.areadtext.model.ModelInstallStatus
import com.example.areadtext.model.ModelManager
import com.example.areadtext.model.ModelMirror
import com.example.areadtext.model.ModelState
import com.example.areadtext.reader.OfflineTtsEngine
import com.example.areadtext.reader.TtsCommand
import com.example.areadtext.reader.TtsEventBus
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型管理界面
 *
 * 布局规则：
 *  1. 顶部：汇总行（共 N 个 · 已下载 M · 占用 X · 当前音色）+ 语言/排序筛选；
 *  2. 列表：每张卡片 = 模型名 + 彩色状态芯片 + 元信息 + （失败时）人话错误面板 +
 *     技术详情折叠 + 下载链接 + 进度条 + 右下对齐的操作按钮；
 *  3. 底部：添加自定义（实心）+ 批量粘贴（描边）等宽双按钮。
 *
 * 下载失败补救：403 / 超时 / DNS / TLS 等网络源问题 → 卡片上出现「换镜像重试」，
 * 轮换 GitHub 直连 → 社区加速 → 自定义镜像，选择按模型持久化。
 */
class ModelManagerActivity : AppCompatActivity(), AddCustomModelDialog.OnModelAddedListener {

    private lateinit var binding: ActivityModelManagerBinding

    private var lastStates: List<ModelState> = emptyList()
    private var languageFilter = "all"
    private var sortMode = "default"

    private val langLabels = arrayOf("全部", "中文", "中英", "粤语", "英文", "韩语", "法语", "孟加拉语")
    private val langKeys = arrayOf("all", "zh", "zh+en", "yue", "en", "ko", "fr", "bn")
    private val sortLabels = arrayOf("默认顺序", "大小从大到小", "大小从小到大")
    private val sortKeys = arrayOf("default", "desc", "asc")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)

        binding.btnAdd.setOnClickListener { addCustom() }
        binding.btnPaste.setOnClickListener {
            startActivity(android.content.Intent(this, CustomModelActivity::class.java))
        }

        setupFilterBar()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ModelManager.observeStates(this@ModelManagerActivity).collectLatest { states ->
                    lastStates = states
                    render()
                }
            }
        }
    }

    private fun setupFilterBar() {
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langLabels)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spLanguage.adapter = langAdapter
        binding.spLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                languageFilter = langKeys[position.coerceIn(0, langKeys.lastIndex)]
                render()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val sortAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sortLabels)
        sortAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spSort.adapter = sortAdapter
        binding.spSort.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                sortMode = sortKeys[position.coerceIn(0, sortKeys.lastIndex)]
                render()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun addCustom() {
        AddCustomModelDialog().show(
            supportFragmentManager,
            "AddCustomModelDialog"
        )
    }

    override fun onModelAdded() {
        // Model states are automatically updated through the observer
    }

    private fun render() {
        updateSummary()
        val filtered = lastStates.filter { matchesLanguage(it.info.language, languageFilter) }
        val sorted = when (sortMode) {
            "asc" -> filtered.sortedBy { it.info.downloadSizeBytes }
            "desc" -> filtered.sortedByDescending { it.info.downloadSizeBytes }
            else -> filtered
        }
        binding.container.removeAllViews()
        sorted.forEach { binding.container.addView(buildCard(it)) }
    }

    /** 顶部汇总：共 N 个模型 · 已下载 M · 占用 X · 当前音色。 */
    private fun updateSummary() {
        val installed = lastStates.filter { it.isInstalled }
        val used = installed.sumOf { it.info.downloadSizeBytes }
        val text = buildString {
            append(getString(
                R.string.model_summary,
                lastStates.size,
                installed.size,
                Formatter.formatFileSize(this@ModelManagerActivity, used)
            ))
            lastStates.firstOrNull { it.isActive }?.info?.name?.let { name ->
                append("  ·  ").append(getString(R.string.model_summary_active, name))
            }
        }
        binding.tvSummary.text = text
    }

    private fun matchesLanguage(modelLang: String, filter: String): Boolean = when (filter) {
        "all" -> true
        "zh+en" -> modelLang.contains("zh") && modelLang.contains("en")
        else -> modelLang.contains(filter)
    }

    private fun buildCard(state: ModelState): View {
        val b = ItemModelBinding.inflate(layoutInflater, binding.container, false)

        // 头部：模型名 + 状态芯片（彩色圆角）
        b.tvName.text = state.info.name
        b.tvStatusChip.text = if (state.isActive) {
            "${statusLabel(state.status)} · ${getString(R.string.model_active_chip)}"
        } else {
            statusLabel(state.status)
        }
        b.tvStatusChip.background = chipDrawable(statusColor(state.status))

        // 描述
        b.tvDesc.text = state.info.description

        // 元信息：语言 · 大小 · 建议内存
        val metaParts = mutableListOf<String>()
        languageLabel(state.info.language)?.let { metaParts.add(it) }
        if (state.info.downloadSizeBytes > 0) {
            metaParts.add(Formatter.formatFileSize(this, state.info.downloadSizeBytes))
        }
        if (state.info.minRamMb > 0) {
            metaParts.add(getString(R.string.model_mem_format, state.info.minRamMb))
        }
        b.tvMeta.text = metaParts.joinToString(" · ")
        b.tvMeta.visibility = if (metaParts.isEmpty()) View.GONE else View.VISIBLE

        // 进度
        val showProgress = state.status in listOf(
            ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING, ModelInstallStatus.PAUSED
        )
        b.progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        if (showProgress) {
            if (state.status == ModelInstallStatus.VERIFYING) {
                b.progress.isIndeterminate = true
            } else {
                b.progress.isIndeterminate = false
                b.progress.progress = state.progressPercent.coerceIn(0, 100)
            }
            b.tvProgress.text = "${state.progressPercent}%"
        } else {
            b.tvProgress.text = ""
        }

        // 失败面板：人话三层 + 下载通道 + 技术详情（折叠）+ 补救按钮
        val failed = state.status == ModelInstallStatus.FAILED
        val errTech = state.error
        if (failed && !errTech.isNullOrBlank()) {
            b.tvErrorBox.visibility = View.VISIBLE
            b.tvHumanError.text = state.errorHuman ?: errTech
            b.tvMirrorLine.text = getString(R.string.mirror_current, ModelMirror.current(this, state.info).label)
            b.tvError.text = errTech
            b.tvError.visibility = View.GONE
            b.btnTechToggle.text = getString(R.string.view_tech_details)
            b.btnTechToggle.setOnClickListener {
                val expanded = b.tvError.visibility == View.VISIBLE
                b.tvError.visibility = if (expanded) View.GONE else View.VISIBLE
                b.btnTechToggle.text = getString(
                    if (expanded) R.string.view_tech_details else R.string.hide_tech_details
                )
            }
            b.btnCopyError.setOnClickListener { copyText(errTech) }
            val mirrorOk = ModelDownloadWorker.supportsMirrorRetry(state.errorClass)
            b.btnMirrorRetry.visibility = if (mirrorOk) View.VISIBLE else View.GONE
            b.btnMirrorRetry.setOnClickListener { switchMirror(state.info) }
        } else {
            b.tvErrorBox.visibility = View.GONE
        }

        // 操作按钮：主操作实心、次级文字，统一右下对齐
        b.actions.removeAllViews()
        when (state.status) {
            ModelInstallStatus.NOT_INSTALLED, ModelInstallStatus.FAILED ->
                addBtn(b.actions, getString(R.string.action_download), primary = true) {
                    ModelManager.download(this, state.info)
                }
            ModelInstallStatus.QUEUED, ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING -> {
                addBtn(b.actions, getString(R.string.action_pause), primary = true) {
                    ModelManager.pause(this, state.info)
                }
                addBtn(b.actions, getString(R.string.action_cancel)) {
                    ModelManager.cancel(this, state.info)
                }
            }
            ModelInstallStatus.PAUSED -> {
                addBtn(b.actions, getString(R.string.action_resume), primary = true) {
                    ModelManager.resume(this, state.info)
                }
                addBtn(b.actions, getString(R.string.action_cancel)) {
                    ModelManager.cancel(this, state.info)
                }
            }
            ModelInstallStatus.INSTALLED -> {
                if (!state.isActive) {
                    addBtn(b.actions, getString(R.string.action_set_active), primary = true) {
                        setActiveWithCheck(state.info)
                    }
                }
                addBtn(b.actions, getString(R.string.action_delete)) {
                    ModelManager.delete(this, state.info)
                }
            }
        }
        return b.root
    }

    /** 换镜像重试：轮换到下一个下载通道并重新入队；下一档是自定义镜像且未配置时先弹窗。 */
    private fun switchMirror(info: LocalModelInfo) {
        val next = ModelMirror.next(this, info)
        if (next.isCustom && ModelMirror.customPrefix(this).isBlank()) {
            showCustomMirrorDialog(info)
            return
        }
        applyMirror(info, next)
    }

    private fun applyMirror(info: LocalModelInfo, mirror: ModelMirror.Mirror) {
        ModelMirror.setSelected(this, info.id, mirror.key)
        ModelManager.download(this, info)
        Toast.makeText(this, getString(R.string.mirror_switched, mirror.label), Toast.LENGTH_SHORT).show()
    }

    /** 自定义镜像前缀弹窗：输入的前缀将拼在 GitHub 链接前。 */
    private fun showCustomMirrorDialog(info: LocalModelInfo) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val et = EditText(this).apply {
            hint = getString(R.string.custom_mirror_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.custom_mirror_dialog_title)
            .setView(ScrollView(this).apply {
                addView(LinearLayout(this@ModelManagerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(pad, pad / 2, pad, 0)
                    addView(et)
                })
            })
            .setPositiveButton(R.string.custom_mirror_set) { _, _ ->
                val prefix = et.text.toString().trim()
                if (prefix.isNotBlank()) {
                    ModelMirror.setCustomPrefix(this, prefix)
                    val custom = ModelMirror.allMirrors(this).firstOrNull { it.isCustom }
                    if (custom != null) {
                        applyMirror(info, custom)
                    } else {
                        Toast.makeText(this, R.string.custom_mirror_dialog_hint, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun copyText(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("model-error-detail", text))
        Toast.makeText(this, R.string.error_detail_copied, Toast.LENGTH_SHORT).show()
    }

    /** 先真正加载一次模型再切换；失败时 Toast 出具体原因而不是静默切换。 */
    private fun setActiveWithCheck(info: LocalModelInfo) {
        lifecycleScope.launch {
            val reason = withContext(Dispatchers.IO) {
                when {
                    !ModelManager.isInstalled(this@ModelManagerActivity, info) ->
                        "模型未完整安装（缺少 installed.marker 或 tokens.txt / .onnx 核心文件），请删除后重新下载"
                    else -> OfflineTtsEngine.validateReason(
                        ModelManager.installedDirectory(this@ModelManagerActivity, info)
                    )?.let { "模型文件无法被 TTS 引擎加载：\n$it" }
                }
            }
            if (reason == null) {
                ModelManager.setActiveModel(this@ModelManagerActivity, info.id)
                // 通知常驻朗读服务热切换音色；服务未运行时命令自然丢弃，
                // 下次启动引擎本就会读新的当前模型。
                TtsEventBus.send(TtsCommand.SetModel(info.id))
                Toast.makeText(this@ModelManagerActivity,
                    "已切换当前模型：${info.name}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@ModelManagerActivity,
                    "切换「${info.name}」失败：\n$reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun addBtn(parent: LinearLayout, label: String, primary: Boolean = false, onClick: () -> Unit) {
        val btn = if (primary) {
            MaterialButton(this, null, 0)
        } else {
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        }.apply {
            text = label
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 16, 0) }
            setOnClickListener { onClick() }
        }
        parent.addView(btn)
    }

    private fun statusLabel(status: ModelInstallStatus): String = when (status) {
        ModelInstallStatus.NOT_INSTALLED -> getString(R.string.model_status_not_installed)
        ModelInstallStatus.QUEUED -> getString(R.string.model_status_queued)
        ModelInstallStatus.DOWNLOADING -> getString(R.string.model_status_downloading)
        ModelInstallStatus.VERIFYING -> getString(R.string.model_status_verifying)
        ModelInstallStatus.PAUSED -> getString(R.string.model_status_paused)
        ModelInstallStatus.INSTALLED -> getString(R.string.model_status_installed)
        ModelInstallStatus.FAILED -> getString(R.string.model_status_failed)
    }

    private fun statusColor(status: ModelInstallStatus): Int = when (status) {
        ModelInstallStatus.INSTALLED -> R.color.chip_installed
        ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING -> R.color.chip_downloading
        ModelInstallStatus.FAILED -> R.color.chip_failed
        ModelInstallStatus.PAUSED -> R.color.chip_paused
        ModelInstallStatus.QUEUED -> R.color.chip_queued
        ModelInstallStatus.NOT_INSTALLED -> R.color.chip_not_installed
    }

    private fun chipDrawable(colorRes: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 8 * resources.displayMetrics.density
            setColor(ContextCompat.getColor(this@ModelManagerActivity, colorRes))
        }

    /** 语言标签 → 中文短名（与筛选器一致）。 */
    private fun languageLabel(tags: String): String? {
        val parts = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val zh = parts.contains("zh")
        val en = parts.contains("en")
        val yue = parts.contains("yue")
        return when {
            zh && en && yue -> "中英粤"
            zh && yue -> "中粤"
            zh && en -> "中英"
            zh -> "中文"
            yue -> "粤语"
            en -> "英文"
            else -> parts.joinToString("/")
        }
    }
}
