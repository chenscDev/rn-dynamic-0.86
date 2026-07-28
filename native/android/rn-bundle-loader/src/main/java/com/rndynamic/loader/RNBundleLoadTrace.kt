package com.rndynamic.loader

import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

/**
 * RN 分包加载性能追踪：记录资源顺序与分段耗时，并持久化「首次」基线供对比。
 */
object RNBundleLoadTrace {
    data class Step(
        val name: String,
        val detail: String,
        val durationMs: Long,
        val cacheHit: Boolean = false,
        val bytes: Long = -1L,
    )

    data class Report(
        val bundleKey: String,
        val channel: String,
        val steps: List<Step>,
        val totalMs: Long,
        val usedRemote: Boolean,
        val loadIndex: Int,
        val firstTotalMs: Long?,
        val startedAt: Long,
    ) {
        val deltaVsFirstMs: Long?
            get() = firstTotalMs?.let { totalMs - it }

        fun toReadableText(): String {
            return buildString {
                appendLine("【加载性能】$bundleKey  ·  第 ${loadIndex} 次")
                appendLine("总耗时 ${totalMs}ms" + formatDelta())
                if (firstTotalMs != null) {
                    appendLine("首次 ${firstTotalMs}ms")
                }
                appendLine("远程配置: ${if (usedRemote) "是" else "否（本地/内置）"}")
                appendLine("—— 资源顺序 ——")
                steps.forEachIndexed { i, step ->
                    val hit = if (step.cacheHit) "cache" else "net/io"
                    val size = if (step.bytes >= 0) " · ${formatBytes(step.bytes)}" else ""
                    appendLine("${i + 1}. ${step.name}  ${step.durationMs}ms  [$hit]$size")
                    if (step.detail.isNotBlank()) {
                        appendLine("   ${step.detail}")
                    }
                }
            }.trimEnd()
        }

        private fun formatDelta(): String {
            val d = deltaVsFirstMs ?: return ""
            return when {
                loadIndex <= 1 -> "（首次基线）"
                d == 0L -> " · 与首次持平"
                d < 0 -> " · 比首次快 ${-d}ms"
                else -> " · 比首次慢 ${d}ms"
            }
        }

        fun toInitialPropsBundle(): Bundle {
            return Bundle().apply {
                putString("loadPerfKey", bundleKey)
                putInt("loadPerfIndex", loadIndex)
                putLong("loadPerfTotalMs", totalMs)
                firstTotalMs?.let { putLong("loadPerfFirstMs", it) }
                deltaVsFirstMs?.let { putLong("loadPerfDeltaMs", it) }
                putString("loadPerfSummary", toReadableText())
                putBoolean("loadPerfUsedRemote", usedRemote)
            }
        }

        fun toJson(): JSONObject {
            val arr = JSONArray()
            steps.forEach { s ->
                arr.put(
                    JSONObject()
                        .put("name", s.name)
                        .put("detail", s.detail)
                        .put("durationMs", s.durationMs)
                        .put("cacheHit", s.cacheHit)
                        .put("bytes", s.bytes),
                )
            }
            return JSONObject()
                .put("bundleKey", bundleKey)
                .put("channel", channel)
                .put("totalMs", totalMs)
                .put("usedRemote", usedRemote)
                .put("loadIndex", loadIndex)
                .put("firstTotalMs", firstTotalMs ?: JSONObject.NULL)
                .put("steps", arr)
        }
    }

    class Session(
        val bundleKey: String,
        val channel: String,
    ) {
        private val startedAt = System.currentTimeMillis()
        private val steps = mutableListOf<Step>()
        private var openName: String? = null
        private var openDetail: String = ""
        private var openAt: Long = 0L
        private var openCacheHit: Boolean = false
        private var openBytes: Long = -1L
        var usedRemote: Boolean = false

        fun begin(
            name: String,
            detail: String = "",
            cacheHit: Boolean = false,
            bytes: Long = -1L,
        ) {
            endOpen()
            openName = name
            openDetail = detail
            openCacheHit = cacheHit
            openBytes = bytes
            openAt = System.currentTimeMillis()
        }

        fun end(
            detail: String? = null,
            cacheHit: Boolean? = null,
            bytes: Long? = null,
        ) {
            val name = openName ?: return
            val duration = (System.currentTimeMillis() - openAt).coerceAtLeast(0L)
            steps += Step(
                name = name,
                detail = detail ?: openDetail,
                durationMs = duration,
                cacheHit = cacheHit ?: openCacheHit,
                bytes = bytes ?: openBytes,
            )
            openName = null
        }

        fun note(
            name: String,
            detail: String = "",
            durationMs: Long = 0L,
            cacheHit: Boolean = false,
            bytes: Long = -1L,
        ) {
            endOpen()
            steps += Step(name, detail, durationMs.coerceAtLeast(0L), cacheHit, bytes)
        }

        fun finish(context: Context): Report {
            endOpen()
            val total = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val countKey = countKey(bundleKey, channel)
            val firstKey = firstKey(bundleKey, channel)
            val loadIndex = prefs.getInt(countKey, 0) + 1
            val firstTotal = if (prefs.contains(firstKey)) prefs.getLong(firstKey, 0L) else null
            if (loadIndex == 1 || !prefs.contains(firstKey)) {
                prefs.edit()
                    .putLong(firstKey, total)
                    .putString(firstJsonKey(bundleKey, channel), toTempJson(total).toString())
                    .apply()
            }
            prefs.edit().putInt(countKey, loadIndex).apply()
            return Report(
                bundleKey = bundleKey,
                channel = channel,
                steps = steps.toList(),
                totalMs = total,
                usedRemote = usedRemote,
                loadIndex = loadIndex,
                firstTotalMs = firstTotal ?: total,
                startedAt = startedAt,
            )
        }

        private fun toTempJson(total: Long): JSONObject {
            return JSONObject()
                .put("totalMs", total)
                .put("steps", JSONArray().also { arr ->
                    steps.forEach { s ->
                        arr.put(
                            JSONObject()
                                .put("name", s.name)
                                .put("durationMs", s.durationMs),
                        )
                    }
                })
        }

        private fun endOpen() {
            if (openName != null) {
                end()
            }
        }
    }

    private val local = ThreadLocal<Session?>()

    fun beginSession(bundleKey: String, channel: String): Session {
        val session = Session(bundleKey, channel)
        local.set(session)
        return session
    }

    fun current(): Session? = local.get()

    fun clear() {
        local.remove()
    }

    fun resetFirstBaseline(context: Context, bundleKey: String, channel: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(firstKey(bundleKey, channel))
            .remove(firstJsonKey(bundleKey, channel))
            .putInt(countKey(bundleKey, channel), 0)
            .apply()
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "-"
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0)
        return String.format("%.2fMB", bytes / (1024.0 * 1024.0))
    }

    private fun countKey(key: String, channel: String) = "count:$channel:$key"
    private fun firstKey(key: String, channel: String) = "firstTotal:$channel:$key"
    private fun firstJsonKey(key: String, channel: String) = "firstJson:$channel:$key"

    private const val PREFS = "rn_bundle_load_perf"
}
