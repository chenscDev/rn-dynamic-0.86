package com.rndynamic.loader

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.facebook.react.bridge.JSBundleLoader
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.common.annotations.UnstableReactNativeAPI
import com.facebook.react.defaults.DefaultComponentsRegistry
import com.facebook.react.defaults.DefaultReactHostDelegate
import com.facebook.react.defaults.DefaultTurboModuleManagerDelegate
import com.facebook.react.fabric.ComponentFactory
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import com.facebook.react.interfaces.TaskInterface
import com.facebook.react.packagerconnection.PackagerConnectionSettings
import com.facebook.react.runtime.ReactHostImpl
import com.facebook.react.runtime.hermes.HermesInstance
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap

/**
 * RN 根视图挂载辅助（RN 0.86 Bridgeless / ReactHost）。
 *
 * Legacy 的 ReactInstanceManager 在 0.86 已不可用，此处为每个分包创建独立 ReactHost + Surface。
 */
@OptIn(UnstableReactNativeAPI::class)
object RNBundleMount {
    data class Request(
        val moduleName: String,
        val pageBundlePathOrUrl: String,
        val commonBundlePathOrUrl: String? = null,
        /**
         * true：生产分包（先 common 再 split 注入 page）
         * false：Metro 全量 page bundle，仅加载 page（common 仅预下载校验，不参与挂载）
         */
        val useSplitPageBundle: Boolean = true,
        val initialProps: Bundle? = null,
        /**
         * Metro 入口模块路径（如 src/docs-agent/index）。
         * 为空且 page 为 http URL 时，从 URL path 自动解析。
         */
        val jsMainModulePath: String? = null,
    )

    class MountException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 挂载后持有 Host，便于 Activity 生命周期转发 */
    private val activeHostsByActivity = mutableMapOf<Int, ReactHostImpl>()

    /** Activity 注册运行时错误回调，避免 JS 异常后静默白屏 */
    private val runtimeErrorListeners = ConcurrentHashMap<Int, (String) -> Unit>()

    @JvmStatic
    fun setRuntimeErrorListener(activity: Activity, listener: ((String) -> Unit)?) {
        val key = activity.hashCode()
        if (listener == null) {
            runtimeErrorListeners.remove(key)
        } else {
            runtimeErrorListeners[key] = listener
        }
    }

    /** Activity onResume 时转发给 ReactHost */
    @JvmStatic
    fun forwardOnHostResume(activity: Activity) {
        val host = activeHostsByActivity[activity.hashCode()] ?: return
        runOnUiThreadSyncFromAnyThread {
            if (activity is DefaultHardwareBackBtnHandler) {
                host.onHostResume(activity, activity)
            } else {
                host.onHostResume(activity)
            }
        }
    }

    /** Activity onPause 时转发给 ReactHost */
    @JvmStatic
    fun forwardOnHostPause(activity: Activity) {
        val host = activeHostsByActivity[activity.hashCode()] ?: return
        runOnUiThreadSyncFromAnyThread { host.onHostPause(activity) }
    }

    /** Activity onDestroy 时转发给 ReactHost */
    @JvmStatic
    fun forwardOnHostDestroy(activity: Activity) {
        val key = activity.hashCode()
        runtimeErrorListeners.remove(key)
        val host = activeHostsByActivity.remove(key) ?: return
        runOnUiThreadSyncFromAnyThread { host.onHostDestroy(activity) }
    }

    /**
     * 系统返回键 / 手势返回：转发给 RN（React Navigation 栈内 pop）。
     * RN 栈顶无法后退时会回调 Activity 的 DefaultHardwareBackBtnHandler。
     */
    @JvmStatic
    fun forwardOnBackPressed(activity: Activity): Boolean {
        val host = activeHostsByActivity[activity.hashCode()] ?: return false
        var handled = false
        runOnUiThreadSyncFromAnyThread {
            handled = host.onBackPressed()
        }
        return handled
    }

