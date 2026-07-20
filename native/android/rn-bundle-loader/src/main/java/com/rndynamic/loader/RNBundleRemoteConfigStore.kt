package com.rndynamic.loader

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置加载：从配置中心 HTTP 拉取 bundles JSON，并缓存到本地
 *
 * URL 约定：
 * - {baseUrl}/config/{rnVersion}/{channel}
 * - 或 {baseUrl}/config/v1/bundles?rnVersion=&channel=
 */
class RNBundleRemoteConfigStore(
    private val configBaseUrl: String,
    val rnVersion: String,
    channelInput: String = "main",
    private val cacheDir: File,
) {
    val channel: String = RNBundleConfigStore.normalizeChannel(channelInput)

    @Volatile
    private var cached: RNBundlesConfigFile? = null

    private val cacheFile: File
        get() = File(
            File(File(cacheDir, "remote-config"), rnVersion),
            "$channel.json",
        )

    fun load(forceReload: Boolean = false): RNBundlesConfigFile {
        if (!forceReload) {
            cached?.let { return it }
        }

        val remote = fetchRemoteConfig()
        cached = remote
        writeCache(remote)
        return remote
    }

    fun item(key: String, platform: String = "android"): RNBundleItem {
        val config = load()
        val list = config.bundles[key]
            ?: throw RNBundleConfigException("未找到分包 key: $key")
        return list.firstOrNull { it.platform == platform }
            ?: throw RNBundleConfigException("分包 $key 缺少当前平台配置")
    }

    private fun fetchRemoteConfig(): RNBundlesConfigFile {
        val base = configBaseUrl.trimEnd('/')
        val urlString = "$base/config/$rnVersion/$channel"
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }

        return try {
            val code = connection.responseCode
            if (code == 304 && cacheFile.exists()) {
                return parseConfigFile(cacheFile.readText(Charsets.UTF_8))
            }
            if (code !in 200..299) {
                throw RNBundleConfigException("远程配置 HTTP $code: $urlString")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseConfigJson(body)
        } catch (error: RNBundleConfigException) {
            if (cacheFile.exists()) {
                return parseConfigFile(cacheFile.readText(Charsets.UTF_8))
            }
            throw error
        } catch (error: Exception) {
            if (cacheFile.exists()) {
                return parseConfigFile(cacheFile.readText(Charsets.UTF_8))
            }
            throw RNBundleConfigException("远程配置拉取失败: ${error.message}")
        } finally {
            connection.disconnect()
        }
    }

    private fun writeCache(config: RNBundlesConfigFile) {
        try {
            cacheFile.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("rnVersion", config.rnVersion)
                put("baseVersion", config.baseVersion)
                put("channel", config.channel ?: channel)
                put("updatedAt", config.updatedAt)
                put("bundles", bundlesToJson(config.bundles))
            }
            cacheFile.writeText(json.toString(2), Charsets.UTF_8)
        } catch (_: Exception) {
            // 缓存失败不阻断主流程
        }
    }

    companion object {
        fun parseConfigJson(text: String): RNBundlesConfigFile = parseConfigFile(text)

        private fun parseConfigFile(text: String): RNBundlesConfigFile {
            val root = JSONObject(text)
            val bundlesJson = root.optJSONObject("bundles") ?: JSONObject()
            val bundles = mutableMapOf<String, List<RNBundleItem>>()
            val keys = bundlesJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val arr = bundlesJson.getJSONArray(key)
                val list = mutableListOf<RNBundleItem>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list += RNBundleItem(
                        key = obj.getString("key"),
                        name = obj.nullableString("name"),
                        componentName = obj.nullableString("componentName"),
                        url = obj.getString("url"),
                        hash = obj.getString("hash"),
                        platform = obj.getString("platform"),
                        channel = obj.nullableString("channel"),
                        kind = obj.nullableString("kind"),
                        dependsOn = obj.nullableStringList("dependsOn"),
                        version = obj.nullableString("version"),
                        assetsUrl = obj.nullableString("assetsUrl"),
                        localPath = obj.nullableString("localPath"),
                    )
                }
                bundles[key] = list
            }
            return RNBundlesConfigFile(
                rnVersion = root.optString("rnVersion", ""),
                baseVersion = root.optString("baseVersion", ""),
                channel = root.optString("channel", null),
                updatedAt = root.optString("updatedAt", ""),
                bundles = bundles,
            )
        }

        private fun bundlesToJson(bundles: Map<String, List<RNBundleItem>>): JSONObject {
            val root = JSONObject()
            bundles.forEach { (key, list) ->
                val arr = org.json.JSONArray()
                list.forEach { item ->
                    arr.put(
                        JSONObject().apply {
                            put("key", item.key)
                            put("url", item.url)
                            put("hash", item.hash)
                            put("platform", item.platform)
                            item.name?.let { put("name", it) }
                            item.componentName?.let { put("componentName", it) }
                            item.channel?.let { put("channel", it) }
                            item.kind?.let { put("kind", it) }
                            item.version?.let { put("version", it) }
                        },
                    )
                }
                root.put(key, arr)
            }
            return root
        }
    }
}
