package com.rndynamic.loader

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 页面加载性能浮层：展示资源顺序、分段耗时、总耗时与相对首次的对比。
 */
object RNLoadPerfPanel {
    fun attach(
        context: Context,
        parent: FrameLayout,
        report: RNBundleLoadTrace.Report,
        initiallyExpanded: Boolean = true,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val delta = report.deltaVsFirstMs
        val deltaText = when {
            report.loadIndex <= 1 -> "首次基线"
            delta == null -> "-"
            delta == 0L -> "与首次持平"
            delta < 0 -> "比首次快 ${-delta}ms"
            else -> "比首次慢 ${delta}ms"
        }
        val deltaColor = when {
            report.loadIndex <= 1 -> 0xFF1565C0.toInt()
            delta == null -> 0xFF555555.toInt()
            delta <= 0L -> 0xFF2E7D32.toInt()
            else -> 0xFFC62828.toInt()
        }

        val titleView = TextView(context).apply {
            text = "加载 · ${report.bundleKey} · 第${report.loadIndex}次 · ${report.totalMs}ms"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(4))
        }
        val summaryView = TextView(context).apply {
            text = deltaText +
                (report.firstTotalMs?.let { "（首次 ${it}ms）" } ?: "") +
                if (report.usedRemote) " · 远程" else " · 本地"
            textSize = 11f
            setTextColor(deltaColor)
            setPadding(dp(10), 0, dp(10), dp(6))
        }
        val detailView = TextView(context).apply {
            text = report.toReadableText()
            textSize = 11f
            setTextColor(0xFF333333.toInt())
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1.1f)
            setPadding(dp(10), 0, dp(10), dp(8))
            typeface = Typeface.MONOSPACE
            visibility = if (initiallyExpanded) View.VISIBLE else View.GONE
        }
        val toggle = Button(context).apply {
            text = if (initiallyExpanded) "收起明细" else "展开明细"
            textSize = 11f
            setOnClickListener {
                val expanded = detailView.visibility == View.VISIBLE
                detailView.visibility = if (expanded) View.GONE else View.VISIBLE
                text = if (expanded) "展开明细" else "收起明细"
            }
        }
        val reset = Button(context).apply {
            text = "重置首次基线"
            textSize = 11f
            setOnClickListener {
                RNBundleLoadTrace.resetFirstBaseline(context, report.bundleKey, report.channel)
                summaryView.text = "已重置，下次打开将记为首次基线"
                summaryView.setTextColor(0xFF1565C0.toInt())
            }
        }
        val close = Button(context).apply {
            text = "关闭"
            textSize = 11f
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), dp(6))
            addView(toggle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(reset, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(close, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2FFFFFF.toInt())
            elevation = dp(4).toFloat()
            addView(titleView)
            addView(summaryView)
            addView(
                ScrollView(context).apply {
                    addView(detailView)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(180),
                ),
            )
            addView(buttons)
        }

        val wrap = FrameLayout(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
        }
        close.setOnClickListener {
            parent.removeView(wrap)
        }

        parent.addView(
            wrap,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        return wrap
    }

    fun showLoadingPlaceholder(context: Context, parent: FrameLayout, message: String): View {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val tv = TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xEEFFFFFF.toInt())
        }
        parent.removeAllViews()
        parent.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER,
            ),
        )
        return tv
    }
}
