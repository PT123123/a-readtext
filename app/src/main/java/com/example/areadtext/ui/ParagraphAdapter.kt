package com.example.areadtext.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.areadtext.databinding.ItemReaderParagraphBinding
import com.example.areadtext.reader.ThemeCatalog
import com.example.areadtext.reader.book.Paragraph

/** 阅读器配色/排版样式（legado 风格的"背景-正文-高亮"三元组）。 */
data class ReaderStyle(
    val fontSp: Float,
    val lineSpacingMult: Float,
    val bgColor: Int,
    val textColor: Int,
    val sentenceBg: Int,
    val currentParaBg: Int,
    val accentColor: Int,
    val secondaryTextColor: Int,
) {
    companion object {
        fun fromTheme(theme: com.example.areadtext.reader.ReaderTheme, fontSp: Float, line: Float) = ReaderStyle(
            fontSp = fontSp,
            lineSpacingMult = line,
            bgColor = theme.bgColor,
            textColor = theme.textColor,
            sentenceBg = theme.highlightColor,
            currentParaBg = theme.paraHighlightColor,
            accentColor = theme.accentColor,
            secondaryTextColor = theme.secondaryTextColor,
        )

        // 向后兼容旧 API
        fun paper(fontSp: Float, line: Float) =
            fromTheme(ThemeCatalog.PAPER, fontSp, line)

        fun sepia(fontSp: Float, line: Float) =
            fromTheme(ThemeCatalog.SEPIA, fontSp, line)

        fun night(fontSp: Float, line: Float) =
            fromTheme(ThemeCatalog.NIGHT, fontSp, line)
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

    /** 首行缩进像素（2 个全角空格 ≈ 2 * fontSp）。 */
    private var indentPx: Int = 0

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

        // 首行缩进（中文阅读习惯：2 字符）
        indentPx = (style.fontSp * 2 * tv.resources.displayMetrics.scaledDensity).toInt()

        // 重要：不要给句子挂 ClickableSpan / LinkMovementMethod。
        // 联想平板（ZUI）上，带 ClickableSpan 的 SpannableString 在文本布局阶段
        // 会算出错误字形（正文只画出零星字母），setLayerType(SOFTWARE) 也拦不住
        // （bug 在布局层而非 GPU 绘制层）。已实测：去掉 ClickableSpan 后渲染正常。
        // 因此"点句跳读"改为在 OnTouch 里用 Layout.getOffsetForHorizontal 反查
        // 点击坐标命中的句子，渲染路径与普通 TextView 完全一致，不可能触发该 bug。
        val ss = SpannableString(p.text)
        // 首行缩进
        if (indentPx > 0) {
            ss.setSpan(
                LeadingMarginSpan.Standard(indentPx, 0),
                0, ss.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE
            )
        }
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
