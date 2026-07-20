package com.rndynamic.loader

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

/**
 * RN 导航与原生容器交互（关闭容器、返回原生）
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
}
