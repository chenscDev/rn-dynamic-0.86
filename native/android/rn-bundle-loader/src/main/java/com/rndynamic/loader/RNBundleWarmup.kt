package com.rndynamic.loader

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.io.File

/**
 * 启动/壳就绪后的后台预热：走与正式打开相同的 [RNBundleResolver]
 *（含 revision → 远程配置 → hash 下载），避免只预载 APK 内旧包。
 */
object RNBundleWarmup {
    private const val TAG = "RNBundleWarmup"

    data class Options(
        /** 仅 Wi-Fi 时预热（蜂窝跳过，避免抢流量） */
        val wifiOnly: Boolean = false,
        /** 失败静默，不影响主流程 */
        val silent: Boolean = true,
    )

    /**
     * 预热 common（远程优先）。
     * 使用 key=common 走 resolver，会下载最新 common。
     */
    @JvmStatic
    fun warmupCommon(
        context: Context,
        channel: String,
        cacheDir: File = File(context.cacheDir, "RNDynamicBundles"),
        options: Options = Options(),
    ) {
        if (options.wifiOnly && !isWifiConnected(context)) {
            Log.i(TAG, "跳过 common 预热：非 Wi-Fi")
            return
        }
        try {
            val resolved = RNBundleResolver.resolve(
                context = context.applicationContext,
                channel = channel,
                bundleKey = "common",
                cacheDir = cacheDir,
            )
            Log.i(
                TAG,
                "common 预热完成 remote=${resolved.usedRemoteConfig}" +
                    " file=${resolved.pageFile.absolutePath}" +
                    " size=${resolved.pageFile.length()}",
            )
        } catch (error: Exception) {
            if (!options.silent) throw error
            Log.w(TAG, "common 预热失败: ${error.message}")
        }
    }

    /**
     * 预热指定业务 page（含其依赖的 common）。
     */
    @JvmStatic
    fun warmupPage(
        context: Context,
        channel: String,
        bundleKey: String,
        cacheDir: File = File(context.cacheDir, "RNDynamicBundles"),
        options: Options = Options(),
    ) {
        if (bundleKey.isBlank() || bundleKey == "common") {
            warmupCommon(context, channel, cacheDir, options)
            return
        }
        if (options.wifiOnly && !isWifiConnected(context)) {
            Log.i(TAG, "跳过 page 预热：$bundleKey（非 Wi-Fi）")
            return
        }
        try {
            val resolved = RNBundleResolver.resolve(
                context = context.applicationContext,
                channel = channel,
                bundleKey = bundleKey,
                cacheDir = cacheDir,
            )
            Log.i(
                TAG,
                "page 预热完成 key=$bundleKey remote=${resolved.usedRemoteConfig}" +
                    " page=${resolved.pageFile.length()}B" +
                    " common=${resolved.commonFile?.length() ?: 0}B",
            )
        } catch (error: Exception) {
            if (!options.silent) throw error
            Log.w(TAG, "page 预热失败 key=$bundleKey: ${error.message}")
        }
    }

    /**
     * 根据 Shell Tab 配置，预热所有 rn-root Tab 对应分包。
     */
    @JvmStatic
    fun warmupRnRootTabs(
        context: Context,
        channel: String,
        tabs: List<ShellTabConfig>,
        cacheDir: File = File(context.cacheDir, "RNDynamicBundles"),
        options: Options = Options(),
    ) {
        val keys = tabs
            .asSequence()
            .filter { it.visible && it.type == "rn-root" }
            .mapNotNull { tab ->
                tab.bundleKey?.trim()?.takeIf { it.isNotEmpty() } ?: tab.id
            }
            .distinct()
            .toList()
        if (keys.isEmpty()) {
            Log.i(TAG, "无 rn-root Tab，跳过 page 预热")
            return
        }
        Log.i(TAG, "预热 rn-root Tabs: ${keys.joinToString()}")
        // 先 common，再各 page（page resolve 也会拉 common，先预热可命中缓存）
        warmupCommon(context, channel, cacheDir, options)
        keys.forEach { key ->
            warmupPage(context, channel, key, cacheDir, options)
        }
    }

    private fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
        @Suppress("DEPRECATION")
        return cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
    }
}
