package com.rndynamic.loader

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 远程配置加载：从配置中心 HTTP 拉取 bundles JSON，并缓存到本地。
 *
 * 更新策略（配合发布台「前置更新远程资源」）：
 * - 始终先拉小文件 {base}/config/{rnVersion}/{channel}.revision.json（禁 HTTP 缓存）
 * - revision 与本地记录相同 → 使用磁盘缓存的配置，不重新拉全量 JSON
 * - revision 不同或本地无记录 → 拉全量配置并替换缓存，再写入新 revision
 * - CDN 无 revision 文件（旧部署）→ 兜底直接拉配置
 *
 * URL 约定：
 * - {baseUrl}/config/{rnVersion}/{channel}.json
 * - 或 {baseUrl}/config/{rnVersion}/{channel}
 */
class RNBundleRemoteConfigStore(
    private val configBaseUrl: String,
    val rnVersion: String,
    channelInput: String = "main",
    private val cacheDir: File,
    private val appContext: Context? = null,
) {
    val channel: String = RNBundleConfigStore.normalizeChannel(channelInput)

    @Volatile
    private var cached: RNBundlesConfigFile? = null

    private val cacheFile: File
        get() = File(
            File(File(cacheDir, "remote-config"), rnVersion),
            "$channel.json",
        )

    private val revisionPrefsKey: String
        get() = "rev_${rnVersion}_$channel"

    /**
     * @param forceReload true 时忽略 revision，强制拉全量（调试用）
     */
    fun load(forceReload: Boolean = false): RNBundlesConfigFile {
        if (!forceReload) {
            cached?.let { return it }
        }

        if (!forceReload) {
            val remoteRevision = fetchRemoteRevisionOrNull()
            val localRevision = readLocalRevision()
            if (remoteRevision != null &&
                localRevision != null &&
                remoteRevision == localRevision &&
                cacheFile.exists()
            ) {
                val fromDisk = parseConfigFile(cacheFile.readText(Charsets.UTF_8))
                cached = fromDisk
                return fromDisk
            }
            // remoteRevision == null：旧 CDN 无标记，继续拉配置（兼容）
            val remote = fetchRemoteConfig()
            cached = remote
            writeCache(remote)
            if (remoteRevision != null) {
                writeLocalRevision(remoteRevision)
            }
            return remote
        }

        val remote = fetchRemoteConfig()
        cached = remote
        writeCache(remote)
        fetchRemoteRevisionOrNull()?.let { writeLocalRevision(it) }
        return remote
    }

    fun item(key: String, platform: String = "android"): RNBundleItem {
        val config = load()
        val list = config.bundles[key]
            ?: throw RNBundleConfigException("未找到分包 key: $key")
        return list.firstOrNull { it.platform == platform }
            ?: throw RNBundleConfigException("分包 $key 缺少当前平台配置")
    }

    /**
     * 探测远程 revision；失败或不存在返回 null（调用方走兼容路径）
     */
    private fun fetchRemoteRevisionOrNull(): String? {
        val base = configBaseUrl.trimEnd('/')
        val urlString = "$base/config/$rnVersion/$channel.revision.json"
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "RnDynamicBundle/0.86")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                root.optString("revision", "").takeIf { it.isNotBlank() }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readLocalRevision(): String? {
        val ctx = appContext ?: return null
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(revisionPrefsKey, null)
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun writeLocalRevision(revision: String) {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(revisionPrefsKey, revision)
                .apply()
        } catch (_: Exception) {
            // revision 落盘失败不阻断主流程
        }
    }

    private fun fetchRemoteConfig(): RNBundlesConfigFile {
        val base = configBaseUrl.trimEnd('/')
        // 优先 .json（Nginx 静态文件）；兼容无后缀
        val candidates = listOf(
            "$base/config/$rnVersion/$channel.json",
            "$base/config/$rnVersion/$channel",
        )
        var lastError: Exception? = null
        for (urlString in candidates) {
            try {
                return fetchUrl(urlString)
            } catch (error: Exception) {
                lastError = error
            }
        }
        if (cacheFile.exists()) {
            return parseConfigFile(cacheFile.readText(Charsets.UTF_8))
        }
        throw RNBundleConfigException(
            "远程配置拉取失败: ${lastError?.message ?: "unknown"} tried=${candidates.joinToString()}",
        )
    }

    private fun fetchUrl(urlString: String): RNBundlesConfigFile {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            // 热更配置禁止本地 HTTP 缓存，否则 Nginx 若误标 immutable 会导致永远拉旧 hash
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("Pragma", "no-cache")
            setRequestProperty("Accept", "application/json")
            // 部分 CDN/WAF 对无 UA 的请求返回 403
            setRequestProperty("User-Agent", "RnDynamicBundle/0.86")
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
        private const val PREFS_NAME = "rn_remote_config_revision"

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
                        bytecodeUrl = obj.nullableString("bytecodeUrl"),
                        sizeBytes = if (obj.has("sizeBytes")) obj.optLong("sizeBytes") else null,
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
                            item.assetsUrl?.let { put("assetsUrl", it) }
                            item.bytecodeUrl?.let { put("bytecodeUrl", it) }
                            item.sizeBytes?.let { put("sizeBytes", it) }
                        },
                    )
                }
                root.put(key, arr)
            }
            return root
        }
    }
}
