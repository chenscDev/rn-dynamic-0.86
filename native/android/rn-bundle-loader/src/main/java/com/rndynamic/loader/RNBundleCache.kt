package com.rndynamic.loader

import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class RNBundleCacheException(message: String) : Exception(message)

/**
 * 按 key + hash 缓存分包；hash 变化时强制重新拉取
 */
class RNBundleCache(private val cacheDirectory: File) {
    init {
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw RNBundleCacheException("无法创建缓存目录: ${cacheDirectory.absolutePath}")
        }
    }

    fun localFile(key: String, hash: String): File {
        return File(File(cacheDirectory, key), "$key.android.$hash.bundle")
    }

    fun resolveBundleFile(item: RNBundleItem): File {
        val target = localFile(item.key, item.hash)
        if (target.exists()) {
            val actual = sha256Prefix(target)
            if (actual == item.hash) {
                return target
            }
            target.delete()
        }

        val keyDir = File(cacheDirectory, item.key)
        if (keyDir.exists()) {
            keyDir.deleteRecursively()
        }
        if (!keyDir.mkdirs()) {
            throw RNBundleCacheException("无法创建分包缓存目录: ${keyDir.absolutePath}")
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
