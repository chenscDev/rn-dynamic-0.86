package com.rndynamicbase

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 原生首页：纯文案，安装后无需 Metro 即可展示。
 */
class NativeHomeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val title = TextView(requireContext()).apply {
            text = "欢迎使用 RnDynamic 内测壳"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        val body = TextView(requireContext()).apply {
            text = """
                这是原生首页，不依赖 Metro，安装后即可查看。

                如需 RN 本地开发：
                1. 切到「我的」页
                2. 进入「RN 调试入口」
                3. 电脑执行 cd rn-biz-0.86 && yarn start
                4. Host 填电脑局域网 IP（或 adb reverse 后用 localhost）
                5. Port 8081，Key 如 home，打开 DevServer
            """.trimIndent()
            textSize = 15f
            setLineSpacing(0f, 1.35f)
        }
        val meta = TextView(requireContext()).apply {
            text = "RN 0.86 · 动态分包基座 · 原生 + RN 混合壳"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
        }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            addView(title)
            addView(
                body,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
            addView(
                meta,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(24) },
            )
            gravity = Gravity.TOP
            applySystemBarInsets(extraTopDp = 4)
        }
    }

    private fun View.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
