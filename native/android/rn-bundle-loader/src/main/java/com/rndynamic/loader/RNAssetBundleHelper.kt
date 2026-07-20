package com.rndynamic.loader

import android.content.Context
import java.io.File

/**
 * 从 APK assets 拷贝 RN 配置与内置分包元数据
 */
object RNAssetBundleHelper {
    private const val DEFAULT_CHANNEL = "main"

    fun ensureChannelConfig(
        context: Context,
        channel: String = DEFAULT_CHANNEL,
        forceFromAssets: Boolean = false,
    ): File {
        val baseDir = File(context.filesDir, "rn-config/channels/$channel")
        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }
        val target = File(baseDir, "bundles.local.json")
        // 每次从 assets 同步，避免旧版 dev URL 配置残留
        copyAssetIfExists(
            context = context,
            assetPath = "rn-config/channels/$channel/bundles.local.json",
            target = target,
        )
        copyAssetIfExists(
            context = context,
            assetPath = "rn-config/channels/$channel/remote.local.json",
            target = File(baseDir, "remote.local.json"),
        )
        return target
    }

    private fun copyAssetIfExists(context: Context, assetPath: String, target: File) {
        try {
            context.assets.open(assetPath).use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (_: Exception) {
            // assets 缺失时由上层处理 mount 失败并降级
        }
    }
}
