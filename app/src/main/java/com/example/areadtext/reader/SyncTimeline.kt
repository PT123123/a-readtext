package com.example.areadtext.reader

/**
 * FolioReader Media Overlays 同步机制的本地实现。
 *
 * EPUB3 的 Media Overlays 用 SMIL 把"音频片段 ↔ 文本 span"绑成时间轴；播放时
 * 由音频位置驱动高亮推进（FolioReader MediaController 每 10ms 轮询
 * MediaPlayer.currentPosition，越过当前 clip.end 就推进到下一段并高亮对应文本）。
 *
 * a-readtext 的音频是 TTS 实时合成的（无预录 SMIL），因此这里用"运行时虚拟
 * SMIL"等价物：每合成一句，就把它（文本区间 + 实际音频时长）追加进 [spans]，
 * 累积成整章时间轴。播放中通过 [spanAt] 用音频时间反查当前句子 —— 这就是
 * "音频时间 → 文本"的同步核心，UI 高亮与音频进度永远对齐。
 */
data class AudioSpan(
    val paragraphIndex: Int,
    val sentenceIndex: Int,
    val textStart: Int,          // 在章节纯文本中的起始下标
    val textEnd: Int,
    val audioStartMs: Long,      // 章节级累积音频起点
    val audioEndMs: Long,
    val text: String,
)

class SyncTimeline {
    private val spans = ArrayList<AudioSpan>()
    var cursorMs: Long = 0
        private set

    val size: Int get() = spans.size
    fun isEmpty(): Boolean = spans.isEmpty()
    fun lastOrNull(): AudioSpan? = spans.lastOrNull()

    /** 追加一个句子 span：textStart/textEnd 为章节文本坐标，durationMs 为实际合成时长。 */
    fun add(
        paragraphIndex: Int,
        sentenceIndex: Int,
        textStart: Int,
        textEnd: Int,
        text: String,
        durationMs: Long,
    ) {
        val start = cursorMs
        cursorMs += durationMs.coerceAtLeast(1)
        spans.add(
            AudioSpan(
                paragraphIndex = paragraphIndex,
                sentenceIndex = sentenceIndex,
                textStart = textStart,
                textEnd = textEnd,
                audioStartMs = start,
                audioEndMs = cursorMs,
                text = text,
            )
        )
    }

    /** 按章节级音频时间（ms）反查当前句子 span —— FolioReader 的核心查询。 */
    fun spanAt(audioMs: Long): AudioSpan? {
        if (spans.isEmpty()) return null
        // 二分：找最后一个 audioStartMs <= audioMs 的 span
        var lo = 0
        var hi = spans.size - 1
        var ans: AudioSpan? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = spans[mid]
            if (s.audioStartMs <= audioMs) {
                ans = s
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 章节级总时长（已合成的部分）。 */
    val totalAudioMs: Long get() = cursorMs

    fun clear() {
        spans.clear()
        cursorMs = 0
    }
}
