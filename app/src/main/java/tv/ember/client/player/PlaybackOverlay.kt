package tv.ember.client.player

import android.view.View
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.*
import tv.ember.client.data.MediaChapter
import tv.ember.client.i18n.*
import tv.ember.client.ui.*

@Stable internal class PlaybackText {
    var text by mutableStateOf<CharSequence>("")
    var visibility by mutableIntStateOf(View.GONE)
    fun append(value: CharSequence) { text=text.toString()+value }
}
internal data class PlaybackAction(val key: String,val label: String,val enabled: Boolean=true,val selected:Boolean=false,val action: ()->Unit)
internal data class PlaybackOptionGroup(val key:String,val title:String,val values:List<String>,val selected:Int,val onSelect:(Int)->Unit)

@Composable internal fun PlaybackOptionsPanel(groups:List<PlaybackOptionGroup>,actions:List<PlaybackAction>) {
    val scale=LocalTvScale.current;val first=remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max=(400*scale).dp).testTag("player_options"),verticalArrangement=Arrangement.spacedBy((14*scale).dp)) {
        groups.forEachIndexed { groupIndex,group ->
            item(group.key) {
                Column(verticalArrangement=Arrangement.spacedBy((6*scale).dp)) {
                    Text(group.title,color=Muted,fontSize=(12*scale).sp)
                    LazyRow(Modifier.focusGroup(),horizontalArrangement=Arrangement.spacedBy((6*scale).dp)) {
                        itemsIndexed(group.values) { index,label ->
                            TvAction(label,"player_option_${group.key}_$index",Modifier.widthIn(max=(260*scale).dp)
                                .then(if(groupIndex==0 && index==0) Modifier.focusRequester(first) else Modifier),selected=group.selected==index) { group.onSelect(index) }
                        }
                    }
                }
            }
        }
        actions.forEach { action -> item(action.key) {
            TvAction(action.label,action.key,Modifier.fillMaxWidth(),enabled=action.enabled,selected=action.selected,onClick=action.action)
        } }
    }
    LaunchedEffect(Unit) { withFrameNanos {};first.requestFocus() }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable internal fun FullscreenPlayback(view: PlayerView,player: ExoPlayer?,title: PlaybackText,info: PlaybackText,
    status: PlaybackText,osd: PlaybackText,debug: PlaybackText,seek: PlaybackText,showControls: Boolean,
    focus: String,onFocus: (String)->Unit,actions: List<PlaybackAction>,badges:List<String>,hudRows:List<Pair<String,String>>,
    chapters:List<MediaChapter>,onSeek: (Int,Int)->Unit) {
    val scale=LocalTvScale.current;val root=remember { FocusRequester() }
    val keys=listOf("previous_section","play_pause","next_section","player_subtitles","player_audio","player_aspect","player_hud","playback_timeline")
    val requests=remember { keys.associateWith { FocusRequester() } }
    var position by remember(player) { mutableLongStateOf(0) };var duration by remember(player) { mutableLongStateOf(0) }
    var buffered by remember(player) { mutableLongStateOf(0) };var playing by remember(player) { mutableStateOf(player?.playWhenReady==true) }
    DisposableEffect(player) {
        val listener=object:Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady:Boolean,reason:Int) { playing=playWhenReady && player?.playbackState!=Player.STATE_ENDED }
            override fun onPlaybackStateChanged(state:Int) { playing=player?.playWhenReady==true && state!=Player.STATE_ENDED }
        }
        player?.addListener(listener);onDispose { player?.removeListener(listener) }
    }
    LaunchedEffect(player) { while(true) { position=player?.currentPosition ?: 0;duration=(player?.duration ?: 0).coerceAtLeast(0);buffered=player?.bufferedPosition ?: position;kotlinx.coroutines.delay(500) } }
    val chapter=chapters.lastOrNull { it.startTicks/10000<=position }?.name.orEmpty()
    @Composable fun TextControl(key:String,modifier:Modifier=Modifier) {
        actions.first { it.key==key }.let { action ->
            PlayerControlButton(action,modifier.focusRequester(requests.getValue(key)).focusProperties { down=requests.getValue("playback_timeline") },onFocus={ onFocus(key) })
        }
    }
    Box(Modifier.fillMaxSize().testTag("playback_surface").background(Color.Black).focusRequester(root).focusable()) {
        AndroidView(factory={ view },modifier=Modifier.fillMaxSize())
        if(status.visibility==View.VISIBLE) Text(status.text.toString(),Modifier.testTag("playback_status").align(Alignment.Center).background(Ink.copy(alpha=.88f)).padding(12.dp),color=Paper,fontSize=(17*scale).sp)
        if(showControls) {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.85f),Color.Transparent)))
                .padding((36*scale).dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.Top) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy((4*scale).dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy((8*scale).dp)) { badges.forEachIndexed { index,badge -> MetaBadge(badge,if(index==0) Cyan else Amber) } }
                    Text(title.text.toString(),Modifier.testTag("playback_title"),color=Paper,fontSize=(22*scale).sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(listOf(chapter.takeIf(String::isNotBlank) ?: info.text.toString(),"${SeekPolicy.time(position)} / ${SeekPolicy.time(duration)}").filter(String::isNotBlank).joinToString("  ·  "),color=Muted,fontSize=(12*scale).sp)
                }
                Spacer(Modifier.width((16*scale).dp))
                TextControl("player_hud")
            }
            PlaybackControlPanel(position,duration,buffered,playing,chapter,actions,requests,onFocus,onSeek,Modifier.align(Alignment.BottomCenter))
        }
        if(osd.visibility==View.VISIBLE) {
            Column(Modifier.align(Alignment.TopEnd).padding(top=(96*scale).dp,end=(36*scale).dp).width((640*scale).dp)
                .testTag("player_hud_panel").background(Color.Black.copy(alpha=.88f),RoundedCornerShape(16.dp)).border(1.dp,CardBorder,RoundedCornerShape(16.dp)).padding((14*scale).dp),verticalArrangement=Arrangement.spacedBy((8*scale).dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(Tr.text(UiText.PLAYER_HUD_TITLE),color=Cyan,fontWeight=FontWeight.Bold,fontSize=(12*scale).sp)
                    Text("Media3",color=Subtle,fontSize=(10*scale).sp)
                }
                Row(horizontalArrangement=Arrangement.spacedBy((16*scale).dp)) {
                    hudRows.chunked(((hudRows.size+1)/2).coerceAtLeast(1)).forEach { column ->
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy((4*scale).dp)) {
                            column.forEach { (label,value) -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy((6*scale).dp)) {
                                Text(label,Modifier.width((96*scale).dp),color=Muted,fontSize=(10*scale).sp,lineHeight=(12*scale).sp,maxLines=2)
                                Text(value,Modifier.weight(1f),color=Paper,fontSize=(10*scale).sp,lineHeight=(12*scale).sp,fontFamily=FontFamily.Monospace,maxLines=2,overflow=TextOverflow.Ellipsis)
                            } }
                        }
                    }
                }
            }
        }
        if(debug.visibility==View.VISIBLE) Text(debug.text.toString(),Modifier.align(Alignment.CenterStart).widthIn(max=(520*scale).dp).padding(12.dp).background(Ink.copy(alpha=.9f)).padding(10.dp),color=Paper,fontSize=(11*scale).sp)
        if(seek.visibility==View.VISIBLE) Text(seek.text.toString(),Modifier.testTag("seek_preview").align(Alignment.Center).background(Ink.copy(alpha=.95f)).border(1.dp,Cyan).padding(18.dp),color=Paper,fontSize=(20*scale).sp)
        LaunchedEffect(showControls) {
            if(showControls) {
                withFrameNanos {}
                val desired=focus.takeUnless { key -> actions.firstOrNull { it.key==key }?.enabled==false }
                (requests[desired] ?: requests.getValue("play_pause")).requestFocus()
            } else root.requestFocus()
        }
    }
}

