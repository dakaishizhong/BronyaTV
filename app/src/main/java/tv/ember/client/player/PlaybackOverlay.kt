package tv.ember.client.player

import android.view.View
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.tv.material3.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import tv.ember.client.ui.*
import tv.ember.client.i18n.*

@Stable internal class PlaybackText {
    var text by mutableStateOf<CharSequence>("")
    var visibility by mutableIntStateOf(View.GONE)
    fun append(value: CharSequence) { text=text.toString()+value }
}
internal data class PlaybackAction(val key: String,val label: String,val enabled: Boolean=true,val action: ()->Unit)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun FullscreenPlayback(view: PlayerView,player: ExoPlayer?,title: PlaybackText,info: PlaybackText,
    status: PlaybackText,osd: PlaybackText,debug: PlaybackText,seek: PlaybackText,showControls: Boolean,
    focus: String,onFocus: (String)->Unit,actions: List<PlaybackAction>,episodeActions: List<PlaybackAction>,onSeek: (Int,Int)->Unit) {
    val scale=LocalTvScale.current;val root=remember { FocusRequester() }
    val requests=remember { mutableMapOf<String,FocusRequester>() }
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(root).focusable()) {
        AndroidView(factory={ view },modifier=Modifier.fillMaxSize())
        if(status.visibility==View.VISIBLE) Text(status.text.toString(),Modifier.testTag("playback_status").align(Alignment.TopCenter).padding(top=(110*scale).dp).background(Ink.copy(alpha=.88f)).padding(12.dp),color=Paper,fontSize=(17*scale).sp)
        if(osd.visibility==View.VISIBLE) Text(osd.text.toString(),Modifier.align(Alignment.TopEnd).widthIn(max=(350*scale).dp).padding(15.dp).background(Ink.copy(alpha=.9f)).padding(10.dp),color=Paper,fontSize=(12*scale).sp)
        if(debug.visibility==View.VISIBLE) Text(debug.text.toString(),Modifier.align(Alignment.CenterStart).widthIn(max=(520*scale).dp).padding(12.dp).background(Ink.copy(alpha=.9f)).padding(10.dp),color=Paper,fontSize=(11*scale).sp)
        if(showControls) {
            PlaybackControlPanel(player,title.text.toString(),info.text.toString(),actions,episodeActions,requests,onFocus,onSeek,Modifier.align(Alignment.BottomCenter))
        }
        if(seek.visibility==View.VISIBLE) Text(seek.text.toString(),Modifier.testTag("seek_preview").align(Alignment.BottomCenter).padding(bottom=(140*scale).dp).background(Ink.copy(alpha=.95f)).border(1.dp,Cyan).padding(18.dp),color=Paper,fontSize=(20*scale).sp)
        LaunchedEffect(showControls) {
            if(showControls) { withFrameNanos {}; (requests[focus] ?: requests["play_pause"])?.requestFocus() } else root.requestFocus()
        }
    }
}
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun PlaybackControlPanel(player: ExoPlayer?,title: String,info: String,actions: List<PlaybackAction>,episodeActions: List<PlaybackAction>,
    requests: MutableMap<String,FocusRequester>,onFocus: (String)->Unit,onSeek: (Int,Int)->Unit,modifier: Modifier) {
    val scale=LocalTvScale.current
    val timeline=remember { FocusRequester() };requests["playback_timeline"]=timeline
    var timelineFocused by remember { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0) };var duration by remember(player) { mutableLongStateOf(0) }
    var playing by remember(player) { mutableStateOf(player?.playWhenReady==true) }
    DisposableEffect(player) {
        val listener=object: Player.Listener { override fun onPlayWhenReadyChanged(playWhenReady: Boolean,reason: Int) { playing=playWhenReady } }
        player?.addListener(listener);onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) { while(true) { position=player?.currentPosition ?: 0;duration=(player?.duration ?: 0).coerceAtLeast(0);kotlinx.coroutines.delay(500) } }
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f),Color.Black.copy(alpha=.96f)))).padding(horizontal=(40*scale).dp,vertical=(22*scale).dp)) {
        if(episodeActions.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(3.dp)) {
            items(episodeActions,key={ it.key }) { action ->
                val request=remember { FocusRequester() };requests[action.key]=request
                TvAction(action.label,action.key,Modifier.focusRequester(request),enabled=action.enabled,onFocus={ onFocus(action.key) },onClick=action.action)
            }
        }
        Text(title,color=Paper,fontSize=(26*scale).sp)
        Text(info,color=Muted,fontSize=(12*scale).sp)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(16.dp).testTag("playback_timeline").focusRequester(timeline).onFocusChanged { timelineFocused=it.isFocused;if(it.isFocused) onFocus("playback_timeline") }.border(if(timelineFocused) 1.dp else 0.dp,if(timelineFocused) Cyan else Color.Transparent)
            .onPreviewKeyEvent { event ->
                if(event.key in listOf(Key.DirectionLeft,Key.DirectionRight)) {
                    if(event.type==KeyEventType.KeyDown) onSeek(if(event.key==Key.DirectionLeft) -1 else 1,event.nativeKeyEvent.repeatCount)
                    true
                } else false
            }.focusable()) {
            Box(Modifier.fillMaxWidth().height(3.dp).align(Alignment.Center).background(Color.DarkGray))
            Box(Modifier.fillMaxWidth(if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f).height(3.dp).align(Alignment.CenterStart).background(Cyan))
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(SeekPolicy.time(position),color=Paper,fontSize=(11*scale).sp);Text(SeekPolicy.time(duration),color=Paper,fontSize=(11*scale).sp) }
        Spacer(Modifier.height(8.dp))
        var focusedKey by remember { mutableStateOf("play_pause") }
        Row(Modifier.align(Alignment.CenterHorizontally).height((60*scale).dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((22*scale).dp)) {
            actions.forEach { action ->
                val request=remember(action.key) { FocusRequester() }
                DisposableEffect(action.key) { requests[action.key]=request;onDispose { if(requests[action.key]===request) requests.remove(action.key) } }
                val label=if(action.key=="play_pause") Tr.text(if(playing) UiText.PAUSE_CONTROL else UiText.PLAY_252) else action.label
                PlaybackIconButton(action.key,label,playing,action.enabled,Modifier.focusRequester(request),onFocus={ focusedKey=action.key;onFocus(action.key) },onClick=action.action)
            }
        }
        Text(if(focusedKey=="play_pause") Tr.text(if(playing) UiText.PAUSE_CONTROL else UiText.PLAY_252) else actions.firstOrNull { it.key==focusedKey }?.label.orEmpty(),Modifier.align(Alignment.CenterHorizontally).height((18*scale).dp),color=Muted,fontSize=(10*scale).sp)

    }
}

