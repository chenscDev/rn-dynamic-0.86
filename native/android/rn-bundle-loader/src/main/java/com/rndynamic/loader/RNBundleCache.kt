package com.rndynamic.loader

import java.io.File
import java.io.FileInputStream
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

    fun resolveBundleFile(item: RNBundleItem): File {
        val platform = item.platform.ifBlank { "android" }
        val target = localFile(item.key, item.hash, platform)
        if (target.exists()) {
            val actual = sha256Prefix(target)
            if (actual == item.hash) {
                return target
            }
            target.delete()
        }

        val keyDir = File(cacheDirectory, item.key)
        if (!keyDir.exists() && !keyDir.mkdirs()) {
            throw RNBundleCacheException("无法创建分包缓存目录: ${keyDir.absolutePath}")
        }
        // 清理同平台旧 bundle
        keyDir.listFiles()?.forEach { file ->
            if (file.name.contains(".$platform.") && file.name.endsWith(".bundle")) {
                file.delete()
            }
        }

        val source = item.url
        try {
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
        } catch (error: RNBundleCacheException) {
            throw error
        } catch (error: Exception) {
            throw RNBundleCacheException("下载/拷贝分包失败: ${error.message}")
        }

        val actual = sha256Prefix(target)
        if (actual != item.hash) {
            target.delete()
            throw RNBundleCacheException("分包 hash 不匹配 expected=${item.hash} actual=$actual")
        }
        return target
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
