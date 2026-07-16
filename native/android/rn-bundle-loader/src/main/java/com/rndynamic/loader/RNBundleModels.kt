package com.rndynamic.loader

/**
 * 单个分包配置（与 config/bundles.local.json 对齐）
 */
data class RNBundleItem(
    val key: String,
    val name: String? = null,
    val componentName: String? = null,
    val url: String,
    val hash: String,
    val platform: String,
    val localPath: String? = null,
) {
    val moduleName: String
        get() = componentName ?: key

    fun withUrl(url: String): RNBundleItem = copy(url = url)
}

data class RNBundlesConfigFile(
    val rnVersion: String,
    val baseVersion: String,
    val updatedAt: String,
    val bundles: Map<String, List<RNBundleItem>>,
)
