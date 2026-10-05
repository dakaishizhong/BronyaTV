package tv.ember.client.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.ember.client.BronyaApp
import tv.ember.client.data.Session
import tv.ember.client.data.VideoItem

val Amber=Color(0xFFE5A93C)
val Subtle=Color(0xFF5A6172)
val CardBorder=Color(0x14FFFFFF)

@Composable fun MetaBadge(text: String,color: Color=Cyan,modifier: Modifier=Modifier) {
    val scale=LocalTvScale.current
    Text(text,modifier.background(color.copy(alpha=.12f),RoundedCornerShape(4.dp))
        .border(1.dp,color.copy(alpha=.3f),RoundedCornerShape(4.dp))
        .padding(horizontal=(7*scale).dp,vertical=(3*scale).dp),
        color=color,fontSize=(10*scale).sp,fontWeight=FontWeight.Bold,maxLines=1)
}

@Composable fun MediaBadges(item: VideoItem,modifier: Modifier=Modifier) {
    Row(modifier,horizontalArrangement=Arrangement.spacedBy(7.dp)) {
        if(item.communityRating.isNotBlank()) MetaBadge("★ ${item.communityRating}",Amber)
        MediaUi.badges(item).take(4).forEach { MetaBadge(it) }
    }
}

/** The backdrop occupies the upper two thirds; copy and shelves keep independent space. */
@Composable fun CinemaBackdrop(app: BronyaApp,session: Session,item: VideoItem,width: Int,modifier: Modifier=Modifier) {
    val url=app.api.landscapeUrl(session,item,true,width)
    Box(modifier) {
        Crossfade(url,animationSpec=tween(400),label="hero_backdrop",modifier=Modifier.fillMaxSize()) { image ->
            CachedImage(app,session,image,width,width*9/16,Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
            0f to Ink,.28f to Ink.copy(alpha=.92f),.55f to Ink.copy(alpha=.55f),.8f to Ink.copy(alpha=.1f),1f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            0f to Color.Transparent,.5f to Ink.copy(alpha=.35f),.82f to Ink.copy(alpha=.85f),1f to Ink)))
    }
}
