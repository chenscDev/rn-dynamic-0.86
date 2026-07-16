package com.rndynamic.loader

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * 正式入口：整页打开某个分包（Mode A）
 *
 * 宿主接入 React Native 0.86 后，在分包文件就绪处创建 ReactRootView：
 *   ReactRootView(this).startReactApplication(manager, moduleName, initialProps)
 *   并通过 JSBundleLoader.createFileLoader(path) 加载对应 bundle。
 */
class RNBundleHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val urlOverride = intent.getStringExtra(EXTRA_URL)
        val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
            ?: File(filesDir, "bundles.local.json").absolutePath

        val status = TextView(this).apply {
            text = "正在准备分包 $key ..."
            setPadding(48, 48, 48, 48)
            textSize = 14f
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(status)
            },
        )

        if (key.isEmpty()) {
            status.text = "缺少 EXTRA_KEY"
            return
        }

        thread {
            try {
                val store = RNBundleConfigStore(File(configPath))
                var item = store.item(key, platform = "android")
                if (!urlOverride.isNullOrBlank()) {
                    item = item.withUrl(urlOverride)
                }
                val cache = RNBundleCache(File(cacheDir, "RNDynamicBundles"))
                val file = cache.resolveBundleFile(item)
                runOnUiThread {
                    status.text = """
                        分包已就绪（Mode A 整页）
                        key: ${item.key}
                        hash: ${item.hash}
                        path: ${file.absolutePath}

                        请在宿主完成 RN 集成后挂载 ReactRootView
                        moduleName=${item.moduleName}
                    """.trimIndent()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    status.text = "加载失败: ${error.message}"
                }
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "rn_bundle_key"
        const val EXTRA_URL = "rn_bundle_url"
        const val EXTRA_CONFIG_PATH = "rn_bundle_config_path"
    }
}
