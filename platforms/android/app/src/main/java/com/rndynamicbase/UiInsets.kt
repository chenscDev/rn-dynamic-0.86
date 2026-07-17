package com.rndynamicbase

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 为根布局应用系统栏 insets，避免内容贴到状态栏 */
internal fun View.applySystemBarInsets(extraTopDp: Int = 8) {
    val density = resources.displayMetrics.density
    val extraTopPx = (extraTopDp * density).toInt()
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(
            baseLeft + bars.left,
            baseTop + bars.top + extraTopPx,
            baseRight + bars.right,
            baseBottom + bars.bottom,
        )
        insets
    }
    ViewCompat.requestApplyInsets(this)
}

internal fun View.dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}
