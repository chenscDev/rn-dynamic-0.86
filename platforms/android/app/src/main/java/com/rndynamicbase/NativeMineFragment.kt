package com.rndynamicbase

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.rndynamic.loader.RNDebugEntryActivity

/**
 * 原生「我的」页：用户信息 + RN 调试入口。
 */
class NativeMineFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val title = TextView(requireContext()).apply {
            text = "我的"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }

        val userCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        userCard.addView(infoRow("昵称", "内测用户"))
        userCard.addView(infoRow("用户 ID", "U10001"))
        userCard.addView(infoRow("应用版本", BuildConfig.VERSION_NAME))
        userCard.addView(infoRow("构建类型", BuildConfig.BUILD_TYPE))

        val hint = TextView(requireContext()).apply {
            text = """
                RN 本地开发：点击下方进入调试页，填写 Metro 地址后打开指定分包。
                真机 Host 请填电脑局域网 IP；USB 调试可先执行：
                adb reverse tcp:8081 tcp:8081
            """.trimIndent()
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setLineSpacing(0f, 1.3f)
        }

        val debugButton = Button(requireContext()).apply {
            text = "RN 调试入口"
            setOnClickListener {
                startActivity(Intent(requireContext(), RNDebugEntryActivity::class.java))
            }
        }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            addView(title)
            addView(
                userCard,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(16)
                },
            )
            addView(
                hint,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(16)
                },
            )
            addView(
                debugButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(20)
                },
            )
            applySystemBarInsets(extraTopDp = 4)
        }
    }

    private fun infoRow(label: String, value: String): TextView {
        return TextView(requireContext()).apply {
            text = "$label：$value"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(4))
        }
    }

    private fun View.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
