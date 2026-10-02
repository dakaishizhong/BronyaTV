package tv.ember.client.ui

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
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
        TvUi.add(hero,TvUi.text(this,Tr.text(UiText.GREAT_STORIES_ON_THE_BIG_SCREEN_274),40f).apply { setLineSpacing(0f,1.18f) },bottom=20)
        TvUi.add(hero,TvUi.text(this,Tr.text(UiText.MOVIES_SERIES_YOUR_LIBRARY_275),15f,TvUi.muted))
        root.addView(hero,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=TvUi.dp(root,48) })
        val scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        val form=TvUi.column(this).apply {
            gravity=Gravity.CENTER_VERTICAL
            setPadding(TvUi.dp(this,28),TvUi.dp(this,22),TvUi.dp(this,28),TvUi.dp(this,22))
            background=TvUi.box(TvUi.panel,32f)
        }
        scroll.addView(form);root.addView(scroll,LinearLayout.LayoutParams(TvUi.dp(root,400),-1));setContentView(root)
        TvUi.add(form,TvUi.text(this,Tr.text(UiText.SIGN_IN_TO_YOUR_LIBRARY_276),26f),bottom=4)
        TvUi.add(form,TvUi.button(this,Tr.text(UiText.LANGUAGE)) { TvUi.chooseLanguage(this) }.apply { tag="interface_language" },height=TvUi.dp(form,36),bottom=8)
        TvUi.add(form,TvUi.text(this,Tr.text(UiText.SIGN_IN_ONCE_CONTINUE_WATCHING_NEXT_277),13f,TvUi.muted),bottom=22)
        TvUi.add(form,TvUi.text(this,Tr.text(UiText.SERVER_URL_278),12f,TvUi.muted),bottom=5)
        val server=TvUi.input(this,"https://emby.example.com",uri=true).apply { setText(app.sessions.lastServer) }
        TvUi.add(form,server,bottom=12)
        TvUi.add(form,TvUi.text(this,Tr.text(UiText.ACCOUNT_279),12f,TvUi.muted),bottom=5)
        val username=TvUi.input(this,Tr.text(UiText.USERNAME_280)).apply { setText(app.sessions.lastUserName) }
        TvUi.add(form,username,bottom=12)
        TvUi.add(form,TvUi.text(this,Tr.text(UiText.PASSWORD_281),12f,TvUi.muted),bottom=5)
        val password=TvUi.input(this,Tr.text(UiText.PASSWORD_281),secret=true)
        TvUi.add(form,password,bottom=3)
        val reveal=CheckBox(this).apply {
            text=Tr.text(UiText.SHOW_PASSWORD_282);textSize=13f;setTextColor(TvUi.muted);isFocusable=true
            setOnCheckedChangeListener { _,checked ->
                password.transformationMethod=if(checked) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                password.setSelection(password.text.length)
            }
        }
        TvUi.add(form,reveal,bottom=10)
        val status=TvUi.text(this,"",13f,TvUi.muted).apply { minLines=2 }
        lateinit var submit: Button
        submit=TvUi.button(this,Tr.text(UiText.CONNECT_283),true) {
            val address=try { EmbyApi.normalizeServer(server.text.toString()) } catch(e: IllegalArgumentException) {
                status.text=e.message;status.setTextColor(TvUi.error);server.requestFocus();return@button
            }
            if(username.text.isBlank()) {
                status.text=Tr.text(UiText.ENTER_YOUR_USERNAME_284);status.setTextColor(TvUi.error);username.requestFocus();return@button
            }
            val name=username.text.toString().trim();val secret=password.text.toString()
            submit.isEnabled=false;status.setTextColor(TvUi.accent);status.text=Tr.text(UiText.CONNECTING_285)
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
                        is UnknownHostException -> Tr.text(UiText.SERVER_NOT_FOUND_CHECK_THE_URL_286)
                        is SocketTimeoutException -> Tr.text(UiText.CONNECTION_TIMED_OUT_TRY_AGAIN_LATER_287)
                        is SSLException -> Tr.text(UiText.HTTPS_CERTIFICATE_VALIDATION_FAILED_CHECK_THE_288)
                        is ApiException -> if(e.status in listOf(401,403)) Tr.text(UiText.INCORRECT_CREDENTIALS_OR_ACCOUNT_ACCESS_DENIED_289) else e.message
                        else -> e.message ?: Tr.text(UiText.CONNECTION_FAILED_CHECK_YOUR_NETWORK_290)
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
