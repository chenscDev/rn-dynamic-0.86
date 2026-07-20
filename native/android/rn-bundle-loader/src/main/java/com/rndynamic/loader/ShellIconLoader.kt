package com.rndynamic.loader

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import java.net.URL
import kotlin.concurrent.thread

/**
 * 解析配置中的 icon 字段：本地 drawable 名或 http(s) URL
 */
object ShellIconLoader {
    fun loadInto(context: Context, icon: String?, target: ImageView, fallbackResId: Int) {
        if (icon.isNullOrBlank()) {
            target.setImageResource(fallbackResId)
            return
        }
        if (icon.startsWith("http://") || icon.startsWith("https://")) {
            target.setImageResource(fallbackResId)
            thread(name = "ShellIconLoader") {
                try {
                    val bytes = URL(icon).openStream().use { it.readBytes() }
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bitmap != null) {
                        target.post { target.setImageBitmap(bitmap) }
                    }
                } catch (_: Exception) {
                    // 保持 fallback
                }
            }
            return
        }
        val resId = context.resources.getIdentifier(icon, "drawable", context.packageName)
        if (resId != 0) {
            target.setImageResource(resId)
        } else {
            target.setImageResource(fallbackResId)
        }
    }

    fun resolveDrawable(context: Context, icon: String?, fallbackResId: Int): Int {
        if (icon.isNullOrBlank()) {
            return fallbackResId
        }
        if (icon.startsWith("http://") || icon.startsWith("https://")) {
            return fallbackResId
        }
        val resId = context.resources.getIdentifier(icon, "drawable", context.packageName)
        return if (resId != 0) resId else fallbackResId
    }
}
