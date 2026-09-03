package com.example.areadtext.ui

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.areadtext.databinding.ItemReaderParagraphBinding
import com.example.areadtext.reader.Paragraph

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

        if (p.sentences.isEmpty()) {
            tv.text = p.text
            tv.movementMethod = null
            return
        }
        val ss = SpannableString(p.text)
        val len = ss.length
        p.sentences.forEachIndexed { si, s ->
            val start = s.start.coerceIn(0, len)
            val end = s.end.coerceIn(start, len)
            if (end > start) {
                val click = object : ClickableSpan() {
                    override fun onClick(widget: View) = onSentenceTap(position, si)
                }
                ss.setSpan(click, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (isCurrentPara && si == highlightSentence) {
                    ss.setSpan(
                        BackgroundColorSpan(style.sentenceBg), start, end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        tv.text = ss
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text: TextView = itemView as TextView
    }
}
