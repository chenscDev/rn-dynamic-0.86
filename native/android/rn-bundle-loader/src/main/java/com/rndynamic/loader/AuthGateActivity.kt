package com.rndynamic.loader

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rndynamicbase.MainShellActivity

/** App 启动门禁：未登录进登录页，已登录进 Shell */
class AuthGateActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AuthSession.init(applicationContext)
        if (AuthSession.isLoggedIn()) {
            startActivity(Intent(this, MainShellActivity::class.java))
        } else {
            startActivity(RNContainerActivity.intentForLogin(this))
        }
        finish()
    }
}
