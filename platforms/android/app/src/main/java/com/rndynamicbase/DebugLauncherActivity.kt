package com.rndynamicbase

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.rndynamic.loader.RNDebugEntryActivity

/**
 * 蒲公英内测包启动页：不直接加载 RN，避免未连 Metro 时红屏。
 * 开发调试请进入「RN 调试入口」。
 */
class DebugLauncherActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val hint = TextView(this).apply {
            text = """
                内测壳 · RN 0.86

                【RN 调试入口】加载业务分包（home/demo/order）
                1. 电脑: cd rn-biz-0.86 && yarn start
                2. 手机与电脑同一 Wi-Fi
                3. Host = 电脑局域网 IP，Port 8081，Key = home
                4. 打开 DevServer + 加载 common → 打开分包

                【壳首页】加载本仓基座页（RnDynamicBase）
                1. 电脑: cd rn-dynamic-0.86 && yarn start
                2. 真机建议: adb reverse tcp:8081 tcp:8081
                3. 再点「壳首页」
            """.trimIndent()
            setPadding(dp(24), dp(24), dp(24), dp(16))
            textSize = 14f
        }

        val debugButton = Button(this).apply {
            text = "RN 调试入口"
            setOnClickListener {
                startActivity(Intent(this@DebugLauncherActivity, RNDebugEntryActivity::class.java))
            }
        }

        val shellButton = Button(this).apply {
            text = "壳首页（需 Metro 连 RnDynamicBase）"
            setOnClickListener {
                startActivity(Intent(this@DebugLauncherActivity, MainActivity::class.java))
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(hint)
            addView(debugButton)
            addView(shellButton)
        }
        setContentView(root)
        applyStatusBarInsets(root)
    }

    private fun applyStatusBarInsets(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top + dp(8),
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
