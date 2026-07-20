package com.rndynamic.loader

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * RN 根视图挂载辅助。
 * 通过反射调用 React Native API，避免 SDK 源码仓在未引入 RN 依赖时无法编译；
 * 宿主工程需已集成 React Native 0.86。
 *
 * 注意：RN 0.86 中 Builder / EventListener 已从内部类提升为顶层类，
 * 且 JSBundleLoader.createRemoteBundleLoader 已移除，Metro http URL 需先下载到本地再加载。
 */
object RNBundleMount {
    data class Request(
        val moduleName: String,
        val pageBundlePathOrUrl: String,
        val commonBundlePathOrUrl: String? = null,
        val initialProps: Bundle? = null,
    )

    class MountException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 在 container 中挂载 RN。
     * - 仅 page：JSBundleFile = page
     * - common + page：先以 common 建实例，再 loadScriptFromFile(page)
     */
    @JvmStatic
    fun mount(activity: Activity, container: ViewGroup, request: Request): Any {
        return try {
            if (request.commonBundlePathOrUrl.isNullOrBlank()) {
                mountSingle(activity, container, request)
            } else {
                mountDual(activity, container, request)
            }
        } catch (error: MountException) {
            throw error
        } catch (error: ClassNotFoundException) {
            throw MountException(
                "宿主未集成 React Native 或 API 不匹配（缺少 ${error.message}）。请引入 RN 0.86 依赖。",
                error,
            )
        } catch (error: NoSuchMethodException) {
            throw MountException(
                "RN API 不匹配（缺少方法 ${error.message}）。请确认宿主为 RN 0.86。",
                error,
            )
        } catch (error: Exception) {
            throw MountException("挂载 RN 失败: ${formatError(error)}", error)
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

    private fun mountSingle(activity: Activity, container: ViewGroup, request: Request): Any {
        val reactRootViewClass = Class.forName("com.facebook.react.ReactRootView")
        val rootView = reactRootViewClass
            .getConstructor(android.content.Context::class.java)
            .newInstance(activity)

        val pageSource = request.pageBundlePathOrUrl
        val pageLocal = resolveToLocalFile(activity, pageSource)
        val manager = createReactInstanceManager(
            activity,
            jsBundleFile = pageLocal,
            jsMainModulePath = null,
            useDeveloperSupport = isHttp(pageSource),
            sourceUrlForLoader = if (isHttp(pageSource)) pageSource else null,
        )

        startReactApplication(reactRootViewClass, rootView, manager, request.moduleName, request.initialProps)
        attachRootView(container, rootView)
        return rootView
    }

    private fun mountDual(activity: Activity, container: ViewGroup, request: Request): Any {
        val commonSource = request.commonBundlePathOrUrl!!
        val pageSource = request.pageBundlePathOrUrl
        val commonPath = resolveToLocalFile(activity, commonSource)
        val pagePath = resolveToLocalFile(activity, pageSource)

        val reactRootViewClass = Class.forName("com.facebook.react.ReactRootView")
        val rootView = reactRootViewClass
            .getConstructor(android.content.Context::class.java)
            .newInstance(activity)

        val manager = createReactInstanceManager(
            activity,
            jsBundleFile = commonPath,
            jsMainModulePath = null,
            useDeveloperSupport = false,
            sourceUrlForLoader = if (isHttp(commonSource)) commonSource else null,
        )

        // common 加载完成后注入 page（RN 0.86：顶层 ReactInstanceEventListener）
        val listenerClass = Class.forName("com.facebook.react.ReactInstanceEventListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            if (method.name == "onReactContextInitialized") {
                try {
                    val context = args?.getOrNull(0)
                    val catalyst = context?.javaClass?.getMethod("getCatalystInstance")?.invoke(context)
                    catalyst?.javaClass
                        ?.getMethod(
                            "loadScriptFromFile",
                            String::class.java,
                            String::class.java,
                            Boolean::class.javaPrimitiveType,
                        )
                        ?.invoke(catalyst, pagePath, if (isHttp(pageSource)) pageSource else pagePath, false)
                } catch (error: Exception) {
                    throw MountException("注入 page bundle 失败: ${error.message}", error)
                }
            }
            null
        }
        manager.javaClass
            .getMethod("addReactInstanceEventListener", listenerClass)
            .invoke(manager, proxy)

        startReactApplication(reactRootViewClass, rootView, manager, request.moduleName, request.initialProps)
        attachRootView(container, rootView)
        return rootView
    }

    private fun startReactApplication(
        reactRootViewClass: Class<*>,
        rootView: Any,
        manager: Any,
        moduleName: String,
        initialProps: Bundle?,
    ) {
        val startMethod = reactRootViewClass.getMethod(
            "startReactApplication",
            Class.forName("com.facebook.react.ReactInstanceManager"),
            String::class.java,
            Bundle::class.java,
        )
        startMethod.invoke(rootView, manager, moduleName, initialProps)
    }

    private fun attachRootView(container: ViewGroup, rootView: Any) {
        container.removeAllViews()
        container.addView(
            rootView as android.view.View,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun createReactInstanceManager(
        activity: Activity,
        jsBundleFile: String?,
        jsMainModulePath: String?,
        useDeveloperSupport: Boolean,
        sourceUrlForLoader: String?,
    ): Any {
        val rimClass = Class.forName("com.facebook.react.ReactInstanceManager")
        // RN 0.86：builder() 返回顶层 ReactInstanceManagerBuilder，不再是内部类 Builder
        val builder = rimClass.getMethod("builder").invoke(null)
            ?: throw MountException("ReactInstanceManager.builder() 返回 null")
        val builderClass = builder.javaClass

        builderClass.getMethod("setApplication", android.app.Application::class.java)
            .invoke(builder, activity.application)
        builderClass.getMethod("setCurrentActivity", Activity::class.java)
            .invoke(builder, activity)
        builderClass.getMethod("setUseDeveloperSupport", Boolean::class.javaPrimitiveType)
            .invoke(builder, useDeveloperSupport)

        // RN 0.86：build() 要求必须设置 initialLifecycleState
        val lifecycleClass = Class.forName("com.facebook.react.common.LifecycleState")
        @Suppress("UNCHECKED_CAST")
        val resumed = java.lang.Enum.valueOf(
            lifecycleClass as Class<out Enum<*>>,
            "RESUMED",
        )
        builderClass.getMethod("setInitialLifecycleState", lifecycleClass)
            .invoke(builder, resumed)

        // 可选：返回键交给 Activity（已 resume 场景更稳妥）
        try {
            val backHandlerClass = Class.forName("com.facebook.react.modules.core.DefaultHardwareBackBtnHandler")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                backHandlerClass.classLoader,
                arrayOf(backHandlerClass),
            ) { _, method, _ ->
                if (method.name == "invokeDefaultOnBackPressed") {
                    activity.onBackPressed()
                }
                null
            }
            builderClass.getMethod("setDefaultHardwareBackBtnHandler", backHandlerClass)
                .invoke(builder, proxy)
        } catch (_: Exception) {
            // 宿主未带该接口时忽略
        }

        if (!jsBundleFile.isNullOrBlank()) {
            val loaderClass = Class.forName("com.facebook.react.bridge.JSBundleLoader")
            val loader = if (!sourceUrlForLoader.isNullOrBlank()) {
                // Metro 下载到本地后，用 network cache loader 保留 sourceURL（堆栈更友好）
                loaderClass
                    .getMethod(
                        "createCachedBundleFromNetworkLoader",
                        String::class.java,
                        String::class.java,
                    )
                    .invoke(null, sourceUrlForLoader, jsBundleFile)
            } else {
                loaderClass
                    .getMethod("createFileLoader", String::class.java)
                    .invoke(null, jsBundleFile)
            }
            builderClass.getMethod(
                "setJSBundleLoader",
                Class.forName("com.facebook.react.bridge.JSBundleLoader"),
            ).invoke(builder, loader)
        }

        if (!jsMainModulePath.isNullOrBlank()) {
            builderClass.getMethod("setJSMainModulePath", String::class.java)
                .invoke(builder, jsMainModulePath)
        }

        // autolink 包（navigation / screens / safe-area 等），仅 MainReactPackage 会导致挂载失败
        val packages = loadAutolinkedPackages(activity)
        builderClass.getMethod("addPackages", java.util.List::class.java).invoke(builder, packages)

        return invokeBuild(builder, builderClass)
    }

    /** 加载宿主 autolink 的全部 ReactPackage */
    private fun loadAutolinkedPackages(activity: Activity): java.util.ArrayList<Any> {
        val packages = java.util.ArrayList<Any>()
        try {
            val packageListClass = Class.forName("com.facebook.react.PackageList")
            val packageList = packageListClass
                .getConstructor(android.app.Application::class.java)
                .newInstance(activity.application)
            @Suppress("UNCHECKED_CAST")
            val linked = packageListClass.getMethod("getPackages").invoke(packageList) as List<Any>
            packages.addAll(linked)
        } catch (_: Exception) {
            try {
                val core = Class.forName("com.facebook.react.shell.MainReactPackage")
                    .getConstructor()
                    .newInstance()
                packages.add(core)
            } catch (_: Exception) {
                // 宿主未集成 RN
            }
        }
        return packages
    }

    private fun invokeBuild(builder: Any, builderClass: Class<*>): Any {
        return try {
            builderClass.getMethod("build").invoke(builder)
                ?: throw MountException("ReactInstanceManager.build() 返回 null")
        } catch (error: java.lang.reflect.InvocationTargetException) {
            val target = error.targetException ?: error
            throw MountException("ReactInstanceManager 构建失败: ${formatError(target)}", target)
        }
    }

    /**
     * 将 http(s) Metro URL 下载到缓存目录；本地 path / file:// 原样返回。
     * 可在后台线程预调用，避免主线程网络导致 ANR。
     * RN 0.86 已移除 createRemoteBundleLoader，必须先落地再加载。
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
                "下载 Metro bundle 失败: ${error.message}。请确认电脑 yarn start 已启动，且手机能访问 $pathOrUrl",
                error,
            )
        }
        return outFile.absolutePath
    }

    private fun downloadHttpToFile(urlString: String, outFile: File) {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
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
