package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.ember.client.data.Session

class LoginActivity : TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val root = paddedColumn()
        scroll.addView(root); setContentView(scroll)
        TvUi.add(root, TvUi.text(this, "EMBER TV", 32f).apply { letterSpacing = .14f })
        TvUi.add(root, TvUi.text(this, "连接你的 Emby，开始观看。", 20f, TvUi.muted))
        fun field(hint: String, secret: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint; textSize = 18f; setTextColor(TvUi.text); setHintTextColor(TvUi.muted)
            inputType = if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            isSingleLine = true; setPadding(16, 10, 16, 10)
        }.also { TvUi.add(root, it) }
        val server = field("服务器地址，例如 https://emby.example.com")
        val username = field("用户名")
        val password = field("密码", true)
        val token = field("Token（选填；填写后使用 Token 登录）", true)
        val userId = field("用户 ID（API Key 或 Token 无法自动识别用户时填写）")
        val status = TvUi.text(this, "登录状态会安全保存；密码不会保存。", 15f, TvUi.muted)
        lateinit var submit: Button
        submit = TvUi.button(this, "连接服务器") {
            if (server.text.isBlank()) { status.text = "请填写完整服务器地址"; server.requestFocus(); return@button }
            if (token.text.isBlank() && username.text.isBlank()) { status.text = "请填写用户名或 Token"; return@button }
            submit.isEnabled = false; status.text = "正在连接…"
            lifecycleScope.launch {
                try {
                    val session: Session = if(token.text.isNotBlank()) app.api.tokenLogin(server.text.toString(), token.text.toString(), userId.text.toString().trim())
                        else app.api.login(server.text.toString(), username.text.toString().trim(), password.text.toString())
                    app.sessions.save(session)
                    password.text.clear(); token.text.clear()
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                } catch(e: CancellationException) { throw e }
                catch(e: Exception) { status.text = e.message ?: "连接失败，请检查网络和登录信息"; submit.isEnabled = true }
            }
        }
        TvUi.add(root, submit); TvUi.add(root, status)
        server.requestFocus()
    }
}
