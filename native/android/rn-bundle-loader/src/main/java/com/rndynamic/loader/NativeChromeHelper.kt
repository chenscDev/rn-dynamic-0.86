package com.rndynamic.loader

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 原生 Chrome 工具：状态栏显隐 / 颜色（任意 Activity 可用）
 */
object NativeChromeHelper {

    fun parseColorOrNull(raw: String?): Int? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) {
            return null
        }
        return try {
            Color.parseColor(if (s.startsWith("#")) s else "#$s")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * @param visible 是否显示状态栏
     * @param backgroundColor 状态栏背景色（ARGB/RGB）
     * @param lightContent true=浅色图标（深色底）；false=深色图标（浅色底）
     */
    fun applyStatusBar(
        activity: Activity,
        visible: Boolean? = null,
        backgroundColor: Int? = null,
        lightContent: Boolean? = null,
    ) {
        val window = activity.window ?: return
        if (backgroundColor != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = backgroundColor
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (visible != null) {
            if (visible) {
                controller.show(WindowInsetsCompat.Type.statusBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            } else {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                // 沉浸时允许内容延伸到系统栏区域
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
        }
        if (lightContent != null) {
            // lightContent=true → 浅色图标 → isAppearanceLightStatusBars=false
            controller.isAppearanceLightStatusBars = !lightContent
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val decor = window.decorView
                var flags = decor.systemUiVisibility
                flags =
                    if (!lightContent) {
                        flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    } else {
                        flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    }
                decor.systemUiVisibility = flags
            }
        }
    }
}
