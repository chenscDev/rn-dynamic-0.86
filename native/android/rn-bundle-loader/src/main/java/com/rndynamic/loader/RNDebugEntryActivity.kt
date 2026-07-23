package com.rndynamic.loader

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * - 本地：host + port + key → Metro（common + page）
 * - 测试/CDN：关闭 DevServer，按 channel（分支）解析分包；未找到时回退 master
 * - channel / host / key 等会记住上次输入
 */
class RNDebugEntryActivity : AppCompatActivity() {
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthSession.init(applicationContext)
        if (!AuthSession.isLoggedIn()) {
            AuthNavigator.openLogin(this)
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val defaultChannel = prefs.getString(KEY_CHANNEL, null)
            ?: RNAssetBundleHelper.readBuildChannel(this).ifBlank { "master" }

        val hostField = EditText(this).apply {
            hint = "Host（真机填电脑局域网 IP）"
            setText(prefs.getString(KEY_HOST, "") ?: "")
            minHeight = dp(48)
        }
        val portField = EditText(this).apply {
            hint = "Port"
            setText(prefs.getString(KEY_PORT, "8081") ?: "8081")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            minHeight = dp(48)
        }
        val keyField = EditText(this).apply {
            hint = "分包 key"
            setText(prefs.getString(KEY_BUNDLE_KEY, "home") ?: "home")
            minHeight = dp(48)
        }
        val channelField = EditText(this).apply {
            hint = "channel（git 分支名，如 ff-cc / master）"
            setText(defaultChannel)
            minHeight = dp(48)
        }
        val urlField = EditText(this).apply {
            hint = "可选：直接填 Bundle URL（优先于 channel CDN）"
            setText(prefs.getString(KEY_URL, "") ?: "")
            minHeight = dp(48)
        }
        val devSwitch = Switch(this).apply {
            text = "使用 DevServer（Metro）"
            isChecked = prefs.getBoolean(KEY_DEV, true)
            minHeight = dp(48)
        }
        val commonSwitch = Switch(this).apply {
            text = "预拉取 common（Metro 挂载仍用 page 全量包）"
            isChecked = prefs.getBoolean(KEY_COMMON, true)
            minHeight = dp(48)
        }
        val info = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            setPadding(0, dp(16), 0, 0)
        }

        val openButton = Button(this).apply { text = "打开分包" }
        val clearButton = Button(this).apply { text = "清除本地分包缓存" }

        fun refreshInfo() {
            val mode = if (devSwitch.isChecked) {
                "本地 Metro（代码以电脑 rn-biz 当前分支为准）"
            } else {
                "channel CDN / 内置 assets（输入分支优先，找不到回退 master）"
            }
            info.text = """
                模式: $mode
                platform: android
                channel: 以本页输入为准；CDN/内置包路径 rn/.../{channel}/...
                Metro 模式下 channel 会写入 initialProps，JS 仍来自电脑 Metro 服务
                CDN: rn/0.86.0/{channel}/{key}/android/...
                Metro 必须在 rn-biz-0.86 目录执行 yarn start（不要用 rn-dynamic）
            """.trimIndent()
        }

        fun savePrefs(channel: String) {
            prefs.edit()
                .putString(KEY_HOST, hostField.text.toString().trim())
                .putString(KEY_PORT, portField.text.toString().trim().ifBlank { "8081" })
                .putString(KEY_BUNDLE_KEY, keyField.text.toString().trim())
                .putString(KEY_CHANNEL, channel)
                .putString(KEY_URL, urlField.text.toString().trim())
                .putBoolean(KEY_DEV, devSwitch.isChecked)
                .putBoolean(KEY_COMMON, commonSwitch.isChecked)
                .apply()
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
            useSplit: Boolean,
            metroHost: String? = null,
            metroPort: Int? = null,
        ) {
            val props = Bundle().apply {
                putString("fromNative", "debug-entry")
                putString("channel", channel)
                // 动态分包挂载的是本地 file bundle，JS 侧靠此字段访问电脑后端
                if (!metroHost.isNullOrBlank()) {
                    putString("metroHost", metroHost.trim())
                }
                if (metroPort != null) {
                    putInt("metroPort", metroPort)
                }
            }
            startActivity(
                RNContainerActivity.intentForLocalPaths(
                    context = this,
                    title = moduleName,
                    moduleName = moduleName,
                    pagePath = pageLocal,
                    commonPath = commonLocal,
                    channel = channel,
                    fromNative = "debug-entry",
                ).apply {
                    putExtra(RNContainerActivity.EXTRA_USE_SPLIT_PAGE, useSplit)
                    putExtra(RNContainerActivity.EXTRA_PROPS, props)
                },
            )
            openButton.isEnabled = true
            clearButton.isEnabled = true
            info.text = buildString {
                append("已打开 RN 容器 channel=$channel split=$useSplit\n")
                append("common=${commonUrl ?: "(无)"}\n")
                append("page=$pageUrl")
                if (!metroHost.isNullOrBlank()) {
                    append("\nmetroHost=$metroHost")
                }
            }
        }

