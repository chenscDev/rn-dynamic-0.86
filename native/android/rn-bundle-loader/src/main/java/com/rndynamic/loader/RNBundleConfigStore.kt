package com.rndynamic.loader

import org.json.JSONObject
import java.io.File

class RNBundleConfigException(message: String) : Exception(message)

/**
 * 读取本地分包配置（按 channel 文件）
 *
 * 路径约定：
 * - 传入文件：直接读该 JSON
 * - 传入 config 目录：读 channels/<channel>/bundles.local.json
 */
class RNBundleConfigStore(
    configFileOrDir: File,
    channelInput: String = "main",
) {
    val channel: String = normalizeChannel(channelInput)
    val configFile: File = resolveConfigFile(configFileOrDir, channel)

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
                        channel = obj.nullableString("channel") ?: channel,
                        kind = obj.nullableString("kind"),
                        dependsOn = obj.nullableStringList("dependsOn"),
                        version = obj.nullableString("version"),
                        assetsUrl = obj.nullableString("assetsUrl"),
                        localPath = obj.nullableString("localPath"),
                    )
                }
                bundles[key] = list
            }
            val parsed = RNBundlesConfigFile(
                rnVersion = root.optString("rnVersion", ""),
                baseVersion = root.optString("baseVersion", ""),
                channel = root.optString("channel", channel).ifEmpty { channel },
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

    companion object {
        fun normalizeChannel(raw: String): String {
            val trimmed = raw.trim().lowercase()
                .removePrefix("refs/heads/")
            if (trimmed.isEmpty()) return "main"
            val safe = trimmed
                .replace('\\', '-')
                .replace('/', '-')
                .replace(Regex("[^a-z0-9._-]+"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
            return if (safe.isEmpty()) "main" else safe
        }

        private fun resolveConfigFile(input: File, channel: String): File {
            return when {
                input.isFile -> input
                input.isDirectory -> File(File(File(input, "channels"), channel), "bundles.local.json")
                input.name == "bundles.local.json" -> {
                    // 兼容旧路径 project/config/bundles.local.json → channels/<channel>/
                    File(File(File(input.parentFile, "channels"), channel), "bundles.local.json")
                }
                else -> File(File(File(input, "channels"), channel), "bundles.local.json")
            }
        }
    }
}

private fun JSONObject.nullableString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    val value = optString(name, "")
    return value.ifEmpty { null }
}

private fun JSONObject.nullableStringList(name: String): List<String>? {
    if (!has(name) || isNull(name)) {
        return null
    }
    val arr = optJSONArray(name) ?: return null
    val list = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        list += arr.getString(i)
    }
    return list
}
