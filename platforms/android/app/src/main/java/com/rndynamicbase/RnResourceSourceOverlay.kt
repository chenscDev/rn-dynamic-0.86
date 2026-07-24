package com.rndynamicbase

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.rndynamic.loader.RnBundleSourcePrefs

/**
 * Debug 包全局悬浮：切换 RN 资源「远程 CDN」/「本地 Metro」
 * 一次设置后写入 SharedPreferences，重启当前 Activity 生效。
 */
object RnResourceSourceOverlay {
    fun attach(activity: Activity, root: FrameLayout) {
        if (!BuildConfig.SHOW_RESOURCE_SOURCE_FAB) {
            return
        }
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF2FFFFFF.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xFFDDDDDD.toInt())
            }
            elevation = dp(8).toFloat()
        }

        val title = TextView(activity).apply {
            text = "资源来源"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF222222.toInt())
        }

        val hint = TextView(activity).apply {
            textSize = 11f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }

        val sourceSwitch = Switch(activity).apply {
            // checked = 本地 Metro
            isChecked = RnBundleSourcePrefs.isLocalMetro(activity)
            text = if (isChecked) "本地 Metro" else "远程 CDN"
        }

        val hostInput = EditText(activity).apply {
            setHint("电脑局域网 IP")
            setText(RnBundleSourcePrefs.metroHost(activity))
            setSingleLine()
            setPadding(dp(10), dp(8), dp(10), dp(8))
            visibility = if (sourceSwitch.isChecked) View.VISIBLE else View.GONE
        }

        fun refreshHint() {
            val local = sourceSwitch.isChecked
            sourceSwitch.text = if (local) "本地 Metro" else "远程 CDN"
            hostInput.visibility = if (local) View.VISIBLE else View.GONE
            hint.text = if (local) {
                "本地：填写电脑 IP，需 yarn start（端口 8081）"
            } else {
                "远程：自动使用 ${RnBundleSourcePrefs.cdnHost(activity)}，无需填写"
            }
        }
        refreshHint()
        sourceSwitch.setOnCheckedChangeListener { _, _ -> refreshHint() }

        val saveBtn = Button(activity).apply {
            text = "保存并生效"
            setOnClickListener {
                val local = sourceSwitch.isChecked
                if (local) {
                    val host = hostInput.text?.toString()?.trim().orEmpty()
                    if (host.isBlank()) {
                        hostInput.error = "请填写电脑 IP"
                        return@setOnClickListener
                    }
                    RnBundleSourcePrefs.setSource(activity, local = true, host = host)
                } else {
                    RnBundleSourcePrefs.setSource(activity, local = false)
                }
                Toast.makeText(
                    activity,
                    if (local) "已切到本地 Metro" else "已切到远程 CDN",
                    Toast.LENGTH_SHORT,
                ).show()
                panel.visibility = View.GONE
                activity.recreate()
            }
        }

        panel.addView(title)
        panel.addView(hint)
        panel.addView(sourceSwitch)
        panel.addView(
            hostInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        panel.addView(
            saveBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val chip = TextView(activity).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(0xCC1565C0.toInt())
                cornerRadius = dp(20).toFloat()
            }
            elevation = dp(6).toFloat()
            fun updateLabel() {
                text = if (RnBundleSourcePrefs.isLocalMetro(activity)) "本地" else "远程"
            }
            updateLabel()
            setOnClickListener {
                if (panel.visibility == View.VISIBLE) {
                    panel.visibility = View.GONE
                } else {
                    sourceSwitch.isChecked = RnBundleSourcePrefs.isLocalMetro(activity)
                    hostInput.setText(RnBundleSourcePrefs.metroHost(activity))
                    refreshHint()
                    panel.visibility = View.VISIBLE
                }
            }
        }

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                dp(220),
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(12), dp(88))
            },
        )
        root.addView(
            chip,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                setMargins(0, 0, dp(12), dp(72))
            },
        )
    }
}
