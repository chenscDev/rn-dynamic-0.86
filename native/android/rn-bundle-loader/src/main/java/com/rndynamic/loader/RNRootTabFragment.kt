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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.concurrent.thread

/**
 * 整 Tab 挂载单个 RN 分包（type=rn-root）
 *
 * Debug 包：直接连 Metro（点 Tab 即可），Host 解析顺序：
 * 1) 调试入口已保存的 IP（可选覆盖）
 * 2) assets/rn-config/metro-host.txt 默认值
 * 3) 127.0.0.1（adb reverse 场景）
 *
 * Release 包：走内置/缓存 bundle。
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
        mountArea.addView(ProgressBar(requireContext()))

        thread(name = "RNRootTabMount") {
            try {
                val request = buildRequest(bundleKey, channel, configPath)
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    mountArea.removeAllViews()
                }
                activity?.let {
                    RNBundleMount.mount(it, mountArea, request)
                }
            } catch (error: Exception) {
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

        val hostInput = EditText(ctx).apply {
            hint = "电脑局域网 IP，例如 10.157.20.204"
            setText(readMetroHostCandidates().firstOrNull().orEmpty())
            setSingleLine()
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val retry = Button(ctx).apply {
            text = "保存并重试"
            setOnClickListener {
                val host = hostInput.text?.toString()?.trim().orEmpty()
                if (host.isBlank()) {
                    hostInput.error = "请填写 IP"
                    return@setOnClickListener
                }
                saveMetroHost(host)
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
                        appendLine("请确认 rn-biz 已 yarn start，手机与电脑同一网络。")
                        append("可在下方修改电脑 IP 后重试（无需再去调试入口）。")
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
                retry,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
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
        if (isDebuggable()) {
            val host = resolveMetroHost()
            val port = readSavedPort()
            val pageUrl = RNBundleMount.metroPageUrl(host, port, bundleKey, "android")
            Log.i(TAG, "Debug Tab 直连 Metro host=$host port=$port key=$bundleKey")
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

        val resolvedConfigPath = configPath
            ?: requireContext().filesDir.resolve("rn-config").absolutePath
        val store = RNBundleConfigStore(File(resolvedConfigPath), channel)
        val pageItem = store.item(bundleKey, platform = "android")
        val cache = RNBundleCache(File(requireContext().cacheDir, "RNDynamicBundles"))
        var commonPath: String? = null
        for (dep in pageItem.dependencyKeys) {
            val depItem = store.item(dep, platform = "android")
            val file = cache.resolveBundleFile(depItem)
            if (dep == "common") {
                commonPath = file.absolutePath
            }
        }
        val pageFile = cache.resolveBundleFile(pageItem)
        // Tab 内嵌不传 fromNative，避免栈底出现「返回」关闭容器
        val props = Bundle().apply {
            putString("channel", channel)
        }
        return RNBundleMount.Request(
            moduleName = pageItem.moduleName,
            pageBundlePathOrUrl = pageFile.absolutePath,
            commonBundlePathOrUrl = commonPath,
            useSplitPageBundle = true,
            initialProps = props,
        )
    }

    private fun isDebuggable(): Boolean {
        val flags = requireContext().applicationInfo.flags
        return flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /** Debug：prefs → assets 默认 → 127.0.0.1 */
    private fun resolveMetroHost(): String {
        return readMetroHostCandidates().first()
    }

    private fun readMetroHostCandidates(): List<String> {
        val list = linkedSetOf<String>()
        val prefs = requireContext().getSharedPreferences(PREFS_DEBUG_ENTRY, Context.MODE_PRIVATE)
        prefs.getString(KEY_HOST, null)?.trim()?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        readAssetMetroHost()?.let { list.add(it) }
        list.add("127.0.0.1")
        return list.toList()
    }

    private fun readAssetMetroHost(): String? {
        return try {
            requireContext().assets.open(ASSET_METRO_HOST).bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readSavedPort(): Int {
        val prefs = requireContext().getSharedPreferences(PREFS_DEBUG_ENTRY, Context.MODE_PRIVATE)
        return prefs.getString(KEY_PORT, "8081")?.toIntOrNull() ?: 8081
    }

    private fun saveMetroHost(host: String) {
        requireContext()
            .getSharedPreferences(PREFS_DEBUG_ENTRY, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, "8081")
            .putBoolean(KEY_DEV, true)
            .apply()
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
        private const val PREFS_DEBUG_ENTRY = "rn_debug_entry"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_DEV = "dev"
        private const val ASSET_METRO_HOST = "rn-config/metro-host.txt"

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