        /** 按 channel 解析内置/CDN 分包；失败则回退 master */
        fun resolveByChannel(key: String, preferredChannel: String): Pair<String, RNBundleResolver.ResolvedBundles> {
            val cacheDir = File(cacheDir, "RNDynamicBundles")
            val tried = linkedSetOf<String>()
            val candidates = listOf(preferredChannel, "master", "main")
                .map { RNBundleConfigStore.normalizeChannel(it) }
                .filter { it.isNotBlank() }
                .distinct()
            var lastError: Exception? = null
            for (channel in candidates) {
                tried.add(channel)
                try {
                    val resolved = RNBundleResolver.resolve(
                        context = this,
                        channel = channel,
                        bundleKey = key,
                        cacheDir = cacheDir,
                    )
                    return channel to resolved
                } catch (error: Exception) {
                    lastError = error
                }
            }
            throw RNBundleMount.MountException(
                "按 channel 解析失败 key=$key tried=${tried.joinToString(",")} → ${lastError?.message}",
                lastError,
            )
        }

        openButton.setOnClickListener {
            if (!openButton.isEnabled) return@setOnClickListener
            refreshInfo()
            val key = keyField.text.toString().trim()
            val channelInput = RNBundleConfigStore.normalizeChannel(
                channelField.text.toString().ifBlank { "master" },
            )
            if (key.isEmpty()) {
                Toast.makeText(this, "请填写分包 key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            savePrefs(channelInput)

            try {
                if (devSwitch.isChecked) {
                    val host = hostField.text.toString().trim().ifBlank {
                        Toast.makeText(this, "请填写电脑局域网 IP", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val port = portField.text.toString().toIntOrNull() ?: 8081
                    val pageUrl = RNBundleMount.metroPageUrl(host, port, key, "android")
                    val commonUrl =
                        if (commonSwitch.isChecked) {
                            RNBundleMount.metroCommonUrl(host, port, "android")
                        } else {
                            null
                        }
                    openButton.isEnabled = false
                    clearButton.isEnabled = false
                    info.text = "正在从 Metro 拉取 bundle…（channel=$channelInput）"
                    Thread {
                        try {
                            if (commonUrl != null) {
                                runOnUiThread { info.text = "正在下载 common bundle…" }
                                val commonLocal = RNBundleMount.resolveToLocalFile(this, commonUrl)
                                runOnUiThread { info.text = "正在下载 page bundle…" }
                                val pageLocal = RNBundleMount.resolveToLocalFile(this, pageUrl)
                                runOnUiThread {
                                    openMountActivity(
                                        key, channelInput, pageLocal, commonLocal,
                                        pageUrl, commonUrl, useSplit = false,
                                        metroHost = host, metroPort = port,
                                    )
                                }
                            } else {
                                runOnUiThread { info.text = "正在下载 page bundle…" }
                                val pageLocal = RNBundleMount.resolveToLocalFile(this, pageUrl)
                                runOnUiThread {
                                    openMountActivity(
                                        key, channelInput, pageLocal, null,
                                        pageUrl, null, useSplit = false,
                                        metroHost = host, metroPort = port,
                                    )
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
                    val directUrl = urlField.text.toString().trim()
                    openButton.isEnabled = false
                    clearButton.isEnabled = false
                    info.text = if (directUrl.isNotEmpty()) {
                        "正在下载指定 Bundle URL…"
                    } else {
                        "正在按 channel=$channelInput 解析分包（失败回退 master）…"
                    }
                    Thread {
                        try {
                            if (directUrl.isNotEmpty()) {
                                val local = RNBundleMount.resolveToLocalFile(this, directUrl)
                                runOnUiThread {
                                    openMountActivity(
                                        key, channelInput, local, null,
                                        directUrl, null, useSplit = false,
                                    )
                                }
                            } else {
                                val (resolvedChannel, resolved) = resolveByChannel(key, channelInput)
                                runOnUiThread {
                                    if (resolvedChannel != channelInput) {
                                        Toast.makeText(
                                            this,
                                            "channel=$channelInput 未找到，已回退 $resolvedChannel",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                        channelField.setText(resolvedChannel)
                                        savePrefs(resolvedChannel)
                                    }
                                    openMountActivity(
                                        moduleName = resolved.pageItem.moduleName,
                                        channel = resolvedChannel,
                                        pageLocal = resolved.pageFile.absolutePath,
                                        commonLocal = resolved.commonFile?.absolutePath,
                                        pageUrl = resolved.pageFile.absolutePath,
                                        commonUrl = resolved.commonFile?.absolutePath,
                                        useSplit = true,
                                    )
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
                }
            } catch (error: Exception) {
                openButton.isEnabled = true
                clearButton.isEnabled = true
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
        devSwitch.setOnCheckedChangeListener { _, _ -> refreshInfo() }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
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
        val root = ScrollView(this).apply {
            addView(
                form,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
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

    companion object {
        private const val PREFS_NAME = "rn_debug_entry"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_BUNDLE_KEY = "bundle_key"
        private const val KEY_CHANNEL = "channel"
        private const val KEY_URL = "url"
        private const val KEY_DEV = "dev"
        private const val KEY_COMMON = "common"
    }
}
