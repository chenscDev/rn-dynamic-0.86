package com.rndynamicbase

import android.app.Application
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.rndynamic.loader.AuthSession
import com.rndynamic.loader.HostPackageRegistry
import com.rndynamic.loader.RNAssetBundleHelper
import com.rndynamic.loader.RNBundleWarmup
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

    // 后台预热 common：走远程 resolver（revision/配置/hash），与正式打开一致
    Thread {
      try {
        val channel = RNAssetBundleHelper.readBuildChannel(applicationContext)
        RNBundleWarmup.warmupCommon(
          context = applicationContext,
          channel = channel,
          cacheDir = File(cacheDir, "RNDynamicBundles"),
          options = RNBundleWarmup.Options(wifiOnly = false, silent = true),
        )
      } catch (_: Exception) {
        // 预加载失败不影响正常流程
      }
    }.start()
  }
}