    /**
     * Activity.onActivityResult 转发给 ReactHost。
     * 文档选择器等 startActivityForResult 依赖此回调才能 resolve Promise。
     */
    @JvmStatic
    fun forwardOnActivityResult(
        activity: Activity,
        requestCode: Int,
        resultCode: Int,
        data: android.content.Intent?,
    ) {
        val host = activeHostsByActivity[activity.hashCode()] ?: return
        runOnUiThreadSyncFromAnyThread {
            host.onActivityResult(activity, requestCode, resultCode, data)
        }
    }

    /**
     * 在 container 中挂载 RN（必须在后台线程调用，禁止在主线程 waitForCompletion）。
     * - 仅 page：以 page 为入口 bundle
     * - common + page：合并为单脚本一次加载（避免二次 load 导致 registerPage 未执行）
     */
    @JvmStatic
    fun mount(activity: Activity, container: ViewGroup, request: Request): Any {
        if (UiThreadUtil.isOnUiThread()) {
            throw MountException(
                "mount 不能在主线程调用（会导致 ANR）。请在后台线程执行挂载，仅 UI 更新切回主线程。",
            )
        }
        return mountInternal(activity, container, request)
    }

    private fun mountInternal(activity: Activity, container: ViewGroup, request: Request): View {
        return try {
            val useDual = !request.commonBundlePathOrUrl.isNullOrBlank() && request.useSplitPageBundle
            if (useDual) {
                mountDual(activity, container, request)
            } else {
                mountSingle(activity, container, request)
            }
        } catch (error: MountException) {
            throw error
        } catch (error: Exception) {
            throw MountException("挂载 RN 失败: ${formatError(error)}", error)
        }
    }

    private fun mountSingle(activity: Activity, container: ViewGroup, request: Request): View {
        val pageSource = request.pageBundlePathOrUrl
        val metroLive = isHttp(pageSource)
        val jsMainModulePath = request.jsMainModulePath?.takeIf { it.isNotBlank() }
            ?: if (metroLive) jsMainModulePathFromMetroUrl(pageSource) else "index"

        // 直连 Metro：写入 debug_http_host，启用 Fast Refresh / 摇一摇 Dev Menu
        if (metroLive) {
            configurePackagerHost(activity, pageSource)
            Log.i(
                "RNBundleMount",
                "Metro 直连 module=${request.moduleName} jsMain=$jsMainModulePath url=$pageSource",
            )
        }

        // 仍下载一份作 Metro 不可达时的兜底；DevSupport 优先从 packager 拉包
        val pageLocalJs = resolveToLocalFile(activity, pageSource)
        val pageLocal = if (!metroLive) {
            RNBundleCache(File(activity.cacheDir, "RNDynamicBundles"))
                .resolveBytecodeSibling(File(pageLocalJs))
                ?.absolutePath
                ?: pageLocalJs
        } else {
            pageLocalJs
        }
        if (pageLocal != pageLocalJs) {
            Log.i("RNBundleMount", "单包优先加载 Hermes bytecode: $pageLocal")
        }
        val reactHost = createReactHost(
            activity = activity,
            bundlePath = pageLocal,
            sourceUrl = if (metroLive) pageSource else null,
            useDevSupport = metroLive,
            jsMainModulePath = jsMainModulePath,
        )
        return attachSurface(activity, container, reactHost, request.moduleName, request.initialProps)
    }

    private fun mountDual(activity: Activity, container: ViewGroup, request: Request): View {
        val commonSource = request.commonBundlePathOrUrl!!
        val pageSource = request.pageBundlePathOrUrl
        val commonLocal = resolveToLocalFile(activity, commonSource)
        val pageLocal = resolveToLocalFile(activity, pageSource)

        // Bridgeless 下二次 loadJSBundle 完整 Metro page 包时，page 入口常因依赖解析失败
        // 导致 registerPage 未执行（表现为 "xxx has not been registered"）。
        // 可靠做法：把 common 模块定义 + page 模块定义拼成单一脚本一次加载。
        val combined = combineCommonAndPageBundle(
            activity = activity,
            moduleName = request.moduleName,
            commonPath = commonLocal,
            pagePath = pageLocal,
        )
        Log.i(
            "RNBundleMount",
            "mountDual combined module=${request.moduleName} file=${combined.absolutePath} size=${combined.length()}",
        )

        val reactHost = createReactHost(
            activity = activity,
            bundlePath = combined.absolutePath,
            sourceUrl = null,
            useDevSupport = false,
        )
        return attachSurface(activity, container, reactHost, request.moduleName, request.initialProps)
    }

