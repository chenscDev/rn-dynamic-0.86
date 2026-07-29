package com.rndynamic.loader

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.concurrent.thread

/**
 * 整 Tab 挂载单个 RN 分包（type=rn-root）
 *
 * Debug 包：跟随全局悬浮「远程/本地」开关（默认远程 CDN）。
 * Release 包：始终走 CDN / 内置，无调试面板。
 */
class RNRootTabFragment : Fragment() {
    private lateinit var mountArea: FrameLayout
    private var bundleKey: String = ""
    private var channel: String = ""
    private var configPath: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        mountArea = FrameLayout(requireContext())
        bundleKey = requireArguments().getString(ARG_BUNDLE_KEY).orEmpty()
        channel = requireArguments().getString(ARG_CHANNEL).orEmpty()
        configPath = requireArguments().getString(ARG_CONFIG_PATH)
        startMount()
        return mountArea
    }

    private fun startMount() {
        mountArea.removeAllViews()
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // 内容层 + 顶层友好 loading：挂载完成前不拆掉 overlay，避免中间空白一闪
        val contentHost = FrameLayout(ctx).apply {
            setBackgroundColor(0xFFF5F5F5.toInt())
        }
        val statusText = TextView(ctx).apply {
            text = "正在打开…"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF111111.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val hintText = TextView(ctx).apply {
            text = "马上就好"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        val loadingOverlay = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFFF5F5F5.toInt())
            setPadding(dp(24), dp(24), dp(24), dp(24))
            elevation = dp(4).toFloat()
            addView(android.widget.ProgressBar(ctx))
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
            addView(hintText)
        }
        mountArea.addView(
            contentHost,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        mountArea.addView(
            loadingOverlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        thread(name = "RNRootTabMount") {
            val channelName = channel.ifBlank {
                RNAssetBundleHelper.readBuildChannel(requireContext())
            }
            val session = RNBundleLoadTrace.beginSession(bundleKey, channelName)
            try {
                activity?.runOnUiThread {
                    if (isAdded) {
                        statusText.text = "正在打开…"
                        hintText.text = "马上就好"
                    }
                }
                val request = buildRequest(bundleKey, channelName, configPath)
                if (!isAdded) return@thread
                val act = activity ?: return@thread
                RNBundleMount.mount(act, contentHost, request)
                val report = session.finish(act.applicationContext)
                RNLoadPerfHolder.lastReport = report
                RNBundleLoadTrace.clear()
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    // 轻淡出，避免生硬切换
                    loadingOverlay.animate()
                        .alpha(0f)
                        .setDuration(160L)
                        .withEndAction {
                            if (isAdded) {
                                mountArea.removeView(loadingOverlay)
                            }
                        }
                        .start()
                }
            } catch (error: Exception) {
                RNBundleLoadTrace.clear()
                Log.e(TAG, "RN Tab 挂载失败", error)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    showMountError(error)
                }
            }
        }
    }

    private fun showMountError(error: Exception) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // Release：只展示错误，不提供 Metro 调试面板
        if (!isDebuggable()) {
            val panel = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                addView(
                    TextView(ctx).apply {
                        text = "加载失败"
                        textSize = 16f
                        setPadding(0, 0, 0, dp(8))
                    },
                )
                addView(
                    TextView(ctx).apply {
                        text = error.message ?: "unknown"
                        textSize = 13f
                    },
                )
                addView(
                    Button(ctx).apply {
                        text = "重试"
                        setOnClickListener { startMount() }
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(16) },
                )
            }
            mountArea.removeAllViews()
            mountArea.addView(panel)
            return
        }

        val hostInput = EditText(ctx).apply {
            hint = "电脑局域网 IP，例如 10.157.20.204"
            setText(RnBundleSourcePrefs.metroHost(ctx))
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val retryMetro = Button(ctx).apply {
            text = "保存并重试本地 Metro"
            setOnClickListener {
                val host = hostInput.text?.toString()?.trim().orEmpty()
                if (host.isBlank()) {
                    hostInput.error = "请填写 IP"
                    return@setOnClickListener
                }
                RnBundleSourcePrefs.setSource(ctx, local = true, host = host)
                startMount()
            }
        }
        val useCdn = Button(ctx).apply {
            text = "改用远程 CDN"
            setOnClickListener {
                RnBundleSourcePrefs.setSource(ctx, local = false)
                startMount()
            }
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(
                TextView(ctx).apply {
                    text = "加载失败"
                    textSize = 16f
                    setPadding(0, 0, 0, dp(8))
                },
            )
            addView(
                TextView(ctx).apply {
                    text = buildString {
                        appendLine(error.message ?: "unknown")
                        appendLine()
                        appendLine("也可点右下角悬浮按钮切换「远程 / 本地」。")
                        append("远程自动使用 ${RnBundleSourcePrefs.cdnHost(ctx)}，无需本机 Metro。")
                    }
                    textSize = 13f
                    setPadding(0, 0, 0, dp(12))
                },
            )
            addView(
                hostInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                retryMetro,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
            addView(
                useCdn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
        mountArea.removeAllViews()
        mountArea.addView(panel)
    }

    private fun buildRequest(
        bundleKey: String,
        channel: String,
        configPath: String?,
    ): RNBundleMount.Request {
        // Release 或 Debug「远程」：CDN / 内置；仅 Debug「本地」走 Metro
        if (isDebuggable() && RnBundleSourcePrefs.isLocalMetro(requireContext())) {
            val host = RnBundleSourcePrefs.metroHost(requireContext())
            val port = RnBundleSourcePrefs.metroPort(requireContext())
            val pageUrl = RNBundleMount.metroPageUrl(host, port, bundleKey, "android")
            Log.i(TAG, "Debug Tab 本地 Metro host=$host port=$port key=$bundleKey")
            val props = Bundle().apply {
                putString("channel", channel)
                putString("metroHost", host)
            }
            return RNBundleMount.Request(
                moduleName = bundleKey,
                pageBundlePathOrUrl = pageUrl,
                commonBundlePathOrUrl = null,
                useSplitPageBundle = false,
                initialProps = props,
                jsMainModulePath = "src/$bundleKey/index",
            )
        }

        Log.i(TAG, "Tab CDN/内置加载 key=$bundleKey channel=$channel（远程配置优先，支持无感热更）")
        val cacheDir = File(requireContext().cacheDir, "RNDynamicBundles")
        val resolved = RNBundleResolver.resolve(
            context = requireContext(),
            channel = channel,
            bundleKey = bundleKey,
            cacheDir = cacheDir,
            platform = "android",
        )
        Log.i(
            TAG,
            "Tab resolve usedRemote=${resolved.usedRemoteConfig} " +
                "pageHash=${resolved.pageItem.hash} commonHash=${resolved.commonItem?.hash}",
        )
        // Tab 内嵌不传 fromNative，避免栈底出现「返回」关闭容器
        val props = Bundle().apply {
            putString("channel", channel)
        }
        return RNBundleMount.Request(
            moduleName = resolved.pageItem.moduleName,
            pageBundlePathOrUrl = resolved.pageFile.absolutePath,
            commonBundlePathOrUrl = resolved.commonFile?.absolutePath,
            useSplitPageBundle = true,
            initialProps = props,
        )
    }

    private fun isDebuggable(): Boolean {
        val flags = requireContext().applicationInfo.flags
        return flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    override fun onResume() {
        super.onResume()
        activity?.let { RNBundleMount.forwardOnHostResume(it) }
    }

    override fun onPause() {
        activity?.let { RNBundleMount.forwardOnHostPause(it) }
        super.onPause()
    }

    override fun onDestroyView() {
        activity?.let { RNBundleMount.forwardOnHostDestroy(it) }
        super.onDestroyView()
    }

    companion object {
        private const val TAG = "RNRootTabFragment"
        private const val ARG_BUNDLE_KEY = "arg_bundle_key"
        private const val ARG_CHANNEL = "arg_channel"
        private const val ARG_CONFIG_PATH = "arg_config_path"

        fun newInstance(
            bundleKey: String,
            channel: String,
            configPath: String? = null,
        ): RNRootTabFragment {
            return RNRootTabFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_BUNDLE_KEY, bundleKey)
                    putString(ARG_CHANNEL, channel)
                    configPath?.let { putString(ARG_CONFIG_PATH, it) }
                }
            }
        }
    }
}