@Composable private fun PlaybackControlPanel(position:Long,duration:Long,buffered:Long,playing:Boolean,chapter:String,
    actions:List<PlaybackAction>,requests:Map<String,FocusRequester>,onFocus:(String)->Unit,onSeek:(Int,Int)->Unit,modifier:Modifier) {
    val scale=LocalTvScale.current;val timeline=requests.getValue("playback_timeline")
    var timelineFocused by remember { mutableStateOf(false) }
    val controls=listOf("previous_section","play_pause","next_section","player_subtitles","player_audio","player_aspect")
    fun horizontal(key:String)=Modifier.focusProperties {
        val index=controls.indexOf(key)
        left=controls.getOrNull(index-1)?.let { requests.getValue(it) } ?: FocusRequester.Cancel
        right=controls.getOrNull(index+1)?.let { requests.getValue(it) } ?: FocusRequester.Cancel
        up=timeline;down=FocusRequester.Cancel
    }
    val fraction=if(duration>0) (position.toFloat()/duration).coerceIn(0f,1f) else 0f
    val bufferFraction=if(duration>0) (buffered.toFloat()/duration).coerceIn(fraction,1f) else fraction
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.95f))))
        .padding(horizontal=(48*scale).dp,vertical=(28*scale).dp),verticalArrangement=Arrangement.spacedBy((16*scale).dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(SeekPolicy.time(position),color=Cyan,fontSize=(13*scale).sp,fontFamily=FontFamily.Monospace)
            Text(chapter,color=Muted,fontSize=(12*scale).sp)
            Text("-"+SeekPolicy.time((duration-position).coerceAtLeast(0)),color=Muted,fontSize=(13*scale).sp,fontFamily=FontFamily.Monospace)
        }
        Canvas(Modifier.fillMaxWidth().height((6*scale).dp).testTag("playback_timeline").focusRequester(timeline)
            .focusProperties { down=requests.getValue("play_pause");up=requests.getValue("player_hud") }
            .semantics { contentDescription=Tr.text(UiText.PLAYBACK_PROGRESS);progressBarRangeInfo=ProgressBarRangeInfo(fraction,0f..1f);stateDescription="${SeekPolicy.time(position)} / ${SeekPolicy.time(duration)}" }
            .onFocusChanged { timelineFocused=it.isFocused;if(it.isFocused) onFocus("playback_timeline") }
            .onPreviewKeyEvent { event -> if(event.key in listOf(Key.DirectionLeft,Key.DirectionRight)) { if(event.type==KeyEventType.KeyDown) onSeek(if(event.key==Key.DirectionLeft) -1 else 1,event.nativeKeyEvent.repeatCount);true } else false }.focusable()) {
            val start=Offset(size.height/2,size.height/2);val end=Offset(size.width-size.height/2,size.height/2)
            drawLine(Paper.copy(alpha=.15f),start,end,size.height,StrokeCap.Round)
            if(bufferFraction>0) drawLine(Paper.copy(alpha=.3f),start,Offset(start.x+(end.x-start.x)*bufferFraction,end.y),size.height,StrokeCap.Round)
            if(fraction>0) drawLine(Cyan,start,Offset(start.x+(end.x-start.x)*fraction,end.y),size.height,StrokeCap.Round)
            if(timelineFocused) drawCircle(Cyan,(6*scale).dp.toPx(),Offset(start.x+(end.x-start.x)*fraction,end.y))
        }
        // Anchor transport controls to the viewport, independent of translated track labels.
        BoxWithConstraints(Modifier.fillMaxWidth().height((56*scale).dp)) {
            val transportWidth=(184*scale).dp // 44 + 20 + 56 + 20 + 44
            val parametersWidth=((maxWidth-transportWidth)/2-(20*scale).dp).coerceAtLeast(0.dp)
            val parameterMaxWidth=((parametersWidth-(20*scale).dp)/3).coerceAtLeast(0.dp)
            Row(Modifier.align(Alignment.Center).testTag("playback_transport"),horizontalArrangement=Arrangement.spacedBy((20*scale).dp),verticalAlignment=Alignment.CenterVertically) {
                listOf("previous_section","play_pause","next_section").forEach { key ->
                    val action=actions.first { it.key==key }
                    val label=if(key=="play_pause") Tr.text(if(playing) UiText.PAUSE_CONTROL else UiText.PLAY_CONTROL) else action.label
                    PlayerCircleButton(action.copy(label=label),playing,Modifier.focusRequester(requests.getValue(key)).then(horizontal(key)),onFocus={ onFocus(key) })
                }
            }
            Row(Modifier.align(Alignment.CenterEnd).widthIn(max=parametersWidth).testTag("playback_parameters"),horizontalArrangement=Arrangement.spacedBy((10*scale).dp)) {
                listOf("player_subtitles","player_audio","player_aspect").forEach { key ->
                    PlayerControlButton(actions.first { it.key==key },Modifier.widthIn(max=parameterMaxWidth).focusRequester(requests.getValue(key)).then(horizontal(key)),onFocus={ onFocus(key) })
                }
            }
        }
    }
}

