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
                    appContext = context.applicationContext,
                )
                // 按 CDN revision 决定：有变更才拉全量配置，否则走本地缓存
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

    /** 远程项优先 url/hash/bytecode；HTTP assetsUrl 用于资源同步，APK 路径由 assetsFallbackItem 兜底 */
    private fun mergeItem(remote: RNBundleItem, assets: RNBundleItem): RNBundleItem {
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
