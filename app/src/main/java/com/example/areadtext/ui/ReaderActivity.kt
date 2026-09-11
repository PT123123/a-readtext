package com.example.areadtext.ui

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.example.areadtext.reader.ThemeCatalog
import com.example.areadtext.reader.book.Book
import com.example.areadtext.reader.book.BookCache
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

    private var book: Book? = null
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
            val b = withContext(Dispatchers.IO) { BookCache.load(this@ReaderActivity, bookId) }
            if (b == null) {
                Toast.makeText(this@ReaderActivity, R.string.book_load_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            book = b
            binding.toolbarTitle.text = b.title
            // 修复竞态：TtsEventBus 状态可能先于 book 加载完成到达，此时 updateToolbar()
            // 已把 loadedChapter 设为当前章节但 book 为 null，adapter 仍为空。
            // book 加载完后主动提交当前章节段落，确保正文总能渲染。
            val snap = TtsEventBus.snapshot()
            if (snap.totalChapters > 0 && snap.bookId == bookId) {
                val ch = b.chapters.getOrNull(snap.chapterIndex)
                if (ch != null) {
                    loadedChapter = snap.chapterIndex
                    adapter.submit(ch.paragraphs)
                }
            }
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
            if (state.isPlaying) R.drawable.ic_pause_white else R.drawable.ic_play_white
        )
        binding.btnPlayPause.contentDescription =
            getString(if (state.isPlaying) R.string.pause else R.string.play)
        binding.speedLabel.text = "×${formatSpeed(state.speed)}"
        // 当前句音频时间（NaturalReader 式 mm:ss 进度）
        binding.timeLabel.text = if (state.sentenceAudioTotalMs > 0) {
            "${formatTime(state.sentenceAudioMs)} / ${formatTime(state.sentenceAudioTotalMs)}"
        } else {
            ""
        }
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

    private fun formatTime(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return if (m > 0) "$m:${s.toString().padStart(2, '0')}" else "0:$s"
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
        val themeId = ReaderPreferences.themeId(this)
        val theme = ThemeCatalog.byId(themeId)
        val style = ReaderStyle.fromTheme(theme, font, line)
        adapter.style = style
        adapter.notifyDataSetChanged()
        binding.root.setBackgroundColor(style.bgColor)
        binding.readingError.setTextColor(style.textColor)
        // toolbar 文字跟随主题（surface 上的对比色）
        binding.toolbarTitle.setTextColor(theme.onSurfaceColor)
        binding.chapterTitle.setTextColor(theme.secondaryTextColor)
        binding.chapterCounter.setTextColor(theme.secondaryTextColor)
        binding.toolbar.setTitleTextColor(theme.onSurfaceColor)
        // toolbar/TTS bar 跟随主题
        binding.toolbar.setBackgroundColor(theme.surfaceColor)
        binding.ttsBar.setBackgroundColor(theme.surfaceColor)
        // ttsBar 内文字颜色
        binding.speedLabel.setTextColor(theme.onSurfaceColor)
        binding.timeLabel.setTextColor(theme.secondaryTextColor)
        // 状态栏跟随阅读主题（T2S 式沉浸）
        window.statusBarColor = theme.surfaceColor
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
        // 底部设置弹窗：主题色块 + 字号/行距滑块
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_reading_settings, null)
        sheet.setContentView(view)

        // 字号滑块
        val sliderFont = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderFont)
        val labelFont = view.findViewById<android.widget.TextView>(R.id.labelFont)
        if (sliderFont != null) {
            sliderFont.value = ReaderPreferences.fontSp(this)
            sliderFont.valueFrom = 12f
            sliderFont.valueTo = 32f
            sliderFont.stepSize = 1f
            labelFont?.text = getString(R.string.font_size_format, sliderFont.value.toInt())
            sliderFont.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    ReaderPreferences.setFontSp(this, value)
                    labelFont?.text = getString(R.string.font_size_format, value.toInt())
                    applyTheme()
                }
            }
        }

        // 行距滑块
        val sliderLine = view.findViewById<com.google.android.material.slider.Slider>(R.id.sliderLine)
        val labelLine = view.findViewById<android.widget.TextView>(R.id.labelLine)
        if (sliderLine != null) {
            sliderLine.value = ReaderPreferences.lineSpacing(this)
            sliderLine.valueFrom = 1.0f
            sliderLine.valueTo = 2.2f
            sliderLine.stepSize = 0.1f
            labelLine?.text = getString(R.string.line_spacing_format, sliderLine.value)
            sliderLine.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    ReaderPreferences.setLineSpacing(this, value)
                    labelLine?.text = getString(R.string.line_spacing_format, value)
                    applyTheme()
                }
            }
        }

        // 主题色块网格
        val themeGrid = view.findViewById<android.widget.LinearLayout>(R.id.themeGrid)
        if (themeGrid != null) {
            val currentThemeId = ReaderPreferences.themeId(this)
            themeGrid.removeAllViews()
            // 每行 4 个
            val themes = ThemeCatalog.themes
            val rowSize = 4
            var rowLayout: android.widget.LinearLayout? = null
            for ((index, theme) in themes.withIndex()) {
                if (index % rowSize == 0) {
                    rowLayout = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(8) }
                    }
                    themeGrid.addView(rowLayout)
                }
                val swatch = createThemeSwatch(theme, theme.id == currentThemeId)
                swatch.setOnClickListener {
                    ReaderPreferences.setTheme(this, theme)
                    applyTheme()
                    sheet.dismiss()
                }
                rowLayout?.addView(swatch)
            }
        }

        // 模型管理按钮
        view.findViewById<android.widget.Button>(R.id.btnModelManager)?.setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
            sheet.dismiss()
        }

        sheet.show()
    }

    /** 创建主题色块 View。 */
    private fun createThemeSwatch(theme: com.example.areadtext.reader.ReaderTheme, selected: Boolean): View {
        val size = dp(44)
        val container = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.MarginLayoutParams(size, size).apply {
                marginEnd = dp(8)
            }
        }

        val circle = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(theme.swatchColor)
                setStroke(dp(2), if (selected) theme.accentColor else 0x33000000)
            }
            layoutParams = android.widget.FrameLayout.LayoutParams(size, size)
        }
        container.addView(circle)

        if (selected) {
            val check = android.widget.TextView(this).apply {
                text = "✓"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(if (theme.isDark) 0xFFFFFFFF.toInt() else theme.textColor)
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            container.addView(check)
        }
        return container
    }

    /** dp 转 px。 */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

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
