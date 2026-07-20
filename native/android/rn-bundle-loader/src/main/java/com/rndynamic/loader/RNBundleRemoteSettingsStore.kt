package com.rndynamic.loader

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 远程 bundles 配置开关（assets 内置，可按环境覆盖）
 */
data class RNBundleRemoteSettings(
    val enabled: Boolean,
    val baseUrl: String,
    val rnVersion: String,
) {
    fun isUsable(): Boolean {
        return enabled && baseUrl.isNotBlank() && rnVersion.isNotBlank()
    }
}

object RNBundleRemoteSettingsStore {
    private const val DEFAULT_RN_VERSION = "0.86.0"

    fun load(context: Context, channel: String = "main"): RNBundleRemoteSettings {
        val fromFile = loadFromFilesDir(context, channel)
            ?: loadFromAssets(context, channel)
        return fromFile ?: RNBundleRemoteSettings(
            enabled = false,
            baseUrl = "",
            rnVersion = DEFAULT_RN_VERSION,
        )
    }

    private fun loadFromFilesDir(context: Context, channel: String): RNBundleRemoteSettings? {
        val file = File(context.filesDir, "rn-config/channels/$channel/remote.local.json")
        if (!file.exists()) {
            return null
        }
        return parseJson(file.readText(Charsets.UTF_8))
    }

    private fun loadFromAssets(context: Context, channel: String): RNBundleRemoteSettings? {
        return try {
            val text = context.assets
                .open("rn-config/channels/$channel/remote.local.json")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            parseJson(text)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseJson(text: String): RNBundleRemoteSettings? {
        return try {
            val root = JSONObject(text)
            RNBundleRemoteSettings(
                enabled = root.optBoolean("enabled", false),
                baseUrl = root.optString("baseUrl", "").trim(),
                rnVersion = root.optString("rnVersion", DEFAULT_RN_VERSION).trim(),
            )
        } catch (_: Exception) {
            null
        }
    }
}
