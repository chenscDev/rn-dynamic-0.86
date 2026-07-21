package com.rndynamic.loader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * 原生登录降级页 / 修改密码页
 *
 * Mock 账号：admin / 123456
 */
class LoginActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AuthSession.init(applicationContext)

    val changePasswordOnly = intent.getStringExtra(EXTRA_MODE) == MODE_CHANGE_PASSWORD

    // 已登录且非「改密」模式：走登录回跳（避免误开登录页）
    if (AuthSession.isLoggedIn() && !changePasswordOnly) {
      AuthNavigator.navigateAfterLogin(this)
      finish()
      return
    }

    // 未登录却点改密：先提示去登录
    if (changePasswordOnly && !AuthSession.isLoggedIn()) {
      Toast.makeText(this, "请先登录后再修改密码", Toast.LENGTH_SHORT).show()
      AuthNavigator.openLogin(this)
      finish()
      return
    }

    val reason = intent.getStringExtra(AuthNavigator.EXTRA_LOGIN_REASON)
    val titleLabel = TextView(this).apply {
      text = if (changePasswordOnly) "修改密码" else "登录"
      textSize = 24f
    }
    val hintLabel = TextView(this).apply {
      text = when {
        changePasswordOnly -> "修改登录密码（Mock）"
        reason == "session_expired" -> "登录已过期，请重新登录"
        else -> "请登录后使用业务功能"
      }
      textSize = 14f
      setTextColor(0xFF666666.toInt())
    }

    val userField = EditText(this).apply {
      this.hint = "用户名（admin）"
      setText("admin")
      visibility = if (changePasswordOnly) View.GONE else View.VISIBLE
    }
    val passField = EditText(this).apply {
      this.hint = "密码（123456）"
      inputType = android.text.InputType.TYPE_CLASS_TEXT or
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
      setText("123456")
      visibility = if (changePasswordOnly) View.GONE else View.VISIBLE
    }
    val oldPassField = EditText(this).apply {
      this.hint = "原密码"
      inputType = android.text.InputType.TYPE_CLASS_TEXT or
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
      visibility = if (changePasswordOnly) View.VISIBLE else View.GONE
    }
    val newPassField = EditText(this).apply {
      this.hint = "新密码（至少 6 位）"
      inputType = android.text.InputType.TYPE_CLASS_TEXT or
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
      visibility = if (changePasswordOnly) View.VISIBLE else View.GONE
    }
    val status = TextView(this).apply { textSize = 13f }

    var changePasswordMode = changePasswordOnly
    val toggleChange = Button(this).apply {
      text = if (changePasswordOnly) "关闭" else "修改密码"
      setOnClickListener {
        if (changePasswordOnly) {
          finish()
          return@setOnClickListener
        }
        changePasswordMode = !changePasswordMode
        oldPassField.visibility = if (changePasswordMode) View.VISIBLE else View.GONE
        newPassField.visibility = if (changePasswordMode) View.VISIBLE else View.GONE
        text = if (changePasswordMode) "返回登录" else "修改密码"
      }
    }
    val submit = Button(this).apply {
      text = if (changePasswordOnly) "确认修改" else "登录"
    }

    submit.setOnClickListener {
      submit.isEnabled = false
      status.text = "请求中…"
      thread {
        try {
          if (changePasswordMode) {
            MockApiService.changePassword(
              oldPassword = oldPassField.text.toString(),
              newPassword = newPassField.text.toString(),
            )
            runOnUiThread {
              status.text = "密码修改成功（Mock）"
              submit.isEnabled = true
              Toast.makeText(this, "密码已更新", Toast.LENGTH_SHORT).show()
              if (changePasswordOnly) {
                finish()
              }
            }
          } else {
            val result = MockApiService.login(
              username = userField.text.toString().trim(),
              password = passField.text.toString(),
            )
            AuthSession.saveLogin(result.token, result.userId, result.nickname)
            runOnUiThread {
              Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
              AuthNavigator.navigateAfterLogin(this)
              finish()
            }
          }
        } catch (error: Exception) {
          runOnUiThread {
            status.text = error.message ?: "请求失败"
            submit.isEnabled = true
          }
        }
      }
    }

    setContentView(
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(48), dp(24), dp(24))
        addView(titleLabel)
        addView(hintLabel)
        addView(userField)
        addView(passField)
        addView(oldPassField)
        addView(newPassField)
        addView(submit)
        addView(toggleChange)
        addView(status)
      },
    )
  }

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

  companion object {
    const val EXTRA_MODE = "login_mode"
    const val MODE_CHANGE_PASSWORD = "change_password"

    /** RN 登录容器加载失败时的原生降级页 */
    fun intentForFallback(context: Context, reason: String?): Intent {
      return Intent(context, LoginActivity::class.java).apply {
        putExtra(AuthNavigator.EXTRA_LOGIN_REASON, reason)
      }
    }

    /** 「我的」页进入修改密码（已登录） */
    fun intentForChangePassword(context: Context): Intent {
      return Intent(context, LoginActivity::class.java).apply {
        putExtra(EXTRA_MODE, MODE_CHANGE_PASSWORD)
      }
    }
  }
}
