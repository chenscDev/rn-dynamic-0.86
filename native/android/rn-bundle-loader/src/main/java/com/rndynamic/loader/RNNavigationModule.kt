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
     * 显示/隐藏底部原生 Tab（全屏发布页用）。
     */
    @ReactMethod
    fun setTabBarVisible(visible: Boolean) {
        val activity = reactContext.currentActivity ?: return
        activity.runOnUiThread {
            val shell = activity as? MainShellActivity ?: return@runOnUiThread
            shell.setBottomBarVisible(visible)
        }
    }

    /**
     * 状态栏显隐。
     */
    @ReactMethod
    fun setStatusBarVisible(visible: Boolean) {
        val activity = reactContext.currentActivity ?: return
        activity.runOnUiThread {
            NativeChromeHelper.applyStatusBar(activity, visible = visible)
            (activity as? MainShellActivity)?.applyStatusBarChrome(visible = visible)
        }
    }

    /**
     * 状态栏颜色与图标深浅。
     * @param backgroundColor #RRGGBB / #AARRGGBB；空串忽略
     * @param lightContent true=浅色图标（深色底）
     */
    @ReactMethod
    fun setStatusBarStyle(backgroundColor: String?, lightContent: Boolean) {
        val activity = reactContext.currentActivity ?: return
        val bg = NativeChromeHelper.parseColorOrNull(backgroundColor)
        activity.runOnUiThread {
            NativeChromeHelper.applyStatusBar(
                activity = activity,
                backgroundColor = bg,
                lightContent = lightContent,
            )
        }
    }

    /**
     * 原生标题栏文案与背景。
     * @param title 标题；传 null 不改文案
     * @param backgroundColor 背景色；空串忽略
     * @param textColor 文字色；空串忽略
     */
    @ReactMethod
    fun setNativeTitle(title: String?, backgroundColor: String?, textColor: String?) {
        val activity = reactContext.currentActivity ?: return
        val bg = NativeChromeHelper.parseColorOrNull(backgroundColor)
        val fg = NativeChromeHelper.parseColorOrNull(textColor)
        activity.runOnUiThread {
            val shell = activity as? MainShellActivity ?: return@runOnUiThread
            shell.applyNativeTitle(
                title = title,
                backgroundColor = bg,
                textColor = fg,
                visible = if (title != null && title.isNotBlank()) true else null,
            )
        }
    }

    /** 显示/隐藏原生标题栏 */
    @ReactMethod
    fun setNativeTitleVisible(visible: Boolean) {
        val activity = reactContext.currentActivity ?: return
        activity.runOnUiThread {
            val shell = activity as? MainShellActivity ?: return@runOnUiThread
            shell.applyNativeTitle(visible = visible)
        }
    }

    /**
     * 一次性配置原生 Chrome（推荐）。
     * keys: tabBarVisible, statusBarVisible, statusBarBackgroundColor,
     * statusBarLightContent, title, titleVisible, titleBackgroundColor, titleTextColor
     */
    @ReactMethod
    fun setChrome(options: ReadableMap?) {
        if (options == null) {
            return
        }
        val activity = reactContext.currentActivity ?: return
        activity.runOnUiThread {
            val shell = activity as? MainShellActivity
            if (options.hasKey("tabBarVisible") && !options.isNull("tabBarVisible")) {
                shell?.setBottomBarVisible(options.getBoolean("tabBarVisible"))
            }
            if (options.hasKey("titleVisible") && !options.isNull("titleVisible")) {
                shell?.applyNativeTitle(visible = options.getBoolean("titleVisible"))
            }
            val title =
                if (options.hasKey("title") && !options.isNull("title")) {
                    options.getString("title")
                } else {
                    null
                }
            val titleBg =
                NativeChromeHelper.parseColorOrNull(
                    if (options.hasKey("titleBackgroundColor")) {
                        options.getString("titleBackgroundColor")
                    } else {
                        null
                    },
                )
            val titleFg =
                NativeChromeHelper.parseColorOrNull(
                    if (options.hasKey("titleTextColor")) {
                        options.getString("titleTextColor")
                    } else {
                        null
                    },
                )
            if (title != null || titleBg != null || titleFg != null) {
                shell?.applyNativeTitle(
                    title = title,
                    backgroundColor = titleBg,
                    textColor = titleFg,
                    visible =
                        when {
                            options.hasKey("titleVisible") && !options.isNull("titleVisible") ->
                                options.getBoolean("titleVisible")
                            title != null && title.isNotBlank() -> true
                            else -> null
                        },
                )
            }

            val statusVisible =
                if (options.hasKey("statusBarVisible") && !options.isNull("statusBarVisible")) {
                    options.getBoolean("statusBarVisible")
                } else {
                    null
                }
            val statusBg =
                NativeChromeHelper.parseColorOrNull(
                    if (options.hasKey("statusBarBackgroundColor")) {
                        options.getString("statusBarBackgroundColor")
                    } else {
                        null
                    },
                )
            val lightContent =
                if (options.hasKey("statusBarLightContent") &&
                    !options.isNull("statusBarLightContent")
                ) {
                    options.getBoolean("statusBarLightContent")
                } else {
                    null
                }
            if (statusVisible != null || statusBg != null || lightContent != null) {
                NativeChromeHelper.applyStatusBar(
                    activity = activity,
                    visible = statusVisible,
                    backgroundColor = statusBg,
                    lightContent = lightContent,
                )
            }
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
                // 记住当前 Shell Tab，登录后回到来源而非写死 chat
                val shell = activity as? MainShellActivity
                val resumeTab = shell?.currentShellTabId()?.trim().orEmpty()
                if (resumeTab.isNotEmpty()) {
                    AuthNavigator.saveResumeFromShell(resumeTab)
                }
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
