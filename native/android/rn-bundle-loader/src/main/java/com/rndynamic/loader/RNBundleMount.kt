package com.rndynamic.loader

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import com.facebook.react.PackageList
import com.facebook.react.bridge.JSBundleLoader
import com.facebook.react.bridge.JSBundleLoaderDelegate
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.common.annotations.UnstableReactNativeAPI
import com.facebook.react.defaults.DefaultComponentsRegistry
import com.facebook.react.defaults.DefaultReactHostDelegate
import com.facebook.react.defaults.DefaultTurboModuleManagerDelegate
import com.facebook.react.fabric.ComponentFactory
import com.facebook.react.interfaces.fabric.ReactSurface
import com.facebook.react.modules.core.DefaultHardwareBackBtnHandler
import com.facebook.react.interfaces.TaskInterface
import com.facebook.react.runtime.ReactHostImpl
import com.facebook.react.runtime.hermes.HermesInstance
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CountDownLatch

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
    )

    class MountException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** 挂载后持有 Host，便于 Activity 生命周期转发 */
    private val activeHostsByActivity = mutableMapOf<Int, ReactHostImpl>()

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
        val host = activeHostsByActivity.remove(activity.hashCode()) ?: return
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
     * 在 container 中挂载 RN（必须在后台线程调用，禁止在主线程 waitForCompletion）。
     * - 仅 page：以 page 为入口 bundle
     * - common + page：先加载 common，再 loadSplitBundle 注入 page
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
        val pageLocal = resolveToLocalFile(activity, pageSource)
        val reactHost = createReactHost(
            activity = activity,
            bundlePath = pageLocal,
            sourceUrl = if (isHttp(pageSource)) pageSource else null,
            useDevSupport = isHttp(pageSource),
        )
        return attachSurface(activity, container, reactHost, request.moduleName, request.initialProps)
    }

    private fun mountDual(activity: Activity, container: ViewGroup, request: Request): View {
        val commonSource = request.commonBundlePathOrUrl!!
        val pageSource = request.pageBundlePathOrUrl
        val commonLocal = resolveToLocalFile(activity, commonSource)
        val pageLocal = resolveToLocalFile(activity, pageSource)

        val reactHost = createReactHost(
            activity = activity,
            bundlePath = commonLocal,
            sourceUrl = if (isHttp(commonSource)) commonSource else null,
            useDevSupport = false,
        )
        runOnUiThreadSync {
            reactHost.onHostResume(activity)
        }
        waitForTask(reactHost.start(), "启动 ReactHost")

        loadSplitBundle(
            reactHost,
            pageLocal,
            if (isHttp(pageSource)) pageSource else pageLocal,
        )

        return attachSurface(activity, container, reactHost, request.moduleName, request.initialProps)
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
    ): ReactHostImpl {
        val packages = HostPackageRegistry.packages(activity.application)
        val loader = if (!sourceUrl.isNullOrBlank()) {
            JSBundleLoader.createCachedBundleFromNetworkLoader(sourceUrl, bundlePath)
        } else {
            JSBundleLoader.createFileLoader(bundlePath)
        }

        val delegate = DefaultReactHostDelegate(
            jsMainModulePath = "index",
            jsBundleLoader = loader,
            reactPackages = packages,
            jsRuntimeFactory = HermesInstance(),
            turboModuleManagerDelegateBuilder = DefaultTurboModuleManagerDelegate.Builder(),
            exceptionHandler = { error ->
                throw MountException("ReactHost 运行时错误: ${formatError(error)}", error)
            },
        )

        val componentFactory = ComponentFactory()
        DefaultComponentsRegistry.register(componentFactory)

        return ReactHostImpl(
            activity.applicationContext,
            delegate,
            componentFactory,
            allowPackagerServerAccess = true,
            useDevSupport = useDevSupport,
        )
    }

    /** RN 0.86：通过 ReactHostImpl.loadBundle（internal，JVM 名带模块后缀）注入 page 分包 */
    private fun loadSplitBundle(host: ReactHostImpl, pagePath: String, sourceUrl: String) {
        val splitLoader = object : JSBundleLoader() {
            override fun loadScript(delegate: JSBundleLoaderDelegate): String {
                delegate.loadSplitBundleFromFile(pagePath, sourceUrl)
                return sourceUrl
            }
        }
        waitForTask(invokeLoadBundle(host, splitLoader), "注入 page bundle")
    }

    /**
     * Kotlin internal 方法在 release AAR 中会 mangled 为 loadBundle$ReactAndroid_release，
     * 不能直接用 "loadBundle" 反射。
     */
    private fun invokeLoadBundle(host: ReactHostImpl, loader: JSBundleLoader): TaskInterface<Boolean> {
        val hostClass = ReactHostImpl::class.java
        val loaderClass = JSBundleLoader::class.java
        val method = hostClass.methods.firstOrNull { candidate ->
            candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == loaderClass &&
                (candidate.name == "loadBundle\$ReactAndroid_release" || candidate.name == "loadBundle")
        } ?: throw MountException("ReactHost 未找到 loadBundle 方法")

        return try {
            @Suppress("UNCHECKED_CAST")
            method.invoke(host, loader) as TaskInterface<Boolean>
        } catch (error: Exception) {
            val cause = if (error is java.lang.reflect.InvocationTargetException) {
                error.targetException ?: error
            } else {
                error
            }
            throw MountException("调用 loadBundle 失败: ${formatError(cause)}", cause)
        }
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
}
