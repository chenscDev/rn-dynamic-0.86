package com.rndynamic.loader

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.io.File
import kotlin.concurrent.thread

/**
 * 整 Tab 挂载单个 RN 分包（type=rn-root）
 */
class RNRootTabFragment : Fragment() {
    private lateinit var mountArea: FrameLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        mountArea = FrameLayout(requireContext())
        val loading = ProgressBar(requireContext())
        mountArea.addView(loading)

        val bundleKey = requireArguments().getString(ARG_BUNDLE_KEY).orEmpty()
        val channel = requireArguments().getString(ARG_CHANNEL).orEmpty()
        val configPath = requireArguments().getString(ARG_CONFIG_PATH)

        thread(name = "RNRootTabMount") {
            try {
                val intent = android.content.Intent().apply {
                    putExtra(RNContainerActivity.EXTRA_MODE, RNContainerActivity.MODE_BUNDLE_KEY)
                    putExtra(RNContainerActivity.EXTRA_BUNDLE_KEY, bundleKey)
                    putExtra(RNContainerActivity.EXTRA_CHANNEL, channel)
                    configPath?.let { putExtra(RNContainerActivity.EXTRA_CONFIG_PATH, it) }
                }
                val request = buildRequestFromIntent(intent)
                activity?.runOnUiThread { mountArea.removeAllViews() }
                activity?.let {
                    RNBundleMount.mount(it, mountArea, request)
                }
            } catch (error: Exception) {
                activity?.runOnUiThread {
                    mountArea.removeAllViews()
                    mountArea.addView(
                        TextView(requireContext()).apply {
                            text = "加载失败: ${error.message}"
                            setPadding(48, 48, 48, 48)
                        },
                    )
                }
            }
        }
        return mountArea
    }

    private fun buildRequestFromIntent(intent: android.content.Intent): RNBundleMount.Request {
        val bundleKey = intent.getStringExtra(RNContainerActivity.EXTRA_BUNDLE_KEY).orEmpty()
        val channel = intent.getStringExtra(RNContainerActivity.EXTRA_CHANNEL).orEmpty()
        val configPath = intent.getStringExtra(RNContainerActivity.EXTRA_CONFIG_PATH)
            ?: requireContext().filesDir.resolve("rn-config").absolutePath

        val store = RNBundleConfigStore(File(configPath), channel)
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
        val props = Bundle().apply {
            putString("fromNative", "shell-rn-root")
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
