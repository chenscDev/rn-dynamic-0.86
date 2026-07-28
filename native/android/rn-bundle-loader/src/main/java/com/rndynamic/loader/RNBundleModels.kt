package com.rndynamic.loader

/**
 * 单个分包配置（支持 common/page + channel）
 */
data class RNBundleItem(
    val key: String,
    val name: String? = null,
    val componentName: String? = null,
    val url: String,
    val hash: String,
    val platform: String,
    val channel: String? = null,
    val kind: String? = null,
    val dependsOn: List<String>? = null,
    val version: String? = null,
    val assetsUrl: String? = null,
    /** Hermes bytecode CDN 地址（可选；双包合并场景仍用 JS） */
    val bytecodeUrl: String? = null,
    val sizeBytes: Long? = null,
    val localPath: String? = null,
) {
    val moduleName: String
        get() = componentName ?: key

    val resolvedKind: String
        get() = kind ?: if (key == "common") "common" else "page"

    val dependencyKeys: List<String>
        get() = dependsOn ?: if (resolvedKind == "page") listOf("common") else emptyList()

    fun withUrl(url: String): RNBundleItem = copy(url = url)
}

data class RNBundlesConfigFile(
    val rnVersion: String,
    val baseVersion: String,
    val channel: String? = null,
    val updatedAt: String,
    val bundles: Map<String, List<RNBundleItem>>,
)
