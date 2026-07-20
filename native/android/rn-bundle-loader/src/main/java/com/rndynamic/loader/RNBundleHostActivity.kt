package com.rndynamic.loader

import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import kotlin.concurrent.thread

/**
 * 正式入口：整页打开某个分包（Mode A）
 * 按 dependsOn 先解析 common，再挂载 page。
 */
class RNBundleHostActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler {
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

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!RNBundleMount.forwardOnBackPressed(this@RNBundleHostActivity)) {
                        finish()
                    }
                }
            },
        )

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
                    status.text = "正在挂载 RN…"
                }

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

                runOnUiThread {
                    status.visibility = android.view.View.GONE
                }
            } catch (error: Exception) {
                runOnUiThread {
                    container.removeAllViews()
                    container.addView(
                        TextView(this).apply {
                            text = "加载失败: ${error.message}"
                            setPadding(48, 48, 48, 48)
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        RNBundleMount.forwardOnHostResume(this)
    }

    override fun onPause() {
        RNBundleMount.forwardOnHostPause(this)
        super.onPause()
    }

    override fun onDestroy() {
        RNBundleMount.forwardOnHostDestroy(this)
        super.onDestroy()
    }

    override fun invokeDefaultOnBackPressed() {
        finish()
    }

    companion object {
        const val EXTRA_KEY = "rn_bundle_key"
        const val EXTRA_CHANNEL = "rn_bundle_channel"
        const val EXTRA_URL = "rn_bundle_url"
        const val EXTRA_CONFIG_PATH = "rn_bundle_config_path"
        const val EXTRA_LOAD_DEPS = "rn_bundle_load_deps"
    }
}
