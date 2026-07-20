package com.rndynamic.loader

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * Mock 接口：模拟 shell 配置、登录、改密、业务数据
 */
object MockApiService {
    data class LoginResult(
        val token: String,
        val userId: String,
        val nickname: String,
    )

    data class ApiException(val code: Int, override val message: String) : Exception(message)

    private val allEntries = listOf(
        entry("home", "Home", "ic_entry_home"),
        entry("order", "订单", "ic_entry_order"),
        entry("demo", "Demo", "ic_entry_home"),
        entry("profile", "我的资料", "ic_tab_mine"),
        entry("wallet", "钱包", "ic_entry_order"),
        entry("message", "消息", "ic_entry_home"),
    )

    private fun entry(key: String, title: String, icon: String): JSONObject {
        return JSONObject()
            .put("key", key)
            .put("title", title)
            .put("icon", icon)
            .put("visible", true)
            .put("order", 0)
    }

    /** 模拟 GET /api/shell/config — 每次随机展示部分 RN 入口 */
    @Throws(InterruptedException::class)
    fun fetchShellConfig(channel: String = "main"): ShellConfigFile {
        Thread.sleep(400)
        val shuffled = allEntries.shuffled(Random(System.currentTimeMillis()))
        val bizEntries = JSONArray()
        val count = Random.nextInt(2, minOf(5, shuffled.size + 1))
        shuffled.take(count).forEachIndexed { index, item ->
            bizEntries.put(
                JSONObject(item.toString()).put("order", index),
            )
        }

        val tabs = JSONArray()
            .put(
                JSONObject()
                    .put("id", "home")
                    .put("title", "首页")
                    .put("type", "native")
                    .put("nativeKey", "home")
                    .put("icon", "ic_tab_home")
                    .put("visible", true)
                    .put("order", 0),
            )
            .put(
                JSONObject()
                    .put("id", "biz")
                    .put("title", "业务")
                    .put("type", "rn-entries")
                    .put("icon", "ic_tab_biz")
                    .put("visible", true)
                    .put("order", 1)
                    .put("entries", bizEntries),
            )
            .put(
                JSONObject()
                    .put("id", "mine")
                    .put("title", "我的")
                    .put("type", "native")
                    .put("nativeKey", "mine")
                    .put("icon", "ic_tab_mine")
                    .put("visible", true)
                    .put("order", 99),
            )

        val root = JSONObject()
            .put("rnVersion", "0.86.0")
            .put("channel", channel)
            .put("updatedAt", System.currentTimeMillis().toString())
            .put("tabs", tabs)

        return ShellConfigStore.parseJson(root.toString(), channel)
    }

    @Throws(InterruptedException::class, ApiException::class)
    fun login(username: String, password: String): LoginResult {
        Thread.sleep(500)
        if (username == "admin" && password == "123456") {
            return LoginResult(
                token = "mock-token-${System.currentTimeMillis()}",
                userId = "U10001",
                nickname = "内测用户",
            )
        }
        throw ApiException(401, "用户名或密码错误（Mock: admin / 123456）")
    }

    @Throws(InterruptedException::class, ApiException::class)
    fun changePassword(oldPassword: String, newPassword: String) {
        Thread.sleep(400)
        if (oldPassword != "123456") {
            throw ApiException(400, "原密码错误")
        }
        if (newPassword.length < 6) {
            throw ApiException(400, "新密码至少 6 位")
        }
    }

    @Throws(InterruptedException::class)
    fun logout() {
        Thread.sleep(200)
    }

    /** 模拟需登录的业务接口；token 含 expired 视为登录过期 */
    @Throws(InterruptedException::class, ApiException::class)
    fun fetchBizData(path: String, authHeaders: Map<String, String>): JSONObject {
        Thread.sleep(300)
        val token = authHeaders["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank()) {
            throw ApiException(401, "未登录")
        }
        if (token.contains("expired")) {
            throw ApiException(401, "登录已过期")
        }
        return JSONObject()
            .put("path", path)
            .put("userId", authHeaders["X-User-Id"])
            .put("message", "Mock 数据: $path")
            .put("balance", Random.nextInt(100, 9999))
            .put("unread", Random.nextInt(0, 20))
    }
}
