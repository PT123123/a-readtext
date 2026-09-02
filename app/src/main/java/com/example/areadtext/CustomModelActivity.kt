package com.example.areadtext

import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.areadtext.databinding.ActivityCustomModelBinding
import com.example.areadtext.model.LocalModelInfo
import com.example.areadtext.model.ModelCatalog
import com.example.areadtext.model.ModelManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * 批量添加自定义模型
 *
 * 直接在文本框里粘贴若干行（每行一个模型），保存后即写入运行时目录
 * （SharedPreferences），无需重新编译。格式支持两种：
 *   1. 名称 | 下载URL
 *   2. 仅下载URL（名称自动取文件名）
 */
class CustomModelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomModelBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomModelBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.navigationIcon =
            ContextCompat.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material)

        binding.btnSave.setOnClickListener { savePasted() }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun savePasted() {
        val text = binding.etPaste.text?.toString().orEmpty()
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        if (lines.isEmpty()) {
            Toast.makeText(this, R.string.paste_empty_error, Toast.LENGTH_SHORT).show()
            return
        }
        var added = 0
        val bad = mutableListOf<String>()
        for (line in lines) {
            val parts = line.split("|", limit = 2)
            val url = (if (parts.size == 2) parts[1] else parts[0]).trim()
            val name = if (parts.size == 2) parts[0].trim() else null
            if (!isValidUrl(url)) {
                bad.add(url.ifBlank { line }.take(60))
                continue
            }
            ModelCatalog.addCustom(this, url, name)
            added++
        }
        binding.etPaste.text = null
        if (bad.isNotEmpty()) {
            Toast.makeText(
                this,
                getString(R.string.paste_partial_error, added, bad.size),
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, getString(R.string.paste_success, added), Toast.LENGTH_SHORT).show()
        }
        refreshList()
    }

    private fun isValidUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun refreshList() {
        val customs = ModelCatalog.custom(this)
        binding.llCustom.removeAllViews()
        binding.tvEmpty.visibility =
            if (customs.isEmpty()) View.VISIBLE else View.GONE
        customs.forEach { binding.llCustom.addView(buildRow(it)) }
    }

    private fun buildRow(info: LocalModelInfo): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(10)) }
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(inner)

        inner.addView(TextView(this).apply {
            text = info.name
            textSize = 15f
            setTextColor(0xFF1A1B1C.toInt())
        })
        inner.addView(TextView(this).apply {
            text = info.archive.url
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setTextColor(0xFF6B7280.toInt())
        })
        if (info.downloadSizeBytes > 0) {
            inner.addView(TextView(this).apply {
                text = "大小: ${Formatter.formatFileSize(this@CustomModelActivity, info.downloadSizeBytes)}"
                textSize = 12f
                setTextColor(0xFF6B7280.toInt())
            })
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        actions.addView(MaterialButton(this).apply {
            text = getString(R.string.action_download)
            setOnClickListener { ModelManager.download(this@CustomModelActivity, info) }
        })
        actions.addView(MaterialButton(this).apply {
            text = getString(R.string.action_delete)
            setOnClickListener {
                ModelManager.delete(this@CustomModelActivity, info)
                ModelCatalog.removeCustom(this@CustomModelActivity, info.id)
                refreshList()
            }
        })
        inner.addView(actions)
        return card
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()
}
