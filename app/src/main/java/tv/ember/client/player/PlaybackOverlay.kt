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
import androidx.tv.material3.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    Box(Modifier.fillMaxSize().testTag("playback_surface").background(Color.Black).focusRequester(root).focusable()) {
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
    val buttonRequests=remember { listOf("player_sources","player_rewind","play_pause","player_forward","player_subtitles","player_more").associateWith { FocusRequester() } }
    DisposableEffect(Unit) {
        requests.putAll(buttonRequests)
        onDispose { buttonRequests.forEach { (key,request) -> if(requests[key]===request) requests.remove(key) };requests.remove("playback_timeline") }
    }
    var timelineFocused by remember { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0) };var duration by remember(player) { mutableLongStateOf(0) }
    var buffered by remember(player) { mutableLongStateOf(0) }
    var playing by remember(player) { mutableStateOf(player?.playWhenReady==true) }
    DisposableEffect(player) {
        val listener=object: Player.Listener { override fun onPlayWhenReadyChanged(playWhenReady: Boolean,reason: Int) { playing=playWhenReady } }
        player?.addListener(listener);onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) { while(true) { position=player?.currentPosition ?: 0;duration=(player?.duration ?: 0).coerceAtLeast(0);buffered=player?.bufferedPosition ?: position;kotlinx.coroutines.delay(500) } }
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.8f),Color.Black.copy(alpha=.96f)))).padding(horizontal=(40*scale).dp,vertical=(22*scale).dp)) {
        if(episodeActions.isNotEmpty()) LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(3.dp)) {
            items(episodeActions,key={ it.key }) { action ->
                val request=remember { FocusRequester() };requests[action.key]=request
                TvAction(action.label,action.key,Modifier.focusRequester(request),enabled=action.enabled,onFocus={ onFocus(action.key) },onClick=action.action)
            }
        }
        Text(title,color=Paper,fontSize=(26*scale).sp,lineHeight=(32*scale).sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(info,color=Paper.copy(alpha=.7f),fontSize=(12*scale).sp,lineHeight=(18*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        Spacer(Modifier.height((8*scale).dp))
        val fraction=if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f
        val bufferedFraction=if(duration>0) (buffered.toFloat()/duration).coerceIn(fraction,1f) else fraction
        Canvas(Modifier.fillMaxWidth().height((22*scale).dp).testTag("playback_timeline").focusRequester(timeline)
            .focusProperties { down=buttonRequests.getValue("play_pause") }
            .semantics { contentDescription=Tr.text(UiText.PLAYBACK_PROGRESS);progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f);stateDescription="${SeekPolicy.time(position)} / ${SeekPolicy.time(duration)}" }
            .onFocusChanged { timelineFocused=it.isFocused;if(it.isFocused) onFocus("playback_timeline") }
            .onPreviewKeyEvent { event ->
                if(event.key in listOf(Key.DirectionLeft,Key.DirectionRight)) {
                    if(event.type==KeyEventType.KeyDown) onSeek(if(event.key==Key.DirectionLeft) -1 else 1,event.nativeKeyEvent.repeatCount)
                    true
                } else false
            }.focusable()) {
            val inset=(7*scale).dp.toPx();val y=size.height/2;val track=(4*scale).dp.toPx()
            val start=Offset(inset,y);val end=Offset(size.width-inset,y)
            val played=Offset(inset+(end.x-inset)*fraction,y)
            drawLine(Paper.copy(alpha=.16f),start,end,track,StrokeCap.Round)
            if(bufferedFraction>0) drawLine(Paper.copy(alpha=.3f),start,Offset(inset+(end.x-inset)*bufferedFraction,y),track,StrokeCap.Round)
            if(fraction>0) drawLine(Brush.horizontalGradient(listOf(Color(0xFF00B8C6),Cyan)),start,played,track,StrokeCap.Round)
            if(timelineFocused) drawCircle(Cyan.copy(alpha=.16f),(10*scale).dp.toPx(),played)
            drawCircle(Cyan,(if(timelineFocused) 6f else 4.5f)*scale*density,played)
            drawCircle(Paper,(if(timelineFocused) 3f else 2f)*scale*density,played)
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(SeekPolicy.time(position),color=Paper,fontSize=(11*scale).sp);Text(SeekPolicy.time(duration),color=Paper,fontSize=(11*scale).sp) }
        Spacer(Modifier.height((7*scale).dp))
        var focusedKey by remember { mutableStateOf("play_pause") }
        val ordered=buttonRequests.keys.toList()
        @Composable fun Control(key: String) {
            actions.firstOrNull { it.key==key }?.let { action ->
                val index=ordered.indexOf(key)
                val label=if(key=="play_pause") Tr.text(if(playing) UiText.PAUSE_CONTROL else UiText.PLAY_CONTROL) else action.label
                PlaybackIconButton(key,label,playing,action.enabled,Modifier.focusRequester(buttonRequests.getValue(key)).focusProperties {
                    up=timeline
                    left=if(index>0) buttonRequests.getValue(ordered[index-1]) else FocusRequester.Cancel
                    right=if(index<ordered.lastIndex) buttonRequests.getValue(ordered[index+1]) else FocusRequester.Cancel
                },onFocus={ focusedKey=key;onFocus(key) },onClick=action.action)
            }
        }
        // Equal side widths keep Play/Pause at the viewport center even with different action counts.
        Row(Modifier.fillMaxWidth().height((60*scale).dp),verticalAlignment=Alignment.CenterVertically) {
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy((20*scale).dp,Alignment.End),verticalAlignment=Alignment.CenterVertically) { Control("player_sources");Control("player_rewind") }
            Spacer(Modifier.width((22*scale).dp));Control("play_pause");Spacer(Modifier.width((22*scale).dp))
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy((20*scale).dp),verticalAlignment=Alignment.CenterVertically) { Control("player_forward");Control("player_subtitles");Control("player_more") }
        }
        Text(if(focusedKey=="play_pause") Tr.text(if(playing) UiText.PAUSE_CONTROL else UiText.PLAY_CONTROL) else actions.firstOrNull { it.key==focusedKey }?.label.orEmpty(),Modifier.align(Alignment.CenterHorizontally).height((18*scale).dp),color=Paper.copy(alpha=.7f),fontSize=(11*scale).sp,lineHeight=(16*scale).sp)

    }
}

