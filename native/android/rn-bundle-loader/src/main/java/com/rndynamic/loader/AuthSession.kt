package com.rndynamic.loader

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * 登录态本地存储（Mock 环境使用 SharedPreferences，正式可换 EncryptedSharedPreferences）
 */
object AuthSession {
    private const val PREFS = "rndynamic_auth"
    private const val KEY_TOKEN = "token"
    private const val KEY_USER_ID = "userId"
    private const val KEY_NICKNAME = "nickname"
    private const val KEY_RESUME_JSON = "resume_route"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences {
        val ctx = appContext
            ?: throw IllegalStateException("AuthSession 未初始化")
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun isLoggedIn(): Boolean {
        return !getToken().isNullOrBlank()
    }

    fun getToken(): String? {
        return prefs().getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
    }

    fun getUserId(): String? = prefs().getString(KEY_USER_ID, null)

    fun getNickname(): String? = prefs().getString(KEY_NICKNAME, null)

    fun saveLogin(token: String, userId: String, nickname: String) {
        prefs().edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_NICKNAME, nickname)
            .apply()
    }

    fun clearLogin() {
        prefs().edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_NICKNAME)
            .apply()
    }

    /** 写入 RN initialProps / 桥接 */
    fun appendAuthProps(props: android.os.Bundle) {
        props.putString("authToken", getToken())
        props.putString("userId", getUserId())
        props.putString("nickname", getNickname())
        props.putBoolean("isLoggedIn", isLoggedIn())
    }

    fun authHeaders(): Map<String, String> {
        val token = getToken() ?: return emptyMap()
        return mapOf(
            "Authorization" to "Bearer $token",
            "X-User-Id" to (getUserId() ?: ""),
        )
    }

    data class ResumeRoute(
        val type: String,
        val tabId: String? = null,
        val bundleKey: String? = null,
        val bundleTitle: String? = null,
    )

    fun saveResumeRoute(route: ResumeRoute) {
        val json = JSONObject()
            .put("type", route.type)
            .put("tabId", route.tabId)
            .put("bundleKey", route.bundleKey)
            .put("bundleTitle", route.bundleTitle)
        prefs().edit().putString(KEY_RESUME_JSON, json.toString()).apply()
    }

    fun consumeResumeRoute(): ResumeRoute? {
        val raw = prefs().getString(KEY_RESUME_JSON, null) ?: return null
        prefs().edit().remove(KEY_RESUME_JSON).apply()
        return try {
            val json = JSONObject(raw)
            ResumeRoute(
                type = json.optString("type", "tab"),
                tabId = json.optString("tabId", null),
                bundleKey = json.optString("bundleKey", null),
                bundleTitle = json.optString("bundleTitle", null),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun peekResumeRoute(): ResumeRoute? {
        val raw = prefs().getString(KEY_RESUME_JSON, null) ?: return null
        return try {
            val json = JSONObject(raw)
            ResumeRoute(
                type = json.optString("type", "tab"),
                tabId = json.optString("tabId", null),
                bundleKey = json.optString("bundleKey", null),
                bundleTitle = json.optString("bundleTitle", null),
            )
        } catch (_: Exception) {
            null
        }
    }
}
