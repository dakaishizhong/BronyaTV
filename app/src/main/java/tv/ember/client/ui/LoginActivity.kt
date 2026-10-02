package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.Gravity
import android.view.View
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
    private var tokenMode=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=TvUi.row(this).apply {
            background=TvUi.canvas()
            setPadding(TvUi.dp(this,48),TvUi.dp(this,28),TvUi.dp(this,48),TvUi.dp(this,28))
        }
        val hero=TvUi.column(this).apply { gravity=Gravity.CENTER_VERTICAL }
        TvUi.add(hero,TvUi.text(this,"BronyaTV",23f,TvUi.accent).apply { letterSpacing=.16f },bottom=28)
        TvUi.add(hero,TvUi.text(this,"让每一幕\n都更精彩",42f).apply { setLineSpacing(0f,1.2f) },bottom=22)
        TvUi.add(hero,TvUi.text(this,"你的影音库，大屏上的新体验。",17f,TvUi.muted),bottom=20)
        TvUi.add(hero,TvUi.text(this,"连接  ·  选择  ·  开始观看",14f,TvUi.accent))
        root.addView(hero,LinearLayout.LayoutParams(0,-1,.43f).apply { marginEnd=TvUi.dp(root,34) })
        val scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        val form=TvUi.column(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(TvUi.dp(this,22),TvUi.dp(this,18),TvUi.dp(this,22),TvUi.dp(this,18))
            background=TvUi.box(0xB0172230.toInt(),24f)
        }
        scroll.addView(form);root.addView(scroll,LinearLayout.LayoutParams(0,-1,.57f));setContentView(root)
        TvUi.add(form,TvUi.text(this,"连接你的媒体库",27f),bottom=4)
        TvUi.add(form,TvUi.text(this,"登录信息加密保存，密码不会保存。",13f,TvUi.muted),bottom=18)
        TvUi.add(form,TvUi.text(this,"服务器地址",13f,TvUi.muted),bottom=6)
        val server=TvUi.input(this,"https://emby.example.com",uri=true).apply { setText(app.sessions.lastServer) }
        TvUi.add(form,server,bottom=12)
        val modes=TvUi.row(this)
        lateinit var accountButton: Button
        lateinit var tokenButton: Button
        val account=TvUi.column(this)
        val advanced=TvUi.column(this)
        val username=TvUi.input(this,"用户名").apply { setText(app.sessions.lastUserName) }
        val password=TvUi.input(this,"密码",secret=true)
        val token=TvUi.input(this,"Token",secret=true)
        val userId=TvUi.input(this,"用户 ID（仅 API Key 需要）")
        TvUi.add(account,username,bottom=9);TvUi.add(account,password,bottom=4)
        TvUi.add(advanced,token,bottom=9);TvUi.add(advanced,userId,bottom=4)
        fun switchMode(value: Boolean) {
            tokenMode=value;account.visibility=if(value) View.GONE else View.VISIBLE
            advanced.visibility=if(value) View.VISIBLE else View.GONE
            accountButton.isSelected=!value;tokenButton.isSelected=value
        }
        accountButton=TvUi.button(this,"账号登录") { switchMode(false);username.requestFocus() }
        tokenButton=TvUi.button(this,"Token 登录") { switchMode(true);token.requestFocus() }
        modes.addView(accountButton,LinearLayout.LayoutParams(0,-2,1f).apply { marginEnd=TvUi.dp(modes,8) })
        modes.addView(tokenButton,LinearLayout.LayoutParams(0,-2,1f))
        TvUi.add(form,modes,bottom=12);TvUi.add(form,account,bottom=2);TvUi.add(form,advanced,bottom=2)
        switchMode(false)
        val reveal=CheckBox(this).apply {
            text="显示密码 / Token";textSize=13f;setTextColor(TvUi.muted);isFocusable=true
            setOnCheckedChangeListener { _,checked ->
                val transformation=if(checked) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                password.transformationMethod=transformation;token.transformationMethod=transformation
            }
        }
        TvUi.add(form,reveal,bottom=8)
        val status=TvUi.text(this,"支持 Emby 地址和反向代理路径；省略协议时使用 HTTPS。",13f,TvUi.muted)
        lateinit var submit: Button
        submit=TvUi.button(this,"连接服务器",true) {
            val address=try { EmbyApi.normalizeServer(server.text.toString()) } catch(e: IllegalArgumentException) {
                status.text=e.message;status.setTextColor(TvUi.error);server.requestFocus();return@button
            }
            if((tokenMode && token.text.isBlank()) || (!tokenMode && username.text.isBlank())) {
                status.text=if(tokenMode) "请输入 Token" else "请输入用户名"
                status.setTextColor(TvUi.error);(if(tokenMode) token else username).requestFocus();return@button
            }
            val loginName=username.text.toString().trim();val loginPassword=password.text.toString()
            val loginToken=token.text.toString().trim();val loginId=userId.text.toString().trim()
            val useToken=tokenMode
            submit.isEnabled=false;accountButton.isEnabled=false;tokenButton.isEnabled=false
            status.setTextColor(TvUi.accent);status.text="正在连接服务器…"
            lifecycleScope.launch {
                try {
                    val session=if(useToken) app.api.tokenLogin(address,loginToken,loginId) else app.api.login(address,loginName,loginPassword)
                    withContext(Dispatchers.IO) { app.sessions.save(session) }
                    password.text.clear();token.text.clear()
                    startActivity(Intent(this@LoginActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                } catch(e: CancellationException) { throw e } catch(e: Exception) {
                    status.text=when(e) {
                        is UnknownHostException -> "找不到服务器，请检查地址或网络。"
                        is SocketTimeoutException -> "连接超时，请确认服务器可以访问后重试。"
                        is SSLException -> "HTTPS 证书验证失败，请检查服务器证书或地址。"
                        is ApiException -> if(e.status in listOf(401,403)) {
                            if(useToken) "Token 无效或账号没有访问权限。" else "用户名或密码不正确，或账号没有访问权限。"
                        } else e.message
                        else -> e.message ?: "连接失败，请检查网络和登录信息。"
                    }
                    status.setTextColor(TvUi.error);submit.isEnabled=true;accountButton.isEnabled=true;tokenButton.isEnabled=true
                    submit.requestFocus()
                }
            }
        }
        password.imeOptions=EditorInfo.IME_ACTION_GO;token.imeOptions=EditorInfo.IME_ACTION_GO
        for(field in listOf(password,token)) field.setOnEditorActionListener { _,action,_ ->
            if(action==EditorInfo.IME_ACTION_GO && submit.isEnabled) { submit.performClick();true } else false
        }
        TvUi.add(form,submit,bottom=10);TvUi.add(form,status,bottom=0)
        if(server.text.isNotBlank()) username.requestFocus() else server.requestFocus()
    }
}
