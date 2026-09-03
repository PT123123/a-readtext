package com.example.areadtext.reader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.example.areadtext.model.ModelCatalog
import com.example.areadtext.model.ModelManager
import com.example.areadtext.reader.book.BookCache
import com.example.areadtext.reader.book.Chapter
import com.example.areadtext.reader.book.Book
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext

/**
 * 朗读调度主机 —— 三套参考架构在 a-readtext 里的落点：
 *
 *  - **MoRealm 段落级调度**：单一事实来源（paragraphIndex / sentenceIndex / speed / 章节），
 *    所有控制都经 [handleCommand] 串行化（controlMutex），播放循环在服务内常驻，
 *    退出阅读器不断声。
 *  - **Lector 逐句朗读**：speakLoop 逐句合成 + 播放，onEnd 自动推进到下一句/下一段/
 *    下一章；当前句区间经 [publishPosition] 发给 UI 做逐句高亮与点句跳读。
 *  - **FolioReader Media Overlays 同步**：每合成一句就把"文本区间 + 实际音频时长"
 *    追加进 [timeline]（运行时虚拟 SMIL），播放中用音频时间反查句子，进度条与
 *    高亮始终对齐。
 *
 * 引擎由 [TtsReadAloudService] 持有并驱动，与 UI 之间只通过 [TtsEventBus] 通信。
 */
