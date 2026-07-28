package com.rndynamic.loader

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap

/**
 * 向 RN 暴露最近一次分包加载性能报告（供 PageShell 展示）
 */
object RNLoadPerfHolder {
    @Volatile
    var lastReport: RNBundleLoadTrace.Report? = null
}

class RNLoadPerfModule(
    reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {
    override fun getName(): String = "RNLoadPerfModule"

    @ReactMethod
    fun getLastReport(promise: Promise) {
        try {
            val report = RNLoadPerfHolder.lastReport
            if (report == null) {
                promise.resolve(null)
                return
            }
            val map = WritableNativeMap()
            map.putString("bundleKey", report.bundleKey)
            map.putString("channel", report.channel)
            map.putInt("loadIndex", report.loadIndex)
            map.putDouble("totalMs", report.totalMs.toDouble())
            report.firstTotalMs?.let { map.putDouble("firstTotalMs", it.toDouble()) }
            report.deltaVsFirstMs?.let { map.putDouble("deltaVsFirstMs", it.toDouble()) }
            map.putBoolean("usedRemote", report.usedRemote)
            map.putString("summary", report.toReadableText())
            promise.resolve(map)
        } catch (error: Exception) {
            promise.reject("PERF_ERROR", error.message, error)
        }
    }
}