    /**
     * 合并 common + page 为单文件：
     * 1) common 全文（保留其 __d / __r）
     * 2) page 从首个 __d( 起截取（去掉第二份 Metro runtime）
     * 同 hash 组合命中磁盘缓存，避免重复读入内存与重写。
     */
    private fun combineCommonAndPageBundle(
        activity: Activity,
        moduleName: String,
        commonPath: String,
        pagePath: String,
    ): File {
        val commonFile = File(commonPath)
        val pageFile = File(pagePath)
        if (!commonFile.exists() || commonFile.length() <= 0L) {
            throw MountException("common bundle 为空: $commonPath")
        }
        if (!pageFile.exists() || pageFile.length() <= 0L) {
            throw MountException("page bundle 为空: $pagePath")
        }

        val commonHash = hashHintFromPath(commonPath)
        val pageHash = hashHintFromPath(pagePath)
        val outDir = File(activity.cacheDir, "RNDynamicBundles/combined")
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw MountException("无法创建合并目录: ${outDir.absolutePath}")
        }
        val outFile = File(outDir, "${moduleName}.${commonHash}_${pageHash}.combined.bundle")
        if (outFile.exists() && outFile.length() > 0L) {
            Log.i("RNBundleMount", "combined cache hit ${outFile.name} size=${outFile.length()}")
            return outFile
        }

        // 流式写入：先写 common，再扫描 page 定位首个 __d( 后追加，避免双份全文 String
        val marker = "__d("
        val pageDefsStart = indexOfMarkerInFile(pageFile, marker)
            ?: throw MountException("page bundle 未找到 __d 模块定义: $pagePath")

        FileOutputStream(outFile).use { output ->
            commonFile.inputStream().use { input -> input.copyTo(output) }
            if (!endsWithNewline(commonFile)) {
                output.write('\n'.code)
            }
            output.write(";\n".toByteArray(Charsets.UTF_8))
            pageFile.inputStream().use { input ->
                var skipped = 0L
                val buffer = ByteArray(8192)
                while (skipped < pageDefsStart) {
                    val toRead = minOf(buffer.size.toLong(), pageDefsStart - skipped).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    skipped += read
                }
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
            if (!endsWithNewline(pageFile)) {
                output.write('\n'.code)
            }
        }

        if (outFile.length() <= 0L) {
            outFile.delete()
            throw MountException("合并 bundle 写入失败: ${outFile.absolutePath}")
        }
        // 清理同 module 的旧合并文件，控制缓存膨胀
        outDir.listFiles()
            ?.filter {
                it.name.startsWith("$moduleName.") &&
                    it.name.endsWith(".combined.bundle") &&
                    it.name != outFile.name
            }
            ?.forEach { it.delete() }
        return outFile
    }

    private fun hashHintFromPath(path: String): String {
        val name = File(path).name
        // key.android.<hash>.bundle
        val parts = name.split('.')
        return if (parts.size >= 4) parts[parts.size - 2] else RNBundleCache.sha256Prefix(File(path), 8)
    }

