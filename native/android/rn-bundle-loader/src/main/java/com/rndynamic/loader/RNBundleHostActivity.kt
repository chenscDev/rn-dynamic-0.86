package com.rndynamic.loader

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import kotlin.concurrent.thread

/**
 * 正式入口：整页打开某个分包（Mode A）
 * 按 dependsOn 先解析 common，再挂载 page。
 */
class RNBundleHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val key = intent.getStringExtra(EXTRA_KEY).orEmpty()
        val channel = RNBundleConfigStore.normalizeChannel(
            intent.getStringExtra(EXTRA_CHANNEL) ?: "main",
        )
        val urlOverride = intent.getStringExtra(EXTRA_URL)
        val configPath = intent.getStringExtra(EXTRA_CONFIG_PATH)
            ?: File(filesDir, "rn-config").absolutePath
        val loadDeps = intent.getBooleanExtra(EXTRA_LOAD_DEPS, true)

        val status = TextView(this).apply {
            text = "正在准备 $channel/$key ..."
            setPadding(48, 48, 48, 48)
            textSize = 14f
        }
        val container = FrameLayout(this)
        container.addView(status)
        setContentView(container)

        if (key.isEmpty()) {
            status.text = "缺少 EXTRA_KEY"
            return
        }

        thread {
            try {
                val store = RNBundleConfigStore(File(configPath), channel)
                var pageItem = store.item(key, platform = "android")
                if (!urlOverride.isNullOrBlank()) {
                    pageItem = pageItem.withUrl(urlOverride)
                }
                val cache = RNBundleCache(File(cacheDir, "RNDynamicBundles"))

                var commonPath: String? = null
                if (loadDeps) {
                    for (dep in pageItem.dependencyKeys) {
                        val depItem = store.item(dep, platform = "android")
                        val file = cache.resolveBundleFile(depItem)
                        if (dep == "common") {
                            commonPath = file.absolutePath
                        }
                    }
                }

                val pageFile = cache.resolveBundleFile(pageItem)
                val props = Bundle().apply {
                    putString("fromNative", "host-entry")
                    putString("channel", channel)
                }

                runOnUiThread {
                    try {
                        container.removeAllViews()
                        RNBundleMount.mount(
                            this,
                            container,
                            RNBundleMount.Request(
                                moduleName = pageItem.moduleName,
                                pageBundlePathOrUrl = pageFile.absolutePath,
                                commonBundlePathOrUrl = commonPath,
                                initialProps = props,
                            ),
                        )
                    } catch (error: Exception) {
                        container.removeAllViews()
                        container.addView(
                            TextView(this).apply {
                                text = """
                                    分包已就绪，挂载失败:
                                    ${error.message}
                                    channel=$channel
                                    key=${pageItem.key}
                                    hash=${pageItem.hash}
                                    page=${pageFile.absolutePath}
                                    common=${commonPath ?: "(无)"}
                                """.trimIndent()
                                setPadding(48, 48, 48, 48)
                            },
                        )
                    }
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
        const val EXTRA_CHANNEL = "rn_bundle_channel"
        const val EXTRA_URL = "rn_bundle_url"
        const val EXTRA_CONFIG_PATH = "rn_bundle_config_path"
        const val EXTRA_LOAD_DEPS = "rn_bundle_load_deps"
    }
}
