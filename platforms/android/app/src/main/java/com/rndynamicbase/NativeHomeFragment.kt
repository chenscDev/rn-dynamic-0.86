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
        val isDebug = BuildConfig.SHOW_RN_DEBUG_ENTRY
        val title = TextView(requireContext()).apply {
            text = if (isDebug) "欢迎使用 RnDynamic 测试壳" else "欢迎使用 RnDynamic"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        val body = TextView(requireContext()).apply {
            text = if (isDebug) {
                """
                这是原生首页，默认走远程 CDN，安装后即可查看。

                资源切换：点右下角悬浮「远程 / 本地」
                · 远程：自动使用服务器，无需填写 IP
                · 本地：填写电脑 IP，需 yarn start

                详细调试：切到「我的」→「RN 调试入口」
                """.trimIndent()
            } else {
                """
                这是原生首页。
                业务与问答页从服务器加载资源，无需本机开发服务。
                """.trimIndent()
            }
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
