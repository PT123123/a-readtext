package com.example.areadtext.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.areadtext.databinding.ItemReaderParagraphBinding
import com.example.areadtext.reader.book.Paragraph

/** 阅读器配色/排版样式（legado 风格的"背景-正文-高亮"三元组）。 */
data class ReaderStyle(
    val fontSp: Float,
    val lineSpacingMult: Float,
    val bgColor: Int,
    val textColor: Int,
    val sentenceBg: Int,
    val currentParaBg: Int,
) {
    companion object {
        fun paper(fontSp: Float, line: Float) = ReaderStyle(
            fontSp, line,
            Color.rgb(250, 246, 236), Color.rgb(46, 42, 38),
            Color.rgb(255, 224, 130), Color.rgb(245, 238, 222),
        )

        fun sepia(fontSp: Float, line: Float) = ReaderStyle(
            fontSp, line,
            Color.rgb(244, 236, 216), Color.rgb(91, 70, 54),
            Color.rgb(217, 179, 108), Color.rgb(238, 227, 203),
        )

        fun night(fontSp: Float, line: Float) = ReaderStyle(
            fontSp, line,
            Color.rgb(18, 18, 18), Color.rgb(200, 200, 200),
            Color.rgb(58, 58, 58), Color.rgb(32, 32, 32),
        )
    }
}

/**
 * 段落列表适配器（Lector 逐句高亮交互的落点）：
 * 每段渲染为 TextView，句末标点切句；当前朗读句加 [BackgroundColorSpan] 高亮，
 * 每句挂 [ClickableSpan] 实现"点句跳读"。
 */
class ParagraphAdapter(
    private val onSentenceTap: (paragraphIndex: Int, sentenceIndex: Int) -> Unit,
) : RecyclerView.Adapter<ParagraphAdapter.VH>() {

    private var paragraphs: List<Paragraph> = emptyList()
    var style: ReaderStyle = ReaderStyle.paper(19f, 1.5f)
    var highlightParagraph: Int = -1
    var highlightSentence: Int = -1

    fun submit(newParagraphs: List<Paragraph>) {
        paragraphs = newParagraphs
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemReaderParagraphBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding.root)
    }

    override fun getItemCount(): Int = paragraphs.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = paragraphs[position]
        val tv = holder.text
        tv.textSize = style.fontSp
        tv.setLineSpacing(0f, style.lineSpacingMult)
        tv.setTextColor(style.textColor)
        val isCurrentPara = position == highlightParagraph
        tv.setBackgroundColor(if (isCurrentPara) style.currentParaBg else Color.TRANSPARENT)

        // 重要：不要给句子挂 ClickableSpan / LinkMovementMethod。
        // 联想平板（ZUI）上，带 ClickableSpan 的 SpannableString 在文本布局阶段
        // 会算出错误字形（正文只画出零星字母），setLayerType(SOFTWARE) 也拦不住
        // （bug 在布局层而非 GPU 绘制层）。已实测：去掉 ClickableSpan 后渲染正常。
        // 因此"点句跳读"改为在 OnTouch 里用 Layout.getOffsetForHorizontal 反查
        // 点击坐标命中的句子，渲染路径与普通 TextView 完全一致，不可能触发该 bug。
        val ss = SpannableString(p.text)
        val len = ss.length
        p.sentences.forEachIndexed { si, s ->
            val start = s.start.coerceIn(0, len)
            val end = s.end.coerceIn(start, len)
            if (end > start && isCurrentPara && si == highlightSentence) {
                ss.setSpan(
                    BackgroundColorSpan(style.sentenceBg), start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        tv.text = ss
        tv.movementMethod = null
        tv.setOnTouchListener(null)
        tv.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val x = event.x.toInt() - tv.totalPaddingLeft + tv.scrollX
                val y = event.y.toInt() - tv.totalPaddingTop + tv.scrollY
                val layout = tv.layout
                if (layout != null) {
                    val line = layout.getLineForVertical(y)
                    if (y >= layout.getLineTop(line) && y <= layout.getLineBottom(line) &&
                        x >= layout.getLineLeft(line) && x <= layout.getLineRight(line)
                    ) {
                        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
                        val si = findSentenceAt(p, offset)
                        if (si >= 0) {
                            onSentenceTap(position, si)
                            return@setOnTouchListener true
                        }
                    }
                }
            }
            false
        }
    }

    /** 根据点击的字符偏移反查所属句子（含端点，兼容 getOffsetForHorizontal 右偏）。 */
    private fun findSentenceAt(p: Paragraph, offset: Int): Int {
        for (i in p.sentences.indices) {
            val s = p.sentences[i]
            if (offset >= s.start && offset <= s.end) return i
        }
        return -1
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView as TextView
    }
}
