package com.rndynamic.loader

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * 工程全流程演示：冷启动 → 登录 → Shell → 分包加载 → 打包构建，
 * 展示顺序、本地资源体积、模拟耗时与可优化点。
 */
class PipelineDemoActivity : AppCompatActivity() {
    private lateinit var logView: TextView
    private lateinit var summaryView: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        AuthSession.init(applicationContext)

        val title = TextView(this).apply {
            text = "分包全流程演示"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        val intro = TextView(this).apply {
            text = "模拟 App 从启动到打开业务页的链路，并读取本机已嵌入的 bundle 体积，便于对照优化点。"
            textSize = 13f
            setTextColor(0xFF666666.toInt())
            setLineSpacing(0f, 1.3f)
        }
        summaryView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF111111.toInt())
            setLineSpacing(0f, 1.25f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFE8F5E9.toInt())
        }
        logView = TextView(this).apply {
            text = "点击下方按钮开始模拟…"
            textSize = 12f
            setTextColor(0xFF333333.toInt())
            setLineSpacing(dp(2).toFloat(), 1.2f)
            typeface = Typeface.MONOSPACE
        }
        runButton = Button(this).apply {
            text = "开始模拟流程"
            setOnClickListener { runSimulation() }
        }
        val tips = TextView(this).apply {
            text = OPTIMIZATION_TIPS
            textSize = 12f
            setTextColor(0xFF444444.toInt())
            setLineSpacing(0f, 1.3f)
            setPadding(0, dp(16), 0, 0)
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(28))
            addView(title)
            addView(intro, marginTop(8))
            addView(runButton, marginTop(12))
            addView(summaryView, marginTop(12))
            addView(
                TextView(context).apply {
                    text = "步骤日志"
                    textSize = 15f
                    setTypeface(typeface, Typeface.BOLD)
                },
                marginTop(16),
            )
            addView(logView, marginTop(8))
            addView(tips)
        }

        setContentView(
            ScrollView(this).apply {
                addView(content)
            },
        )
    }

    private fun runSimulation() {
        runButton.isEnabled = false
        logView.text = "扫描本地资源…"
        summaryView.text = "运行中…"
        thread(name = "PipelineDemo") {
            val channel = RNAssetBundleHelper.readBuildChannel(this)
            val started = System.currentTimeMillis()
            val lines = mutableListOf<String>()
            val sizes = mutableListOf<Pair<String, Long>>()

            fun step(title: String, detail: String, sleepMs: Long, sizeLabel: String? = null, sizeBytes: Long? = null) {
                Thread.sleep(sleepMs)
                val sizePart = if (sizeLabel != null && sizeBytes != null) {
                    sizes.add(sizeLabel to sizeBytes)
                    " · ${formatBytes(sizeBytes)}"
                } else {
                    ""
                }
                lines.add("[$sleepMs ms] $title$sizePart\n  $detail")
                runOnUiThread {
                    logView.text = lines.joinToString("\n\n")
                }
            }

            try {
                step(
                    "1. 冷启动 AuthGate",
                    "检查 AuthSession；未登录 → login 容器；已登录 → MainShell",
                    120,
                )
                val loginSize = probeBundleSize(channel, "login")
                step(
                    "2. 加载 login 分包",
                    "assets/rn-bundles/$channel/… 或 cache；common 预置 + page=login",
                    280,
                    "login.page",
                    loginSize.page,
                )
                if (loginSize.common >= 0) {
                    sizes.add("common" to loginSize.common)
                }
                step(
                    "3. 登录成功 → Shell",
                    "Mock /api/shell/config；Tab=首页/业务/我的；业务入口全部下发",
                    200,
                )
                step(
                    "4. 同步 channel 配置",
                    "files/rn-config/channels/$channel/{bundles,remote}.local.json",
                    100,
                )

                val pageKeys = listOf("home", "order", "demo", "profile", "wallet", "message")
                pageKeys.forEachIndexed { index, key ->
                    val probed = probeBundleSize(channel, key)
                    step(
                        "5.${index + 1} 打开业务页 `$key`",
                        "顺序: resolve → common(${formatBytes(probed.common)}) → page(${formatBytes(probed.page)}) → 合并挂载",
                        90 + index * 15L,
                        "$key.page",
                        probed.page,
                    )
                }

                step(
                    "6. 打包 / 发布 / Embed（研发侧）",
                    "rn-biz pack:build → CDN/publish → embed-login-bundles → assembleInternalRelease",
                    150,
                )

                val totalMs = System.currentTimeMillis() - started
                val uniqueSizes = sizes
                    .groupBy { it.first.substringBefore('.') }
                    .mapValues { (_, list) -> list.maxOf { it.second } }
                val commonBytes = uniqueSizes["common"] ?: loginSize.common.coerceAtLeast(0)
                val pagesTotal = uniqueSizes.filterKeys { it != "common" }.values.sum()
                val summary = buildString {
                    appendLine("channel: $channel")
                    appendLine("模拟总耗时: ${totalMs}ms（含演示 sleep）")
                    appendLine("common: ${formatBytes(commonBytes)}")
                    appendLine("业务 page 合计: ${formatBytes(pagesTotal)}")
                    appendLine("加载顺序建议: common 启动预热 → 按需 page")
                    append("优化重点: 减 common 体积、真正双注入、CDN 差量、预下载热门页")
                }
                runOnUiThread {
                    summaryView.text = summary
                    runButton.isEnabled = true
                    runButton.text = "再跑一遍"
                }
            } catch (error: Exception) {
                runOnUiThread {
                    summaryView.text = "模拟失败: ${error.message}"
                    runButton.isEnabled = true
                }
            }
        }
    }

    private data class BundleSizes(val common: Long, val page: Long)

    private fun probeBundleSize(channel: String, bundleKey: String): BundleSizes {
        return try {
            val resolved = RNBundleResolver.resolve(
                context = this,
                channel = channel,
                bundleKey = bundleKey,
                cacheDir = File(cacheDir, "RNDynamicBundles"),
            )
            BundleSizes(
                common = resolved.commonFile?.length() ?: probeAssetFallback(channel, "common"),
                page = resolved.pageFile.length().takeIf { it > 0 }
                    ?: probeAssetFallback(channel, bundleKey),
            )
        } catch (_: Exception) {
            BundleSizes(
                common = probeAssetFallback(channel, "common"),
                page = probeAssetFallback(channel, bundleKey),
            )
        }
    }

    /** 从 bundles.local.json 的 assetsUrl 探测 assets 体积 */
    private fun probeAssetFallback(channel: String, key: String): Long {
        return try {
            val json = assets.open("rn-config/channels/$channel/bundles.local.json")
                .bufferedReader()
                .use { it.readText() }
            val root = JSONObject(json)
            val bundles = root.optJSONObject("bundles") ?: return -1L
            val arr = bundles.optJSONArray(key) ?: return -1L
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                if (item.optString("platform", "android") != "android") continue
                val localPath = item.optString("assetsUrl").ifBlank {
                    item.optString("localPath")
                }
                if (localPath.isBlank()) return -1L
                return assets.open(localPath).use { input ->
                    var total = 0L
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                    }
                    total
                }
            }
            -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "未找到"
        if (bytes < 1024) return "${bytes} B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        return String.format("%.2f MB", kb / 1024.0)
    }

    private fun marginTop(dpValue: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(dpValue) }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics,
        ).toInt()
    }

    companion object {
        private val OPTIMIZATION_TIPS = """
            可优化方向
            · common：去掉无用 UI 库（已卸 HeroUI），只留导航/鉴权/工具
            · page：保持薄包，按入口按需加载，避免全量合并进 APK
            · 挂载：恢复真正「预加载 common + 二次注入 page」，减少合并 IO
            · 网络：CDN hash 命中跳过下载；启动预拉 hot keys
            · Shell：配置缓存，减少每次 Mock 400ms
            · 构建：rn-release-admin 看产物体积；CI 阻断 common 过大
        """.trimIndent()
    }
}
