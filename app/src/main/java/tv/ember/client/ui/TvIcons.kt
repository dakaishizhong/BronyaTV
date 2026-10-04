package tv.ember.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/** Shared 24-unit vectors: decorative icons have no font-dependent baseline or semantics. */
enum class TvGlyph { Home,Movies,Series,Favorite,Search,Server,Settings,Play,Pause,Rewind,Forward,Sources,Subtitles,More,Info,Restart,Filter,ChevronRight,Screen,Remote,Appearance,Account,Check,Language }

@Composable fun TvIcon(glyph: TvGlyph,color: Color=Paper,modifier: Modifier=Modifier) {
    Canvas(modifier) {
        val u=size.minDimension/24f
        val origin=Offset((size.width-24*u)/2,(size.height-24*u)/2)
        fun point(x: Float,y: Float)=origin+Offset(x*u,y*u)
        val stroke=Stroke(1.7f*u,cap=StrokeCap.Round,join=StrokeJoin.Round)
        fun line(x: Float,y: Float,x2: Float,y2: Float)=drawLine(color,point(x,y),point(x2,y2),stroke.width,StrokeCap.Round)
        fun circle(x: Float,y: Float,r: Float)=drawCircle(color,r*u,point(x,y),style=stroke)
        fun dot(x: Float,y: Float,r: Float=1f)=drawCircle(color,r*u,point(x,y))
        fun rect(x: Float,y: Float,w: Float,h: Float,r: Float=2f)=drawRoundRect(color,point(x,y),Size(w*u,h*u),CornerRadius(r*u),style=stroke)
        fun path(points: List<Pair<Float,Float>>,closed: Boolean=false,filled: Boolean=false) {
            val p=Path();points.forEachIndexed { i,(x,y) -> val q=point(x,y);if(i==0) p.moveTo(q.x,q.y) else p.lineTo(q.x,q.y) };if(closed) p.close()
            if(filled) drawPath(p,color) else drawPath(p,color,style=stroke)
        }
        fun triangle(x: Float,d: Int)=path(listOf(x to 5f,(x+d*8) to 12f,x to 19f),true,true)
        when(glyph) {
            TvGlyph.Home -> { path(listOf(3f to 10f,12f to 3f,21f to 10f));path(listOf(5f to 9f,5f to 21f,10f to 21f,10f to 15f,14f to 15f,14f to 21f,19f to 21f,19f to 9f)) }
            TvGlyph.Movies -> { rect(3f,7f,18f,14f);path(listOf(3f to 7f,2f to 3f,20f to 2f,21f to 6f));line(7f,3f,9f,6f);line(13f,3f,15f,6f);line(3f,11f,21f,11f) }
            TvGlyph.Series -> { rect(3f,7f,18f,14f);line(8f,3f,12f,7f);line(16f,3f,12f,7f) }
            TvGlyph.Favorite -> path((0..9).map { i -> val a=-Math.PI/2+i*Math.PI/5;val r=if(i%2==0) 10f else 4.4f;(12+r*cos(a).toFloat()) to (12+r*sin(a).toFloat()) },true)
            TvGlyph.Search -> { circle(10.5f,10.5f,7.3f);line(16f,16f,21f,21f) }
            TvGlyph.Server -> for(y in listOf(3f,10f,17f)) { rect(3f,y,18f,4f,1f);dot(6f,y+2,0.65f);line(10f,y+2,17f,y+2) }
            TvGlyph.Settings -> { path((0..31).map { i -> val a=i*Math.PI/16;val r=if(i%4<2) 10f else 8.2f;(12+r*cos(a).toFloat()) to (12+r*sin(a).toFloat()) },true);circle(12f,12f,3.1f) }
            TvGlyph.Play -> triangle(8f,1)
            TvGlyph.Pause -> { line(8f,5f,8f,19f);line(16f,5f,16f,19f) }
            TvGlyph.Rewind -> { triangle(11f,-1);triangle(21f,-1) }
            TvGlyph.Forward -> { triangle(3f,1);triangle(13f,1) }
            TvGlyph.Sources -> for(y in listOf(6f,12f,18f)) { dot(3f,y);line(7f,y,21f,y) }
            TvGlyph.Subtitles -> { rect(2f,4f,20f,14f);line(7f,18f,7f,21f);line(7f,21f,11f,18f);for(x in listOf(7f,12f,17f)) dot(x,11f) }
            TvGlyph.More -> for(x in listOf(5f,12f,19f)) dot(x,12f,1.45f)
            TvGlyph.Info -> { circle(12f,12f,10f);dot(12f,7f);line(12f,11f,12f,17f) }
            TvGlyph.Restart -> { drawArc(color,35f,285f,false,point(3f,3f),Size(18*u,18*u),style=stroke);path(listOf(2f to 5f,3f to 10f,8f to 9f)) }
            TvGlyph.Filter -> { for(y in listOf(6f,12f,18f)) line(3f,y,21f,y);for((x,y) in listOf(8f to 6f,16f to 12f,10f to 18f)) dot(x,y,2.2f) }
            TvGlyph.ChevronRight -> path(listOf(9f to 5f,16f to 12f,9f to 19f))
            TvGlyph.Screen -> { rect(2f,3f,20f,15f);line(12f,18f,12f,22f);line(8f,22f,16f,22f) }
            TvGlyph.Remote -> { rect(7f,2f,10f,20f,3f);circle(12f,8f,2.5f);dot(10f,15f,.7f);dot(14f,15f,.7f);dot(10f,18f,.7f);dot(14f,18f,.7f) }
            TvGlyph.Appearance -> { circle(12f,12f,10f);dot(7f,9f,1.2f);dot(12f,6f,1.2f);dot(17f,9f,1.2f);circle(16f,16f,2.5f) }
            TvGlyph.Account -> { circle(12f,7f,4f);drawArc(color,180f,180f,false,point(4f,14f),Size(16*u,12*u),style=stroke) }
            TvGlyph.Check -> path(listOf(4f to 12f,9f to 17f,20f to 6f))
            TvGlyph.Language -> { circle(12f,12f,10f);drawOval(color,point(7f,2f),Size(10*u,20*u),style=stroke);line(3f,8f,21f,8f);line(3f,16f,21f,16f) }
        }
    }
}

internal fun actionGlyph(tag: String): TvGlyph? = when(tag) {
    "hero_play","detail_play","login_connect" -> TvGlyph.Play
    "hero_details","detail_info" -> TvGlyph.Info
    "detail_start","filter_reset","browse_retry" -> TvGlyph.Restart
    "detail_player" -> TvGlyph.Screen
    "browse_filters" -> TvGlyph.Filter
    "search_submit" -> TvGlyph.Search
    "interface_language" -> TvGlyph.Language
    else -> null
}
