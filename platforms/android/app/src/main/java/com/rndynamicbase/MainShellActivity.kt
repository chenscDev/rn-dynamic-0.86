package com.rndynamicbase

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment

/**
 * 内测壳主界面：原生 Tab（首页 / 我的）。
 * 首页纯原生可离线展示；RN 开发从「我的 → RN 调试入口」进入。
 */
class MainShellActivity : AppCompatActivity() {
    private enum class Tab { HOME, MINE }

    private val containerId = View.generateViewId()
    private lateinit var tabHome: TextView
    private lateinit var tabMine: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val fragmentContainer = FrameLayout(this).apply {
            id = containerId
        }

        val bottomBar = createBottomBar()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                fragmentContainer,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                bottomBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        setContentView(root)
        root.applySystemBarInsets(extraTopDp = 0)

        if (savedInstanceState == null) {
            switchTab(Tab.HOME)
        }
    }

    private fun createBottomBar(): LinearLayout {
        tabHome = createTabButton("首页", Tab.HOME)
        tabMine = createTabButton("我的", Tab.MINE)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(4).toFloat()
            addView(
                tabHome,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                tabMine,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            setPadding(0, dp(8), 0, dp(8))
        }
    }

    private fun createTabButton(label: String, tab: Tab): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener { switchTab(tab) }
        }
    }

    private fun switchTab(tab: Tab) {
        val selectedColor = 0xFF111111.toInt()
        val normalColor = 0xFF888888.toInt()

        tabHome.setTextColor(if (tab == Tab.HOME) selectedColor else normalColor)
        tabMine.setTextColor(if (tab == Tab.MINE) selectedColor else normalColor)
        tabHome.setTypeface(null, if (tab == Tab.HOME) Typeface.BOLD else Typeface.NORMAL)
        tabMine.setTypeface(null, if (tab == Tab.MINE) Typeface.BOLD else Typeface.NORMAL)

        val fragment: Fragment = when (tab) {
            Tab.HOME -> NativeHomeFragment()
            Tab.MINE -> NativeMineFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(containerId, fragment)
            .commit()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
