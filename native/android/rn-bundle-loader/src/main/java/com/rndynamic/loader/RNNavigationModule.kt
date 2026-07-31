package com.rndynamic.loader

import android.os.Bundle
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import com.rndynamicbase.MainShellActivity

/**
 * RN 导航与原生容器交互（关闭容器、切 Tab、打开业务分包）
 */
class RNNavigationModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "RNNavigationModule"

    /** RN 栈底且来自原生入口时，关闭容器回到原生 */
    @ReactMethod
    fun finishContainer() {
        val activity = reactContext.currentActivity ?: return
        activity.runOnUiThread {
            if (activity is RNContainerActivity) {
                activity.finishToNative()
            } else {
                activity.finish()
            }
        }
    }

    /**
     * 切换底部 Shell Tab（如 biz / chat / home / mine）。
     * 当前不在 MainShell 时静默忽略。
     */
    @ReactMethod
    fun switchTab(tabId: String) {
        val activity = reactContext.currentActivity ?: return
        val id = tabId.trim()
        if (id.isEmpty()) {
            return
        }
        activity.runOnUiThread {
            val shell = activity as? MainShellActivity ?: return@runOnUiThread
            shell.switchToTab(id)
        }
    }

    /**
     * 打开业务 RN 分包，并可带 initialProps（落到首屏 route.params）。
     *
     * props 可为空；仅支持扁平基本类型（string / number / boolean）。
     * 注意：TurboModule 不允许同名 @ReactMethod 重载，故只保留三参数签名。
     */
    @ReactMethod
    fun openBundle(bundleKey: String, title: String?, props: ReadableMap?) {
        val activity = reactContext.currentActivity ?: return
        val key = bundleKey.trim()
        if (key.isEmpty()) {
            return
        }
        val channel = RNAssetBundleHelper.readBuildChannel(activity)
        val initialProps = readableMapToStringBundle(props)
        activity.runOnUiThread {
            try {
                // 从问答 Tab 一键出片时，记住回到聊天
                AuthNavigator.saveResumeFromShell("chat")
            } catch (_: Exception) {
                // ignore
            }
            val intent =
                RNContainerActivity.intentForBundleKey(
                    context = activity,
                    title = (title ?: key).ifBlank { key },
                    bundleKey = key,
                    channel = channel,
                    configPath = "assets",
                    fromNative = "rn-open-bundle",
                    initialProps = initialProps,
                )
            activity.startActivity(intent)
        }
    }

    /**
     * ReadableMap → Bundle。嵌套 Map 会再展平一层；复杂对象请用 JSON 字符串。
     */
    private fun readableMapToStringBundle(map: ReadableMap?): Bundle? {
        if (map == null) {
            return null
        }
        return try {
            Arguments.toBundle(map)
        } catch (_: Exception) {
            null
        }
    }
}
