package com.rndynamic.loader

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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

        loadingView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFF5F5F5.toInt())
            addView(ProgressBar(this@RNContainerActivity))
            addView(
                TextView(this@RNContainerActivity).apply {
                    text = "正在加载…"
                    textSize = 15f
                    setTextColor(0xFF333333.toInt())
                    setPadding(0, dp(16), 0, 0)
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
            // 登录容器运行时失败时降级原生登录，避免一直白屏
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
                    // 优先 RN 栈内 pop；栈底时 RN 回调 invokeDefaultOnBackPressed → finishToNative
                    if (!RNBundleMount.forwardOnBackPressed(this@RNContainerActivity)) {
                        finishToNative()
                    }
                }
            },
        )

        thread(name = "RNContainerMount") {
            try {
                val request = buildMountRequest()
                if (isActivityDestroyed) return@thread
                RNBundleMount.mount(this, rnContainer, request)
                if (isActivityDestroyed) return@thread
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
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
        return (value * resources.displayMetrics.density).toInt()
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