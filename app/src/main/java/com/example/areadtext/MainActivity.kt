package com.example.areadtext

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.areadtext.data.AppDatabase
import com.example.areadtext.data.TranscriptEntity
import com.example.areadtext.data.TranscriptRepository
import com.example.areadtext.databinding.ActivityMainBinding
import com.example.areadtext.databinding.ItemMainRecordingBinding
import com.example.areadtext.model.ModelCatalog
import com.example.areadtext.model.ModelManager
import com.example.areadtext.utils.AudioFileDecoder
import com.example.areadtext.utils.FileTranscriber
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var recording = false
    private var paused = false
    private var processing = false
    private val repo by lazy {
        TranscriptRepository(AppDatabase.get(this).transcriptDao())
    }

    private val recordAdapter = MainRecordingsAdapter(
        onPlay = { togglePlay(it) },
        onSeek = { item, pos -> seekTo(item, pos) },
        onTranscribe = { transcribe(it) },
        onCopy = { copyText(it.text) }
    )

    /** 主窗口「非实时转写」Tab 的历史列表控制器。 */
    private lateinit var historyController: HistoryListController

    // ---- 录音播放 ----
    private var player: MediaPlayer? = null
    private var playingId = -1L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            val vh = recordAdapter.activeVh
            if (vh != null) {
                val pos = if (p.isPlaying) p.currentPosition else vh.lastPos
                if (!vh.binding.sbProgress.isPressed) {
                    vh.binding.sbProgress.progress = pos
                    vh.binding.tvItemPos.text = formatMs(pos.toLong())
                }
                vh.lastPos = pos
            }
            // Keep ticking while a player is alive, even if the playing item is scrolled off.
            mainHandler.postDelayed(this, 200L)
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importAudio(it) } }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) startRecording() else
            Toast.makeText(this, "需要麦克风/通知权限", Toast.LENGTH_SHORT).show()
    }

    private val partialReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            binding.tvTranscript.text = i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: ""
        }
    }
    private val finalReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val text = i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: ""
            val wav = i.getStringExtra(TranscriptionService.EXTRA_WAV)
            val dur = i.getLongExtra(TranscriptionService.EXTRA_DURATION, 0L)
            Log.d("MainActivity", "FINAL received text=$text wav=$wav dur=$dur")
            binding.tvTranscript.text = text
            updateUI()
            // Recordings land in the main-window list (showInMain=true), not in history.
            if (text.isNotBlank() || !wav.isNullOrBlank()) saveRecording(text, wav, dur)
        }
    }
    private val errorReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            Toast.makeText(c, i.getStringExtra(TranscriptionService.EXTRA_TEXT) ?: "错误",
                Toast.LENGTH_LONG).show()
            updateUI()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_models -> { startActivity(Intent(this, ModelManagerActivity::class.java)); true }
                R.id.action_import -> { if (!processing) importLauncher.launch("audio/*"); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }

        binding.btnRecord.setOnClickListener {
            if (recording) stopRecording() else checkPermissionsAndRecord()
        }
        binding.btnPause.setOnClickListener {
            if (!recording) return@setOnClickListener
            if (paused) {
                startService(Intent(this, TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_RESUME))
                paused = false
            } else {
                startService(Intent(this, TranscriptionService::class.java)
                    .setAction(TranscriptionService.ACTION_PAUSE))
                paused = true
            }
            updateUI()
        }

        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = recordAdapter

        // 实时转写 / 非实时转写 两个 Tab 切换显示
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val realtime = tab.position == 0
                binding.llRealtime.visibility = if (realtime) View.VISIBLE else View.GONE
                binding.llNonRealtime.visibility = if (realtime) View.GONE else View.VISIBLE
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // 非实时转写 Tab：复用历史记录列表逻辑（导入 / 离线转写）
        historyController = HistoryListController(this, binding.rvHistory, binding.tvHistoryEmpty, repo)
        historyController.start()

        // 实时转写内容一键复制
        binding.btnCopyTranscript.setOnClickListener {
            copyText(binding.tvTranscript.text?.toString().orEmpty())
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.main.collectLatest { list ->
                    recordAdapter.submitList(list)
                    binding.tvRecordingsLabel.visibility =
                        if (list.isEmpty()) View.GONE else View.VISIBLE
                    binding.tvRecordingsEmpty.visibility =
                        if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }

        updateUI()
    }

    override fun onStart() {
        super.onStart()
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(partialReceiver, IntentFilter(TranscriptionService.ACTION_PARTIAL), flags)
        registerReceiver(finalReceiver, IntentFilter(TranscriptionService.ACTION_FINAL), flags)
        registerReceiver(errorReceiver, IntentFilter(TranscriptionService.ACTION_ERROR), flags)
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(partialReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(finalReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(errorReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        refreshModelStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    private fun refreshModelStatus() {
        val ready = ModelManager.getActiveModelDirectory(this) != null
        if (!ready) {
            binding.tvTranscript.hint = getString(R.string.hint_no_model)
        } else if (!recording) {
            binding.tvTranscript.hint = getString(R.string.hint_ready)
        }
        updateUI()
    }

    private fun checkPermissionsAndRecord() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        ).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRecording() else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startRecording() {
        startForegroundService(Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_START))
        recording = true; paused = false
        updateUI()
    }

    private fun stopRecording() {
        startService(Intent(this, TranscriptionService::class.java)
            .setAction(TranscriptionService.ACTION_STOP))
        recording = false; paused = false
        updateUI()
    }

    private fun updateUI() {
        binding.btnRecord.isEnabled = !processing
        if (recording) {
            binding.btnRecord.text = getString(R.string.stop_recording)
            binding.btnRecord.setBackgroundColor(getColor(R.color.rec))
            binding.btnPause.visibility = android.view.View.VISIBLE
            binding.btnPause.text = if (paused) getString(R.string.resume_recording) else getString(R.string.pause_recording)
            binding.tvStatus.text = if (paused) getString(R.string.paused_status) else getString(R.string.recording_status)
        } else {
            binding.btnRecord.text = getString(R.string.start_recording)
            binding.btnRecord.setBackgroundColor(getColor(R.color.seed))
            binding.btnPause.visibility = android.view.View.GONE
            binding.tvStatus.text = getString(R.string.idle_status)
        }
    }

    /** Copies the given text to the system clipboard, with empty guard. */
    private fun copyText(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, R.string.nothing_to_copy, Toast.LENGTH_SHORT).show()
            return
        }
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("transcript", text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    /** A finished recording goes to the main-window list, not to history. */
    private fun saveRecording(text: String, wav: String?, durationMs: Long) {
        val id = ModelManager.getActiveModelId(this)
        val modelName = id?.let { ModelCatalog.findById(this, it)?.name }
            ?: getString(R.string.pending_transcribe)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val rowId = repo.insert(text, wav, durationMs, modelName, showInMain = true)
                Log.d("MainActivity", "inserted rowId=$rowId text='$text'")
            } catch (e: Exception) {
                Log.e("MainActivity", "insert failed", e)
            }
        }
    }

    // ================= 播放 / 进度条 =================

    private fun togglePlay(item: TranscriptEntity) {
        val path = item.wavPath
        if (path.isNullOrBlank()) {
            Toast.makeText(this, "无音频文件", Toast.LENGTH_SHORT).show()
            return
        }
        if (playingId == item.id) {
            val p = player
            if (p?.isPlaying == true) {
                p.pause()
            } else {
                p?.start()
                mainHandler.postDelayed(progressTick, 0L)
            }
            return
        }
        stopPlayback()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnPreparedListener { prepared ->
                prepared.start()
                playingId = item.id
                recordAdapter.playingId = item.id
                mainHandler.postDelayed(progressTick, 0L)
            }
            mp.setOnCompletionListener { stopPlayback() }
            mp.setOnErrorListener { _, _, _ -> stopPlayback(); true }
            mp.prepareAsync()
            player = mp
        } catch (e: Exception) {
            Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        mainHandler.removeCallbacks(progressTick)
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        if (playingId != -1L) {
            playingId = -1L
            recordAdapter.playingId = -1L
        }
    }

    private fun seekTo(item: TranscriptEntity, progressMs: Int) {
        if (playingId == item.id) {
            try { player?.seekTo(progressMs) } catch (_: Exception) {}
        }
    }

    /** Re-transcribes a pending main-window recording once a model is available. */
    private fun transcribe(item: TranscriptEntity) {
        val path = item.wavPath ?: run {
            Toast.makeText(this, "无音频文件", Toast.LENGTH_SHORT).show(); return
        }
        val modelDir = ModelManager.getActiveModelDirectory(this) ?: run {
            Toast.makeText(this, getString(R.string.transcribe_no_model), Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(this, getString(R.string.transcribing_status), Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                FileTranscriber.transcribe(this@MainActivity, modelDir, path)
            }
            if (result == null) {
                Toast.makeText(this@MainActivity, getString(R.string.transcribe_failed), Toast.LENGTH_SHORT).show()
                return@launch
            }
            val modelName = ModelManager.getActiveModelId(this@MainActivity)
                ?.let { ModelCatalog.findById(this@MainActivity, it)?.name } ?: "sherpa-onnx"
            repo.update(item.copy(text = result.text, durationMs = result.durationMs, modelName = modelName))
            Toast.makeText(this@MainActivity, getString(R.string.transcribe_success), Toast.LENGTH_SHORT).show()
        }
    }

    /** Copies a picked audio file into app storage and transcribes it if a model is ready. */
    private fun importAudio(uri: Uri) {
        if (processing) return
        processing = true
        binding.tvStatus.text = getString(R.string.importing_status)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val originalName = queryDisplayName(uri) ?: "import_$ts"
                val ext = originalName.substringAfterLast('.', "").ifBlank { "m4a" }
                val dir = File(filesDir, "recordings").apply { if (!exists()) mkdirs() }
                val dest = File(dir, "import_$ts.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IOException("无法读取所选文件")

                val modelDir = ModelManager.getActiveModelDirectory(this@MainActivity)
                var text = ""
                var durationMs = 0L
                var modelName = getString(R.string.pending_transcribe)
                if (modelDir != null) {
                    val r = FileTranscriber.transcribe(this@MainActivity, modelDir, dest.absolutePath)
                    if (r != null) {
                        text = r.text
                        durationMs = r.durationMs
                        modelName = ModelManager.getActiveModelId(this@MainActivity)
                            ?.let { ModelCatalog.findById(this@MainActivity, it)?.name } ?: "sherpa-onnx"
                    }
                } else {
                    // No model: still record duration so the pending entry is informative.
                    durationMs = AudioFileDecoder
                        .decodeToPcm16kMono(this@MainActivity, dest.absolutePath)
                        ?.size?.div(16)?.toLong() ?: 0L
                }
                // Imports stay in history (showInMain = false).
                repo.insert(text, dest.absolutePath, durationMs, modelName)
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        if (text.isNotBlank()) getString(R.string.import_success)
                        else getString(R.string.import_pending),
                        Toast.LENGTH_LONG
                    ).show()
                    processing = false
                    updateUI()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.import_failed),
                        Toast.LENGTH_LONG
                    ).show()
                    processing = false
                    updateUI()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    // ================= 录音列表适配器 =================

    private class MainRecordingsAdapter(
        private val onPlay: (TranscriptEntity) -> Unit,
        private val onSeek: (TranscriptEntity, Int) -> Unit,
        private val onTranscribe: (TranscriptEntity) -> Unit,
        private val onCopy: (TranscriptEntity) -> Unit
    ) : ListAdapter<TranscriptEntity, MainRecordingsAdapter.VH>(DIFF) {

        /** Currently playing item id; -1 when nothing plays. */
        var playingId: Long = -1L
            set(value) {
                if (field == value) return
                field = value
                if (value == -1L) activeVh = null
                notifyDataSetChanged()
            }

        /** ViewHolder of the item currently being played (updated by MainActivity tick). */
        var activeVh: VH? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemMainRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            holder.item = item
            val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(item.createdAt))
            holder.binding.tvItemTitle.text = "$time · ${item.modelName}"
            holder.binding.tvItemTotal.text = formatMs(item.durationMs)
            holder.binding.tvItemPos.text = formatMs(0L)
            holder.binding.tvItemText.text = when {
                item.text.isNotBlank() -> item.text
                !item.wavPath.isNullOrBlank() ->
                    holder.binding.root.context.getString(R.string.pending_transcribe)
                else -> "[空]"
            }
            holder.binding.sbProgress.max = item.durationMs.coerceAtLeast(1).toInt()
            holder.binding.sbProgress.progress = 0
            holder.lastPos = 0

            val isPlaying = item.id == playingId
            holder.binding.btnPlay.text = holder.binding.root.context.getString(
                if (isPlaying) R.string.stop_playback else R.string.play
            )
            // Keep activeVh pointing only at the currently-playing holder; clear it when a
            // non-playing item binds into a recycled holder so progress never leaks elsewhere.
            if (isPlaying) activeVh = holder else if (activeVh === holder) activeVh = null

            // Pending (no text but has audio) entries get a one-tap transcribe when a model is ready.
            val pending = item.text.isBlank() && !item.wavPath.isNullOrBlank()
            holder.binding.btnTranscribe.visibility =
                if (pending && ModelManager.getActiveModelDirectory(holder.binding.root.context) != null)
                    View.VISIBLE else View.GONE

            holder.binding.btnPlay.setOnClickListener { onPlay(item) }
            holder.binding.btnTranscribe.setOnClickListener { onTranscribe(item) }
            holder.binding.btnCopy.setOnClickListener { onCopy(item) }
            holder.binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        holder.lastPos = progress
                        holder.binding.tvItemPos.text = formatMs(progress.toLong())
                    }
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val pos = sb?.progress ?: 0
                    holder.lastPos = pos
                    holder.binding.tvItemPos.text = formatMs(pos.toLong())
                    onSeek(item, pos)
                }
            })
        }

        class VH(val binding: ItemMainRecordingBinding) : RecyclerView.ViewHolder(binding.root) {
            var item: TranscriptEntity? = null
            var lastPos: Int = 0
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<TranscriptEntity>() {
                override fun areItemsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a.id == b.id
                override fun areContentsTheSame(a: TranscriptEntity, b: TranscriptEntity) = a == b
            }
        }
    }
}

/** mm:ss（或 h:mm:ss）时长格式化。 */
private fun formatMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%02d:%02d", m, s)
}
