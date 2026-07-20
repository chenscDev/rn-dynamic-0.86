package com.rndynamicbase

import android.app.Application
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint.loadReactNative
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.rndynamic.loader.AuthSession
import com.rndynamic.loader.HostPackageRegistry

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
  }
}
