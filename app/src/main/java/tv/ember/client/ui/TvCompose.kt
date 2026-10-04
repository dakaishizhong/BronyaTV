package tv.ember.client.ui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import tv.ember.client.BronyaApp
import tv.ember.client.data.*
import tv.ember.client.i18n.*

val Cyan=Color(TvUi.accent)
val Ink=Color(TvUi.bg)
val Paper=Color(TvUi.text)
val Muted=Color(TvUi.muted)
val LocalTvScale=staticCompositionLocalOf { 1f }
val LocalRailFocus=staticCompositionLocalOf<()->Unit> { {} }
fun TvActivity.tvContent(content: @Composable ()->Unit) {
    setContentView(ComposeView(this).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { TvTheme { content() } }
    })
}
@Composable fun TvTheme(content: @Composable ()->Unit) {
    val context=LocalContext.current
    CompositionLocalProvider(LocalTvScale provides TvUi.scale(context)) {
        MaterialTheme(colorScheme=darkColorScheme(primary=Cyan,onPrimary=Ink,surface=Color(TvUi.panel),onSurface=Paper)) { content() }
    }
}
@Composable fun TvAction(label: String,tag: String=label,modifier: Modifier=Modifier,primary: Boolean=false,selected: Boolean=false,
                         enabled: Boolean=true,onFocus: ()->Unit={},onClick: ()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale=LocalTvScale.current
    Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=(34*scale).dp).testTag(tag)
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }
        .border(if(focused || selected) 1.5.dp else 1.dp,if(focused || selected) Cyan else Color(0xFF264550),RoundedCornerShape(8.dp)),
        shape=ButtonDefaults.shape(shape=RoundedCornerShape(8.dp)),border=ButtonDefaults.border(focusedBorder=Border.None),
        scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=if(primary) Cyan else if(selected) Cyan.copy(alpha=.13f) else Color(TvUi.panel),
            contentColor=if(primary) Ink else Paper,focusedContainerColor=if(primary) Color(0xFF8AFFFF) else Cyan.copy(alpha=.18f),focusedContentColor=if(primary) Ink else Cyan),
        contentPadding=PaddingValues(horizontal=(12*scale).dp,vertical=(5*scale).dp)) {
        Text(label,fontSize=(14*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
@Composable fun TvField(value: String,onValue: (String)->Unit,label: String,tag: String,modifier: Modifier=Modifier,
                        secret: Boolean=false,uri: Boolean=false,onSubmit: ()->Unit={}) {
    var focused by remember { mutableStateOf(false) }
    val scale=LocalTvScale.current;val keyboard=LocalSoftwareKeyboardController.current;val focusManager=LocalFocusManager.current
    BasicTextField(value,onValue,modifier.heightIn(min=(40*scale).dp).testTag(tag)
        .onFocusChanged { focused=it.isFocused }.onPreviewKeyEvent {
            when(it.key) {
                Key.DirectionUp,Key.DirectionDown -> {
                    if(it.type==KeyEventType.KeyDown) focusManager.moveFocus(if(it.key==Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                    true
                }
                Key.DirectionCenter -> { if(it.type==KeyEventType.KeyDown) keyboard?.show();true }
                else -> false
            }
        }.background(Color(TvUi.panel),RoundedCornerShape(8.dp)).border(1.dp,if(focused) Cyan else Color(0xFF35505C),RoundedCornerShape(8.dp)).padding((12*scale).dp),
        singleLine=true,textStyle=TextStyle(color=Paper,fontSize=(15*scale).sp),cursorBrush=SolidColor(Cyan),
        visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(secret) KeyboardType.Password else if(uri) KeyboardType.Uri else KeyboardType.Text,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={ keyboard?.hide();onSubmit() }),
        decorationBox={ inner -> Box { if(value.isEmpty()) Text(label,color=Muted,fontSize=(15*scale).sp);inner() } })
}
@Composable fun TvClock() {
    var now by remember { mutableStateOf(java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date())) }
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(15000);now=java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date()) } }
    Text(now,color=Paper,fontSize=(12*LocalTvScale.current).sp)
}
@Composable fun CachedImage(app: BronyaApp,session: Session,url: String,width: Int,height: Int,modifier: Modifier=Modifier) {
    val key=remember(session.server,session.userId,url,width,height) { "${session.server}:${session.userId}:$url:${width}x$height" }
    var bitmap by remember(session.server,session.userId) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val owner=LocalLifecycleOwner.current
    LaunchedEffect(key,owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        try { app.imageCache.load(session,url,width,height)?.let { bitmap=it };awaitCancellation() }
        finally { if(!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) bitmap=null }
    } }
    bitmap?.let { Image(it.asImageBitmap(),null,modifier,contentScale=ContentScale.Crop) }
}
@Composable fun Section(label: String,modifier: Modifier=Modifier) {
    Row(modifier.padding(vertical=(5*LocalTvScale.current).dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height((17*LocalTvScale.current).dp).background(Cyan,RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp));Text(label,color=Paper,fontWeight=FontWeight.Bold,fontSize=(16*LocalTvScale.current).sp)
    }
}
@Composable fun TvShell(selected: String,onNavigate: (String)->Unit,backdrop: VideoItem?=null,
                        homeRequester: FocusRequester?=null,onRailFocused: (String)->Unit={},content: @Composable ()->Unit) {
    val context=LocalContext.current;val app=context.applicationContext as BronyaApp;val session=app.sessions.load()
    val scale=LocalTvScale.current
    val names=listOf(UiText.HOME_267,UiText.MOVIES_316,UiText.SERIES_317,UiText.FAVORITES_318,UiText.SEARCH_295,UiText.SETTINGS_268)
    val labels=names.map(Tr::text);val requests=remember { List(6) { FocusRequester() } }
    Box(Modifier.fillMaxSize().background(Ink)) {
        if(backdrop!=null && session!=null) {
            val width=context.resources.displayMetrics.widthPixels.coerceAtMost(1920)
            CachedImage(app,session,app.api.landscapeUrl(session,backdrop,true,width),width,width*9/16,Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Ink,Ink.copy(alpha=.5f),Ink.copy(alpha=.12f)))))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Ink.copy(alpha=.75f),Ink))))
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(TvUi.railWidth(context).dp).fillMaxHeight().background(Ink.copy(alpha=.94f)).padding(horizontal=5.dp,vertical=(16*scale).dp)) {
                Text("▶",Modifier.align(Alignment.CenterHorizontally),color=Cyan,fontSize=(30*scale).sp)
                Text("BronyaTV",Modifier.align(Alignment.CenterHorizontally),color=Paper,fontWeight=FontWeight.Bold,fontSize=(10*scale).sp)
                Spacer(Modifier.height((24*scale).dp))
                labels.take(5).forEachIndexed { i,label ->
                    RailAction(label,listOf("⌂","◉","▣","☆","⌕")[i],selected==label,
                        Modifier.focusRequester(if(i==0 && homeRequester!=null) homeRequester else requests[i]),
                        { onRailFocused(label) }) { onNavigate(label) }
                    Spacer(Modifier.height((9*scale).dp))
                }
                Spacer(Modifier.weight(1f))
                RailAction("Emby","▶",false) {
                    TvDialogBuilder(context).setTitle("Emby").setMessage(listOf(session?.userName,session?.server).filterNotNull().joinToString("\n"))
                        .setPositiveButton(Tr.text(UiText.SETTINGS_268)) { _,_-> onNavigate(labels[5]) }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
                }
                Spacer(Modifier.height((9*scale).dp))
                RailAction(labels[5],"⚙",selected==labels[5],Modifier.focusRequester(requests[5]),{ onRailFocused(labels[5]) }) { onNavigate(labels[5]) }
            }
            CompositionLocalProvider(LocalRailFocus provides { val i=labels.indexOf(selected).coerceAtLeast(0);(if(i==0 && homeRequester!=null) homeRequester else requests[i]).requestFocus() }) {
                Box(Modifier.weight(1f).fillMaxHeight()) { content() }
            }
        }
    }
}
@Composable private fun RailAction(label: String,symbol: String,selected: Boolean,modifier: Modifier=Modifier,onFocus: ()->Unit={},onClick: ()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale=LocalTvScale.current
    Button(onClick,modifier=modifier.fillMaxWidth().height((34*scale).dp).testTag("nav_$label").semantics { this.selected=selected }
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }
        .border(if(focused || selected) 1.dp else 0.dp,if(focused || selected) Cyan else Color.Transparent,RoundedCornerShape(7.dp)),
        colors=ButtonDefaults.colors(containerColor=if(selected) Cyan.copy(alpha=.1f) else Color.Transparent,contentColor=Paper,
            focusedContainerColor=Cyan.copy(alpha=.18f),focusedContentColor=Cyan),shape=ButtonDefaults.shape(shape=RoundedCornerShape(7.dp)),border=ButtonDefaults.border(focusedBorder=Border.None),scale=ButtonDefaults.scale(focusedScale=1f),contentPadding=PaddingValues(4.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text(symbol,fontSize=(17*scale).sp);Spacer(Modifier.width(3.dp));Text(label,fontSize=(10.5f*scale).sp,maxLines=1)
        }
    }
}
fun TvActivity.navigateTo(name: String) {
    if(name==Tr.text(UiText.SETTINGS_268)) startActivity(Intent(this,SettingsActivity::class.java))
    else startActivity(Intent(this,MainActivity::class.java).putExtra("navigate",name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
}