@Composable private fun PlayerCircleButton(action:PlaybackAction,playing:Boolean,modifier:Modifier,onFocus:()->Unit) {
    val scale=LocalTvScale.current;val primary=action.key=="play_pause";var focused by remember { mutableStateOf(false) };val size=if(primary) 56 else 44
    Surface(onClick=action.action,enabled=action.enabled,modifier=modifier.size((size*scale).dp).testTag(action.key)
        .semantics { contentDescription=action.label;stateDescription=action.label;role=Role.Button }.onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }
        .shadow(if(focused) (24*scale).dp else 0.dp,CircleShape,clip=false,ambientColor=Cyan.copy(alpha=.35f),spotColor=Cyan.copy(alpha=.35f))
        .border(((if(focused) 2 else 1)*scale).dp,if(focused) Cyan else CardBorder,CircleShape),
        shape=ClickableSurfaceDefaults.shape(CircleShape),border=ClickableSurfaceDefaults.border(focusedBorder=Border.None),scale=ClickableSurfaceDefaults.scale(focusedScale=1.045f),
        colors=ClickableSurfaceDefaults.colors(containerColor=if(primary) Cyan.copy(alpha=.9f) else Paper.copy(alpha=.1f),contentColor=if(primary) Color.Black else Paper,
            focusedContainerColor=if(primary) Cyan else Paper.copy(alpha=.25f),focusedContentColor=if(primary) Color.Black else Paper,
            disabledContainerColor=Paper.copy(alpha=.1f),disabledContentColor=Muted)) {
        val glyph=when(action.key) { "previous_section"->TvGlyph.SkipPrevious;"next_section"->TvGlyph.SkipNext;else->if(playing) TvGlyph.Pause else TvGlyph.Play }
        TvIcon(glyph,if(primary) Color.Black else if(action.enabled) Paper else Muted,Modifier.align(Alignment.Center).size((size*.5f*scale).dp))
    }
}