class TtsReadAloudEngine(private val context: Context) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.Default
    private val scope: CoroutineScope get() = this

    // ---- 引擎 ----
    private var offlineEngine: OfflineTtsEngine? = null
    private var engineModelKey: String = ""

    // ---- 朗读状态（唯一事实来源）----
    private var book: Book? = null
    private var chapterIndex = 0
    private var paragraphIndex = 0
    private var sentenceIndex = 0
    private var speed = 1f
    private var sleepMinutes = 0

    /** FolioReader 式运行时音频↔文本时间轴。 */
    private val timeline = SyncTimeline()

    // ---- 断点续读（MoRealm paragraphStartPos 的逐句版）----
    private var currentAudio: SynthesizedAudio? = null
    private var currentAudioStartFrame = 0
    private var currentSentenceHash: String = ""

    private var speakJob: Job? = null
    private var sleepJob: Job? = null
    private val controlMutex = Mutex()

    private val tag = "TtsReadAloudEngine"

    /** 服务 onCreate 调用：订阅命令总线。 */
    fun start() {
        scope.launch {
            TtsEventBus.commands.collect { cmd ->
                try {
                    handleCommand(cmd)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(tag, "handleCommand ${cmd::class.simpleName} failed: ${e.message}")
                }
            }
        }
    }

    fun release() {
        scope.coroutineContext.cancel()
        speakJob?.cancel()
        sleepJob?.cancel()
        offlineEngine?.release()
        offlineEngine = null
        currentAudio = null
    }

    // ── 命令处理 ────────────────────────────────────────────────────────────

    private fun handleCommand(cmd: TtsCommand) {
        when (cmd) {
            is TtsCommand.LoadBook -> scope.launch { controlMutex.withLock { loadBook(cmd) } }
            TtsCommand.Play, TtsCommand.Resume -> startPlaying()
            TtsCommand.Pause -> pause()
            TtsCommand.Stop -> stop()
            TtsCommand.NextSentence -> stepSentence(+1)
            TtsCommand.PrevSentence -> stepSentence(-1)
            TtsCommand.NextParagraph -> stepParagraph(+1)
            TtsCommand.PrevParagraph -> stepParagraph(-1)
            TtsCommand.NextChapter -> switchChapter(+1)
            TtsCommand.PrevChapter -> switchChapter(-1)
            is TtsCommand.JumpTo -> jumpTo(cmd.paragraphIndex, cmd.sentenceIndex)
            is TtsCommand.SetSpeed -> setSpeed(cmd.speed)
            is TtsCommand.SetModel -> setModel(cmd.modelId)
            is TtsCommand.SetSleep -> setSleep(cmd.minutes)
            TtsCommand.StopService -> stop()
        }
    }

    private suspend fun loadBook(cmd: TtsCommand.LoadBook) {
        val b = BookCache.load(context, cmd.bookId) ?: run {
            TtsEventBus.update { it.copy(error = "书籍数据缺失，请重新导入") }
            return
        }
        book = b
        chapterIndex = cmd.chapterIndex.coerceIn(0, (b.totalChapters - 1).coerceAtLeast(0))
        paragraphIndex = cmd.paragraphIndex
        sentenceIndex = cmd.sentenceIndex
        currentAudio = null
        currentAudioStartFrame = 0
        timeline.clear()
        ensureEngine()
        val chapter = b.chapters.getOrNull(chapterIndex)
        TtsEventBus.update {
            it.copy(
                bookId = b.bookId,
                bookTitle = b.title,
                chapterTitle = chapter?.title ?: "",
                chapterIndex = chapterIndex,
                totalChapters = b.totalChapters,
                isPlaying = false,
            )
        }
        publishPosition(playing = false)
    }

    private fun startPlaying() {
        scope.launch {
            controlMutex.withLock {
                if (book == null) return@withLock
                if (!ensureEngine()) {
                    TtsEventBus.update { it.copy(isPlaying = false, error = "TTS 模型未就绪") }
                    return@withLock
                }
                if (TtsEventBus.snapshot().isPlaying && speakJob?.isActive == true) return@withLock
                speakJob?.cancelAndJoin()
                TtsEventBus.update { it.copy(isPlaying = true, error = null) }
                publishPosition(playing = true)
                speakJob = scope.launch { speakLoop() }
            }
        }
    }

    private fun pause() {
        scope.launch {
            controlMutex.withLock {
                if (!TtsEventBus.snapshot().isPlaying) return@withLock
                speakJob?.cancelAndJoin() // playSentence 的 finally 里已记录 head frame
                TtsEventBus.update { it.copy(isPlaying = false, sentenceAudioMs = 0) }
            }
        }
    }

    private fun stop() {
        scope.launch {
            controlMutex.withLock {
                speakJob?.cancelAndJoin()
                sleepJob?.cancel()
                currentAudio = null
                currentAudioStartFrame = 0
                timeline.clear()
                TtsEventBus.update {
                    it.copy(isPlaying = false, sentenceAudioMs = 0, sentenceAudioTotalMs = 0)
                }
            }
        }
    }

    // ── 逐句 / 段落 / 章节导航（Lector 交互）───────────────────────────────

    private fun stepSentence(direction: Int) {
        scope.launch {
            controlMutex.withLock {
                val chapter = currentChapter() ?: return@withLock
                if (direction > 0) {
                    // 当前段还有下一句 → 前进；否则找下一个非空段
                    val para = chapter.paragraphs.getOrNull(paragraphIndex)
                    if (para != null && !para.isEmpty && sentenceIndex < para.sentences.size - 1) {
                        sentenceIndex++
                    } else if (advanceParagraph(chapter, +1)) {
                        sentenceIndex = 0
                    } else {
                        switchChapterInternal(+1)
                        return@withLock
                    }
                } else {
                    if (sentenceIndex > 0) {
                        sentenceIndex--
                    } else {
                        // 回退到上一非空段的末尾
                        var p = paragraphIndex - 1
                        while (p >= 0 && chapter.paragraphs.getOrNull(p)?.isEmpty != false) {
                            p--
                        }
                        if (p >= 0) {
                            paragraphIndex = p
                            sentenceIndex = (chapter.paragraphs[p].sentences.size - 1).coerceAtLeast(0)
                        } else {
                            switchChapterInternal(-1)
                            return@withLock
                        }
                    }
                }
                resetSentencePlayback()
                restartOrPublish()
            }
        }
    }

    private fun stepParagraph(direction: Int) {
        scope.launch {
            controlMutex.withLock {
                val chapter = currentChapter() ?: return@withLock
                if (direction > 0) {
                    if (!advanceParagraph(chapter, +1)) {
                        switchChapterInternal(+1)
                        return@withLock
                    }
                } else {
                    if (!retreatParagraph(chapter)) {
                        switchChapterInternal(-1)
                        return@withLock
                    }
                }
                sentenceIndex = 0
                resetSentencePlayback()
                restartOrPublish()
            }
        }
    }

    private fun switchChapter(direction: Int) {
        scope.launch { controlMutex.withLock { switchChapterInternal(direction) } }
    }

    private fun jumpTo(paraIdx: Int, sentIdx: Int) {
        scope.launch {
            controlMutex.withLock {
                val chapter = currentChapter() ?: return@withLock
                paragraphIndex = paraIdx.coerceIn(0, chapter.paragraphs.lastIndex.coerceAtLeast(0))
                val para = chapter.paragraphs.getOrNull(paragraphIndex)
                val n = para?.sentences?.size ?: 0
                sentenceIndex = if (n > 0) sentIdx.coerceIn(0, n - 1) else 0
                resetSentencePlayback()
                // 点句跳读：直接开播（Lector 交互）
                if (!TtsEventBus.snapshot().isPlaying) TtsEventBus.update { it.copy(isPlaying = true, error = null) }
                publishPosition(playing = true)
                speakJob?.cancelAndJoin()
                speakJob = scope.launch { speakLoop() }
            }
        }
    }

    // ── 配置 ────────────────────────────────────────────────────────────────

    private fun setSpeed(newSpeed: Float) {
        scope.launch {
            controlMutex.withLock {
                val s = newSpeed.coerceIn(0.3f, 4f)
                if (s == speed) return@withLock
                speed = s
                resetSentencePlayback() // 换速必须重新合成
                TtsEventBus.update { it.copy(speed = s) }
                restartOrPublish()
            }
        }
    }

    private fun setModel(modelId: String) {
        scope.launch {
            controlMutex.withLock {
                ModelManager.setActiveModel(context, modelId)
                engineModelKey = ""
                offlineEngine?.release()
                offlineEngine = null
                resetSentencePlayback()
                ensureEngine()
                if (TtsEventBus.snapshot().isPlaying) {
                    speakJob?.cancelAndJoin()
                    speakJob = scope.launch { speakLoop() }
                }
            }
        }
    }

    private fun setSleep(minutes: Int) {
        scope.launch {
            controlMutex.withLock {
                sleepMinutes = minutes
                sleepJob?.cancel()
                TtsEventBus.update { it.copy(sleepMinutes = minutes) }
                if (minutes > 0) {
                    sleepJob = scope.launch {
                        var remaining = minutes
                        while (remaining > 0) {
                            delay(60_000L)
                            remaining--
                            TtsEventBus.update { it.copy(sleepMinutes = remaining) }
                        }
                        stop()
                        TtsEventBus.send(TtsCommand.StopService)
                    }
                }
            }
        }
    }

    // ── 朗读主循环（Lector 逐句）───────────────────────────────────────────

    private suspend fun speakLoop() {
        while (TtsEventBus.snapshot().isPlaying) {
            // 定位当前应读的句子：自动跳过空段 / 句末推进 / 章末切章
            var chapter = currentChapter()
            while (chapter != null) {
                if (paragraphIndex >= chapter.paragraphs.size) {
                    // 章末 → 切下一章；没有则结束
                    if (!switchChapterInternal(+1)) {
                        TtsEventBus.update { it.copy(isPlaying = false) }
                        return
                    }
                    chapter = currentChapter()
                    continue
                }
                val para = chapter.paragraphs[paragraphIndex]
                if (para.isEmpty) { paragraphIndex++; sentenceIndex = 0; continue }
                if (sentenceIndex >= para.sentences.size) { paragraphIndex++; sentenceIndex = 0; continue }
                break
            }
            if (chapter == null) {
                TtsEventBus.update { it.copy(isPlaying = false) }
                return
            }
            val para = chapter.paragraphs[paragraphIndex]
            val sentence = para.sentences[sentenceIndex]

            // 断点续读：同一句且上次被暂停在半途 → 复用已合成音频
            val resumeAudio = if (currentAudio?.text == sentence.text && currentAudioStartFrame > 0) {
                currentAudio
            } else null

            val audio: SynthesizedAudio = if (resumeAudio != null) {
                resumeAudio
            } else {
                val engine = offlineEngine
                if (engine == null) {
                    TtsEventBus.update { it.copy(error = "TTS 引擎未就绪", isPlaying = false) }
                    return
                }
                val syn = engine.synthesize(sentence.text, speed)
                if (syn == null) {
                    TtsEventBus.update { it.copy(error = "句子合成失败，跳过", isPlaying = false) }
                    return
                }
                currentAudio = syn
                currentAudioStartFrame = 0
                currentSentenceHash = sentence.text
                timeline.add(
                    paragraphIndex, sentenceIndex,
                    para.offset + sentence.start, para.offset + sentence.end,
                    sentence.text, syn.durationMs,
                )
                syn
            }

            publishSentence(playing = true, audio = audio)
            playSentence(audio, currentAudioStartFrame)
            // 播放结束 → 推进
            sentenceIndex++
            if (sentenceIndex >= para.sentences.size) {
                sentenceIndex = 0
                paragraphIndex++
            }
            currentAudioStartFrame = 0
            yield()
        }
    }

    /**
     * 播放一句（FolioReader 式音频时间驱动）。
     * 播放中用 [AudioTrack.playbackHeadPosition] 实时更新 state.sentenceAudioMs /
     * chapterAudioMs，UI 的逐句进度与 SyncTimeline 即由此对齐。
     * 被取消（pause/stop/导航）时在 finally 记录已播帧到 [currentAudioStartFrame]。
     */
    private suspend fun playSentence(audio: SynthesizedAudio, startFrame: Int) {
        val sr = audio.sampleRate
        if (sr <= 0) return
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(audio.samples.size * 4, 8192))
                .build()

            var offset = 0
            val chunk = 9600
            while (offset < audio.samples.size) {
                ensureActive()
                val n = minOf(chunk, audio.samples.size - offset)
                track.write(audio.samples, offset, n, AudioTrack.WRITE_BLOCKING)
                offset += n
            }
            if (startFrame > 0) {
                try { track.setPlaybackHeadPosition(startFrame) } catch (_: Exception) {}
            }
            track.play()
            val totalFrames = audio.samples.size
            val baseMs = timeline.lastOrNull()?.audioStartMs ?: 0L
            while (isActive) {
                val head = track.playbackHeadPosition
                val playedMs = head * 1000L / sr
                TtsEventBus.update {
                    it.copy(
                        sentenceAudioMs = playedMs.coerceAtMost(audio.durationMs),
                        chapterAudioMs = baseMs + playedMs,
                    )
                }
                if (head >= totalFrames || track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                delay(80)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(tag, "playSentence error: ${e.message}")
        } finally {
            // 被取消时记录已播帧（断点续读）；自然播完则置 0
            if (currentSentenceHash == (currentAudio?.text ?: "")) {
                currentAudioStartFrame = if (!TtsEventBus.snapshot().isPlaying) {
                    track?.playbackHeadPosition ?: 0
                } else {
                    0
                }
            }
            try { track?.pause() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
        }
    }

    /** 预合成下一句（Lector prefetch 思想）。 */
    // 注：sherpa-onnx OfflineTts 不支持同一实例并发 generate，预合成与主合成会串行化，
    // 收益被抵消；为保证线程安全 v1 不做预取，句间停顿由短句合成延迟承担。

    // ── 章节推进 ────────────────────────────────────────────────────────────

    /** 尝试切章；成功返回 true。切换后重置段/句游标并发布。 */
    private suspend fun switchChapterInternal(direction: Int): Boolean {
        val b = book ?: return false
        val target = chapterIndex + direction
        if (target < 0 || target >= b.totalChapters) return false
        chapterIndex = target
        paragraphIndex = 0
        sentenceIndex = 0
        currentAudio = null
        currentAudioStartFrame = 0
        timeline.clear()
        val chapter = b.chapters[target]
        TtsEventBus.update {
            it.copy(
                chapterTitle = chapter.title,
                chapterIndex = chapterIndex,
                paragraphIndex = 0,
                sentenceIndex = 0,
                sentenceAudioMs = 0,
                sentenceAudioTotalMs = 0,
                chapterAudioMs = 0,
            )
        }
        publishPosition(playing = TtsEventBus.snapshot().isPlaying)
        return true
    }

    /** 从当前段向前找下一个非空段；返回是否找到（不跨章）。 */
    private fun advanceParagraph(chapter: Chapter, direction: Int): Boolean {
        var p = paragraphIndex + direction
        while (p in chapter.paragraphs.indices) {
            val para = chapter.paragraphs[p]
            if (!para.isEmpty) {
                paragraphIndex = p
                return true
            }
            p += direction
        }
        return false
    }

    /** 从当前段向后找上一个非空段。 */
    private fun retreatParagraph(chapter: Chapter): Boolean {
        var p = paragraphIndex - 1
        while (p >= 0) {
            val para = chapter.paragraphs[p]
            if (!para.isEmpty) {
                paragraphIndex = p
                return true
            }
            p--
        }
        return false
    }

    private fun resetSentencePlayback() {
        currentAudio = null
        currentAudioStartFrame = 0
    }

    /** 播放中重启循环；暂停态只发布位置。 */
    private fun restartOrPublish() {
        if (TtsEventBus.snapshot().isPlaying) {
            scope.launch {
                controlMutex.withLock {
                    speakJob?.cancelAndJoin()
                    publishPosition(playing = true)
                    speakJob = scope.launch { speakLoop() }
                }
            }
        } else {
            publishPosition(playing = false)
        }
    }

    // ── 引擎管理 ────────────────────────────────────────────────────────────

    private fun ensureEngine(): Boolean {
        val dir = ModelManager.getInstalledDirectory(context)
        if (dir == null) {
            offlineEngine?.release()
            offlineEngine = null
            TtsEventBus.update { it.copy(error = "未启用 TTS 模型：请在模型管理中下载并启用", isPlaying = false) }
            return false
        }
        val key = dir.absolutePath
        if (offlineEngine != null && engineModelKey == key) return true
        offlineEngine?.release()
        val engine = OfflineTtsEngine(dir, ModelManager.getActiveModelId(context) ?: "model")
        if (!engine.init()) {
            offlineEngine = null
            engineModelKey = ""
            TtsEventBus.update { it.copy(error = "模型加载失败，请检查模型完整性") }
            return false
        }
        offlineEngine = engine
        engineModelKey = key
        val name = ModelManager.getActiveModelId(context)
            ?.let { ModelCatalog.findById(context, it)?.name }
            ?: "离线 TTS"
        TtsEventBus.update { it.copy(modelName = name, error = null) }
        return true
    }

    // ── 状态发布 ────────────────────────────────────────────────────────────

    private fun currentChapter(): Chapter? = book?.chapters?.getOrNull(chapterIndex)

    private fun publishPosition(playing: Boolean) {
        val chapter = currentChapter()
        val para = chapter?.paragraphs?.getOrNull(paragraphIndex)
        val sentence = para?.sentences?.getOrNull(sentenceIndex)
        val paraCount = chapter?.paragraphs?.size ?: 0
        TtsEventBus.update {
            it.copy(
                isPlaying = playing,
                chapterTitle = chapter?.title ?: it.chapterTitle,
                paragraphIndex = paragraphIndex,
                totalParagraphs = paraCount,
                sentenceIndex = sentenceIndex,
                sentenceCount = para?.sentences?.size ?: 0,
                sentenceTextStart = if (para != null && sentence != null) para.offset + sentence.start else -1,
                sentenceTextEnd = if (para != null && sentence != null) para.offset + sentence.end else -1,
                chapterPosition = para?.offset ?: 0,
                scrollProgress = if (paraCount > 1) paragraphIndex.toFloat() / (paraCount - 1) else if (paraCount == 1) 1f else -1f,
            )
        }
    }

    private fun publishSentence(playing: Boolean, audio: SynthesizedAudio) {
        publishPosition(playing)
        TtsEventBus.update {
            it.copy(
                sentenceAudioTotalMs = audio.durationMs,
                sentenceAudioMs = 0,
                chapterAudioMs = timeline.lastOrNull()?.audioStartMs ?: 0,
            )
        }
    }
}
