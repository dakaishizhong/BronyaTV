package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import tv.ember.client.emby.EmbyApi
import tv.ember.client.network.ApiException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

class LoginActivity: TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=TvUi.row(this).apply {
            background=TvUi.canvas()
            setPadding(TvUi.dp(this,60),TvUi.dp(this,24),TvUi.dp(this,60),TvUi.dp(this,24))
        }
        val hero=TvUi.column(this).apply { gravity=Gravity.CENTER_VERTICAL }
        TvUi.add(hero,TvUi.text(this,"BronyaTV",24f).apply { letterSpacing=.04f },bottom=30)
        TvUi.add(hero,TvUi.text(this,"好故事。\n在大屏相遇。",40f).apply { setLineSpacing(0f,1.18f) },bottom=20)
        TvUi.add(hero,TvUi.text(this,"电影 · 剧集 · 你的媒体库",15f,TvUi.muted))
        root.addView(hero,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=TvUi.dp(root,48) })
        val scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        val form=TvUi.column(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(TvUi.dp(this,28),TvUi.dp(this,22),TvUi.dp(this,28),TvUi.dp(this,22))
            background=TvUi.box(TvUi.panel,32f)
        }
        scroll.addView(form);root.addView(scroll,LinearLayout.LayoutParams(TvUi.dp(root,400),-1));setContentView(root)
        TvUi.add(form,TvUi.text(this,"登录媒体库",26f),bottom=4)
        TvUi.add(form,TvUi.text(this,"登录后，下次打开即可继续观看。",13f,TvUi.muted),bottom=22)
        TvUi.add(form,TvUi.text(this,"服务器地址",12f,TvUi.muted),bottom=5)
        val server=TvUi.input(this,"https://emby.example.com",uri=true).apply { setText(app.sessions.lastServer) }
        TvUi.add(form,server,bottom=12)
        TvUi.add(form,TvUi.text(this,"账号",12f,TvUi.muted),bottom=5)
        val username=TvUi.input(this,"用户名").apply { setText(app.sessions.lastUserName) }
        TvUi.add(form,username,bottom=12)
        TvUi.add(form,TvUi.text(this,"密码",12f,TvUi.muted),bottom=5)
        val password=TvUi.input(this,"密码",secret=true)
        TvUi.add(form,password,bottom=3)
        val reveal=CheckBox(this).apply {
            text="显示密码";textSize=13f;setTextColor(TvUi.muted);isFocusable=true
            setOnCheckedChangeListener { _,checked ->
                password.transformationMethod=if(checked) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        }
        TvUi.add(form,reveal,bottom=10)
        val status=TvUi.text(this,"",13f,TvUi.muted).apply { minLines=2 }
        lateinit var submit: Button
        submit=TvUi.button(this,"连接服务器",true) {
            val address=try { EmbyApi.normalizeServer(server.text.toString()) } catch(e: IllegalArgumentException) {
                status.text=e.message;status.setTextColor(TvUi.error);server.requestFocus();return@button
            }
            if(username.text.isBlank()) {
                status.text="请输入用户名";status.setTextColor(TvUi.error);username.requestFocus();return@button
            }
            val name=username.text.toString().trim();val secret=password.text.toString()
            submit.isEnabled=false;status.setTextColor(TvUi.accent);status.text="正在连接…"
            lifecycleScope.launch {
                try {
                    val session=app.api.login(address,name,secret)
                    withContext(Dispatchers.IO) { app.sessions.save(session) }
                    password.text.clear()
                    startActivity(Intent(this@LoginActivity,MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    status.text=when(e) {
                        is UnknownHostException -> "找不到服务器，请检查地址。"
                        is SocketTimeoutException -> "连接超时，请稍后重试。"
                        is SSLException -> "HTTPS 证书验证失败，请检查服务器证书。"
                        is ApiException -> if(e.status in listOf(401,403)) "账号或密码不正确，或账号没有访问权限。" else e.message
                        else -> e.message ?: "连接失败，请检查网络。"
                    }
                    status.setTextColor(TvUi.error);submit.isEnabled=true;submit.requestFocus()
                }
            }
        }
        password.imeOptions=EditorInfo.IME_ACTION_GO
        password.setOnEditorActionListener { _,action,_ ->
            if(action==EditorInfo.IME_ACTION_GO && submit.isEnabled) { submit.performClick();true } else false
        }
        TvUi.add(form,submit,bottom=10);TvUi.add(form,status,bottom=0)
        if(server.text.isNotBlank()) username.requestFocus() else server.requestFocus()
    }
}
