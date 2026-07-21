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
import com.rndynamic.loader.AuthNavigator
import com.rndynamic.loader.AuthSession
import com.rndynamic.loader.LoginActivity
import com.rndynamic.loader.PipelineDemoActivity
import com.rndynamic.loader.RNDebugEntryActivity

/**
 * 原生「我的」页：用户信息 + 退出登录 + RN 调试入口
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
        userCard.addView(infoRow("昵称", AuthSession.getNickname() ?: "未登录"))
        userCard.addView(infoRow("用户 ID", AuthSession.getUserId() ?: "-"))
        userCard.addView(infoRow("应用版本", BuildConfig.VERSION_NAME))

        val logoutButton = Button(requireContext()).apply {
            text = "退出登录"
            setOnClickListener {
                AuthNavigator.logoutAndOpenLogin(requireContext())
            }
        }

        val changePasswordButton = Button(requireContext()).apply {
            text = "修改密码"
            setOnClickListener {
                startActivity(LoginActivity.intentForChangePassword(requireContext()))
            }
        }

        val pipelineButton = Button(requireContext()).apply {
            text = "分包全流程演示"
            setOnClickListener {
                startActivity(Intent(requireContext(), PipelineDemoActivity::class.java))
            }
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
            addView(userCard, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(logoutButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(changePasswordButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(pipelineButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(debugButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
