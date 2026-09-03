package com.example.areadtext.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.areadtext.ModelManagerActivity
import com.example.areadtext.R
import com.example.areadtext.data.AppDatabase
import com.example.areadtext.data.ProgressEntity
import com.example.areadtext.databinding.ActivityReaderBinding
import com.example.areadtext.reader.EpubBook
import com.example.areadtext.reader.EpubParser
import com.example.areadtext.reader.ReaderPreferences
import com.example.areadtext.reader.ReadState
import com.example.areadtext.reader.TtsCommand
import com.example.areadtext.reader.TtsEventBus
import com.example.areadtext.service.TtsReadAloudService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读器（legado 风格阅读页）：
 *  - 正文段落流 + 逐句高亮（Lector）；
 *  - 点句跳读：点任意句子从此句开始朗读；
 *  - 底部朗读控制条（上一段/上一句/播放暂停/下一句/下一段 + 语速）；
 *  - 朗读循环常驻前台服务，退出本页继续读（MoRealm）。
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private lateinit var adapter: ParagraphAdapter

    private var book: EpubBook? = null
    private var bookId: String = ""
    private var highlightJob: Job? = null

    private var loadedChapter = -1
    private var lastHighlightParagraph = -1
    private var lastHighlightSentence = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        // 服务已在朗读其它书时也能正确切换；通知栏点击进入则用状态里的 bookId
        val fallbackId = TtsEventBus.snapshot().bookId
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: fallbackId
        if (bookId.isBlank()) { finish(); return }
        val openChapter = intent.getIntExtra(EXTRA_CHAPTER, 0)
        val openParagraph = intent.getIntExtra(EXTRA_PARAGRAPH, 0)
        val openSentence = intent.getIntExtra(EXTRA_SENTENCE, 0)

        adapter = ParagraphAdapter(onSentenceTap = { pi, si ->
            if (TtsEventBus.snapshot().isLoaded) {
                TtsEventBus.send(TtsCommand.JumpTo(pi, si))
            }
        })
        binding.readerList.layoutManager = LinearLayoutManager(this)
        binding.readerList.adapter = adapter

        setupTtsBar()

        // 确保前台朗读服务在跑（朗读循环常驻）
        TtsReadAloudService.start(this)

        lifecycleScope.launch {
            val b = withContext(Dispatchers.IO) { EpubParser.loadCache(this@ReaderActivity, bookId) }
            if (b == null) {
                Toast.makeText(this@ReaderActivity, R.string.book_load_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            book = b
            binding.toolbarTitle.text = b.title
            // 仅当服务里不是这本书时才重新 LoadBook（避免打断正在进行的朗读/复位进度）
            if (TtsEventBus.snapshot().bookId != bookId) {
                TtsEventBus.send(
                    TtsCommand.LoadBook(bookId, openChapter, openParagraph, openSentence)
                )
            }
        }

        observeState()
    }

    override fun onResume() {
        super.onResume()
        applyTheme()
    }

    override fun onPause() {
        super.onPause()
        persistProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        highlightJob?.cancel()
        persistProgress()
        super.onDestroy()
    }

    // ── 状态订阅 ────────────────────────────────────────────────────────────

    private fun observeState() {
        highlightJob = lifecycleScope.launch {
            TtsEventBus.state.collect { state ->
                updateToolbar(state)
                updateTtsBar(state)
                updateHighlight(state)
            }
        }
    }

    private fun updateToolbar(state: ReadState) {
        if (state.chapterIndex != loadedChapter && state.totalChapters > 0) {
            loadedChapter = state.chapterIndex
            val ch = book?.chapters?.getOrNull(state.chapterIndex)
            if (ch != null && adapter.itemCount != ch.paragraphs.size) {
                adapter.submit(ch.paragraphs)
            }
        }
        binding.chapterTitle.text = state.chapterTitle.ifBlank { "" }
        binding.chapterCounter.text = if (state.totalChapters > 0) {
            "${state.chapterIndex + 1}/${state.totalChapters}"
        } else ""
    }

    private fun updateHighlight(state: ReadState) {
        val p = state.paragraphIndex
        val s = state.sentenceIndex
        if (p == lastHighlightParagraph && s == lastHighlightSentence) return
        // 只刷新受影响的旧/新两项（不整体重建，避免大章节卡顿）
        val prevP = lastHighlightParagraph
        lastHighlightParagraph = p
        lastHighlightSentence = s
        if (prevP in 0 until adapter.itemCount) adapter.notifyItemChanged(prevP)
        if (p in 0 until adapter.itemCount) adapter.notifyItemChanged(p)
        if (state.isPlaying && p in 0 until adapter.itemCount) {
            (binding.readerList.layoutManager as LinearLayoutManager)
                .smoothScrollToPosition(binding.readerList, null, p)
        }
    }

    private fun updateTtsBar(state: ReadState) {
        binding.btnPlayPause.setImageResource(
            if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlayPause.contentDescription =
            getString(if (state.isPlaying) R.string.pause else R.string.play)
        binding.speedLabel.text = "×${formatSpeed(state.speed)}"
        // 逐句进度（FolioReader 式音频时间 → 句内进度条）
        val total = state.sentenceAudioTotalMs
        if (total > 0) {
            binding.sentenceProgress.isVisible = true
            binding.sentenceProgress.max = 100
            binding.sentenceProgress.progress =
                (state.sentenceAudioMs * 100 / total).toInt().coerceIn(0, 100)
        } else {
            binding.sentenceProgress.progress = 0
        }
        binding.readingError.text = state.error
        binding.readingError.isVisible = state.error != null
    }

    private fun formatSpeed(s: Float): String =
        if (s % 1f == 0f) s.toInt().toString() else String.format("%.2f", s).trimEnd('0').trimEnd('.')

    // ── 朗读控制条 ──────────────────────────────────────────────────────────

    private fun setupTtsBar() {
        binding.btnPrevParagraph.setOnClickListener { TtsEventBus.send(TtsCommand.PrevParagraph) }
        binding.btnPrevSentence.setOnClickListener { TtsEventBus.send(TtsCommand.PrevSentence) }
        binding.btnPlayPause.setOnClickListener {
            val state = TtsEventBus.snapshot()
            if (!state.isLoaded) {
                Toast.makeText(this, R.string.book_not_ready, Toast.LENGTH_SHORT).show()
            } else if (state.isPlaying) {
                TtsEventBus.send(TtsCommand.Pause)
            } else {
                TtsEventBus.send(TtsCommand.Play)
            }
        }
        binding.btnNextSentence.setOnClickListener { TtsEventBus.send(TtsCommand.NextSentence) }
        binding.btnNextParagraph.setOnClickListener { TtsEventBus.send(TtsCommand.NextParagraph) }

        binding.btnSpeed.setOnClickListener { showSpeedMenu() }
        binding.btnSpeed.setOnLongClickListener {
            val state = TtsEventBus.snapshot()
            if (state.isLoaded && state.modelName.isNotBlank()) {
                Toast.makeText(this, state.modelName, Toast.LENGTH_SHORT).show()
            }
            true
        }
    }

    private fun showSpeedMenu() {
        val menu = PopupMenu(this, binding.btnSpeed)
        val speeds = floatArrayOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        speeds.forEachIndexed { i, s ->
            menu.menu.add(0, i, i, "×${formatSpeed(s)}")
        }
        menu.setOnMenuItemClickListener { item ->
            val s = speeds[item.itemId]
            ReaderPreferences.setSpeed(this, s)
            TtsEventBus.send(TtsCommand.SetSpeed(s))
            true
        }
        menu.show()
    }

    // ── 阅读设置 ────────────────────────────────────────────────────────────

    private fun applyTheme() {
        val font = ReaderPreferences.fontSp(this)
        val line = ReaderPreferences.lineSpacing(this)
        val style = when (ReaderPreferences.theme(this)) {
            ReaderPreferences.THEME_SEPIA -> ReaderStyle.sepia(font, line)
            ReaderPreferences.THEME_NIGHT -> ReaderStyle.night(font, line)
            else -> ReaderStyle.paper(font, line)
        }
        adapter.style = style
        adapter.notifyDataSetChanged()
        binding.root.setBackgroundColor(style.bgColor)
        binding.readingError.setTextColor(style.textColor)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> { onBackPressedDispatcher.onBackPressed(); true }
            R.id.action_prev_chapter -> { TtsEventBus.send(TtsCommand.PrevChapter); true }
            R.id.action_next_chapter -> { TtsEventBus.send(TtsCommand.NextChapter); true }
            R.id.action_settings -> { showReadingSettings(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showReadingSettings() {
        val menu = PopupMenu(this, binding.toolbar)
        menu.menu.apply {
            add(0, 1, 0, getString(R.string.font_inc))
            add(0, 2, 0, getString(R.string.font_dec))
            add(0, 3, 0, getString(R.string.theme_paper))
            add(0, 4, 0, getString(R.string.theme_sepia))
            add(0, 5, 0, getString(R.string.theme_night))
            add(0, 6, 0, getString(R.string.model_manager_short))
        }
        menu.setOnMenuItemClickListener { it ->
            when (it.itemId) {
                1 -> { ReaderPreferences.setFontSp(this, ReaderPreferences.fontSp(this) + 1f); applyTheme(); true }
                2 -> { ReaderPreferences.setFontSp(this, ReaderPreferences.fontSp(this) - 1f); applyTheme(); true }
                3 -> { ReaderPreferences.setTheme(this, ReaderPreferences.THEME_PAPER); applyTheme(); true }
                4 -> { ReaderPreferences.setTheme(this, ReaderPreferences.THEME_SEPIA); applyTheme(); true }
                5 -> { ReaderPreferences.setTheme(this, ReaderPreferences.THEME_NIGHT); applyTheme(); true }
                6 -> {
                    startActivity(Intent(this, ModelManagerActivity::class.java))
                    true
                }
                else -> false
            }
        }
        menu.show()
    }

    // ── 进度持久化 ──────────────────────────────────────────────────────────

    private fun persistProgress() {
        val state = TtsEventBus.snapshot()
        if (!state.isLoaded || bookId.isBlank()) return
        lifecycleScope.launch {
            AppDatabase.get(this@ReaderActivity).bookDao().upsertProgress(
                ProgressEntity(
                    bookId = bookId,
                    chapterIndex = state.chapterIndex,
                    paragraphIndex = state.paragraphIndex,
                    sentenceIndex = state.sentenceIndex,
                )
            )
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_CHAPTER = "chapter"
        const val EXTRA_PARAGRAPH = "paragraph"
        const val EXTRA_SENTENCE = "sentence"

        fun open(context: android.content.Context, bookId: String, progress: com.example.areadtext.data.ProgressEntity? = null) {
            val intent = Intent(context, ReaderActivity::class.java).apply {
                putExtra(EXTRA_BOOK_ID, bookId)
                if (progress != null) {
                    putExtra(EXTRA_CHAPTER, progress.chapterIndex)
                    putExtra(EXTRA_PARAGRAPH, progress.paragraphIndex)
                    putExtra(EXTRA_SENTENCE, progress.sentenceIndex)
                }
            }
            if (context !is AppCompatActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
