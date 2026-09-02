package com.example.areadtext

import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.areadtext.databinding.ActivityModelManagerBinding
import com.example.areadtext.databinding.ItemModelBinding
import com.example.areadtext.dialog.AddCustomModelDialog
import com.example.areadtext.model.ModelCatalog
import com.example.areadtext.model.ModelInstallStatus
import com.example.areadtext.model.ModelManager
import com.example.areadtext.model.ModelState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 模型管理界面
 * 支持模型下载、暂停、恢复、删除、设置活动模型，以及按语言筛选、按大小排序。
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
        val filtered = lastStates.filter { matchesLanguage(it.info.language, languageFilter) }
        val sorted = when (sortMode) {
            "asc" -> filtered.sortedBy { it.info.downloadSizeBytes }
            "desc" -> filtered.sortedByDescending { it.info.downloadSizeBytes }
            else -> filtered
        }
        binding.container.removeAllViews()
        sorted.forEach { binding.container.addView(buildCard(it)) }
    }

    private fun matchesLanguage(modelLang: String, filter: String): Boolean = when (filter) {
        "all" -> true
        "zh+en" -> modelLang.contains("zh") && modelLang.contains("en")
        else -> modelLang.contains(filter)
    }

    private fun buildCard(state: ModelState): View {
        val b = ItemModelBinding.inflate(layoutInflater, binding.container, false)
        b.tvName.text = state.info.name
        val sizeText = if (state.info.downloadSizeBytes > 0) {
            "大小: ${Formatter.formatFileSize(this, state.info.downloadSizeBytes)}"
        } else {
            null
        }
        b.tvDesc.text = buildString {
            append(state.info.description)
            if (sizeText != null) append("\n").append(sizeText)
        }

        val statusText = when (state.status) {
            ModelInstallStatus.NOT_INSTALLED -> getString(R.string.model_status_not_installed)
            ModelInstallStatus.QUEUED -> getString(R.string.model_status_queued)
            ModelInstallStatus.DOWNLOADING -> getString(R.string.model_status_downloading)
            ModelInstallStatus.VERIFYING -> getString(R.string.model_status_verifying)
            ModelInstallStatus.PAUSED -> getString(R.string.model_status_paused)
            ModelInstallStatus.INSTALLED -> getString(R.string.model_status_installed)
            ModelInstallStatus.FAILED -> getString(R.string.model_status_failed)
        }
        b.tvStatus.text = if (state.isActive) "$statusText · ${getString(R.string.active_model)}" else statusText

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

        if (state.status == ModelInstallStatus.FAILED && !state.error.isNullOrBlank()) {
            b.tvDesc.text = "${b.tvDesc.text}\n错误: ${state.error}"
        }

        b.actions.removeAllViews()
        when (state.status) {
            ModelInstallStatus.NOT_INSTALLED, ModelInstallStatus.FAILED ->
                addBtn(b.actions, getString(R.string.action_download)) {
                    ModelManager.download(this, state.info)
                }
            ModelInstallStatus.QUEUED, ModelInstallStatus.DOWNLOADING, ModelInstallStatus.VERIFYING -> {
                addBtn(b.actions, getString(R.string.action_pause)) {
                    ModelManager.pause(this, state.info)
                }
                addBtn(b.actions, getString(R.string.action_cancel)) {
                    ModelManager.cancel(this, state.info)
                }
            }
            ModelInstallStatus.PAUSED -> {
                addBtn(b.actions, getString(R.string.action_resume)) {
                    ModelManager.resume(this, state.info)
                }
                addBtn(b.actions, getString(R.string.action_cancel)) {
                    ModelManager.cancel(this, state.info)
                }
            }
            ModelInstallStatus.INSTALLED -> {
                if (!state.isActive) {
                    addBtn(b.actions, getString(R.string.action_set_active)) {
                        ModelManager.setActiveModel(this, state.info.id)
                    }
                }
                addBtn(b.actions, getString(R.string.action_delete)) {
                    ModelManager.delete(this, state.info)
                }
            }
        }
        return b.root
    }

    private fun addBtn(parent: LinearLayout, label: String, onClick: () -> Unit) {
        val btn = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 16, 0) }
            setOnClickListener { onClick() }
        }
        parent.addView(btn)
    }
}
