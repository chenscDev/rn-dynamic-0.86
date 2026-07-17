package com.rndynamic.loader

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        val hostField = EditText(this).apply {
            hint = "Host"
            setText("10.0.2.2")
        }
        val portField = EditText(this).apply {
            hint = "Port"
            setText("8081")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val keyField = EditText(this).apply {
            hint = "分包 key"
            setText("home")
        }
        val channelField = EditText(this).apply {
            hint = "channel（分支）"
            setText("main")
        }
        val urlField = EditText(this).apply {
            hint = "测试环境 Bundle URL"
        }
        val devSwitch = Switch(this).apply {
            text = "使用 DevServer（Metro）"
            isChecked = true
        }
        val commonSwitch = Switch(this).apply {
            text = "加载 common"
            isChecked = true
        }
        val info = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        val openButton = Button(this).apply { text = "打开分包" }
        val clearButton = Button(this).apply { text = "清除本地分包缓存" }
        val mountContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        fun refreshInfo() {
            val mode = if (devSwitch.isChecked) "本地 Metro（双包）" else "测试包 / channel CDN"
            info.text = """
                模式: $mode
                platform: android（宿主标识）
                channel: 发测/正式拉 CDN；Metro 用当前工作区
                CDN: rn/0.86.0/{channel}/{key}/android/...
            """.trimIndent()
        }

        openButton.setOnClickListener {
            refreshInfo()
            val key = keyField.text.toString().trim()
            val channel = RNBundleConfigStore.normalizeChannel(
                channelField.text.toString().ifBlank { "main" },
            )
            if (key.isEmpty()) {
                Toast.makeText(this, "请填写分包 key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = hostField.text.toString().ifBlank { "10.0.2.2" }
            val port = portField.text.toString().toIntOrNull() ?: 8081
            val props = Bundle().apply {
                putString("fromNative", "debug-entry")
                putString("channel", channel)
            }

            try {
                if (devSwitch.isChecked) {
                    val pageUrl = RNBundleMount.metroPageUrl(host, port, key, "android")
                    val commonUrl =
                        if (commonSwitch.isChecked) {
                            RNBundleMount.metroCommonUrl(host, port, "android")
                        } else {
                            null
                        }
                    mountContainer.removeAllViews()
                    RNBundleMount.mount(
                        this,
                        mountContainer,
                        RNBundleMount.Request(
                            moduleName = key,
                            pageBundlePathOrUrl = pageUrl,
                            commonBundlePathOrUrl = commonUrl,
                            initialProps = props,
                        ),
                    )
                    info.text = "已挂载 Metro channel=$channel\ncommon=$commonUrl\npage=$pageUrl"
                } else {
                    val url = urlField.text.toString().trim()
                    if (url.isEmpty()) {
                        Toast.makeText(this, "请填写测试 Bundle URL", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    mountContainer.removeAllViews()
                    RNBundleMount.mount(
                        this,
                        mountContainer,
                        RNBundleMount.Request(
                            moduleName = key,
                            pageBundlePathOrUrl = url,
                            commonBundlePathOrUrl = null,
                            initialProps = props,
                        ),
                    )
                    info.text = "已挂载测试包 channel=$channel\nurl=$url"
                }
            } catch (error: Exception) {
                info.text = "挂载失败: ${error.message}"
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

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
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
                addView(mountContainer)
            },
        )
    }
}
