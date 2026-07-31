package com.rndynamic.loader

import android.app.Activity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.modules.core.PermissionListener

/**
 * 非 ReactActivity 的 RN 宿主权限桥：实现 PermissionsAndroid 所需的回调链路。
 */
class RNPermissionBridge(private val activity: Activity) {
    @Volatile
    private var listener: PermissionListener? = null

    fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        return activity.applicationContext.checkPermission(permission, pid, uid)
    }

    fun checkSelfPermission(permission: String): Int {
        return ContextCompat.checkSelfPermission(activity, permission)
    }

    fun shouldShowRequestPermissionRationale(permission: String): Boolean {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun requestPermissions(
        permissions: Array<String>,
        requestCode: Int,
        permissionListener: PermissionListener?,
    ) {
        listener = permissionListener
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        val current = listener ?: return
        if (current.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            listener = null
        }
    }
}
