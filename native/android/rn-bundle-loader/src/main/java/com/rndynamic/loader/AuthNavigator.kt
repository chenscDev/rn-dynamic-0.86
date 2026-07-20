package com.rndynamic.loader

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.rndynamicbase.MainShellActivity

/**
 * 登录 / 回跳路由导航
 */
object AuthNavigator {
    const val EXTRA_LOGIN_REASON = "login_reason"

    fun openLogin(context: Context, reason: String? = null) {
        val intent = RNContainerActivity.intentForLogin(
            context = context,
            reason = reason,
        )
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun saveResumeFromShell(tabId: String?) {
        AuthSession.saveResumeRoute(
            AuthSession.ResumeRoute(
                type = "tab",
                tabId = tabId,
            ),
        )
    }

    fun saveResumeFromRnContainer(bundleKey: String?, bundleTitle: String?) {
        AuthSession.saveResumeRoute(
            AuthSession.ResumeRoute(
                type = "rn_container",
                bundleKey = bundleKey,
                bundleTitle = bundleTitle,
            ),
        )
    }

    fun navigateAfterLogin(context: Context) {
        val route = AuthSession.consumeResumeRoute()
        when (route?.type) {
            "rn_container" -> {
                if (!route.bundleKey.isNullOrBlank()) {
                    context.startActivity(
                        RNContainerActivity.intentForBundleKey(
                            context = context,
                            title = route.bundleTitle ?: route.bundleKey,
                            bundleKey = route.bundleKey,
                            channel = RNAssetBundleHelper.readBuildChannel(context),
                        ),
                    )
                    return
                }
            }
            "tab" -> {
                val intent = Intent(context, MainShellActivity::class.java).apply {
                    putExtra(MainShellActivity.EXTRA_INITIAL_TAB, route.tabId)
                    if (context !is Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
                context.startActivity(intent)
                return
            }
        }
        val intent = Intent(context, MainShellActivity::class.java).apply {
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
        context.startActivity(intent)
    }

    fun logoutAndOpenLogin(context: Context) {
        AuthSession.clearLogin()
        val intent = RNContainerActivity.intentForLogin(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }
}
