package com.example.areadtext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.areadtext.data.TranscriptEntity
import com.example.areadtext.data.TranscriptRepository
import com.example.areadtext.databinding.ItemHistoryBinding
import com.example.areadtext.model.ModelCatalog
import com.example.areadtext.model.ModelManager
import com.example.areadtext.utils.FileTranscriber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 非实时转写（历史记录）列表控制器：加载 showInMain=false 的导入 / 离线转写记录，
 * 支持点击查看详情、复制、转写、分享、播放、删除。
 * 主窗口「非实时转写」Tab 与独立的「历史记录」页共用同一套逻辑。
 */
class HistoryListController(
    private val activity: AppCompatActivity,
    private val recycler: RecyclerView,
    private val emptyView: View,
    private val repo: TranscriptRepository
) {
    private val adapter = HistoryListAdapter { showDetails(it) }
    private var transcribingId = -1L

    fun start() {
        recycler.layoutManager = LinearLayoutManager(activity)
        recycler.adapter = adapter
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.history.collectLatest { list ->
                    adapter.submitList(list)
                    emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /**
     * 尚未执行过转写的记录：文本为空、有音频文件、且模型名仍为待转写标记。
     * 转写完成（即使结果为空）后模型名会变成实际模型名，不再视为 pending，
     * 避免「待转写」无限循环。
     */
    private fun isPending(item: TranscriptEntity): Boolean =
        item.text.isBlank() && !item.wavPath.isNullOrBlank() &&
            (item.modelName.isBlank() || item.modelName == activity.getString(R.string.pending_transcribe))

    private fun showDetails(item: TranscriptEntity) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
        val dur = DateUtils.formatElapsedTime(item.durationMs / 1000)
        val modelDir = ModelManager.getActiveModelDirectory(activity)
        val modelReady = modelDir != null
        val pending = isPending(item)
        // 模型就绪时，点击「待转写」记录即自动开始转写；详情弹窗同时保留，便于播放/删除等。
        val willAutoTranscribe = pending && modelReady && transcribingId != item.id
        val isTranscribing = transcribingId == item.id
        val actions = buildList {
            add(activity.getString(R.string.copy))
            if (pending && modelReady) add(activity.getString(R.string.action_transcribe))
            add(activity.getString(R.string.share))
            add(activity.getString(R.string.play))
            add(activity.getString(R.string.delete))
        }
        val message = when {
            item.text.isNotBlank() -> item.text
            pending && !modelReady -> activity.getString(R.string.history_pending_no_model)
            pending && (isTranscribing || willAutoTranscribe) -> activity.getString(R.string.history_pending_transcribing)
            pending -> activity.getString(R.string.history_pending_ready)
            item.wavPath.isNullOrBlank() -> "[空]"
            else -> activity.getString(R.string.history_pending_empty)
        }
        AlertDialog.Builder(activity)
            .setTitle("$time · $dur")
            .setMessage(message)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    activity.getString(R.string.copy) -> {
                        activity.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("transcript", item.text))
                        Toast.makeText(activity, activity.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                    }
                    activity.getString(R.string.action_transcribe) -> modelDir?.let { transcribe(item, it) }
                    activity.getString(R.string.share) -> activity.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, item.text)
                        }, activity.getString(R.string.share)))
                    activity.getString(R.string.play) -> play(item)
                    activity.getString(R.string.delete) -> activity.lifecycleScope.launch { repo.delete(item) }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // 模型就绪时点击待转写记录：直接开始转写，完成后列表自动更新
        if (willAutoTranscribe) modelDir?.let { transcribe(item, it) }
    }

    /** 重新转写一条待转写记录（无模型时的录音 / 导入）。 */
    private fun transcribe(item: TranscriptEntity, modelDir: File) {
        if (transcribingId == item.id) return // 已在转写中，避免重复
        val path = item.wavPath ?: run {
            Toast.makeText(activity, "无音频文件", Toast.LENGTH_SHORT).show(); return
        }
        transcribingId = item.id
        Toast.makeText(activity, activity.getString(R.string.transcribing_status), Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileTranscriber.transcribe(activity, modelDir, path)
            }
            transcribingId = -1L
            if (result == null) {
                Toast.makeText(activity, activity.getString(R.string.transcribe_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val modelName = ModelManager.getActiveModelId(activity)
                ?.let { ModelCatalog.findById(activity, it)?.name } ?: "sherpa-onnx"
            repo.update(item.copy(text = result.text, durationMs = result.durationMs, modelName = modelName))
            Toast.makeText(activity, activity.getString(R.string.transcribe_success), Toast.LENGTH_SHORT).show()
        }
    }

    private fun play(item: TranscriptEntity) {
        val path = item.wavPath ?: run {
            Toast.makeText(activity, "无音频文件", Toast.LENGTH_SHORT).show(); return
        }
        try {
            MediaPlayer().apply {
                setDataSource(path); setOnCompletionListener { it.release() }
                setOnErrorListener { mp, _, _ -> mp.release(); true }
                prepare(); start()
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "播放失败", Toast.LENGTH_SHORT).show()
        }
    }

    private class HistoryListAdapter(
        private val onClick: (TranscriptEntity) -> Unit
    ) : ListAdapter<TranscriptEntity, HistoryListAdapter.VH>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.bind(item)
            holder.itemView.setOnClickListener { onClick(item) }
        }

        class VH(private val b: ItemHistoryBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(item: TranscriptEntity) {
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
                val dur = DateUtils.formatElapsedTime(item.durationMs / 1000)
                b.tvTitle.text = "$time · $dur · ${item.modelName}"
                b.tvSnippet.text = when {
                    item.text.isNotBlank() -> item.text
                    !item.wavPath.isNullOrBlank() &&
                        (item.modelName.isBlank() ||
                            item.modelName == b.root.context.getString(R.string.pending_transcribe)) ->
                        b.root.context.getString(R.string.pending_transcribe)
                    !item.wavPath.isNullOrBlank() -> b.root.context.getString(R.string.transcribe_empty)
                    else -> "[空]"
                }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<TranscriptEntity>() {
                override fun areItemsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a.id == b.id
                override fun areContentsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a == b
            }
        }
    }
}
