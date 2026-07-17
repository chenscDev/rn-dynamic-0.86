package com.rndynamic.loader

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import java.io.File

/**
 * RN 根视图挂载辅助。
 * 通过反射调用 React Native API，避免 SDK 源码仓在未引入 RN 依赖时无法编译；
 * 宿主工程需已集成 React Native 0.86。
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
                "宿主未集成 React Native，无法挂载。请引入 RN 0.86 依赖。",
                error,
            )
        } catch (error: Exception) {
            throw MountException("挂载 RN 失败: ${error.message}", error)
        }
    }

    private fun mountSingle(activity: Activity, container: ViewGroup, request: Request): Any {
        val reactRootViewClass = Class.forName("com.facebook.react.ReactRootView")
        val rootView = reactRootViewClass
            .getConstructor(android.content.Context::class.java)
            .newInstance(activity)

        val manager = createReactInstanceManager(
            activity,
            jsBundleFile = toFilePath(request.pageBundlePathOrUrl),
            jsMainModulePath = null,
            useDeveloperSupport = isHttp(request.pageBundlePathOrUrl),
            bundleHttpUrl = if (isHttp(request.pageBundlePathOrUrl)) request.pageBundlePathOrUrl else null,
        )

        val startMethod = reactRootViewClass.getMethod(
            "startReactApplication",
            Class.forName("com.facebook.react.ReactInstanceManager"),
            String::class.java,
            Bundle::class.java,
        )
        startMethod.invoke(rootView, manager, request.moduleName, request.initialProps)

        container.removeAllViews()
        container.addView(
            rootView as android.view.View,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return rootView
    }

    private fun mountDual(activity: Activity, container: ViewGroup, request: Request): Any {
        val commonPath = toFilePath(request.commonBundlePathOrUrl!!)
        val pagePath = toFilePath(request.pageBundlePathOrUrl)

        val reactRootViewClass = Class.forName("com.facebook.react.ReactRootView")
        val rootView = reactRootViewClass
            .getConstructor(android.content.Context::class.java)
            .newInstance(activity)

        val manager = createReactInstanceManager(
            activity,
            jsBundleFile = commonPath,
            jsMainModulePath = null,
            useDeveloperSupport = false,
            bundleHttpUrl = null,
        )

        // common 加载完成后注入 page
        val listenerClass = Class.forName("com.facebook.react.ReactInstanceManager\$ReactInstanceEventListener")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            if (method.name == "onReactContextInitialized") {
                try {
                    val context = args?.getOrNull(0)
                    val catalyst = context?.javaClass?.getMethod("getCatalystInstance")?.invoke(context)
                    catalyst?.javaClass
                        ?.getMethod("loadScriptFromFile", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                        ?.invoke(catalyst, pagePath, pagePath, false)
                } catch (error: Exception) {
                    throw MountException("注入 page bundle 失败: ${error.message}", error)
                }
            }
            null
        }
        manager.javaClass
            .getMethod("addReactInstanceEventListener", listenerClass)
            .invoke(manager, proxy)

        val startMethod = reactRootViewClass.getMethod(
            "startReactApplication",
            Class.forName("com.facebook.react.ReactInstanceManager"),
            String::class.java,
            Bundle::class.java,
        )
        startMethod.invoke(rootView, manager, request.moduleName, request.initialProps)

        container.removeAllViews()
        container.addView(
            rootView as android.view.View,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        return rootView
    }

    private fun createReactInstanceManager(
        activity: Activity,
        jsBundleFile: String?,
        jsMainModulePath: String?,
        useDeveloperSupport: Boolean,
        bundleHttpUrl: String?,
    ): Any {
        val builderClass = Class.forName("com.facebook.react.ReactInstanceManager\$Builder")
        // ReactInstanceManager.builder()
        val rimClass = Class.forName("com.facebook.react.ReactInstanceManager")
        val builder = rimClass.getMethod("builder").invoke(null)

        builderClass.getMethod("setApplication", android.app.Application::class.java)
            .invoke(builder, activity.application)
        builderClass.getMethod("setCurrentActivity", Activity::class.java)
            .invoke(builder, activity)
        builderClass.getMethod("setUseDeveloperSupport", Boolean::class.javaPrimitiveType)
            .invoke(builder, useDeveloperSupport)

        if (!bundleHttpUrl.isNullOrBlank()) {
            // Metro 调试：用 JSMainModulePath 不够表达多入口；此处用 BundleAssetName 空 + setJSBundleFile 不适合 http
            // 简化：开发模式下把 http URL 交给 createReactInstanceManager 的 JSBundleLoader
            val loaderClass = Class.forName("com.facebook.react.bridge.JSBundleLoader")
            val loader = loaderClass
                .getMethod("createRemoteBundleLoader", String::class.java, String::class.java, Boolean::class.javaPrimitiveType)
                .invoke(null, bundleHttpUrl, bundleHttpUrl, false)
            builderClass.getMethod(
                "setJSBundleLoader",
                Class.forName("com.facebook.react.bridge.JSBundleLoader"),
            ).invoke(builder, loader)
        } else if (!jsBundleFile.isNullOrBlank()) {
            val loaderClass = Class.forName("com.facebook.react.bridge.JSBundleLoader")
            val loader = loaderClass
                .getMethod("createFileLoader", String::class.java)
                .invoke(null, jsBundleFile)
            builderClass.getMethod(
                "setJSBundleLoader",
                Class.forName("com.facebook.react.bridge.JSBundleLoader"),
            ).invoke(builder, loader)
        }

        if (!jsMainModulePath.isNullOrBlank()) {
            builderClass.getMethod("setJSMainModulePath", String::class.java)
                .invoke(builder, jsMainModulePath)
        }

        // 最小 packages
        val packages = java.util.ArrayList<Any>()
        try {
            val core = Class.forName("com.facebook.react.shell.MainReactPackage").getConstructor().newInstance()
            packages.add(core)
        } catch (_: Exception) {
            // 宿主可自行扩展 package 列表
        }
        builderClass.getMethod("setPackages", java.util.List::class.java).invoke(builder, packages)

        return builderClass.getMethod("build").invoke(builder)
            ?: throw MountException("ReactInstanceManager.build() 返回 null")
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
