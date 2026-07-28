package com.rndynamic.loader

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * 分包加载：远程配置优先，APK assets 内置兜底（支持无感热更）
 *
 * CDN-only 分包（未打入 APK）允许仅存在于远程配置中。
 */
object RNBundleResolver {
    data class ResolvedBundles(
        val pageFile: File,
        val commonFile: File?,
        val pageItem: RNBundleItem,
        val commonItem: RNBundleItem?,
        val usedRemoteConfig: Boolean,
    )

    /**
     * 解析 page + common 分包路径
     * 1. 读取 assets 内置配置作为兜底（可缺页）
     * 2. 尝试拉远程 bundles 配置并合并（远程 url/hash 优先）
     * 3. 分包文件：CDN/HTTP 优先，失败则读 APK assets
     */
    @Throws(RNBundleCacheException::class, RNBundleConfigException::class)
    fun resolve(
        context: Context,
        channel: String,
        bundleKey: String,
        cacheDir: File,
        platform: String = "android",
    ): ResolvedBundles {
        val trace = RNBundleLoadTrace.current()
        trace?.begin("config:assets", "读取 APK / 本地配置")
        val assetsConfigFile = RNAssetBundleHelper.ensureChannelConfig(
            context = context,
            channel = channel,
            forceFromAssets = false,
        )
        val assetsStore = RNBundleConfigStore(assetsConfigFile, channel)
        // 业务入口可能仅 CDN 下发，APK 内不必有该 key
        val assetsPage = assetsStore.findItem(bundleKey, platform = platform)
        val assetsCommon = assetsStore.findItem("common", platform = platform)
        trace?.end(
            detail = if (assetsPage != null) {
                "内置含 $bundleKey"
            } else {
                "内置无 $bundleKey（将走远程）"
            },
        )

        val remoteSettings = RNBundleRemoteSettingsStore.load(context, channel)
        var usedRemote = false
        var pageItem: RNBundleItem? = assetsPage
        var commonItem: RNBundleItem? = assetsCommon

        if (remoteSettings.isUsable()) {
            try {
                trace?.begin("config:remote", remoteSettings.baseUrl)
                val remoteStore = RNBundleRemoteConfigStore(
                    configBaseUrl = remoteSettings.baseUrl,
                    rnVersion = remoteSettings.rnVersion,
                    channelInput = channel,
                    cacheDir = cacheDir,
                    appContext = context.applicationContext,
                )
                val remoteConfig = remoteStore.load(forceReload = false)
                remoteConfig.bundles[bundleKey]
                    ?.firstOrNull { it.platform == platform }
                    ?.let { remotePage ->
                        pageItem = mergeItem(remotePage, assetsPage)
                        usedRemote = true
                    }
                remoteConfig.bundles["common"]
                    ?.firstOrNull { it.platform == platform }
                    ?.let { remoteCommon ->
                        commonItem = mergeItem(remoteCommon, assetsCommon)
                        usedRemote = true
                    }
                trace?.end(detail = if (usedRemote) "已合并远程 url/hash" else "远程无对应项")
                trace?.usedRemote = usedRemote
            } catch (error: Exception) {
                trace?.end(detail = "远程失败: ${error.message}")
            }
        } else {
            trace?.note("config:remote", "未启用远程", 0L)
        }

        val resolvedPage = pageItem
            ?: throw RNBundleConfigException(
                "未找到分包 key: $bundleKey（APK 未内置且远程配置也没有；请发布该分包到 CDN 或嵌入 APK）",
            )
        val resolvedCommon = commonItem

        val cache = RNBundleCache(cacheDir)
        val assetOpener: (String) -> InputStream = { path ->
            context.assets.open(path)
        }

        val commonFile = resolvedCommon?.let { item ->
            cache.resolveBundleFile(
                item = item,
                assetOpener = assetOpener,
                preferRemote = true,
                assetsFallbackItem = assetsCommon,
            )
        }
        val pageFile = cache.resolveBundleFile(
            item = resolvedPage,
            assetOpener = assetOpener,
            preferRemote = true,
            assetsFallbackItem = assetsPage,
        )

        return ResolvedBundles(
            pageFile = pageFile,
            commonFile = commonFile,
            pageItem = resolvedPage,
            commonItem = resolvedCommon,
            usedRemoteConfig = usedRemote,
        )
    }

    /** 远程项优先；assets 可为 null（纯 CDN 分包） */
    private fun mergeItem(remote: RNBundleItem, assets: RNBundleItem?): RNBundleItem {
        if (assets == null) {
            return remote
        }
        val remoteAssets = remote.assetsUrl?.trim().orEmpty()
        val assetsAssets = assets.assetsUrl?.trim().orEmpty()
        val mergedAssetsUrl = when {
            remoteAssets.startsWith("http://") || remoteAssets.startsWith("https://") -> remoteAssets
            assetsAssets.isNotEmpty() -> assetsAssets
            else -> remoteAssets.ifEmpty { null }
        }
        return remote.copy(
            assetsUrl = mergedAssetsUrl,
            bytecodeUrl = remote.bytecodeUrl ?: assets.bytecodeUrl,
            sizeBytes = remote.sizeBytes ?: assets.sizeBytes,
            name = remote.name ?: assets.name,
            componentName = remote.componentName ?: assets.componentName,
        )
    }
}