@Composable private fun PlaybackIconButton(key: String,label: String,playing: Boolean,enabled: Boolean,modifier: Modifier,onFocus: ()->Unit,onClick: ()->Unit) {
    val scale=LocalTvScale.current;val primary=key=="play_pause";var focused by remember { mutableStateOf(false) }
    Button(onClick=onClick,enabled=enabled,modifier=modifier.size(((if(primary) 46 else 31)*scale).dp).testTag(key)
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }.semantics { contentDescription=label }
        .shadow(if(focused) 8.dp else 0.dp,CircleShape,ambientColor=Cyan,spotColor=Cyan)
        .border(if(focused) 1.5.dp else 1.dp,if(focused) Cyan else Paper.copy(alpha=.14f),CircleShape),
        shape=ButtonDefaults.shape(CircleShape),border=ButtonDefaults.border(focusedBorder=Border.None),scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=Ink.copy(alpha=.6f),contentColor=Paper,focusedContainerColor=Color(TvUi.panel).copy(alpha=.9f),focusedContentColor=Cyan),contentPadding=PaddingValues(0.dp)) {
        ControlIcon(key,playing,if(focused) Cyan else Paper,Modifier.size(((if(primary) 22 else 14)*scale).dp))
    }
}
@Composable private fun ControlIcon(key: String,playing: Boolean,color: Color,modifier: Modifier) {
    Canvas(modifier) {
        val unit=size.width/24;val stroke=1.65f*unit
        fun line(x1: Float,y1: Float,x2: Float,y2: Float)=drawLine(color,Offset(x1*unit,y1*unit),Offset(x2*unit,y2*unit),stroke,StrokeCap.Round)
        fun triangle(x: Float,direction: Int) { drawPath(Path().apply { moveTo(x*unit,5*unit);lineTo((x+direction*8)*unit,12*unit);lineTo(x*unit,19*unit);close() },color) }
        when(key) {
            "play_pause" -> if(playing) { line(8f,5f,8f,19f);line(16f,5f,16f,19f) } else triangle(8f,1)
            "player_rewind" -> { triangle(11f,-1);triangle(21f,-1) }
            "player_forward" -> { triangle(3f,1);triangle(13f,1) }
            "player_sources" -> for(y in listOf(6f,12f,18f)) { drawCircle(color,unit,Offset(3*unit,y*unit));line(7f,y,21f,y) }
            "player_subtitles" -> { drawRoundRect(color,Offset(2*unit,4*unit),androidx.compose.ui.geometry.Size(20*unit,14*unit),androidx.compose.ui.geometry.CornerRadius(2*unit),style=Stroke(stroke));line(7f,18f,7f,21f);line(7f,21f,11f,18f);for(x in listOf(7f,12f,17f)) drawCircle(color,unit,Offset(x*unit,11*unit)) }
            "player_audio" -> { line(3f,9f,7f,9f);line(7f,9f,12f,5f);line(12f,5f,12f,19f);line(12f,19f,7f,15f);line(7f,15f,3f,15f);line(3f,15f,3f,9f);drawArc(color,-55f,110f,false,Offset(9*unit,6*unit),androidx.compose.ui.geometry.Size(10*unit,12*unit),style=Stroke(stroke));drawArc(color,-55f,110f,false,Offset(7*unit,2*unit),androidx.compose.ui.geometry.Size(16*unit,20*unit),style=Stroke(stroke)) }
            else -> for(x in listOf(5f,12f,19f)) drawCircle(color,1.3f*unit,Offset(x*unit,12*unit))
        }
    }
}
