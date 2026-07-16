package com.rndynamic.loader

import org.json.JSONObject
import java.io.File

class RNBundleConfigException(message: String) : Exception(message)

/**
 * 读取本地分包配置
 */
class RNBundleConfigStore(private val configFile: File) {
    @Volatile
    private var cached: RNBundlesConfigFile? = null

    fun load(forceReload: Boolean = false): RNBundlesConfigFile {
        if (!forceReload) {
            cached?.let { return it }
        }
        if (!configFile.exists()) {
            throw RNBundleConfigException("配置文件不存在: ${configFile.absolutePath}")
        }

        return try {
            val root = JSONObject(configFile.readText(Charsets.UTF_8))
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
                        localPath = obj.nullableString("localPath"),
                    )
                }
                bundles[key] = list
            }
            val parsed = RNBundlesConfigFile(
                rnVersion = root.optString("rnVersion", ""),
                baseVersion = root.optString("baseVersion", ""),
                updatedAt = root.optString("updatedAt", ""),
                bundles = bundles,
            )
            cached = parsed
            parsed
        } catch (error: RNBundleConfigException) {
            throw error
        } catch (error: Exception) {
            throw RNBundleConfigException("配置解析失败: ${error.message}")
        }
    }

    fun item(key: String, platform: String = "android"): RNBundleItem {
        val config = load()
        val list = config.bundles[key]
            ?: throw RNBundleConfigException("未找到分包 key: $key")
        return list.firstOrNull { it.platform == platform }
            ?: throw RNBundleConfigException("分包 $key 缺少当前平台配置")
    }
}

private fun JSONObject.nullableString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    val value = optString(name, "")
    return value.ifEmpty { null }
}
