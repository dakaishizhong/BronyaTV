package tv.ember.client.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.ember.client.BronyaApp
import tv.ember.client.data.*

internal const val MediaCardAspectRatio=16f/9f

@Composable fun MediaCard(app: BronyaApp,session: Session,item: VideoItem,modifier: Modifier=Modifier,
                          requester: FocusRequester?=null,onFocused: ()->Unit={},onLeft: (() ->Unit)?=null,
                          morph: Boolean=false,onClick: ()->Unit) {
    var focused by remember(item.id) { mutableStateOf(false) }
    val scale=LocalTvScale.current
    val cardWidth by animateDpAsState((if(focused) 260 else 140).times(scale).dp,tween(260),label="movie_card_width")
    val zoom by animateFloatAsState(if(focused && morph) 1.035f else 1f,tween(200),label="movie_card_scale")
    val bringIntoView=remember { BringIntoViewRequester() }
    val shape=RoundedCornerShape(14.dp)
    LaunchedEffect(focused) {
        if(focused && morph) { bringIntoView.bringIntoView();kotlinx.coroutines.delay(280);bringIntoView.bringIntoView() }
    }
    Column(modifier.then(if(morph) Modifier.width(cardWidth) else Modifier)
        .graphicsLayer { scaleX=zoom;scaleY=zoom }
        .then(if(requester!=null) Modifier.focusRequester(requester) else Modifier)
        .bringIntoViewRequester(bringIntoView)
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocused() }
        .onPreviewKeyEvent { if(onLeft!=null && it.type==KeyEventType.KeyDown && it.key==Key.DirectionLeft) { onLeft();true } else false }
        .testTag("media_${item.id}")
        .clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onClick)) {
        Box(Modifier.fillMaxWidth().then(if(morph) Modifier.height((180*scale).dp) else Modifier.aspectRatio(MediaCardAspectRatio))
            .shadow(if(focused) (24*scale).dp else 0.dp,shape,clip=false,ambientColor=Cyan.copy(alpha=.3f),spotColor=Cyan.copy(alpha=.3f))
            .clip(shape).testTag("artwork_${item.id}").background(Color(TvUi.raised))) {
            if(morph) {
                Crossfade(focused,animationSpec=tween(260),label="poster_to_backdrop",modifier=Modifier.fillMaxSize()) { landscape ->
                    // Fixed decode sizes avoid scheduling a new image request on every animation frame.
                    if(landscape) CachedImage(app,session,app.api.landscapeUrl(session,item,width=780),780,540,Modifier.fillMaxSize())
                    else CachedImage(app,session,app.api.imageUrl(session,item,420),420,540,Modifier.fillMaxSize())
                }
            } else CachedImage(app,session,app.api.landscapeUrl(session,item,width=480),480,270,Modifier.fillMaxSize())
            val badge=MediaUi.badges(item).take(2).joinToString(" ")
            if(badge.isNotBlank()) Text(badge,Modifier.align(Alignment.TopStart).padding((8*scale).dp)
                .background(Color.Black.copy(alpha=.75f),RoundedCornerShape(6.dp))
                .border(1.dp,Paper.copy(alpha=.15f),RoundedCornerShape(6.dp))
                .padding(horizontal=(6*scale).dp,vertical=(3*scale).dp),color=Paper,fontSize=(9*scale).sp,fontWeight=FontWeight.Bold,maxLines=1)
            if(item.resumeTicks>0) Box(Modifier.fillMaxWidth(MediaUi.percent(item)/100f).height(3.dp).align(Alignment.BottomStart).background(Cyan))
            Box(Modifier.fillMaxSize().border(if(focused) 2.dp else 1.dp,if(focused) Cyan else CardBorder,shape))
        }
        Spacer(Modifier.height((7*scale).dp))
        Text(item.name,Modifier.testTag("media_title_${item.id}"),color=if(focused) Cyan else Paper,fontWeight=FontWeight.Bold,fontSize=(12*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(if(item.resumeTicks>0) MediaUi.remaining(item) else listOf(item.year,item.episodeLabel).filter(String::isNotBlank).joinToString("  ·  "),
            modifier=Modifier.testTag("media_info_${item.id}"),color=Muted,fontSize=(11*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}

@Composable fun VersionCard(version: MediaVersion,selected: Boolean,modifier: Modifier=Modifier,onClick: ()->Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale=LocalTvScale.current;val shape=RoundedCornerShape(14.dp)
    val video=version.streams.firstOrNull { it.type=="Video" }
    Column(modifier.testTag("version_${version.id}")
        .onFocusChanged { focused=it.isFocused }
        .shadow(if(focused) 18.dp else 0.dp,shape,clip=false,ambientColor=Cyan.copy(alpha=.25f),spotColor=Cyan.copy(alpha=.25f))
        .background(if(selected) Cyan.copy(alpha=.08f) else Color(TvUi.panel),shape)
        .border(if(focused) 2.dp else 1.dp,if(focused) Cyan else if(selected) Cyan.copy(alpha=.45f) else CardBorder,shape)
        .semantics { this.selected=selected }
        .clickable(interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onClick)
        .padding((14*scale).dp),verticalArrangement=Arrangement.spacedBy((7*scale).dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            if(selected) TvIcon(TvGlyph.Check,Cyan,Modifier.size((16*scale).dp)) else Spacer(Modifier.width(1.dp))
            MetaBadge(video?.videoRange?.takeUnless { it in listOf("","None","SDR") } ?: version.container.uppercase(),Amber)
        }
        Text(version.name,color=if(focused || selected) Cyan else Paper,fontWeight=FontWeight.Bold,fontSize=(13*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        val filename=version.path.substringAfterLast('/').substringAfterLast('\\')
        if(filename.isNotBlank()) Text(filename,color=Subtle,fontSize=(10*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(version.displayDetails(),color=Muted,fontSize=(11*scale).sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(version.sizeBytes>0) Text("%.1f GB".format(java.util.Locale.ROOT,version.sizeBytes/1_000_000_000.0),color=Cyan,fontSize=(11*scale).sp)
    }
}
fun MediaVersion.displayDetails(): String {
    val video=streams.firstOrNull { it.type=="Video" }
    return listOf(video?.let { if(it.width>0 && it.height>0) "${it.width} × ${it.height}" else if(it.height>0) "${it.height}P" else "" }.orEmpty(),
        video?.videoRange?.takeUnless { it in listOf("","None") }.orEmpty(),video?.codec?.uppercase().orEmpty(),container.uppercase(),
        bitrate.takeIf { it>0 }?.let { "%.1f Mbps".format(java.util.Locale.ROOT,it/1_000_000.0) }.orEmpty()).filter(String::isNotBlank).joinToString("  ·  ")
}
