package com.rndynamic.loader

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
 * 原生登录降级页：RN 登录容器加载失败时使用
 *
 * Mock 账号：admin / 123456
 */
class LoginActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    if (AuthSession.isLoggedIn()) {
      AuthNavigator.navigateAfterLogin(this)
      finish()
      return
    }

    val reason = intent.getStringExtra(AuthNavigator.EXTRA_LOGIN_REASON)
    val hintLabel = TextView(this).apply {
      text = when (reason) {
        "session_expired" -> "登录已过期，请重新登录"
        else -> "请登录后使用业务功能"
      }
      textSize = 14f
      setTextColor(0xFF666666.toInt())
    }

    val userField = EditText(this).apply {
      this.hint = "用户名（admin）"
      setText("admin")
    }
    val passField = EditText(this).apply {
      this.hint = "密码（123456）"
      inputType = android.text.InputType.TYPE_CLASS_TEXT or
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
      setText("123456")
    }
    val oldPassField = EditText(this).apply {
      this.hint = "原密码"
      inputType = android.text.InputType.TYPE_CLASS_TEXT or
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
      visibility = View.GONE
    }
    val newPassField = EditText(this).apply {
      this.hint = "新密码（至少 6 位）"
      inputType = android.text.InputType.TYPE_CLASS_TEXT or
        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
      visibility = View.GONE
    }
    val status = TextView(this).apply { textSize = 13f }

    var changePasswordMode = false
    val toggleChange = Button(this).apply {
      text = "修改密码"
      setOnClickListener {
        changePasswordMode = !changePasswordMode
        oldPassField.visibility = if (changePasswordMode) View.VISIBLE else View.GONE
        newPassField.visibility = if (changePasswordMode) View.VISIBLE else View.GONE
        text = if (changePasswordMode) "返回登录" else "修改密码"
      }
    }
    val submit = Button(this).apply { text = "登录" }

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
        addView(TextView(context).apply {
          text = "登录"
          textSize = 24f
        })
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
    /** RN 登录容器加载失败时的原生降级页 */
    fun intentForFallback(context: android.content.Context, reason: String?): android.content.Intent {
      return android.content.Intent(context, LoginActivity::class.java).apply {
        putExtra(AuthNavigator.EXTRA_LOGIN_REASON, reason)
      }
    }
  }
}
