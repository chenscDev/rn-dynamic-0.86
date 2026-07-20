package com.rndynamic.loader

import android.content.Context
import org.json.JSONObject
import java.io.File

class ShellConfigException(message: String) : Exception(message)

/**
 * 读取 Shell 导航配置（Tab / RN 入口宫格）
 *
 * 路径约定：channels/<channel>/shell.local.json
 */
class ShellConfigStore private constructor(
    private val jsonLoader: () -> String,
    val channel: String,
) {
    @Volatile
    private var cached: ShellConfigFile? = null

    fun load(forceReload: Boolean = false): ShellConfigFile {
        if (!forceReload) {
            cached?.let { return it }
        }
        return try {
            val root = JSONObject(jsonLoader())
            val tabsJson = root.optJSONArray("tabs") ?: throw ShellConfigException("缺少 tabs 数组")
            val tabs = mutableListOf<ShellTabConfig>()
            for (i in 0 until tabsJson.length()) {
                tabs += parseTab(tabsJson.getJSONObject(i))
            }
            val parsed = ShellConfigFile(
                rnVersion = root.optString("rnVersion", ""),
                channel = root.optString("channel", channel).ifEmpty { channel },
                updatedAt = root.optString("updatedAt", ""),
                tabs = tabs,
            )
            cached = parsed
            parsed
        } catch (error: ShellConfigException) {
            throw error
        } catch (error: Exception) {
            throw ShellConfigException("Shell 配置解析失败: ${error.message}")
        }
    }

    fun tab(tabId: String): ShellTabConfig {
        return load().tabs.firstOrNull { it.id == tabId }
            ?: throw ShellConfigException("未找到 Tab: $tabId")
    }

    companion object {
        fun fromAssets(context: Context, channelInput: String = "main"): ShellConfigStore {
            val channel = RNBundleConfigStore.normalizeChannel(channelInput)
            val assetPath = "rn-config/channels/$channel/shell.local.json"
            return ShellConfigStore(
                channel = channel,
                jsonLoader = {
                    context.assets.open(assetPath).bufferedReader(Charsets.UTF_8).use { it.readText() }
                },
            )
        }

        fun fromFile(configFileOrDir: File, channelInput: String = "main"): ShellConfigStore {
            val channel = RNBundleConfigStore.normalizeChannel(channelInput)
            val file = resolveConfigFile(configFileOrDir, channel)
            return ShellConfigStore(
                channel = channel,
                jsonLoader = {
                    if (!file.exists()) {
                        throw ShellConfigException("Shell 配置不存在: ${file.absolutePath}")
                    }
                    file.readText(Charsets.UTF_8)
                },
            )
        }

        fun parseJson(json: String, channelInput: String = "main"): ShellConfigFile {
            val channel = RNBundleConfigStore.normalizeChannel(channelInput)
            val root = JSONObject(json)
            val tabsJson = root.optJSONArray("tabs") ?: throw ShellConfigException("缺少 tabs 数组")
            val tabs = mutableListOf<ShellTabConfig>()
            for (i in 0 until tabsJson.length()) {
                tabs += parseTab(tabsJson.getJSONObject(i))
            }
            return ShellConfigFile(
                rnVersion = root.optString("rnVersion", ""),
                channel = root.optString("channel", channel).ifEmpty { channel },
                updatedAt = root.optString("updatedAt", ""),
                tabs = tabs,
            )
        }

        private fun resolveConfigFile(input: File, channel: String): File {
            return when {
                input.isFile -> input
                input.isDirectory -> File(File(File(input, "channels"), channel), "shell.local.json")
                else -> File(File(File(input, "channels"), channel), "shell.local.json")
            }
        }

        private fun parseTab(obj: JSONObject): ShellTabConfig {
            val entriesJson = obj.optJSONArray("entries")
            val entries = mutableListOf<ShellEntryConfig>()
            if (entriesJson != null) {
                for (i in 0 until entriesJson.length()) {
                    val entryObj = entriesJson.getJSONObject(i)
                    entries += ShellEntryConfig(
                        key = entryObj.getString("key"),
                        title = entryObj.optString("title", entryObj.getString("key")),
                        icon = entryObj.nullableString("icon"),
                        visible = entryObj.optBoolean("visible", true),
                        order = entryObj.optInt("order", 0),
                    )
                }
            }
            return ShellTabConfig(
                id = obj.getString("id"),
                title = obj.getString("title"),
                type = obj.getString("type"),
                nativeKey = obj.nullableString("nativeKey"),
                icon = obj.nullableString("icon"),
                visible = obj.optBoolean("visible", true),
                order = obj.optInt("order", 0),
                entries = entries,
                bundleKey = obj.nullableString("bundleKey"),
            )
        }
    }
}
