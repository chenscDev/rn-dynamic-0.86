package com.rndynamic.loader

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * 分包加载：远程配置优先，APK assets 内置兜底（支持无感热更）
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
     * 1. 读取 assets 内置配置作为兜底
     * 2. 尝试拉远程 bundles 配置并合并（远程 url/hash 优先，保留 assetsUrl）
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
        val assetsConfigFile = RNAssetBundleHelper.ensureChannelConfig(
            context = context,
            channel = channel,
            forceFromAssets = false,
        )
        val assetsStore = RNBundleConfigStore(assetsConfigFile, channel)
        val assetsPage = assetsStore.item(bundleKey, platform = platform)
        val assetsCommon = runCatching {
            assetsStore.item("common", platform = platform)
        }.getOrNull()

        val remoteSettings = RNBundleRemoteSettingsStore.load(context, channel)
        var usedRemote = false
        var pageItem = assetsPage
        var commonItem = assetsCommon

        if (remoteSettings.isUsable()) {
            try {
                val remoteStore = RNBundleRemoteConfigStore(
                    configBaseUrl = remoteSettings.baseUrl,
                    rnVersion = remoteSettings.rnVersion,
                    channelInput = channel,
                    cacheDir = cacheDir,
                )
                val remoteConfig = remoteStore.load(forceReload = true)
                remoteConfig.bundles[bundleKey]
                    ?.firstOrNull { it.platform == platform }
                    ?.let { remotePage ->
                        pageItem = mergeItem(remotePage, assetsPage)
                        usedRemote = true
                    }
                remoteConfig.bundles["common"]
                    ?.firstOrNull { it.platform == platform }
                    ?.let { remoteCommon ->
                        commonItem = mergeItem(remoteCommon, assetsCommon ?: remoteCommon)
                        usedRemote = true
                    }
            } catch (_: Exception) {
                // 远程不可用，继续使用 assets 配置
            }
        }

        val cache = RNBundleCache(cacheDir)
        val assetOpener: (String) -> InputStream = { path ->
            context.assets.open(path)
        }

        val commonFile = commonItem?.let { item ->
            cache.resolveBundleFile(
                item = item,
                assetOpener = assetOpener,
                preferRemote = true,
                assetsFallbackItem = assetsCommon,
            )
        }
        val pageFile = cache.resolveBundleFile(
            item = pageItem,
            assetOpener = assetOpener,
            preferRemote = true,
            assetsFallbackItem = assetsPage,
        )

        return ResolvedBundles(
            pageFile = pageFile,
            commonFile = commonFile,
            pageItem = pageItem,
            commonItem = commonItem,
            usedRemoteConfig = usedRemote,
        )
    }

    /** 远程项优先 url/hash，保留 assets 内置路径作降级 */
    private fun mergeItem(remote: RNBundleItem, assets: RNBundleItem): RNBundleItem {
        return remote.copy(
            assetsUrl = assets.assetsUrl ?: remote.assetsUrl,
            name = remote.name ?: assets.name,
            componentName = remote.componentName ?: assets.componentName,
        )
    }
}
