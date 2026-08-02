package com.rndynamicbase

import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import com.rndynamic.loader.AuthSession
import com.rndynamic.loader.MockApiService
import com.rndynamic.loader.NativeChromeHelper
import com.rndynamic.loader.RNAssetBundleHelper
import com.rndynamic.loader.RNBundleMount
import com.rndynamic.loader.RNBundleRemoteSettingsStore
import com.rndynamic.loader.RNBundleWarmup
import com.rndynamic.loader.RNEntryFragment
import com.rndynamic.loader.RNRootTabFragment
import com.rndynamic.loader.ShellConfigFile
import com.rndynamic.loader.ShellConfigHolder
import com.rndynamic.loader.ShellConfigStore
import com.rndynamic.loader.ShellIconLoader
import com.rndynamic.loader.ShellRemoteConfigStore
import com.rndynamic.loader.ShellTabConfig
import java.io.File
import kotlin.concurrent.thread

/**
 * 原生 Shell：Tab / RN 入口优先远程配置，失败回退 assets / Mock
 */
class MainShellActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler, PermissionAwareActivity {
    private val permissionBridge = com.rndynamic.loader.RNPermissionBridge(this)
    private val containerId = View.generateViewId()
    private var shellConfig: ShellConfigFile? = null
    private var configPath: String? = null
    private val tabButtons = linkedMapOf<String, LinearLayout>()
    /** Tab 保活：hide/show，避免 replace 销毁 RN 状态 */
    private val tabFragments = linkedMapOf<String, Fragment>()
    private var currentTabId: String? = null
    private lateinit var buildChannel: String
    private lateinit var bottomBar: LinearLayout
    private lateinit var titleBar: TextView
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
        // 原生标题栏：默认隐藏，由 RN setChrome / setNativeTitle 控制
        titleBar = TextView(this).apply {
            textSize = 17f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(4).toFloat()
            setPadding(0, dp(4), 0, dp(4))
            visibility = View.GONE
        }
        loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(ProgressBar(context))
            addView(
                TextView(context).apply {
                    text = "正在进入 App Shell…"
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(0, dp(16), 0, dp(8))
                },
            )
            if (BuildConfig.SHOW_RN_DEBUG_ENTRY) {
                addView(
                    TextView(context).apply {
                        text = buildString {
                            appendLine("channel: $buildChannel")
                            appendLine("配置目录: files/rn-config/channels/$buildChannel/")
                            append("测试包可点右下角切换远程/本地资源")
                        }
                        textSize = 12f
                        setTextColor(0xFF555555.toInt())
                        setLineSpacing(0f, 1.25f)
                    },
                )
            }
        }

        val root = FrameLayout(this).apply {
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        titleBar,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ),
                    )
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
        // Debug：右下角悬浮切换资源「远程 CDN / 本地 Metro」
        RnResourceSourceOverlay.attach(this, root)

        // 问答等 rn-root Tab：先交给 RN 栈 pop，未处理再退到后台（不直接 finish 退出）
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (RNBundleMount.forwardOnBackPressed(this@MainShellActivity)) {
                        return
                    }
                    moveTaskToBack(true)
                }
            },
        )

        loadShellConfig(
            initialTab = intent.getStringExtra(EXTRA_INITIAL_TAB),
        )
    }

    /** RN 栈底无法再后退时回调：Shell 退到后台，保持进程 */
    override fun invokeDefaultOnBackPressed() {
        moveTaskToBack(true)
    }

    private fun loadShellConfig(initialTab: String?) {
        thread(name = "ShellConfigLoad") {
            try {
                val config = resolveShellConfig()
                ShellConfigHolder.config = config
                // Shell 就绪后后台预热 rn-root Tab（如 docs-agent），与正式打开同一 resolver
                scheduleRnRootWarmup(config)
                runOnUiThread {
                    shellConfig = config
                    loadingView.visibility = View.GONE
                    bottomBar.visibility = View.VISIBLE
                    buildBottomBar(config.visibleTabs)
                    val tabId = initialTab ?: config.visibleTabs.firstOrNull()?.id
                    tabId?.let { switchTab(it) }
                }
            } catch (error: Exception) {
                // 最终兜底：保证至少有原生壳
                val fallback = MockApiService.fetchShellConfig(buildChannel)
                ShellConfigHolder.config = fallback
                scheduleRnRootWarmup(fallback)
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

    /** 空闲线程预热 rn-root 分包，失败静默 */
    private fun scheduleRnRootWarmup(config: ShellConfigFile) {
        thread(name = "RNBundleWarmup") {
            RNBundleWarmup.warmupRnRootTabs(
                context = applicationContext,
                channel = buildChannel,
                tabs = config.visibleTabs,
                cacheDir = File(cacheDir, "RNDynamicBundles"),
                options = RNBundleWarmup.Options(wifiOnly = false, silent = true),
            )
        }
    }

    /**
     * 加载顺序：远程 Shell → assets(channel) → master/main → Mock
     */
    private fun resolveShellConfig(): ShellConfigFile {
        val remoteSettings = RNBundleRemoteSettingsStore.load(this, buildChannel)
        if (remoteSettings.isUsable()) {
            runCatching {
                return ShellRemoteConfigStore.fetch(this, buildChannel, remoteSettings)
            }
        }
        return runCatching {
            ShellConfigStore.fromAssets(this, buildChannel).load()
        }.recoverCatching {
            ShellConfigStore.fromAssets(this, "master").load()
        }.recoverCatching {
            ShellConfigStore.fromAssets(this, "main").load()
        }.getOrElse {
            MockApiService.fetchShellConfig(buildChannel)
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
            tag = "tab_icon"
        }
        ShellIconLoader.loadInto(
            this,
            tab.icon,
            iconView,
            ShellIconLoader.resolveDrawable(this, "ic_tab_home", android.R.drawable.ic_menu_compass),
        )
        val label = TextView(this).apply {
            text = tab.title
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
            tag = "tab_label"
        }
        // 选中底部分割指示条
        val indicator = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(3)).apply {
                topMargin = dp(4)
                gravity = Gravity.CENTER_HORIZONTAL
            }
            setBackgroundColor(Color.TRANSPARENT)
            tag = "tab_indicator"
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(6))
            addView(iconView)
            addView(label)
            addView(indicator)
            setOnClickListener { switchTab(tab.id) }
        }
    }

    private fun switchTab(tabId: String) {
        val config = shellConfig ?: return
        val tab = config.visibleTabs.firstOrNull { it.id == tabId } ?: return
        val previousId = currentTabId
        updateTabStyles(tabId)

        val fm = supportFragmentManager
        val ft = fm.beginTransaction()
        if (previousId != null && previousId != tabId) {
            tabFragments[previousId]?.let { ft.hide(it) }
        }

        val existing = tabFragments[tabId]
        if (existing != null) {
            ft.show(existing)
        } else {
            val fragment = createFragment(tab, config)
            tabFragments[tabId] = fragment
            ft.add(containerId, fragment, "shell_tab_$tabId")
        }
        // 同步提交，便于随后设置前台 Host / 发事件
        ft.commitNowAllowingStateLoss()

        currentTabId = tabId

        // rn-root：切换前台 ReactHost，并通知 JS 拉取 handoff
        if (tab.type == "rn-root") {
            val mountId = (tab.bundleKey ?: tab.id).trim()
            if (mountId.isNotEmpty()) {
                RNBundleMount.setForegroundMount(this, mountId)
                // 新 Fragment 首挂时 context 可能尚未就绪，短延迟再发
                fragmentContainer.post {
                    RNBundleMount.setForegroundMount(this, mountId)
                    RNBundleMount.emitShellTabSelected(this, tabId, mountId)
                }
            }
        }
    }

    /** 供 RN 桥接调用：切换底部 Tab */
    fun switchToTab(tabId: String) {
        switchTab(tabId)
    }

    /** 供全屏页隐藏/恢复底部原生 Tab */
    fun setBottomBarVisible(visible: Boolean) {
        if (!::bottomBar.isInitialized) {
            return
        }
        bottomBar.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /**
     * 原生标题栏：文案 / 背景色 / 文字色 / 显隐
     * title 为 null 时仅改颜色与显隐；空串视为隐藏标题文案但仍可保留栏位（由 titleVisible 控制）
     */
    fun applyNativeTitle(
        title: String? = null,
        backgroundColor: Int? = null,
        textColor: Int? = null,
        visible: Boolean? = null,
    ) {
        if (!::titleBar.isInitialized) {
            return
        }
        if (title != null) {
            titleBar.text = title
        }
        if (backgroundColor != null) {
            titleBar.setBackgroundColor(backgroundColor)
        }
        if (textColor != null) {
            titleBar.setTextColor(textColor)
        }
        if (visible != null) {
            titleBar.visibility = if (visible) View.VISIBLE else View.GONE
        } else if (title != null && title.isNotBlank()) {
            // 传入非空标题时默认显示
            titleBar.visibility = View.VISIBLE
        }
    }

    /** 状态栏显隐 / 颜色（委托 NativeChromeHelper） */
    fun applyStatusBarChrome(
        visible: Boolean? = null,
        backgroundColor: Int? = null,
        lightContent: Boolean? = null,
    ) {
        NativeChromeHelper.applyStatusBar(
            activity = this,
            visible = visible,
            backgroundColor = backgroundColor,
            lightContent = lightContent,
        )
    }

    private fun updateTabStyles(selectedId: String) {
        val selectedColor = 0xFF0F172A.toInt()
        val normalColor = 0xFF94A3B8.toInt()
        val accentColor = 0xFFFE2C55.toInt()
        tabButtons.forEach { (id, layout) ->
            val selected = id == selectedId
            val iconView = layout.findViewWithTag<ImageView>("tab_icon")
            val label = layout.findViewWithTag<TextView>("tab_label")
            val indicator = layout.findViewWithTag<View>("tab_indicator")
            label?.setTextColor(if (selected) selectedColor else normalColor)
            label?.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            label?.textSize = if (selected) 12f else 11f
            iconView?.setColorFilter(
                if (selected) selectedColor else normalColor,
                PorterDuff.Mode.SRC_IN,
            )
            indicator?.setBackgroundColor(if (selected) accentColor else Color.TRANSPARENT)
            layout.alpha = if (selected) 1f else 0.78f
            layout.isSelected = selected
            // 选中项轻微抬高，未选中更淡，保证点击反馈可见
            layout.scaleX = if (selected) 1.04f else 1f
            layout.scaleY = if (selected) 1.04f else 1f
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


    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        return permissionBridge.checkPermission(permission, pid, uid)
    }

    override fun checkSelfPermission(permission: String): Int {
        return permissionBridge.checkSelfPermission(permission)
    }

    override fun shouldShowRequestPermissionRationale(permission: String): Boolean {
        return permissionBridge.shouldShowRequestPermissionRationale(permission)
    }

    override fun requestPermissions(
        permissions: Array<String>,
        requestCode: Int,
        listener: PermissionListener?,
    ) {
        permissionBridge.requestPermissions(permissions, requestCode, listener)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionBridge.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 文档选择器等依赖此回调，否则 RN pick() Promise 会一直挂起
        RNBundleMount.forwardOnActivityResult(this, requestCode, resultCode, data)
    }

    companion object {
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        private const val DEFAULT_CHANNEL = "master"
    }
}