@Composable private fun PlayerControlButton(action:PlaybackAction,modifier:Modifier=Modifier,onFocus:()->Unit) {
    val scale=LocalTvScale.current;var focused by remember { mutableStateOf(false) };val shape=RoundedCornerShape((10*scale).dp)
    // Surface keeps remote focus/click behavior without Button's unrelated minimum size.
    Surface(onClick=action.action,enabled=action.enabled,modifier=modifier.testTag(action.key).semantics { contentDescription=action.label;stateDescription=action.label;selected=action.selected;role=Role.Button }
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocus() }
        .shadow(if(focused) (24*scale).dp else 0.dp,shape,clip=false,ambientColor=Cyan.copy(alpha=.35f),spotColor=Cyan.copy(alpha=.35f))
        .border(((if(focused) 2 else 1)*scale).dp,if(focused) Cyan else CardBorder,shape),
        shape=ClickableSurfaceDefaults.shape(shape),border=ClickableSurfaceDefaults.border(focusedBorder=Border.None),scale=ClickableSurfaceDefaults.scale(focusedScale=1.045f),
        colors=ClickableSurfaceDefaults.colors(containerColor=if(action.selected) Cyan.copy(alpha=.2f) else Paper.copy(alpha=.1f),contentColor=if(action.selected) Cyan else Paper,focusedContainerColor=Paper.copy(alpha=.25f),focusedContentColor=Cyan)) {
        Text(action.label,Modifier.padding(horizontal=(14*scale).dp,vertical=(8*scale).dp),fontSize=(12*scale).sp,lineHeight=(16*scale).sp,
            style=LocalTextStyle.current.copy(lineHeightStyle=LineHeightStyle(LineHeightStyle.Alignment.Center,LineHeightStyle.Trim.None)),maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
