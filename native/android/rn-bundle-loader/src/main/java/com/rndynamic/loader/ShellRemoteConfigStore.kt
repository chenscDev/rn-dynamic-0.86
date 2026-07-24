package com.rndynamic.loader

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程 Shell 配置：从 CDN / 配置中心拉取 Tab 结构。
 *
 * URL 约定（按优先级）：
 * - {baseUrl}/config/{rnVersion}/{channel}/shell.json
 * - {baseUrl}/config/{rnVersion}/{channel}/shell
 * - {shellApiBase}/config/v1/shell?rnVersion=&channel=（可选，见 remote.local.json）
 */
object ShellRemoteConfigStore {
    fun fetch(
        context: Context,
        channelInput: String,
        settings: RNBundleRemoteSettings = RNBundleRemoteSettingsStore.load(context, channelInput),
    ): ShellConfigFile {
        val channel = RNBundleConfigStore.normalizeChannel(channelInput)
        if (!settings.isUsable()) {
            throw ShellConfigException("远程 Shell 未启用或缺少 baseUrl")
        }
        val base = (settings.shellBaseUrl.ifBlank { settings.baseUrl }).trimEnd('/')
        val candidates = mutableListOf(
            "$base/config/${settings.rnVersion}/$channel/shell.json",
            "$base/config/${settings.rnVersion}/$channel/shell",
        )
        // 若单独配置了配置中心域名，再试 v1 查询接口
        val apiBase = settings.shellApiBaseUrl.trimEnd('/')
        if (apiBase.isNotBlank()) {
            candidates +=
                "$apiBase/config/v1/shell?rnVersion=${settings.rnVersion}&channel=$channel"
        }

        var lastError: Exception? = null
        for (urlString in candidates) {
            try {
                val body = fetchUrl(urlString)
                return ShellConfigStore.parseJson(body, channel)
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw ShellConfigException(
            "远程 Shell 拉取失败: ${lastError?.message ?: "unknown"} tried=${candidates.joinToString()}",
        )
    }

    private fun fetchUrl(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "RnDynamicShell/0.86")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw ShellConfigException("远程 Shell HTTP $code: $urlString")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
