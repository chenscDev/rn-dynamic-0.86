package com.rndynamic.loader

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

/**
 * 本地 / 测试环境调试入口
 *
 * - 本地：host + port + key → Metro（common + page 双 URL）
 * - 测试：关闭 DevServer，填写测试包 URL
 * - platform 固定 android（由宿主标识）
 */
class RNDebugEntryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthSession.init(applicationContext)
        if (!AuthSession.isLoggedIn()) {
            AuthNavigator.openLogin(this)
            finish()
            return
        }
        // 避免内容顶到状态栏，保证顶部输入框可点
        WindowCompat.setDecorFitsSystemWindows(window, true)

        val hostField = EditText(this).apply {
            hint = "Host（真机填电脑局域网 IP）"
            setText("")
            minHeight = dp(48)
        }
        val portField = EditText(this).apply {
            hint = "Port"
            setText("8081")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            minHeight = dp(48)
        }
        val keyField = EditText(this).apply {
            hint = "分包 key"
            setText("home")
            minHeight = dp(48)
        }
        val channelField = EditText(this).apply {
            hint = "channel（分支，Metro 可填 master）"
            setText("master")
            minHeight = dp(48)
        }
        val urlField = EditText(this).apply {
            hint = "测试环境 Bundle URL"
            minHeight = dp(48)
        }
        val devSwitch = Switch(this).apply {
            text = "使用 DevServer（Metro）"
            isChecked = true
            minHeight = dp(48)
        }
        val commonSwitch = Switch(this).apply {
            text = "预拉取 common（Metro 挂载仍用 page 全量包）"
            isChecked = true
            minHeight = dp(48)
        }
        val info = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(16), 0, 0)
        }

        val openButton = Button(this).apply { text = "打开分包" }
        val clearButton = Button(this).apply { text = "清除本地分包缓存" }

        fun refreshInfo() {
            val mode = if (devSwitch.isChecked) "本地 Metro（双包）" else "测试包 / channel CDN"
            info.text = """
                模式: $mode
                platform: android（宿主标识）
                channel: Metro 调试填 git 分支（如 master）；CDN 发测再填对应 channel
                连手机热点时 Host 填电脑在热点网段的 IP（非 192.168.1.x）
                CDN: rn/0.86.0/{channel}/{key}/android/...
            """.trimIndent()
        }

        fun formatError(error: Throwable): String {
            val parts = mutableListOf<String>()
            var current: Throwable? = error
            var depth = 0
            while (current != null && depth < 6) {
                current.message?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
                val next = current.cause
                if (next == null || next === current) break
                current = next
                depth++
            }
            return parts.distinct().joinToString(" → ").ifBlank { error.javaClass.simpleName }
        }

        fun openMountActivity(
            moduleName: String,
            channel: String,
            pageLocal: String,
            commonLocal: String?,
            pageUrl: String,
            commonUrl: String?,
        ) {
            startActivity(
                RNContainerActivity.intentForLocalPaths(
                    context = this,
                    title = moduleName,
                    moduleName = moduleName,
                    pagePath = pageLocal,
                    commonPath = commonLocal,
                    channel = channel,
                    fromNative = "debug-entry",
                ),
            )
            openButton.isEnabled = true
            clearButton.isEnabled = true
            info.text = buildString {
                append("已打开 RN 容器 channel=$channel\n")
                append("common=${commonUrl ?: "(无)"}\n")
                append("page=$pageUrl")
            }
        }

        openButton.setOnClickListener {
            if (!openButton.isEnabled) return@setOnClickListener
            refreshInfo()
            val key = keyField.text.toString().trim()
            val channel = RNBundleConfigStore.normalizeChannel(
                channelField.text.toString().ifBlank { "master" },
            )
            if (key.isEmpty()) {
                Toast.makeText(this, "请填写分包 key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = hostField.text.toString().trim().ifBlank {
                Toast.makeText(this, "请填写电脑局域网 IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val port = portField.text.toString().toIntOrNull() ?: 8081

            try {
                if (devSwitch.isChecked) {
                    val pageUrl = RNBundleMount.metroPageUrl(host, port, key, "android")
                    val commonUrl =
                        if (commonSwitch.isChecked) {
                            RNBundleMount.metroCommonUrl(host, port, "android")
                        } else {
                            null
                        }
                    openButton.isEnabled = false
                    clearButton.isEnabled = false
                    info.text = "正在从 Metro 拉取 bundle…"
                    Thread {
                        try {
                            if (commonUrl != null) {
                                runOnUiThread { info.text = "正在下载 common bundle…" }
                                val commonLocal = RNBundleMount.resolveToLocalFile(this, commonUrl)
                                runOnUiThread { info.text = "正在下载 page bundle…" }
                                val pageLocal = RNBundleMount.resolveToLocalFile(this, pageUrl)
                                runOnUiThread {
                                    openMountActivity(key, channel, pageLocal, commonLocal, pageUrl, commonUrl)
                                }
                            } else {
                                runOnUiThread { info.text = "正在下载 page bundle…" }
                                val pageLocal = RNBundleMount.resolveToLocalFile(this, pageUrl)
                                runOnUiThread {
                                    openMountActivity(key, channel, pageLocal, null, pageUrl, null)
                                }
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                openButton.isEnabled = true
                                clearButton.isEnabled = true
                                info.text = "挂载失败: ${formatError(error)}"
                            }
                        }
                    }.start()
                } else {
                    val url = urlField.text.toString().trim()
                    if (url.isEmpty()) {
                        Toast.makeText(this, "请填写测试 Bundle URL", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    openButton.isEnabled = false
                    clearButton.isEnabled = false
                    info.text = "正在准备 Bundle…"
                    Thread {
                        try {
                            runOnUiThread { info.text = "正在下载测试 bundle…" }
                            val local = RNBundleMount.resolveToLocalFile(this, url)
                            runOnUiThread {
                                openMountActivity(key, channel, local, null, url, null)
                            }
                        } catch (error: Exception) {
                            runOnUiThread {
                                openButton.isEnabled = true
                                clearButton.isEnabled = true
                                info.text = "挂载失败: ${formatError(error)}"
                            }
                        }
                    }.start()
                }
            } catch (error: Exception) {
                info.text = "挂载失败: ${formatError(error)}"
            }
        }

        clearButton.setOnClickListener {
            try {
                RNBundleCache(File(cacheDir, "RNDynamicBundles")).clearAll()
                info.text = "已清除本地分包缓存"
            } catch (error: Exception) {
                info.text = "清缓存失败: ${error.message}"
            }
        }

        refreshInfo()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 顶部额外下移，避开状态栏 / 刘海，增大可点区域
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(hostField)
            addView(portField)
            addView(keyField)
            addView(channelField)
            addView(devSwitch)
            addView(commonSwitch)
            addView(urlField)
            addView(openButton)
            addView(clearButton)
            addView(info)
        }

        setContentView(root)
        applyStatusBarInsets(root)
    }

    /** 按系统栏 insets 再给顶部加一截 padding，避免输入框贴边难点 */
    private fun applyStatusBarInsets(root: View) {
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top + dp(12),
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
