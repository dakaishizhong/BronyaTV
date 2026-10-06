package tv.ember.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.*
import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText

internal data class SettingsTile(val title: String,val index: Int,val color: Int,val values: List<Pair<String,String>>)

/** TV settings overview; editing keeps each tile's established setting category. */
@Composable
internal fun SettingsDashboard(tiles: List<SettingsTile>,initialFocus: Int,scale: Float,
                               onFocused: (Int)->Unit,onOpen: (Int)->Unit,onFocusSidebar: ()->Unit,onReturnFocus:(FocusRequester)->Unit={},
                               quickSettings: @Composable ColumnScope.()->Unit={}) {
    val accent=Color(TvUi.accent)
    val text=Color(TvUi.text)
    val muted=Color(TvUi.muted)
    val requests=remember { List(tiles.size) { FocusRequester() } }
    MaterialTheme(colorScheme=darkColorScheme(primary=accent,onPrimary=Color(TvUi.bg),surface=Color(TvUi.panel),onSurface=text)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=(40*scale).dp,vertical=(28*scale).dp)) {
            MetaBadge("SYSTEM PREFERENCES")
            Spacer(Modifier.height((6*scale).dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(Tr.text(UiText.SETTINGS_268),fontSize=(32*scale).sp,fontWeight=FontWeight.Bold,color=text)
                TvClock()
            }
            Text(Tr.text(UiText.SETTINGS_SUBTITLE),color=muted,fontSize=(12*scale).sp)
            Spacer(Modifier.height((16*scale).dp))
            quickSettings()
            Spacer(Modifier.height((20*scale).dp))
            tiles.chunked(3).forEachIndexed { rowIndex,group ->
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy((9*scale).dp)) {
                    group.forEachIndexed { column,tile ->
                        val position=rowIndex*3+column
                        var focused by remember { mutableStateOf(false) }
                        Column(Modifier.weight(1f).background(Color(TvUi.raised),RoundedCornerShape((16*scale).dp))
                            .border(((if(focused) 1.5f else 1f)*scale).dp,if(focused) accent else CardBorder,RoundedCornerShape((16*scale).dp))
                            .padding((12*scale).dp)) {
                            Button(onClick={ onOpen(tile.index) },modifier=Modifier.fillMaxWidth().height((42*scale).dp)
                                .testTag("settings_tile_$position").focusRequester(requests[position])
                                .focusProperties {
                                    if(column>0) left=requests[position-1]
                                    if(column<2) right=requests[position+1]
                                    if(rowIndex>0) up=requests[position-3]
                                    if(position+3<tiles.size) down=requests[position+3]
                                }
                                .onFocusChanged { focused=it.isFocused;if(it.isFocused) { onFocused(position);onReturnFocus(requests[position]) } }
                                .onPreviewKeyEvent { event ->
                                    if(column==0 && event.type==KeyEventType.KeyDown && event.key==Key.DirectionLeft) {
                                        onFocusSidebar();true
                                    } else false
                                },scale=ButtonDefaults.scale(focusedScale=1f),border=ButtonDefaults.border(focusedBorder=Border.None),
                                colors=ButtonDefaults.colors(containerColor=Color.Transparent,contentColor=text,
                                    focusedContainerColor=accent.copy(alpha=.14f),focusedContentColor=accent),
                                contentPadding=PaddingValues(horizontal=(4*scale).dp)) {
                                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy((10*scale).dp)) {
                                    val tint=Color(tile.color)
                                    val glyph=listOf(TvGlyph.Play,TvGlyph.Subtitles,TvGlyph.Server,TvGlyph.Remote,TvGlyph.Appearance,TvGlyph.Account)[position]
                                    Box(Modifier.size((28*scale).dp).background(tint.copy(alpha=.18f),RoundedCornerShape((7*scale).dp)),contentAlignment=Alignment.Center) { TvIcon(glyph,tint,Modifier.size((18*scale).dp)) }
                                    Text(tile.title,fontSize=(16*scale).sp,lineHeight=(20*scale).sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                            Spacer(Modifier.height((10*scale).dp))
                            tile.values.forEach { (label,value) ->
                                Row(Modifier.fillMaxWidth().heightIn(min=(29*scale).dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text(label,Modifier.weight(1f),fontSize=(12*scale).sp,color=muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                                    Text(value,Modifier.widthIn(max=(105*scale).dp),fontSize=(12*scale).sp,
                                        color=if(value==Tr.text(UiText.ON_186)) accent else text,maxLines=1,overflow=TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height((10*scale).dp))
            }
        }
        LaunchedEffect(Unit) { if(initialFocus>=0) requests[initialFocus.coerceIn(tiles.indices)].requestFocus() }
    }
}

@Composable internal fun QuickSettingBlock(title: String,modifier: Modifier=Modifier,content: @Composable ColumnScope.()->Unit) {
    val scale=LocalTvScale.current
    Column(modifier.background(Color(TvUi.raised),RoundedCornerShape(20.dp)).border(1.dp,CardBorder,RoundedCornerShape(20.dp))
        .padding((18*scale).dp),verticalArrangement=Arrangement.spacedBy((12*scale).dp)) {
        Text(title,color=Paper,fontWeight=FontWeight.Bold,fontSize=(15*scale).sp)
        content()
    }
}

@Composable internal fun QuickSettingToggle(title: String,enabled: Boolean,tag: String,modifier: Modifier=Modifier,onClick: ()->Unit) {
    val scale=LocalTvScale.current;var focused by remember { mutableStateOf(false) };val shape=RoundedCornerShape(20.dp)
    Row(modifier.testTag(tag).onFocusChanged { focused=it.isFocused }
        .background(Color(TvUi.raised),shape).border(if(focused) 2.dp else 1.dp,if(focused) Cyan else CardBorder,shape)
        .clickable(onClick=onClick).padding((18*scale).dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(title,Modifier.weight(1f),color=Paper,fontSize=(14*scale).sp,fontWeight=FontWeight.Bold)
        Box(Modifier.size((46*scale).dp,(26*scale).dp).background(if(enabled) Cyan else Paper.copy(alpha=.2f),RoundedCornerShape(13.dp)).padding((3*scale).dp),
            contentAlignment=if(enabled) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.size((20*scale).dp).background(if(enabled) Ink else Paper,RoundedCornerShape(10.dp)))
        }
    }
}
