package com.rndynamic.loader

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * 本地 / 测试环境调试入口
 *
 * - 本地：host + port + key → Metro（可用 RN 自带调试）
 * - 测试：关闭 DevServer，填写测试包 URL；可清除缓存验证 hash 强制更新
 */
class RNDebugEntryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val hostField = EditText(this).apply {
            hint = "Host"
            setText("10.0.2.2") // Android 模拟器访问本机
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
        val urlField = EditText(this).apply {
            hint = "测试环境 Bundle URL"
        }
        val devSwitch = Switch(this).apply {
            text = "使用 DevServer（Metro）"
            isChecked = true
        }
        val info = TextView(this).apply {
            textSize = 13f
            setPadding(0, 24, 0, 0)
        }

        val openButton = Button(this).apply { text = "打开分包" }
        val clearButton = Button(this).apply { text = "清除本地分包缓存" }

        fun refreshInfo() {
            val mode = if (devSwitch.isChecked) "本地 Metro" else "测试包 URL"
            info.text = """
                模式: $mode
                Metro 示例: http://host:port/src/key/index.bundle?platform=android&dev=true
                正式环境请使用配置入口，不要暴露本页面。
            """.trimIndent()
        }

        openButton.setOnClickListener {
            refreshInfo()
            val key = keyField.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "请填写分包 key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val host = hostField.text.toString().ifBlank { "10.0.2.2" }
            val port = portField.text.toString().toIntOrNull() ?: 8081
            if (devSwitch.isChecked) {
                val metro =
                    "http://$host:$port/src/$key/index.bundle?platform=android&dev=true&minify=false"
                info.text = """
                    准备连接 Metro:
                    $metro
                    moduleName=$key

                    请先在 packages/base 执行 yarn start，
                    宿主接入后用该 URL 创建 ReactRootView。
                """.trimIndent()
            } else {
                val url = urlField.text.toString().trim()
                if (url.isEmpty()) {
                    Toast.makeText(this, "测试模式请填写 Bundle URL", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                info.text = "准备加载测试包:\nurl=$url\nmoduleName=$key"
            }
        }

        clearButton.setOnClickListener {
            try {
                val cache = RNBundleCache(File(cacheDir, "RNDynamicBundles"))
                cache.clearAll()
                info.text = "已清除缓存: ${cacheDirectoryPath()}"
            } catch (error: Exception) {
                info.text = "清缓存失败: ${error.message}"
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(hostField)
                addView(portField)
                addView(keyField)
                addView(devSwitch)
                addView(urlField)
                addView(openButton)
                addView(clearButton)
                addView(info)
            },
        )
        refreshInfo()
    }

    private fun cacheDirectoryPath(): String {
        return File(cacheDir, "RNDynamicBundles").absolutePath
    }
}
