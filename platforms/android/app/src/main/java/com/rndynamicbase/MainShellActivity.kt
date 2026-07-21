package com.rndynamicbase

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.rndynamic.loader.AuthSession
import com.rndynamic.loader.RNAssetBundleHelper
import com.rndynamic.loader.MockApiService
import com.rndynamic.loader.RNEntryFragment
import com.rndynamic.loader.RNRootTabFragment
import com.rndynamic.loader.ShellConfigFile
import com.rndynamic.loader.ShellConfigHolder
import com.rndynamic.loader.ShellConfigStore
import com.rndynamic.loader.ShellIconLoader
import com.rndynamic.loader.ShellTabConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * 原生 Shell：Tab / RN 入口由 Mock 接口随机下发
 */
class MainShellActivity : AppCompatActivity() {
    private val containerId = View.generateViewId()
    private var shellConfig: ShellConfigFile? = null
    private var configPath: String? = null
    private val tabButtons = linkedMapOf<String, LinearLayout>()
    private var currentTabId: String? = null
    private lateinit var buildChannel: String
    private lateinit var bottomBar: LinearLayout
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var loadingView: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        AuthSession.init(applicationContext)

        buildChannel = RNAssetBundleHelper.readBuildChannel(applicationContext)
        copyAssetConfigIfNeeded()
        configPath = File(filesDir, "rn-config").absolutePath

        fragmentContainer = FrameLayout(this).apply { id = containerId }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(4).toFloat()
            setPadding(0, dp(8), 0, dp(8))
            visibility = View.GONE
        }
        loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(ProgressBar(context))
            addView(TextView(context).apply { text = "正在拉取 Mock 配置…" })
        }

        val root = FrameLayout(this).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        fragmentContainer,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            0,
                            1f,
                        ),
                    )
                    addView(
                        bottomBar,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(loadingView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        setContentView(root)
        root.applySystemBarInsets(extraTopDp = 0)

        loadShellConfig(
            initialTab = intent.getStringExtra(EXTRA_INITIAL_TAB),
        )
    }

    private fun loadShellConfig(initialTab: String?) {
        thread(name = "MockShellConfig") {
            try {
                val config = MockApiService.fetchShellConfig(buildChannel)
                ShellConfigHolder.config = config
                runOnUiThread {
                    shellConfig = config
                    loadingView.visibility = View.GONE
                    bottomBar.visibility = View.VISIBLE
                    buildBottomBar(config.visibleTabs)
                    val tabId = initialTab ?: config.visibleTabs.firstOrNull()?.id
                    tabId?.let { switchTab(it) }
                }
            } catch (error: Exception) {
                // Mock 失败时优先读当前 channel 的 shell；缺失则回退 master/main
                val fallback = runCatching {
                    ShellConfigStore.fromAssets(this, buildChannel).load()
                }.recoverCatching {
                    ShellConfigStore.fromAssets(this, "master").load()
                }.recoverCatching {
                    ShellConfigStore.fromAssets(this, "main").load()
                }.getOrElse {
                    // 最终兜底：用 Mock 再试一次（同步失败时至少保证有原生壳）
                    MockApiService.fetchShellConfig(buildChannel)
                }
                ShellConfigHolder.config = fallback
                runOnUiThread {
                    shellConfig = fallback
                    loadingView.visibility = View.GONE
                    bottomBar.visibility = View.VISIBLE
                    buildBottomBar(fallback.visibleTabs)
                    fallback.visibleTabs.firstOrNull()?.let { switchTab(it.id) }
                }
            }
        }
    }

    private fun buildBottomBar(tabs: List<ShellTabConfig>) {
        bottomBar.removeAllViews()
        tabButtons.clear()
        tabs.forEach { tab ->
            val button = createTabButton(tab)
            tabButtons[tab.id] = button
            bottomBar.addView(
                button,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
    }

    private fun createTabButton(tab: ShellTabConfig): LinearLayout {
        val iconView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        }
        ShellIconLoader.loadInto(
            this,
            tab.icon,
            iconView,
            ShellIconLoader.resolveDrawable(this, "ic_tab_home", android.R.drawable.ic_menu_compass),
        )
        val label = TextView(this).apply {
            text = tab.title
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
            addView(iconView)
            addView(label)
            setOnClickListener { switchTab(tab.id) }
        }
    }

    private fun switchTab(tabId: String) {
        val config = shellConfig ?: return
        val tab = config.visibleTabs.firstOrNull { it.id == tabId } ?: return
        currentTabId = tabId
        updateTabStyles(tabId)

        val fragment = createFragment(tab, config)
        supportFragmentManager.beginTransaction()
            .replace(containerId, fragment)
            .commit()
    }

    private fun updateTabStyles(selectedId: String) {
        val selectedColor = 0xFF111111.toInt()
        val normalColor = 0xFF888888.toInt()
        tabButtons.forEach { (id, layout) ->
            val label = layout.getChildAt(1) as TextView
            val selected = id == selectedId
            label.setTextColor(if (selected) selectedColor else normalColor)
            label.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun createFragment(tab: ShellTabConfig, config: ShellConfigFile): Fragment {
        return when (tab.type) {
            "rn-entries" -> RNEntryFragment.newInstance(
                tabId = tab.id,
                channel = config.channel,
                configPath = configPath,
            )
            "rn-root" -> RNRootTabFragment.newInstance(
                bundleKey = tab.bundleKey ?: tab.id,
                channel = config.channel,
                configPath = configPath,
            )
            "native" -> when (tab.nativeKey) {
                "mine" -> NativeMineFragment()
                else -> NativeHomeFragment()
            }
            else -> NativeHomeFragment()
        }
    }

    private fun copyAssetConfigIfNeeded() {
        val channel = buildChannel
        val baseDir = File(filesDir, "rn-config/channels/$channel")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        // 每次启动同步 assets 配置，避免旧版 127.0.0.1 配置残留
        copyAssetOverwrite(
            assetPath = "rn-config/channels/$channel/bundles.local.json",
            target = File(baseDir, "bundles.local.json"),
        )
        copyAssetOverwrite(
            assetPath = "rn-config/channels/$channel/remote.local.json",
            target = File(baseDir, "remote.local.json"),
        )
    }

    private fun copyAssetOverwrite(assetPath: String, target: File) {
        try {
            assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
        }
    }

    private fun copyAssetIfMissing(assetPath: String, target: File) {
        if (target.exists()) {
            return
        }
        try {
            assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        private const val DEFAULT_CHANNEL = "master"
    }
}
