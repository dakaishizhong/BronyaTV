package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.*
import tv.ember.client.emby.EmbyApi
import tv.ember.client.i18n.*
import tv.ember.client.network.ApiException

class LoginActivity: TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tvContent {
            val scale=LocalTvScale.current
            var server by rememberSaveable { mutableStateOf(app.sessions.lastServer) }
            var name by rememberSaveable { mutableStateOf(app.sessions.lastUserName) }
            // Password is deliberately neither saveable nor persisted.
            var password by remember { mutableStateOf("") }
            var reveal by remember { mutableStateOf(false) }
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            val serverEmptyAtStart=remember { server.isBlank() }
            val first=remember { FocusRequester() };val submitFocus=remember { FocusRequester() }
            fun submit() {
                if(busy) return
                val address=try { EmbyApi.normalizeServer(server) } catch(e: IllegalArgumentException) { status=e.message.orEmpty();return }
                if(name.isBlank()) { status=Tr.text(UiText.ENTER_YOUR_USERNAME_284);return }
                busy=true;status=Tr.text(UiText.CONNECTING_285)
                lifecycleScope.launch {
                    try {
                        val session=app.api.login(address,name.trim(),password)
                        withContext(Dispatchers.IO) { app.sessions.save(session) };password=""
                        startActivity(Intent(this@LoginActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()
                    } catch(e: CancellationException) { throw e } catch(e: Exception) {
                        status=when(e) {
                            is java.net.UnknownHostException -> Tr.text(UiText.SERVER_NOT_FOUND_CHECK_THE_URL_286)
                            is java.net.SocketTimeoutException -> Tr.text(UiText.CONNECTION_TIMED_OUT_TRY_AGAIN_LATER_287)
                            is javax.net.ssl.SSLException -> Tr.text(UiText.HTTPS_CERTIFICATE_VALIDATION_FAILED_CHECK_THE_288)
                            is ApiException -> if(e.status in listOf(401,403)) Tr.text(UiText.INCORRECT_CREDENTIALS_OR_ACCOUNT_ACCESS_DENIED_289) else e.message.orEmpty()
                            else -> e.message ?: Tr.text(UiText.CONNECTION_FAILED_CHECK_YOUR_NETWORK_290)
                        }
                        busy=false;submitFocus.requestFocus()
                    }
                }
            }
            Row(Modifier.fillMaxSize().background(Ink).padding((32*scale).dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((28*scale).dp)) {
                Column(Modifier.weight(1f)) {
                    Text("BronyaTV",color=Cyan,fontSize=(26*scale).sp)
                    Spacer(Modifier.height((28*scale).dp));Text(Tr.text(UiText.GREAT_STORIES_ON_THE_BIG_SCREEN_274),color=Paper,fontSize=(37*scale).sp,lineHeight=(44*scale).sp)
                    Spacer(Modifier.height((18*scale).dp));Text(Tr.text(UiText.MOVIES_SERIES_YOUR_LIBRARY_275),color=Muted,fontSize=(15*scale).sp)
                }
                Column(Modifier.width((370*scale).dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding((18*scale).dp),verticalArrangement=Arrangement.Center) {
                    Text(Tr.text(UiText.SIGN_IN_TO_YOUR_LIBRARY_276),color=Paper,fontSize=(25*scale).sp)
                    Spacer(Modifier.height(8.dp));TvAction(Tr.text(UiText.LANGUAGE),"interface_language") { TvUi.chooseLanguage(this@LoginActivity) }
                    Spacer(Modifier.height(12.dp))
                    Text(Tr.text(UiText.SERVER_URL_278),color=Muted)
                    TvField(server,{ server=it },"https://emby.example.com","login_server",Modifier.fillMaxWidth().then(if(serverEmptyAtStart) Modifier.focusRequester(first) else Modifier),uri=true)
                    Spacer(Modifier.height(10.dp));Text(Tr.text(UiText.ACCOUNT_279),color=Muted)
                    TvField(name,{ name=it },Tr.text(UiText.USERNAME_280),"login_username",Modifier.fillMaxWidth().then(if(!serverEmptyAtStart) Modifier.focusRequester(first) else Modifier))
                    Spacer(Modifier.height(10.dp));Text(Tr.text(UiText.PASSWORD_281),color=Muted)
                    TvField(password,{ password=it },Tr.text(UiText.PASSWORD_281),"login_password",Modifier.fillMaxWidth(),secret=!reveal,onSubmit=::submit)
                    Spacer(Modifier.height(6.dp));TvAction(Tr.text(UiText.SHOW_PASSWORD_282),"login_reveal",selected=reveal) { reveal=!reveal }
                    Spacer(Modifier.height(12.dp));TvAction(Tr.text(UiText.CONNECT_283),"login_connect",Modifier.fillMaxWidth().focusRequester(submitFocus),primary=true,enabled=!busy,onClick=::submit)
                    Spacer(Modifier.height(8.dp));Text(status,color=if(busy) Cyan else Color(TvUi.error),fontSize=(13*scale).sp)
                }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
    }
}
