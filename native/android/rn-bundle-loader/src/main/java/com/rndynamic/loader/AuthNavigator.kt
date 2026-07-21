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
                        ).apply {
                            // 登录成功后清栈，避免残留旧业务页
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        },
                    )
                    return
                }
            }
            "tab" -> {
                openShell(context, route.tabId ?: "home")
                return
            }
        }
        // 无回跳（含退出后再登录）：进入首页
        openShell(context, "home")
    }

    private fun openShell(context: Context, tabId: String) {
        val intent = Intent(context, MainShellActivity::class.java).apply {
            putExtra(MainShellActivity.EXTRA_INITIAL_TAB, tabId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }

    fun logoutAndOpenLogin(context: Context) {
        AuthSession.clearLogin()
        // 退出后丢弃回跳，避免再登录直接回到之前离开的 RN 页
        AuthSession.clearResumeRoute()
        val intent = RNContainerActivity.intentForLogin(context).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
    }
}