    private fun endsWithNewline(file: File): Boolean {
        if (file.length() <= 0L) return true
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - 1)
            return raf.read() == '\n'.code
        }
    }

    /** 在文件中查找 ASCII marker 的字节偏移（适合 __d( 等 ASCII 标记） */
    private fun indexOfMarkerInFile(file: File, marker: String): Long? {
        val needle = marker.toByteArray(Charsets.UTF_8)
        file.inputStream().use { input ->
            val window = ByteArray(needle.size)
            var filled = 0
            var offset = 0L
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (filled < needle.size) {
                    window[filled++] = b.toByte()
                } else {
                    System.arraycopy(window, 1, window, 0, needle.size - 1)
                    window[needle.size - 1] = b.toByte()
                }
                offset++
                if (filled == needle.size && window.contentEquals(needle)) {
                    return offset - needle.size
                }
            }
        }
        return null
    }

    private fun attachSurface(
        activity: Activity,
        container: ViewGroup,
        reactHost: ReactHostImpl,
        moduleName: String,
        initialProps: Bundle?,
    ): View {
        activeHostsByActivity[activity.hashCode()] = reactHost
        runOnUiThreadSync {
            if (activity is DefaultHardwareBackBtnHandler) {
                reactHost.onHostResume(activity, activity)
            } else {
                reactHost.onHostResume(activity)
            }
        }

        waitForTask(reactHost.start(), "启动 ReactHost")

        val surface: ReactSurface = reactHost.createSurface(activity, moduleName, initialProps)
        val surfaceView = surface.view
            ?: throw MountException("ReactSurface 未创建 View")

        // 先 attach 到容器并完成 layout，再 startSurface，避免 Fabric 尺寸为 0 导致白屏
        runOnUiThreadSync {
            container.removeAllViews()
            container.addView(
                surfaceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        awaitViewLaidOut(surfaceView)

        waitForTask(surface.start(), "启动 ReactSurface")
        return surfaceView
    }

    /** 等待 Surface View 完成首次 layout（后台线程可调） */
    private fun awaitViewLaidOut(target: View) {
        if (target.width > 0 && target.height > 0) {
            return
        }
        val latch = CountDownLatch(1)
        UiThreadUtil.runOnUiThread {
            if (target.width > 0 && target.height > 0) {
                latch.countDown()
                return@runOnUiThread
            }
            val observer = target.viewTreeObserver
            observer.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (target.width > 0 && target.height > 0) {
                            target.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            latch.countDown()
                        }
                    }
                },
            )
        }
        latch.await()
    }

    /** 在 UI 线程执行并等待完成（调用方必须在后台线程） */
    private fun runOnUiThreadSync(block: () -> Unit) {
        if (UiThreadUtil.isOnUiThread()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        var error: Exception? = null
        UiThreadUtil.runOnUiThread {
            try {
                block()
            } catch (e: Exception) {
                error = e
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        error?.let { throw if (it is MountException) it else MountException(formatError(it), it) }
    }

    /** 生命周期转发：主线程直接执行，后台线程则 post 后等待 */
    private fun runOnUiThreadSyncFromAnyThread(block: () -> Unit) {
        if (UiThreadUtil.isOnUiThread()) {
            block()
        } else {
            runOnUiThreadSync(block)
        }
    }

    private fun createReactHost(
        activity: Activity,
        bundlePath: String,
        sourceUrl: String?,
        useDevSupport: Boolean,
        jsMainModulePath: String = "index",
    ): ReactHostImpl {
        val packages = HostPackageRegistry.packages(activity.application)
        val loader = if (!sourceUrl.isNullOrBlank()) {
            JSBundleLoader.createCachedBundleFromNetworkLoader(sourceUrl, bundlePath)
        } else {
            JSBundleLoader.createFileLoader(bundlePath)
        }

        val delegate = DefaultReactHostDelegate(
            jsMainModulePath = jsMainModulePath,
            jsBundleLoader = loader,
            reactPackages = packages,
            jsRuntimeFactory = HermesInstance(),
            turboModuleManagerDelegateBuilder = DefaultTurboModuleManagerDelegate.Builder(),
            exceptionHandler = { error ->
                val message = formatError(error)
                Log.e("RNBundleMount", "ReactHost 运行时错误: $message", error)
                val listener = runtimeErrorListeners[activity.hashCode()]
                if (listener != null) {
                    UiThreadUtil.runOnUiThread {
                        listener.invoke(message)
                    }
                }
            },
        )

        val componentFactory = ComponentFactory()
        DefaultComponentsRegistry.register(componentFactory)

        return ReactHostImpl(
            activity.applicationContext,
            delegate,
            componentFactory,
            allowPackagerServerAccess = useDevSupport,
            useDevSupport = useDevSupport,
        )
    }

    /**
     * 配置 Metro packager 地址，供 DevSupport / HMR / 摇一摇菜单连接。
     * URL 示例：http://10.157.20.204:8081/src/docs-agent/index.bundle?...
     */
    @Suppress("DEPRECATION")
    private fun configurePackagerHost(activity: Activity, metroBundleUrl: String) {
        val uri = Uri.parse(metroBundleUrl)
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) {
            return
        }
        val port = if (uri.port > 0) uri.port else 8081
        val debugHost = "$host:$port"
        val appContext = activity.applicationContext
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .edit()
            .putString(PREFS_DEBUG_SERVER_HOST_KEY, debugHost)
            .commit()
        val settings = PackagerConnectionSettings(appContext)
        settings.resetDebugServerHost()
        settings.debugServerHost = debugHost
        Log.i("RNBundleMount", "已配置 debug_http_host=$debugHost")
    }

    /** 从 Metro bundle URL 解析 jsMainModulePath */
    private fun jsMainModulePathFromMetroUrl(metroBundleUrl: String): String {
        val path = Uri.parse(metroBundleUrl).path?.trim('/').orEmpty()
        if (path.isEmpty()) {
            return "index"
        }
        return path.removeSuffix(".bundle").ifBlank { "index" }
    }

    private fun waitForTask(task: TaskInterface<*>, label: String) {
        task.waitForCompletion()
        when {
            task.isFaulted() -> {
                throw MountException(
                    "$label 失败: ${formatError(task.getError() ?: Exception("未知错误"))}",
                    task.getError(),
                )
            }
            task.isCancelled() -> throw MountException("$label 已取消")
        }
    }

    /** 展开反射/嵌套异常，避免界面只显示 null */
    private fun formatError(error: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 6) {
            val msg = current.message?.trim()
            if (!msg.isNullOrEmpty()) {
                parts.add(msg)
            }
            val next = current.cause
            if (next == null || next === current) break
            current = next
            depth++
        }
        return parts.distinct().joinToString(" → ").ifBlank { error.javaClass.simpleName }
    }

    /**
     * 将 http(s) Metro URL 下载到缓存目录；本地 path / file:// 原样返回。
     */
    @JvmStatic
    fun resolveToLocalFile(activity: Activity, pathOrUrl: String): String {
        if (!isHttp(pathOrUrl)) {
            return toFilePath(pathOrUrl)
        }
        val cacheDir = File(activity.cacheDir, "RNDynamicBundles/metro")
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw MountException("无法创建 Metro 缓存目录: ${cacheDir.absolutePath}")
        }
        val outFile = File(cacheDir, "${pathOrUrl.hashCode().toUInt()}.bundle")
        try {
            downloadHttpToFile(pathOrUrl, outFile)
        } catch (error: Exception) {
            throw MountException(
                "下载 Metro bundle 失败: ${formatError(error)}。请确认电脑 yarn start 已启动，且手机能访问 $pathOrUrl",
                error,
            )
        }
        return outFile.absolutePath
    }

    private fun downloadHttpToFile(urlString: String, outFile: File) {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw MountException("HTTP $code 拉取失败: $urlString")
            }
            connection.inputStream.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.length() <= 0L) {
                throw MountException("下载内容为空: $urlString")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun isHttp(pathOrUrl: String): Boolean {
        return pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")
    }

    private fun toFilePath(pathOrUrl: String): String {
        return when {
            pathOrUrl.startsWith("file://") -> URI(pathOrUrl).path
            else -> pathOrUrl
        }
    }

    /** 构造 Metro 调试 URL */
    @JvmStatic
    fun metroPageUrl(host: String, port: Int, key: String, platform: String = "android"): String {
        return "http://$host:$port/src/$key/index.bundle?platform=$platform&dev=true&minify=false"
    }

    @JvmStatic
    fun metroCommonUrl(host: String, port: Int, platform: String = "android"): String {
        return "http://$host:$port/packages/common/src/index.bundle?platform=$platform&dev=true&minify=false"
    }

    private const val PREFS_DEBUG_SERVER_HOST_KEY = "debug_http_host"
}
