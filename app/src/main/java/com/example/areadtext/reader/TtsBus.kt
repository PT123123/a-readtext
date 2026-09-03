package com.example.areadtext.reader

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 朗读器对外发布的只读状态（UI 观察）。
 *
 * 字段语义综合 MoRealm（段落级）与 Lector（逐句级）：
 *  - [paragraphIndex]/[sentenceIndex] 是调度游标，唯一事实来源在 TtsReadAloudEngine。
 *  - [sentenceTextStart]/[sentenceTextEnd] 为当前句在章节纯文本中的字符区间，
 *    渲染层据此画逐句高亮（对齐 MoRealm publishState 的 paragraphRange）。
 *  - [sentenceAudioMs]/[chapterAudioMs] 为 FolioReader 式"音频时间"，UI 进度条
 *    与 SyncTimeline.spanAt 共用。
 */
data class ReadState(
    val bookId: String = "",
    val isPlaying: Boolean = false,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val totalChapters: Int = 0,
    val paragraphIndex: Int = 0,
    val totalParagraphs: Int = 0,
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val sentenceTextStart: Int = -1,
    val sentenceTextEnd: Int = -1,
    val chapterPosition: Int = 0,
    val scrollProgress: Float = -1f,
    val sentenceAudioMs: Long = 0,
    val sentenceAudioTotalMs: Long = 0,
    val chapterAudioMs: Long = 0,
    val speed: Float = 1f,
    val modelName: String = "",
    val error: String? = null,
    val sleepMinutes: Int = 0,
) {
    val isLoaded: Boolean get() = bookTitle.isNotEmpty()
}

/**
 * 朗读命令（MoRealm TtsEventBus.Command 的简化版）。
 * UI / 通知栏按钮只向总线发命令，引擎在服务内消费 —— 服务生命周期内常驻，
 * 退出阅读器后通知栏仍能切章/暂停（MoRealm 把播放循环从 ViewModel 移进
 * TtsEngineHost 就是为了这一点）。
 */
sealed class TtsCommand {
    data class LoadBook(
        val bookId: String,
        val chapterIndex: Int = 0,
        val paragraphIndex: Int = 0,
        val sentenceIndex: Int = 0,
    ) : TtsCommand()

    data object Play : TtsCommand()
    data object Pause : TtsCommand()
    data object Resume : TtsCommand()
    data object Stop : TtsCommand()
    data object NextSentence : TtsCommand()
    data object PrevSentence : TtsCommand()
    data object NextParagraph : TtsCommand()
    data object PrevParagraph : TtsCommand()
    data object NextChapter : TtsCommand()
    data object PrevChapter : TtsCommand()
    data class JumpTo(val paragraphIndex: Int, val sentenceIndex: Int) : TtsCommand()
    data class SetSpeed(val speed: Float) : TtsCommand()
    data class SetModel(val modelId: String) : TtsCommand()
    data class SetSleep(val minutes: Int) : TtsCommand()
    data object StopService : TtsCommand()
}

/** 全局朗读总线：命令 SharedFlow + 状态 StateFlow，进程内单例。 */
object TtsEventBus {
    private val _commands = MutableSharedFlow<TtsCommand>(extraBufferCapacity = 64)
    val commands: SharedFlow<TtsCommand> = _commands

    private val _state = MutableStateFlow(ReadState())
    val state: StateFlow<ReadState> = _state

    fun send(command: TtsCommand) {
        _commands.tryEmit(command)
    }

    fun update(transform: (ReadState) -> ReadState) {
        _state.update(transform)
    }

    fun snapshot(): ReadState = _state.value
}
