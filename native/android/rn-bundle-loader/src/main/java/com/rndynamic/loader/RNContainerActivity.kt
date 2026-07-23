package com.rndynamic.loader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import java.io.File
import kotlin.concurrent.thread

/**
 * 原生 RN 容器：全屏 RN；系统返回 / RN 导航返回在栈内 pop，栈底关闭容器回原生
 */
class RNContainerActivity : AppCompatActivity(), DefaultHardwareBackBtnHandler {
    private lateinit var rnContainer: FrameLayout
    private lateinit var loadingView: View
    private lateinit var loadingTitle: TextView
    private lateinit var loadingDetail: TextView
    private lateinit var errorView: TextView

    /** mount 线程检查此标志，Activity 销毁时置 true */
    @Volatile
    private var isActivityDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthSession.init(applicationContext)

        if (isLoginContainer()) {
            if (AuthSession.isLoggedIn()) {
                AuthNavigator.navigateAfterLogin(this)
                finish()
                return
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, true)
        // 避免 DayNight 深色窗体 + RN 透明根导致「全黑/全白」误判为空白
        window.decorView.setBackgroundColor(0xFFF5F5F5.toInt())

        loadingTitle = TextView(this).apply {
            text = "正在加载分包…"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        loadingDetail = TextView(this).apply {
            text = "解析包路径中…"
            textSize = 12f
            setTextColor(0xFF555555.toInt())
            setLineSpacing(dp(2).toFloat(), 1.15f)
            setPadding(dp(20), dp(12), dp(20), 0)
        }
        loadingView = ScrollView(this).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
            isFillViewport = true
            addView(
                LinearLayout(this@RNContainerActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(16), dp(48), dp(16), dp(32))
                    addView(ProgressBar(this@RNContainerActivity))
                    addView(
                        loadingTitle,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = dp(16) },
                    )
                    addView(loadingDetail)
                },
            )
        }

        errorView = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB00020.toInt())
            setBackgroundColor(0xFFFFEBEE.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
            visibility = View.GONE
        }

        rnContainer = FrameLayout(this).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
            addView(
                rnContainer,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(
                loadingView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            addView(
                errorView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        setContentView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        RNBundleMount.setRuntimeErrorListener(this) { message ->
            if (isFinishing || isDestroyed) return@setRuntimeErrorListener
            loadingView.visibility = View.GONE
            errorView.visibility = View.VISIBLE
            errorView.text = "RN 运行时错误:\n$message"
            if (isLoginContainer()) {
                startActivity(
                    LoginActivity.intentForFallback(
                        this@RNContainerActivity,
                        intent.getStringExtra(AuthNavigator.EXTRA_LOGIN_REASON),
                    ),
                )
                finish()
            }
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!RNBundleMount.forwardOnBackPressed(this@RNContainerActivity)) {
                        finishToNative()
                    }
                }
            },
        )

        thread(name = "RNContainerMount") {
            try {
                updateLoading("解析分包配置…", "channel / bundleKey / assets / cache")
                val request = buildMountRequest()
                if (isActivityDestroyed) return@thread
                showResolvedPaths(request)
                updateLoading("挂载 ReactHost…", loadingDetail.text?.toString().orEmpty())
                val startedAt = System.currentTimeMillis()
                RNBundleMount.mount(this, rnContainer, request)
                if (isActivityDestroyed) return@thread
                val costMs = System.currentTimeMillis() - startedAt
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        loadingDetail.append("\n\n挂载完成 · ${costMs}ms")
                        loadingView.visibility = View.GONE
                        RNBundleMount.forwardOnHostResume(this)
                    }
                }
            } catch (error: Exception) {
                if (isActivityDestroyed) return@thread
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (isLoginContainer()) {
                        startActivity(
                            LoginActivity.intentForFallback(
                                this@RNContainerActivity,
                                intent.getStringExtra(AuthNavigator.EXTRA_LOGIN_REASON),
                            ),
                        )
                        finish()
                        return@runOnUiThread
                    }
                    loadingView.visibility = View.GONE
                    errorView.visibility = View.VISIBLE
                    errorView.text = "加载失败: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    private fun updateLoading(title: String, detail: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            loadingTitle.text = title
            loadingDetail.text = detail
        }
    }

    private fun showResolvedPaths(request: RNBundleMount.Request) {
        val channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
            .ifBlank { RNAssetBundleHelper.readBuildChannel(this) }
        val bundleKey = intent.getStringExtra(EXTRA_BUNDLE_KEY)
            ?: intent.getStringExtra(EXTRA_MODULE_NAME)
            ?: request.moduleName
        val commonPath = request.commonBundlePathOrUrl
        val pagePath = request.pageBundlePathOrUrl
        val commonSize = commonPath?.let { formatBytes(fileLength(it)) } ?: "-"
        val pageSize = formatBytes(fileLength(pagePath))
        val mode = if (request.useSplitPageBundle && !commonPath.isNullOrBlank()) {
            "common + page（运行时合并）"
        } else {
            "单 page"
        }
        val detail = buildString {
            appendLine("channel: $channel")
            appendLine("bundleKey / module: $bundleKey / ${request.moduleName}")
            appendLine("模式: $mode")
            appendLine()
            appendLine("① common")
            appendLine(commonPath ?: "(无)")
            appendLine("大小: $commonSize")
            appendLine()
            appendLine("② page")
            appendLine(pagePath)
            appendLine("大小: $pageSize")
            appendLine()
            append("顺序: 解析 → 读盘/下载 → 合并(如需) → ReactHost → Surface")
        }
        updateLoading("正在加载包…", detail)
    }

    private fun fileLength(pathOrUrl: String): Long {
        return try {
            if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
                -1L
            } else {
                File(pathOrUrl).takeIf { it.exists() }?.length() ?: -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "未知/远程"
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.2f MB", kb / 1024.0)
    }

    /** 关闭容器回到原生（栈底 / 导航栏返回） */
    fun finishToNative() {
        if (!isLoginContainer()) {
            AuthNavigator.saveResumeFromRnContainer(
                bundleKey = intent.getStringExtra(EXTRA_BUNDLE_KEY)
                    ?: intent.getStringExtra(EXTRA_MODULE_NAME),
                bundleTitle = intent.getStringExtra(EXTRA_TITLE),
            )
        }
        finish()
    }

    private fun buildMountRequest(): RNBundleMount.Request {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_LOCAL_PATHS
        val channel = intent.getStringExtra(EXTRA_CHANNEL).orEmpty()
        val props = intent.getBundleExtra(EXTRA_PROPS) ?: Bundle().apply {
            putString("fromNative", intent.getStringExtra(EXTRA_FROM_NATIVE) ?: "container")
            putString("channel", channel)
        }
        if (isLoginContainer()) {
            props.putString("loginReason", intent.getStringExtra(AuthNavigator.EXTRA_LOGIN_REASON))
        } else {
            AuthSession.appendAuthProps(props)
        }

        return when (mode) {
            MODE_BUNDLE_KEY -> {
                if (isLoginContainer()) {
                    buildLoginMountRequest(channel, props)
                } else {
                    buildRequestFromBundleKey(channel, props)
                }
            }
            else -> {
                val moduleName = intent.getStringExtra(EXTRA_MODULE_NAME).orEmpty()
                val pagePath = intent.getStringExtra(EXTRA_PAGE_PATH).orEmpty()
                val commonPath = intent.getStringExtra(EXTRA_COMMON_PATH)
                if (moduleName.isEmpty() || pagePath.isEmpty()) {
                    throw RNBundleMount.MountException("缺少 moduleName 或 pagePath")
                }
                RNBundleMount.Request(
                    moduleName = moduleName,
                    pageBundlePathOrUrl = pagePath,
                    commonBundlePathOrUrl = commonPath,
                    useSplitPageBundle = intent.getBooleanExtra(EXTRA_USE_SPLIT_PAGE, false),
                    initialProps = props,
                )
            }
        }
    }

    private fun buildLoginMountRequest(channel: String, props: Bundle): RNBundleMount.Request {
        val resolved = RNBundleResolver.resolve(
            context = this,
            channel = channel.ifBlank { RNAssetBundleHelper.readBuildChannel(this) },
            bundleKey = "login",
            cacheDir = File(cacheDir, "RNDynamicBundles"),
        )
        return RNBundleMount.Request(
            moduleName = resolved.pageItem.moduleName,
            pageBundlePathOrUrl = resolved.pageFile.absolutePath,
            commonBundlePathOrUrl = resolved.commonFile?.absolutePath,
            useSplitPageBundle = true,
            initialProps = props,
        )
    }

    private fun buildRequestFromBundleKey(channel: String, props: Bundle): RNBundleMount.Request {
        val bundleKey = intent.getStringExtra(EXTRA_BUNDLE_KEY).orEmpty()
        if (bundleKey.isEmpty()) {
            throw RNBundleMount.MountException("缺少 bundleKey")
        }
        val resolved = RNBundleResolver.resolve(
            context = this,
            channel = channel.ifBlank { RNAssetBundleHelper.readBuildChannel(this) },
            bundleKey = bundleKey,
            cacheDir = File(cacheDir, "RNDynamicBundles"),
        )
        return RNBundleMount.Request(
            moduleName = resolved.pageItem.moduleName,
            pageBundlePathOrUrl = resolved.pageFile.absolutePath,
            commonBundlePathOrUrl = resolved.commonFile?.absolutePath,
            useSplitPageBundle = true,
            initialProps = props,
        )
    }

    override fun onResume() {
        super.onResume()
        RNBundleMount.forwardOnHostResume(this)
    }

    override fun onPause() {
        RNBundleMount.forwardOnHostPause(this)
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        RNBundleMount.forwardOnActivityResult(this, requestCode, resultCode, data)
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        RNBundleMount.forwardOnHostDestroy(this)
        super.onDestroy()
    }

    /** RN 栈底无法再 pop 时由 RN 调用，关闭容器回到原生 */
    override fun invokeDefaultOnBackPressed() {
        finishToNative()
    }

    private fun isLoginContainer(): Boolean {
        return intent.getStringExtra(EXTRA_CONTAINER_KIND) == CONTAINER_KIND_LOGIN
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        const val EXTRA_CONTAINER_KIND = "rn_container_kind"
        const val CONTAINER_KIND_LOGIN = "login"
        const val CONTAINER_KIND_BUSINESS = "business"

        fun isLoginContainer(activity: Activity): Boolean {
            return activity is RNContainerActivity &&
                activity.intent.getStringExtra(EXTRA_CONTAINER_KIND) == CONTAINER_KIND_LOGIN
        }

        const val EXTRA_TITLE = "rn_container_title"
        const val EXTRA_MODE = "rn_container_mode"
        const val EXTRA_MODULE_NAME = "rn_container_module_name"
        const val EXTRA_PAGE_PATH = "rn_container_page_path"
        const val EXTRA_COMMON_PATH = "rn_container_common_path"
        const val EXTRA_BUNDLE_KEY = "rn_container_bundle_key"
        const val EXTRA_CHANNEL = "rn_container_channel"
        const val EXTRA_FROM_NATIVE = "rn_container_from_native"
        const val EXTRA_CONFIG_PATH = "rn_container_config_path"
        const val EXTRA_LOAD_DEPS = "rn_container_load_deps"
        const val EXTRA_USE_SPLIT_PAGE = "rn_container_use_split_page"
        const val EXTRA_PROPS = "rn_container_props"

        const val MODE_LOCAL_PATHS = "local_paths"
        const val MODE_BUNDLE_KEY = "bundle_key"

        fun intentForLocalPaths(
            context: Context,
            title: String,
            moduleName: String,
            pagePath: String,
            commonPath: String?,
            channel: String,
            fromNative: String = "debug-entry",
        ): Intent {
            return Intent(context, RNContainerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_LOCAL_PATHS)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MODULE_NAME, moduleName)
                putExtra(EXTRA_PAGE_PATH, pagePath)
                commonPath?.let { putExtra(EXTRA_COMMON_PATH, it) }
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_FROM_NATIVE, fromNative)
                putExtra(EXTRA_USE_SPLIT_PAGE, false)
            }
        }

        fun intentForBundleKey(
            context: Context,
            title: String,
            bundleKey: String,
            channel: String,
            configPath: String? = null,
            fromNative: String = "shell-entry",
        ): Intent {
            return Intent(context, RNContainerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_BUNDLE_KEY)
                putExtra(EXTRA_CONTAINER_KIND, CONTAINER_KIND_BUSINESS)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BUNDLE_KEY, bundleKey)
                putExtra(EXTRA_CHANNEL, channel)
                putExtra(EXTRA_FROM_NATIVE, fromNative)
                configPath?.let { putExtra(EXTRA_CONFIG_PATH, it) }
            }
        }

        /** 登录专用 RN 容器（内置 login 分包） */
        fun intentForLogin(
            context: Context,
            channel: String? = null,
            reason: String? = null,
        ): Intent {
            val resolvedChannel = channel ?: RNAssetBundleHelper.readBuildChannel(context)
            return Intent(context, RNContainerActivity::class.java).apply {
                putExtra(EXTRA_MODE, MODE_BUNDLE_KEY)
                putExtra(EXTRA_CONTAINER_KIND, CONTAINER_KIND_LOGIN)
                putExtra(EXTRA_TITLE, "登录")
                putExtra(EXTRA_BUNDLE_KEY, "login")
                putExtra(EXTRA_CHANNEL, resolvedChannel)
                putExtra(EXTRA_FROM_NATIVE, "auth-gate")
                putExtra(EXTRA_CONFIG_PATH, "assets")
                reason?.let { putExtra(AuthNavigator.EXTRA_LOGIN_REASON, it) }
            }
        }
    }
}
