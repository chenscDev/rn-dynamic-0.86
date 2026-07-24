package com.rndynamic.loader

import android.content.Context

/**
 * RN 分包资源加载来源（Debug 悬浮开关与调试入口共用）
 *
 * - REMOTE：走 CDN / 内置（bundles.local.json 中的 http 或 embedded）
 * - LOCAL：走本机 Metro（需填写电脑局域网 IP）
 *
 * Release 包不读此配置，始终 REMOTE。
 */
object RnBundleSourcePrefs {
    const val PREFS_NAME = "rn_debug_entry"

    const val KEY_HOST = "host"
    const val KEY_PORT = "port"
    const val KEY_DEV = "dev"
    /** 显式来源：remote / local；缺省时回退 KEY_DEV（兼容旧版） */
    const val KEY_SOURCE = "bundle_source"

    const val SOURCE_REMOTE = "remote"
    const val SOURCE_LOCAL = "local"

    private const val ASSET_CDN_HOST = "rn-config/cdn-host.txt"
    private const val ASSET_METRO_HOST = "rn-config/metro-host.txt"
    private const val DEFAULT_CDN_HOST = "47.93.207.70"
    private const val DEFAULT_METRO_PORT = "8081"

    fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Debug：是否走本地 Metro；默认远程（无需本机 yarn start） */
    fun isLocalMetro(context: Context): Boolean {
        val p = prefs(context)
        val source = p.getString(KEY_SOURCE, null)
        if (source == SOURCE_LOCAL) return true
        if (source == SOURCE_REMOTE) return false
        // 兼容旧键：曾默认 true；新装无 KEY_SOURCE 时按远程
        return if (p.contains(KEY_SOURCE)) {
            false
        } else {
            p.getBoolean(KEY_DEV, false)
        }
    }

    fun setSource(context: Context, local: Boolean, host: String? = null, port: String? = null) {
        val editor = prefs(context).edit()
            .putString(KEY_SOURCE, if (local) SOURCE_LOCAL else SOURCE_REMOTE)
            .putBoolean(KEY_DEV, local)
        if (local) {
            val h = host?.trim().orEmpty()
            if (h.isNotBlank()) {
                editor.putString(KEY_HOST, h)
            }
            editor.putString(KEY_PORT, port?.trim()?.ifBlank { DEFAULT_METRO_PORT } ?: DEFAULT_METRO_PORT)
        }
        editor.apply()
    }

    fun metroHost(context: Context): String {
        val saved = prefs(context).getString(KEY_HOST, null)?.trim().orEmpty()
        if (saved.isNotBlank()) return saved
        return readAssetFirstLine(context, ASSET_METRO_HOST) ?: "127.0.0.1"
    }

    fun metroPort(context: Context): Int {
        return prefs(context).getString(KEY_PORT, DEFAULT_METRO_PORT)?.toIntOrNull() ?: 8081
    }

    /** 远程 CDN 主机（仅展示 / 文档用；实际 URL 以 bundles.local.json 为准） */
    fun cdnHost(context: Context): String {
        return readAssetFirstLine(context, ASSET_CDN_HOST) ?: DEFAULT_CDN_HOST
    }

    private fun readAssetFirstLine(context: Context, assetPath: String): String? {
        return try {
            context.assets.open(assetPath).bufferedReader().use { reader ->
                reader.lineSequence()
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            }
        } catch (_: Exception) {
            null
        }
    }
}
