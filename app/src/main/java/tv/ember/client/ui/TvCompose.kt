package tv.ember.client.ui

import android.content.Intent
import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
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
        MaterialTheme(colorScheme=darkColorScheme(primary=Cyan,onPrimary=Ink,background=Ink,onBackground=Paper,surface=Color(TvUi.panel),onSurface=Paper)) { content() }
    }
}
@Composable fun TvAction(label: String,tag: String=label,modifier: Modifier=Modifier,primary: Boolean=false,selected: Boolean=false,
                         enabled: Boolean=true,large: Boolean=false,onFocus: ()->Unit={},onClick: ()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale=LocalTvScale.current
    val icon=actionGlyph(tag)
    val caption=if(icon!=null) label.removePrefix("▶").removePrefix("ⓘ").trimStart() else label
    val shape=RoundedCornerShape(if(large) 14.dp else 12.dp)
    Button(onClick=onClick,enabled=enabled,modifier=modifier.heightIn(min=(36*scale).dp).testTag(tag).semantics { this.selected=selected }
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }
        .shadow(if(focused) (18*scale).dp else 0.dp,shape,clip=false,ambientColor=Cyan.copy(alpha=.25f),spotColor=Cyan.copy(alpha=.25f))
        .border(if(focused) 1.5.dp else 1.dp,if(focused) Cyan else if(selected) Cyan.copy(alpha=.65f) else Paper.copy(alpha=.13f),shape),
        shape=ButtonDefaults.shape(shape=shape),border=ButtonDefaults.border(focusedBorder=Border.None),
        scale=ButtonDefaults.scale(focusedScale=if(large) 1.02f else 1f),
        colors=ButtonDefaults.colors(containerColor=if(primary) Cyan else if(selected) Cyan.copy(alpha=.12f) else Color(TvUi.panel).copy(alpha=.88f),
            contentColor=if(primary) Ink else Paper,focusedContainerColor=if(primary) (if(large) Cyan else Color(0xFF69E5B9)) else Cyan.copy(alpha=.15f),focusedContentColor=if(primary) Ink else Cyan),
        contentPadding=PaddingValues(horizontal=((if(large) 28 else 12)*scale).dp,vertical=((if(large) 14 else 6)*scale).dp)) {
        Row(if(tag=="login_connect") Modifier.fillMaxWidth() else Modifier,horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
        icon?.let { TvIcon(it,if(primary) Ink else if(focused) Cyan else Paper,Modifier.size((16*scale).dp));Spacer(Modifier.width((7*scale).dp)) }
        Text(caption,fontSize=((if(large) 16 else 14)*scale).sp,fontWeight=if(large) FontWeight.Bold else FontWeight.Normal,lineHeight=((if(large) 22 else 18)*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
@Composable fun TvIconAction(glyph: TvGlyph,label: String,tag: String,onClick: ()->Unit) {
    val scale=LocalTvScale.current;var focused by remember { mutableStateOf(false) };val shape=RoundedCornerShape(8.dp)
    Button(onClick,modifier=Modifier.size((36*scale).dp).testTag(tag).semantics { contentDescription=label }.onFocusChanged { focused=it.isFocused }
        .border(1.dp,if(focused) Cyan else Paper.copy(alpha=.12f),shape),shape=ButtonDefaults.shape(shape),border=ButtonDefaults.border(focusedBorder=Border.None),
        scale=ButtonDefaults.scale(focusedScale=1f),colors=ButtonDefaults.colors(containerColor=Color(TvUi.panel).copy(alpha=.75f),focusedContainerColor=Cyan.copy(alpha=.15f),focusedContentColor=Cyan),contentPadding=PaddingValues(0.dp)) {
        TvIcon(glyph,if(focused) Cyan else Paper,Modifier.size((18*scale).dp))
    }
}
@OptIn(ExperimentalLayoutApi::class,ExperimentalComposeUiApi::class)
@Composable fun TvField(value: String,onValue: (String)->Unit,label: String,tag: String,modifier: Modifier=Modifier,
                        secret: Boolean=false,uri: Boolean=false,icon: TvGlyph?=null,onSubmit: ()->Unit={}) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) };var keyboardWasVisible by remember { mutableStateOf(false) }
    var editingBack by remember { mutableStateOf(false) }
    val scale=LocalTvScale.current;val keyboard=LocalSoftwareKeyboardController.current;val focusManager=LocalFocusManager.current
    val imeVisible=WindowInsets.isImeVisible;val backOwner=LocalView.current.findViewTreeOnBackPressedDispatcherOwner()
    // Include the show/hide transition: the IME can own keys before its inset arrives,
    // and must keep them until its window has actually closed.
    fun imeOwnsKeys()=editing || imeVisible
    fun stopEditing() { editing=false }
    fun navigate(key:Key) { focusManager.moveFocus(when(key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionLeft -> FocusDirection.Left
        else -> FocusDirection.Right
    }) }
    LaunchedEffect(imeVisible,focused) {
        if(imeVisible && focused) { editing=true;keyboardWasVisible=true }
        else if(!imeVisible && keyboardWasVisible) { stopEditing();keyboardWasVisible=false }
    }
    LaunchedEffect(editing,focused) {
        if(editing && focused) {
            // A newly focused editor may still be binding its input connection.
            // Retry after its first frame, and cancel when Back ends editing.
            withFrameNanos { }
            if(editing && focused && !keyboardWasVisible) keyboard?.show()
        }
    }
    DisposableEffect(backOwner,focused,imeOwnsKeys()) {
        val callback=object:OnBackPressedCallback(focused && imeOwnsKeys()) {
            override fun handleOnBackPressed() { stopEditing();keyboard?.hide() }
        }
        backOwner?.onBackPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }
    BasicTextField(value,onValue,modifier.heightIn(min=(40*scale).dp).testTag(tag)
        .onPreInterceptKeyBeforeSoftKeyboard { event ->
            when {
                event.key==Key.Back && (imeOwnsKeys() || editingBack) -> {
                    if(event.type==KeyEventType.KeyDown) { editingBack=true;stopEditing();keyboard?.hide() }
                    else if(event.type==KeyEventType.KeyUp) editingBack=false
                    true
                }
                !imeOwnsKeys() && event.key in listOf(Key.DirectionUp,Key.DirectionDown,Key.DirectionLeft,Key.DirectionRight) -> {
                    if(event.type==KeyEventType.KeyDown) navigate(event.key)
                    true
                }
                else -> false
            }
        }
        .onFocusChanged { focused=it.isFocused;if(!it.isFocused) { stopEditing();keyboardWasVisible=false } }.onPreviewKeyEvent {
            when(it.key) {
                Key.DirectionUp,Key.DirectionDown,Key.DirectionLeft,Key.DirectionRight -> {
                    // Pre-IME dispatch above lets the keyboard select its keys first.
                    // Swallow any keys it returns while editing so Compose's fallback
                    // focus navigation cannot move the login page behind the keyboard.
                    if(!imeOwnsKeys() && it.type==KeyEventType.KeyDown) navigate(it.key)
                    true
                }
                Key.DirectionCenter -> {
                    if(!imeOwnsKeys() && it.type==KeyEventType.KeyDown) { editing=true;keyboard?.show() }
                    true
                }
                else -> false
            }
        }.background(Color(TvUi.panel),RoundedCornerShape(12.dp)).border(1.dp,if(focused) Cyan else CardBorder,RoundedCornerShape(12.dp)).padding((12*scale).dp),
        singleLine=true,textStyle=TextStyle(color=Paper,fontSize=((if(icon==null) 15 else 14)*scale).sp),cursorBrush=SolidColor(Cyan),
        visualTransformation=if(secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=if(secret) KeyboardType.Password else if(uri) KeyboardType.Uri else KeyboardType.Text,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={ stopEditing();keyboard?.hide();onSubmit() }),
        decorationBox={ inner -> Row(verticalAlignment=Alignment.CenterVertically) {
            if(icon!=null) { TvIcon(icon,Cyan,Modifier.size((18*scale).dp));Spacer(Modifier.width((10*scale).dp)) }
            Box(Modifier.weight(1f)) { if(value.isEmpty()) Text(label,color=Muted,fontSize=(14*scale).sp);inner() }
        } })
}
@Composable fun TvClock() {
    var now by remember { mutableStateOf(java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date())) }
    LaunchedEffect(Unit) { while(true) { kotlinx.coroutines.delay(15000);now=java.text.SimpleDateFormat("HH:mm",java.util.Locale.ROOT).format(java.util.Date()) } }
    Text(now,color=Paper,fontSize=(12*LocalTvScale.current).sp)
}
@Composable fun CachedImage(app: BronyaApp,session: Session,url: String,width: Int,height: Int,modifier: Modifier=Modifier) {
    val key=remember(session.server,session.userId,url,width,height) { "${session.server}:${session.userId}:$url:${width}x$height" }
    var bitmap by remember(key) { mutableStateOf<android.graphics.Bitmap?>(null) }
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
                        homeRequester: FocusRequester?=null,onRailFocused: (String)->Unit={},onContentFocus:(()->Boolean)?=null,content: @Composable ()->Unit) {
    val context=LocalContext.current;val app=context.applicationContext as BronyaApp;val session=app.sessions.load()
    val scale=LocalTvScale.current
    val names=listOf(UiText.HOME_267,UiText.MOVIES_316,UiText.SERIES_317,UiText.SETTINGS_268)
    val labels=names.map(Tr::text);val requests=remember { List(4) { FocusRequester() } }
    var expanded by remember { mutableStateOf(false) }
    val railWidth by animateDpAsState((if(expanded) 152 else 56).times(scale).dp,tween(280),label="sidebar_width")
    Row(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.width(railWidth).fillMaxHeight().testTag("navigation_sidebar")
            .onFocusChanged { expanded=it.hasFocus }.focusGroup()
            .background(Color(0xFF06070A).copy(alpha=.98f)).border(1.dp,CardBorder)
            .padding(horizontal=(8*scale).dp,vertical=(24*scale).dp),verticalArrangement=Arrangement.SpaceBetween) {
            RailAction(Tr.text(UiText.LOGIN_SCREEN),TvGlyph.Account,selected==Tr.text(UiText.LOGIN_SCREEN),expanded,
                onFocus={ onRailFocused(Tr.text(UiText.LOGIN_SCREEN)) }) {
                context.startActivity(Intent(context,LoginActivity::class.java))
            }
            Column(verticalArrangement=Arrangement.spacedBy((8*scale).dp)) {
                labels.forEachIndexed { i,label ->
                    RailAction(label,listOf(TvGlyph.Home,TvGlyph.Movies,TvGlyph.Series,TvGlyph.Settings)[i],selected==label,expanded,
                        Modifier.focusRequester(if(i==0 && homeRequester!=null) homeRequester else requests[i]).onPreviewKeyEvent { event ->
                            event.key==Key.DirectionRight && event.type==KeyEventType.KeyDown && onContentFocus?.invoke()==true
                        },
                        { onRailFocused(label) }) { onNavigate(label) }
                }
            }
            Box(Modifier.height((14*scale).dp).padding(start=(6*scale).dp)) {
                if(expanded) Text("v${tv.ember.client.BuildConfig.VERSION_NAME}",color=Subtle,fontSize=(10*scale).sp)
            }
        }
        CompositionLocalProvider(LocalRailFocus provides {
            val i=labels.indexOf(selected).coerceAtLeast(0)
            (if(i==0 && homeRequester!=null) homeRequester else requests[i]).requestFocus()
        }) {
            Box(Modifier.weight(1f).fillMaxHeight().testTag("navigation_content")) {
                if(backdrop!=null && session!=null) {
                    val width=context.resources.displayMetrics.widthPixels.coerceAtMost(1920)
                    CinemaBackdrop(app,session,backdrop,width,Modifier.fillMaxWidth().fillMaxHeight(.67f))
                }
                content()
            }
        }
    }
}
@Composable private fun RailAction(label: String,icon: TvGlyph,selected: Boolean,expanded: Boolean,
                                  modifier: Modifier=Modifier,onFocus: ()->Unit={},onClick: ()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale=LocalTvScale.current;val shape=RoundedCornerShape(12.dp)
    Button(onClick,modifier=modifier.fillMaxWidth().height((44*scale).dp).testTag("nav_$label")
        .semantics { this.selected=selected;contentDescription=label }
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }
        .border(if(focused) 1.dp else 0.dp,if(focused) Cyan.copy(alpha=.65f) else Color.Transparent,shape),
        colors=ButtonDefaults.colors(containerColor=if(selected) Cyan.copy(alpha=.12f) else Color.Transparent,contentColor=Paper,
            focusedContainerColor=Cyan.copy(alpha=.25f),focusedContentColor=Cyan),shape=ButtonDefaults.shape(shape=shape),
        border=ButtonDefaults.border(focusedBorder=Border.None),scale=ButtonDefaults.scale(focusedScale=1f),contentPadding=PaddingValues(0.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.width((3*scale).dp).height((18*scale).dp).background(if(selected || focused) Cyan else Color.Transparent,RoundedCornerShape(2.dp)))
            Spacer(Modifier.width((6*scale).dp))
            TvIcon(icon,if(focused || selected) Cyan else Muted,Modifier.size((22*scale).dp))
            if(expanded) { Spacer(Modifier.width((12*scale).dp));Text(label,fontSize=(13*scale).sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis) }
        }
    }
}
fun TvActivity.navigateTo(name: String) {
    if(name==Tr.text(UiText.LOGIN_SCREEN)) startActivity(Intent(this,LoginActivity::class.java))
    else if(name==Tr.text(UiText.SETTINGS_268)) startActivity(Intent(this,SettingsActivity::class.java))
    else startActivity(Intent(this,MainActivity::class.java).putExtra("navigate",name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
}
