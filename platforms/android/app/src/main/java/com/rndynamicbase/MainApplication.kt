package com.rndynamicbase

import android.app.Application
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.rndynamic.loader.AuthSession
import com.rndynamic.loader.HostPackageRegistry
import com.rndynamic.loader.RNAssetBundleHelper
import com.rndynamic.loader.RNBundleCache
import com.rndynamic.loader.RNBundleConfigStore
import com.rndynamic.loader.RNBundlePreloader
import java.io.File

class MainApplication : Application(), ReactApplication {

  override val reactHost: ReactHost by lazy {
    getDefaultReactHost(
      context = applicationContext,
      packageList = HostPackageRegistry.packages(this),
    )
  }

  override fun onCreate() {
    super.onCreate()
    AuthSession.init(applicationContext)
    loadReactNative(this)

    // 后台预加载 common 分包到缓存，减少首次打开 RN 页面的等待时间
    Thread {
      try {
        val channel = RNAssetBundleHelper.readBuildChannel(applicationContext)
        val configFile = RNAssetBundleHelper.ensureChannelConfig(applicationContext, channel)
        val configStore = RNBundleConfigStore(configFile, channel)
        val cache = RNBundleCache(File(cacheDir, "RNDynamicBundles"))
        val preloader = RNBundlePreloader(configStore, cache)
        preloader.preloadCommon()
      } catch (_: Exception) {
        // 预加载失败不影响正常流程
      }
    }.start()
  }
}
