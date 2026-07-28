package com.rndynamic.loader

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class RNBundleCacheException(message: String) : Exception(message)

/**
 * 按 key + hash 缓存分包；支持 preload
 */
class RNBundleCache(private val cacheDirectory: File) {
    init {
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw RNBundleCacheException("无法创建缓存目录: ${cacheDirectory.absolutePath}")
        }
    }

    fun localFile(key: String, hash: String, platform: String = "android"): File {
        return File(File(cacheDirectory, key), "$key.$platform.$hash.bundle")
    }

    fun resolveBundleFile(
        item: RNBundleItem,
        assetOpener: ((String) -> InputStream)? = null,
        preferRemote: Boolean = true,
        assetsFallbackItem: RNBundleItem? = null,
    ): File {
        val platform = item.platform.ifBlank { "android" }
        // 仅命中「当前 hash」才直接用；禁止用旧 hash/assets 缓存抢先返回，否则热更永远下不到新包
        findCachedFile(item.key, item.hash, platform)?.let { cached ->
            syncRemoteAssetsIfNeeded(item, cached)
            syncBytecodeIfNeeded(item, cached)
            return cached
        }

        val keyDir = File(cacheDirectory, item.key)
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw RNBundleCacheException("无法创建分包缓存目录: ${keyDir.absolutePath}")
        }
        keyDir.listFiles()?.forEach { file ->
            if (file.name.contains(".$platform.") && file.name.endsWith(".bundle")) {
                file.delete()
            }
        }

        val errors = mutableListOf<String>()

        if (preferRemote && isRemoteUrl(item.url)) {
            val target = localFile(item.key, item.hash, platform)
            try {
                copyFromUrl(item.url, target)
                verifyHash(target, item.hash)
                syncRemoteAssetsIfNeeded(item, target)
                syncBytecodeIfNeeded(item, target)
                return target
            } catch (error: Exception) {
                errors.add("remote(${item.url}): ${error.message}")
                if (target.exists()) {
                    target.delete()
                }
            }
        }

        // APK assets 路径（如 rn-bundles/...），禁止把 HTTP assetsUrl 交给 assets.open
        val assetsPath = item.assetsUrl?.trim().orEmpty()
        if (assetsPath.isNotEmpty() && isApkAssetPath(assetsPath) && assetOpener != null) {
            val assetsHash = assetsFallbackItem?.hash ?: item.hash
            val assetsTarget = localFile(item.key, assetsHash, platform)
            try {
                assetOpener(assetsPath).use { input ->
                    assetsTarget.outputStream().use { output -> input.copyTo(output) }
                }
                verifyHash(assetsTarget, assetsHash)
                return assetsTarget
            } catch (error: Exception) {
                errors.add("assets($assetsPath): ${error.message}")
                if (assetsTarget.exists()) {
                    assetsTarget.delete()
                }
            }
        }

        // 兜底：内嵌配置上的 assetsUrl 可能是相对路径；fallback 项也可能带 APK 路径
        val fallbackAssets = assetsFallbackItem?.assetsUrl?.trim().orEmpty()
        if (fallbackAssets.isNotEmpty() && isApkAssetPath(fallbackAssets) && assetOpener != null) {
            val assetsHash = assetsFallbackItem?.hash ?: item.hash
            val assetsTarget = localFile(item.key, assetsHash, platform)
            try {
                assetOpener(fallbackAssets).use { input ->
                    assetsTarget.outputStream().use { output -> input.copyTo(output) }
                }
                verifyHash(assetsTarget, assetsHash)
                return assetsTarget
            } catch (error: Exception) {
                errors.add("assets-fallback($fallbackAssets): ${error.message}")
                if (assetsTarget.exists()) {
                    assetsTarget.delete()
                }
            }
        }

        if (!preferRemote && isRemoteUrl(item.url)) {
            val target = localFile(item.key, item.hash, platform)
            try {
                copyFromUrl(item.url, target)
                verifyHash(target, item.hash)
                syncRemoteAssetsIfNeeded(item, target)
                syncBytecodeIfNeeded(item, target)
                return target
            } catch (error: Exception) {
                errors.add("remote-retry(${item.url}): ${error.message}")
                if (target.exists()) {
                    target.delete()
                }
            }
        }

        if (!isRemoteUrl(item.url) && item.url.isNotBlank() && !item.url.startsWith("embedded://")) {
            val target = localFile(item.key, item.hash, platform)
            try {
                copyFromUrl(item.url, target)
                verifyHash(target, item.hash)
                syncRemoteAssetsIfNeeded(item, target)
                syncBytecodeIfNeeded(item, target)
                return target
            } catch (error: Exception) {
                errors.add("local(${item.url}): ${error.message}")
                if (target.exists()) {
                    target.delete()
                }
            }
        }

        throw RNBundleCacheException(
            "分包 ${item.key} 加载失败: ${errors.joinToString("; ")}",
        )
    }

    /** HTTP(S) assets 目录：按 assets-manifest.json 拉到本地，供 require 资源 */
    private fun syncRemoteAssetsIfNeeded(item: RNBundleItem, bundleFile: File) {
        val assetsUrl = item.assetsUrl?.trim().orEmpty()
        if (!isRemoteUrl(assetsUrl)) {
            return
        }
        val assetsDir = File(bundleFile.parentFile, "assets-${item.hash}")
        val marker = File(assetsDir, ".assets-ready")
        if (marker.exists() && assetsDir.isDirectory) {
            return
        }
        try {
            if (assetsDir.exists()) {
                assetsDir.deleteRecursively()
            }
            if (!assetsDir.mkdirs()) {
                return
            }
            val base = assetsUrl.trimEnd('/')
            val manifestUrl = "$base/assets-manifest.json"
            val manifestFile = File(assetsDir, "assets-manifest.json")
            download(manifestUrl, manifestFile)
            val text = manifestFile.readText(Charsets.UTF_8)
            val paths = parseAssetsManifestPaths(text)
            for (rel in paths) {
                val safeRel = rel.trim().trimStart('/')
                if (safeRel.isEmpty() || safeRel.contains("..")) continue
                val dest = File(assetsDir, safeRel)
                dest.parentFile?.mkdirs()
                download("$base/$safeRel", dest)
            }
            marker.writeText("ok\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // 资源同步失败不阻断 JS bundle 加载（多数业务未 require 本地图）
        }
    }

    private fun parseAssetsManifestPaths(json: String): List<String> {
        // 轻量解析：支持 {"files":["a.png","b/c.png"]} 或 ["a.png"]
        val filesKey = Regex(""""files"\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
        val arrayBody = filesKey.find(json)?.groupValues?.get(1) ?: run {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) trimmed.removePrefix("[").removeSuffix("]") else return emptyList()
        }
        return Regex(""""([^"]+)"""")
            .findAll(arrayBody)
            .map { it.groupValues[1] }
            .toList()
    }

    /** 下载 Hermes bytecode（若配置了 bytecodeUrl）；合并场景仍用 JS，单包可优先 .hbc */
    private fun syncBytecodeIfNeeded(item: RNBundleItem, bundleFile: File) {
        val bytecodeUrl = item.bytecodeUrl?.trim().orEmpty()
        if (!isRemoteUrl(bytecodeUrl)) {
            return
        }
        val hbc = File(bundleFile.parentFile, bundleFile.name.replace(Regex("\\.bundle$", RegexOption.IGNORE_CASE), ".hbc"))
        if (hbc.exists() && hbc.length() > 0L) {
            return
        }
        try {
            download(bytecodeUrl, hbc)
        } catch (_: Exception) {
            if (hbc.exists()) hbc.delete()
        }
    }

    /** 若同目录存在有效 .hbc，返回其路径（供单包挂载优先使用） */
    fun resolveBytecodeSibling(bundleFile: File): File? {
        val hbc = File(
            bundleFile.parentFile,
            bundleFile.name.replace(Regex("\\.bundle$", RegexOption.IGNORE_CASE), ".hbc"),
        )
        return if (hbc.exists() && hbc.length() > 0L) hbc else null
    }

    private fun isApkAssetPath(path: String): Boolean {
        if (isRemoteUrl(path)) return false
        if (path.startsWith("file://") || path.startsWith("/")) return false
        if (path.startsWith("embedded://")) return false
        return true
    }

    private fun findCachedFile(key: String, hash: String, platform: String): File? {
        val target = localFile(key, hash, platform)
        if (!target.exists()) {
            return null
        }
        // 文件名已包含 hash，只要大小 > 0 即视为有效，跳过 SHA256 复算
        if (target.length() > 0) {
            return target
        }
        target.delete()
        return null
    }

    private fun verifyHash(target: File, expectedHash: String) {
        val actual = sha256Prefix(target)
        if (actual != expectedHash) {
            target.delete()
            throw RNBundleCacheException("分包 hash 不匹配 expected=$expectedHash actual=$actual")
        }
    }

    private fun isRemoteUrl(source: String): Boolean {
        return source.startsWith("http://") || source.startsWith("https://")
    }

    private fun copyFromUrl(source: String, target: File) {
        when {
            source.startsWith("file://") -> {
                val path = URI(source).path
                File(path).copyTo(target, overwrite = true)
            }
            source.startsWith("/") -> {
                File(source).copyTo(target, overwrite = true)
            }
            source.startsWith("http://") || source.startsWith("https://") -> {
                download(source, target)
            }
            else -> throw RNBundleCacheException("非法分包地址: $source")
        }
    }

    /** 预加载：仅下载缓存 */
    fun preload(item: RNBundleItem): File = resolveBundleFile(item)

    fun clearAll() {
        if (cacheDirectory.exists()) {
            cacheDirectory.deleteRecursively()
        }
        if (!cacheDirectory.mkdirs()) {
            throw RNBundleCacheException("重建缓存目录失败")
        }
    }

    private fun download(url: String, target: File) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.instanceFollowRedirects = true
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw RNBundleCacheException("HTTP $code")
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun sha256Prefix(file: File, length: Int = 12): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }.take(length)
        }
    }
}

/**
 * App 启动预加载 common
 * @deprecated 请优先使用 [RNBundleWarmup]，以走远程 resolver / revision。
 */
class RNBundlePreloader(
    private val configStore: RNBundleConfigStore,
    private val cache: RNBundleCache,
    private val platform: String = "android",
) {
    fun preloadCommon(): File? {
        return try {
            val item = configStore.item("common", platform)
            cache.preload(item)
        } catch (_: Exception) {
            null
        }
    }

    fun preloadPage(key: String): File {
        val item = configStore.item(key, platform)
        return cache.preload(item)
    }
}
