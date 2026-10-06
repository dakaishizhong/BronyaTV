package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.*
import tv.ember.client.emby.EmbyApi
import tv.ember.client.data.PublicUser
import tv.ember.client.i18n.*
import tv.ember.client.network.ApiException

class LoginActivity: TvActivity() {
    @OptIn(ExperimentalLayoutApi::class,ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tvContent {
            val scale=LocalTvScale.current
            var server by rememberSaveable { mutableStateOf(app.sessions.lastServer) }
            var name by rememberSaveable { mutableStateOf(app.sessions.lastUserName) }
            // Password is deliberately neither saveable nor persisted.
            var password by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            var status by remember { mutableStateOf("") }
            var users by remember { mutableStateOf<List<PublicUser>>(emptyList()) }
            LaunchedEffect(server) {
                users=emptyList()
                val address=runCatching { EmbyApi.normalizeServer(server) }.getOrNull() ?: return@LaunchedEffect
                delay(800)
                users=try { app.api.publicUsers(address) } catch(e: CancellationException) { throw e } catch(_: Exception) { emptyList() }
            }
            val serverEmptyAtStart=remember { server.isBlank() }
            val first=remember { FocusRequester() };val submitFocus=remember { FocusRequester() }
            val passwordFocus=remember { FocusRequester() };val imeVisible=WindowInsets.isImeVisible
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
            TvShell(Tr.text(UiText.LOGIN_SCREEN),::navigateTo) {
            Column(Modifier.fillMaxSize().padding((48*scale).dp)) {
                Row(Modifier.fillMaxWidth().padding(bottom=(12*scale).dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                    Column {
                        MetaBadge("EMBY CINEMA MAX",Cyan)
                        Spacer(Modifier.height((4*scale).dp))
                        Text(Tr.text(UiText.CINEMA_LOGIN_TITLE),color=Paper,fontSize=(28*scale).sp,fontWeight=FontWeight.Bold)
                        Text(Tr.text(UiText.CINEMA_LOGIN_DESCRIPTION),color=Muted,fontSize=(12*scale).sp)
                    }
                    MetaBadge("Android TV v${tv.ember.client.BuildConfig.VERSION_NAME}",Cyan)
                }
                Spacer(Modifier.height((24*scale).dp))
                Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy((28*scale).dp)) {
                    Column(Modifier.weight(5f).fillMaxHeight().background(Color(TvUi.raised),RoundedCornerShape(24.dp))
                        .border(1.dp,CardBorder,RoundedCornerShape(24.dp)).verticalScroll(rememberScrollState()).padding((24*scale).dp)) {
                        Text(Tr.text(UiText.CHOOSE_LOGIN_USER),color=Paper,fontSize=(16*scale).sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height((14*scale).dp))
                        val sameServer=runCatching { EmbyApi.normalizeServer(server)==EmbyApi.normalizeServer(app.sessions.lastServer) }.getOrDefault(false)
                        val profiles=users.ifEmpty { app.sessions.lastUserName.takeIf { sameServer && it.isNotBlank() }?.let { listOf(PublicUser("recent",it,true)) }.orEmpty() }
                        profiles.forEach { user ->
                            UserProfileCard(user,name==user.name,enabled=!busy) { name=user.name;password="";status="" }
                            Spacer(Modifier.height((14*scale).dp))
                        }
                    }
                    Column(Modifier.weight(7f).fillMaxHeight().background(Color(TvUi.raised),RoundedCornerShape(24.dp))
                        .border(1.dp,CardBorder,RoundedCornerShape(24.dp)).padding((24*scale).dp),verticalArrangement=Arrangement.SpaceBetween) {
                        Column(verticalArrangement=Arrangement.spacedBy((8*scale).dp)) {
                            Text(Tr.text(UiText.ACCOUNT_CREDENTIALS),color=Paper,fontSize=(16*scale).sp,fontWeight=FontWeight.Bold)
                            Column(verticalArrangement=Arrangement.spacedBy((4*scale).dp)) {
                                Text(Tr.text(UiText.CINEMA_SERVER),color=Muted,fontSize=(12*scale).sp)
                                TvField(server,{ server=it },"https://emby.cinema-home.tv:443","login_server",Modifier.fillMaxWidth().then(if(serverEmptyAtStart) Modifier.focusRequester(first) else Modifier),uri=true,icon=TvGlyph.Server)
                            }
                            Column(verticalArrangement=Arrangement.spacedBy((4*scale).dp)) {
                                Text(Tr.text(UiText.CINEMA_USER),color=Muted,fontSize=(12*scale).sp)
                                TvField(name,{ name=it },Tr.text(UiText.USERNAME_280),"login_username",Modifier.fillMaxWidth().then(if(!serverEmptyAtStart) Modifier.focusRequester(first) else Modifier),icon=TvGlyph.Account)
                            }
                            Column(verticalArrangement=Arrangement.spacedBy((4*scale).dp)) {
                                Text(Tr.text(UiText.PASSWORD_281),color=Muted,fontSize=(12*scale).sp)
                                TvField(password,{ password=it },"••••••••","login_password",Modifier.fillMaxWidth().focusRequester(passwordFocus),secret=true,icon=TvGlyph.Lock,onSubmit=::submit)
                            }
                            if(status.isNotBlank()) Text(status,color=if(busy) Cyan else Color(TvUi.error),fontSize=(12*scale).sp)
                        }
                        TvAction(Tr.text(UiText.CINEMA_LOGIN_BUTTON),"login_connect",Modifier.fillMaxWidth().height((56*scale).dp).focusRequester(submitFocus)
                            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                                // Keep the closed-keyboard return path on this page.
                                if(!imeVisible && event.key==Key.DirectionUp) {
                                    if(event.type==KeyEventType.KeyDown) passwordFocus.requestFocus()
                                    true
                                } else false
                            },primary=true,enabled=!busy,large=true,onClick=::submit)
                    }
                }
            }
            }
            LaunchedEffect(Unit) { first.requestFocus() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class,ExperimentalComposeUiApi::class)
@Composable private fun UserProfileCard(user: PublicUser,selected: Boolean,enabled: Boolean,onClick: ()->Unit) {
    val scale=LocalTvScale.current;var focused by remember { mutableStateOf(false) };val shape=RoundedCornerShape(16.dp)
    val focusManager=LocalFocusManager.current;val imeVisible=WindowInsets.isImeVisible
    Row(Modifier.fillMaxWidth().testTag("login_user_${user.id}").onFocusChanged { focused=it.isFocused }
        .onPreInterceptKeyBeforeSoftKeyboard { event ->
            if(!imeVisible && event.key in listOf(Key.DirectionUp,Key.DirectionDown,Key.DirectionLeft,Key.DirectionRight)) {
                if(event.type==KeyEventType.KeyDown) focusManager.moveFocus(when(event.key) {
                    Key.DirectionUp -> FocusDirection.Up
                    Key.DirectionDown -> FocusDirection.Down
                    Key.DirectionLeft -> FocusDirection.Left
                    else -> FocusDirection.Right
                })
                true
            } else false
        }
        .background(if(selected || focused) Cyan.copy(alpha=.12f) else Color(TvUi.panel),shape)
        .border(if(focused) 2.dp else 1.dp,if(focused || selected) Cyan else CardBorder,shape)
        .clickable(enabled=enabled,onClick=onClick).padding((14*scale).dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((12*scale).dp)) {
        Box(Modifier.size((42*scale).dp).background(Cyan,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
            Text(user.avatarInitials.ifBlank { user.name.take(2).uppercase() },color=Ink,fontSize=(16*scale).sp,fontWeight=FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement=Arrangement.spacedBy((6*scale).dp),verticalAlignment=Alignment.CenterVertically) {
                Text(user.name,color=Paper,fontSize=(14*scale).sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(user.tag.isNotBlank()) MetaBadge(user.tag,Cyan)
            }
            Text(user.description.ifBlank { Tr.text(if(user.hasPassword) UiText.PASSWORD_REQUIRED else UiText.NO_PASSWORD_REQUIRED) },color=Muted,fontSize=(11*scale).sp)
        }
        if(selected) TvIcon(TvGlyph.Check,Cyan,Modifier.size((20*scale).dp))
    }
}
