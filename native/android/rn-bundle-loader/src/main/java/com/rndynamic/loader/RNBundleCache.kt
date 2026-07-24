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
        findCachedFile(item.key, item.hash, platform)?.let { return it }

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
                return target
            } catch (error: Exception) {
                errors.add("remote(${item.url}): ${error.message}")
                if (target.exists()) {
                    target.delete()
                }
            }
        }

        val assetsPath = item.assetsUrl?.trim().orEmpty()
        if (assetsPath.isNotEmpty() && assetOpener != null) {
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

        if (!preferRemote && isRemoteUrl(item.url)) {
            val target = localFile(item.key, item.hash, platform)
            try {
                copyFromUrl(item.url, target)
                verifyHash(target, item.hash)
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
