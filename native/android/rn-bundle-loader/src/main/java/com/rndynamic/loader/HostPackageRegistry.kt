package com.rndynamic.loader

import android.app.Application
import com.facebook.react.PackageList
import com.facebook.react.ReactPackage

/** 宿主 + 动态挂载共用的 Native Package 列表 */
object HostPackageRegistry {
    fun packages(application: Application): List<ReactPackage> {
        return PackageList(application).packages.toMutableList().apply {
            add(RNHostPackage())
        }
    }
}