@Composable private fun PlaybackIconButton(key: String,label: String,playing: Boolean,enabled: Boolean,modifier: Modifier,onFocus: ()->Unit,onClick: ()->Unit) {
    val scale=LocalTvScale.current;val primary=key=="play_pause";var focused by remember { mutableStateOf(false) }
    Button(onClick=onClick,enabled=enabled,modifier=modifier.size(((if(primary) 50 else 34)*scale).dp).testTag(key)
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }.semantics { contentDescription=label }
        .shadow(if(focused) (8*scale).dp else 0.dp,CircleShape,clip=false,ambientColor=Cyan.copy(alpha=.35f),spotColor=Cyan.copy(alpha=.35f))
        .border(((if(focused) 1.5f else 1f)*scale).dp,if(focused) Cyan else if(primary) Cyan.copy(alpha=.6f) else Paper.copy(alpha=.18f),CircleShape),
        shape=ButtonDefaults.shape(CircleShape),border=ButtonDefaults.border(focusedBorder=Border.None),scale=ButtonDefaults.scale(focusedScale=1f),
        colors=ButtonDefaults.colors(containerColor=if(primary) Cyan.copy(alpha=.1f) else Ink.copy(alpha=.65f),contentColor=Paper,focusedContainerColor=Cyan.copy(alpha=.16f),focusedContentColor=Cyan),contentPadding=PaddingValues(0.dp)) {
        val glyph=when(key) { "play_pause" -> if(playing) TvGlyph.Pause else TvGlyph.Play;"player_rewind" -> TvGlyph.Rewind;"player_forward" -> TvGlyph.Forward;"player_sources" -> TvGlyph.Sources;"player_subtitles" -> TvGlyph.Subtitles;else -> TvGlyph.More }
        TvIcon(glyph,if(!enabled) Muted else if(focused || primary) Cyan else Paper.copy(alpha=.9f),Modifier.size(((if(primary) 24 else 16)*scale).dp))
    }
}
