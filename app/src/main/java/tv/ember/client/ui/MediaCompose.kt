package tv.ember.client.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
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
                          requester: FocusRequester?=null,onFocused: ()->Unit={},onLeft: (() ->Unit)?=null,onClick: ()->Unit) {
    var focused by remember(item.id) { mutableStateOf(false) }
    val scale=LocalTvScale.current;val context=LocalContext.current
    Column(modifier.then(if(requester!=null) Modifier.focusRequester(requester) else Modifier)
        .onFocusChanged { focused=it.isFocused;if(it.isFocused) onFocused() }
        .onPreviewKeyEvent { if(onLeft!=null && it.type==KeyEventType.KeyDown && it.key==Key.DirectionLeft) { onLeft();true } else false }
        .testTag("media_${item.id}").clickable(onClick=onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(MediaCardAspectRatio).clip(RoundedCornerShape(7.dp)).testTag("artwork_${item.id}").background(Color(TvUi.panel),RoundedCornerShape(7.dp))) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val width=(maxWidth.value*context.resources.displayMetrics.density).toInt().coerceIn(48,960)
                CachedImage(app,session,app.api.landscapeUrl(session,item,width=width),width,(width/MediaCardAspectRatio).toInt(),Modifier.fillMaxSize())
            }
            if(item.resumeTicks>0) Box(Modifier.fillMaxWidth(MediaUi.percent(item)/100f).height(3.dp).align(androidx.compose.ui.Alignment.BottomStart).background(Cyan))
            if(focused) Box(Modifier.fillMaxSize().border(1.5.dp,Cyan,RoundedCornerShape(7.dp)))
        }
        Spacer(Modifier.height((5*scale).dp))
        Text(item.name,Modifier.testTag("media_title_${item.id}"),color=Paper,fontWeight=FontWeight.Bold,fontSize=(12*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(if(item.resumeTicks>0) MediaUi.remaining(item) else listOf(item.year,item.episodeLabel,MediaUi.badges(item).take(2).joinToString("  ")).filter(String::isNotBlank).joinToString("  "),
            modifier=Modifier.testTag("media_info_${item.id}"),color=Muted,fontSize=(11*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
}
fun MediaVersion.displayDetails(): String {
    val video=streams.firstOrNull { it.type=="Video" }
    return listOf(video?.let { if(it.width>0 && it.height>0) "${it.width} × ${it.height}" else if(it.height>0) "${it.height}P" else "" }.orEmpty(),
        video?.videoRange?.takeUnless { it in listOf("","None") }.orEmpty(),video?.codec?.uppercase().orEmpty(),container.uppercase(),
        bitrate.takeIf { it>0 }?.let { "%.1f Mbps".format(java.util.Locale.ROOT,it/1_000_000.0) }.orEmpty()).filter(String::isNotBlank).joinToString("  ·  ")
}
