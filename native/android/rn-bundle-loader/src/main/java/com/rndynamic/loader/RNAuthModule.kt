package com.rndynamic.loader

import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.uimanager.ViewManager

class RNHostPackage : ReactPackage {
    override fun createNativeModules(reactContext: ReactApplicationContext): List<NativeModule> {
        return listOf(RNAuthModule(reactContext), RNNavigationModule(reactContext))
    }

    override fun createViewManagers(
        reactContext: ReactApplicationContext,
    ): List<ViewManager<*, *>> = emptyList()
}

/**
 * RN 与原生登录态桥接
 */
class RNAuthModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "RNAuthModule"

    @ReactMethod
    fun getAuthHeaders(promise: Promise) {
        try {
            val map = WritableNativeMap()
            AuthSession.authHeaders().forEach { (k, v) -> map.putString(k, v) }
            promise.resolve(map)
        } catch (error: Exception) {
            promise.reject("AUTH_ERROR", error.message, error)
        }
    }

    @ReactMethod
    fun getSession(promise: Promise) {
        try {
            promise.resolve(buildSessionMap())
        } catch (error: Exception) {
            promise.reject("AUTH_ERROR", error.message, error)
        }
    }

    @ReactMethod
    fun getLoginReason(promise: Promise) {
        try {
            val activity = reactContext.currentActivity
            val reason = activity?.intent?.getStringExtra(AuthNavigator.EXTRA_LOGIN_REASON)
            promise.resolve(reason)
        } catch (error: Exception) {
            promise.reject("AUTH_ERROR", error.message, error)
        }
    }

    @ReactMethod
    fun login(username: String, password: String, promise: Promise) {
        Thread {
            try {
                val result = MockApiService.login(username, password)
                AuthSession.saveLogin(result.token, result.userId, result.nickname)
                val activity = reactContext.currentActivity
                activity?.runOnUiThread {
                    AuthNavigator.navigateAfterLogin(activity)
                    activity.finish()
                }
                promise.resolve(buildSessionMap())
            } catch (error: MockApiService.ApiException) {
                if (error.code == 401) {
                    promise.reject("UNAUTHORIZED", error.message)
                } else {
                    promise.reject("API_ERROR", error.message)
                }
            } catch (error: Exception) {
                promise.reject("API_ERROR", error.message, error)
            }
        }.start()
    }

    @ReactMethod
    fun changePassword(oldPassword: String, newPassword: String, promise: Promise) {
        Thread {
            try {
                MockApiService.changePassword(oldPassword, newPassword)
                promise.resolve(null)
            } catch (error: MockApiService.ApiException) {
                promise.reject("API_ERROR", error.message)
            } catch (error: Exception) {
                promise.reject("API_ERROR", error.message, error)
            }
        }.start()
    }

    @ReactMethod
    fun completeLoginNavigation(promise: Promise) {
        try {
            val activity = reactContext.currentActivity
            if (activity != null && AuthSession.isLoggedIn()) {
                activity.runOnUiThread {
                    AuthNavigator.navigateAfterLogin(activity)
                    activity.finish()
                }
            }
            promise.resolve(null)
        } catch (error: Exception) {
            promise.reject("AUTH_ERROR", error.message, error)
        }
    }

    @ReactMethod
    fun openLogin(reason: String?) {
        val activity = reactContext.currentActivity ?: return
        if (RNContainerActivity.isLoginContainer(activity)) {
            return
        }
        AuthNavigator.saveResumeFromRnContainer(
            bundleKey = activity.intent?.getStringExtra(RNContainerActivity.EXTRA_BUNDLE_KEY),
            bundleTitle = activity.intent?.getStringExtra(RNContainerActivity.EXTRA_TITLE),
        )
        activity.runOnUiThread {
            AuthNavigator.openLogin(activity, reason ?: "rn_request")
        }
    }

    /** Mock 业务接口（RN 侧 authFetch 可调用） */
    @ReactMethod
    fun mockRequest(path: String, promise: Promise) {
        Thread {
            try {
                val data = MockApiService.fetchBizData(path, AuthSession.authHeaders())
                val map = WritableNativeMap()
                data.keys().forEach { key ->
                    when (val value = data.get(key)) {
                        is String -> map.putString(key, value)
                        is Int -> map.putInt(key, value)
                        is Long -> map.putDouble(key, value.toDouble())
                        is Double -> map.putDouble(key, value)
                        is Boolean -> map.putBoolean(key, value)
                        else -> map.putString(key, value?.toString())
                    }
                }
                promise.resolve(map)
            } catch (error: MockApiService.ApiException) {
                if (error.code == 401) {
                    promise.reject("UNAUTHORIZED", error.message)
                } else {
                    promise.reject("API_ERROR", error.message)
                }
            } catch (error: Exception) {
                promise.reject("API_ERROR", error.message, error)
            }
        }.start()
    }

    private fun buildSessionMap(): WritableNativeMap {
        return WritableNativeMap().apply {
            putBoolean("isLoggedIn", AuthSession.isLoggedIn())
            putString("token", AuthSession.getToken())
            putString("userId", AuthSession.getUserId())
            putString("nickname", AuthSession.getNickname())
        }
    }
}
