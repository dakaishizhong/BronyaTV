package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.*
import tv.ember.client.data.*
import tv.ember.client.i18n.*
import tv.ember.client.player.*
import tv.ember.client.settings.PlayerChoice

class DetailActivity: TvActivity() {
    private var item by mutableStateOf<VideoItem?>(null)
    private var versions by mutableStateOf<List<MediaVersion>>(emptyList())
    private var related by mutableStateOf<List<VideoItem>>(emptyList())
    private var selected by mutableStateOf("")
    private var choice by mutableStateOf(PlayerChoice.INTERNAL)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf("")
    private var work: Job?=null
    private var autoPlay=false
    private var returnToPlay=false
    private var focusedControl by mutableStateOf("detail_play")
    private val focusTargets=mutableMapOf<String,FocusRequester>()
    private var detailFirst=0;private var detailOffset=0
    private var loadFinished by mutableStateOf(false)
    private var focusPending by mutableStateOf(true)
    private var windowFocused by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        focusedControl=savedInstanceState?.getString("focus")?.takeUnless { it=="detail_back" } ?: "detail_play"
        detailFirst=savedInstanceState?.getInt("first") ?: 0;detailOffset=savedInstanceState?.getInt("offset") ?: 0
        choice=savedInstanceState?.getString("player")?.let { runCatching { PlayerChoice.valueOf(it) }.getOrNull() } ?: app.settings.player
        selected=savedInstanceState?.getString("source").orEmpty()
        autoPlay=intent.getBooleanExtra("auto_play",false);intent.removeExtra("auto_play")
        tvContent { Screen() };load()
    }
    override fun onResume() { super.onResume();if(returnToPlay) { returnToPlay=false;focusedControl="detail_play";focusPending=true };if(item!=null && !busy) load() }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus);windowFocused=hasFocus }
    private fun apply(video: VideoItem) {
        item=app.sessions.load()?.let { app.progress.apply(it,video) } ?: video;MediaUi.backdropItem=video
        versions=video.sources
        if(selected.isBlank()) selected=versions.firstOrNull()?.id.orEmpty()
    }
    private fun load() {
        val s=app.sessions.load() ?: return finish();val id=intent.getStringExtra("item_id") ?: return finish()
        work?.cancel();loadFinished=false;work=lifecycleScope.launch {
            if(item==null) app.api.cachedDetail(s,id)?.let(::apply)
            try {
                apply(app.api.detail(s,id));error=""
                if(versions.isEmpty()) versions=app.api.playbackInfo(s,id).versions
                if(selected.isBlank()) selected=versions.firstOrNull()?.id.orEmpty()
                if(autoPlay) { autoPlay=false;play(item!!.resumeTicks/10000) }
                related=try { app.api.similar(s,id).filter { it.id!=id } } catch(e: CancellationException) { throw e } catch(_: Exception) { emptyList() }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message.orEmpty() }
            finally { loadFinished=true }
        }
    }
    private fun play(positionMs: Long) {
        if(busy) return
        val video=item ?: return;val s=app.sessions.load() ?: return
        if(selected.isBlank()) { error=Tr.text(UiText.SERVER_HAS_NO_VIDEO_VERSIONS_269);return }
        val sourceId=selected;busy=true;error=Tr.text(UiText.FETCHING_PLAYABLE_VERSIONS_206)
        lifecycleScope.launch {
            try {
                // Negotiate at activation time. The detail cache never contains a playable URL/session.
                val info=app.api.playbackInfo(s,video.id,sourceId)
                val source=info.versions.firstOrNull { it.id==sourceId } ?: error(Tr.text(UiText.VERSION_UNAVAILABLE))
                val spec=app.api.playbackSpec(s,video.id,source,info.playSessionId)
                if(choice==PlayerChoice.INTERNAL) {
                    app.launches.put(s,video,spec)
                    startActivity(Intent(this@DetailActivity,PlaybackActivity::class.java).putExtra("item_id",video.id).putExtra("source_id",source.id).putExtra("position_ms",positionMs))
                } else ExternalPlayers.launch(this@DetailActivity,choice,spec,video.name,positionMs)
                returnToPlay=true;error=""
            } catch(e: CancellationException) { throw e } catch(e: Exception) { error=e.message.orEmpty() }
            finally { busy=false;if(!returnToPlay) { focusedControl="detail_play";focusPending=true } }
        }
    }
    @Composable private fun control(tag: String): Modifier {
        val request=remember(tag) { FocusRequester() }
        DisposableEffect(tag) { focusTargets[tag]=request;onDispose { if(focusTargets[tag]===request) focusTargets.remove(tag) } }
        return Modifier.focusRequester(request).onFocusChanged { if(it.isFocused) focusedControl=tag }
    }
    @Composable private fun Screen() {
        val s=app.sessions.load();val video=item;val scale=LocalTvScale.current
        val scroll=rememberLazyListState(detailFirst,detailOffset)
        val versionScroll=rememberLazyListState();val relatedScroll=rememberLazyListState()
        TvShell(Tr.text(UiText.HOME_267),::navigateTo,video) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal=(40*scale).dp),state=scroll,contentPadding=PaddingValues(vertical=(18*scale).dp),verticalArrangement=Arrangement.spacedBy((8*scale).dp)) {
                item(key="top") { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { TvClock() } }
                if(video==null) item(key="loading") {
                    Text(if(error.isBlank()) Tr.text(UiText.FETCHING_MOVIE_DETAILS_246) else error,color=Paper)
                    if(error.isNotBlank()) TvAction(Tr.text(UiText.RETRY_248),onClick=::load)
                } else {
                    item(key="title") {
                        if(video.studio.isNotBlank()) { Text(video.studio,color=Cyan,fontSize=(11*scale).sp,letterSpacing=1.2.sp);Spacer(Modifier.height((8*scale).dp)) }
                        MediaBadges(video);Spacer(Modifier.height((12*scale).dp))
                        Text(video.name,color=Paper,fontWeight=FontWeight.ExtraBold,fontSize=(42*scale).sp,lineHeight=(50*scale).sp,maxLines=2)
                        if(video.originalTitle.isNotBlank() && video.originalTitle!=video.name) Text(video.originalTitle,color=Muted,fontSize=(18*scale).sp)
                        Text(MediaUi.metadata(video),color=Muted,fontSize=(14*scale).sp)
                    }
                    item(key="overview") { Text(video.overview.ifBlank { Tr.text(UiText.NO_OVERVIEW_PROVIDED_BY_THE_SERVER_250) },Modifier.widthIn(max=(530*scale).dp),color=Muted,fontSize=(14*scale).sp,lineHeight=(22*scale).sp,maxLines=3,overflow=TextOverflow.Ellipsis) }
                    item(key="actions") {
                        TvAction(Tr.text(UiText.PLAY_NOW),"detail_play",control("detail_play"),primary=true,enabled=!busy && selected.isNotBlank(),large=true) { play(video.resumeTicks/10000) }
                    }
                    item(key="versions") {
                        Spacer(Modifier.height((16*scale).dp));Section(Tr.text(UiText.MEDIA_VERSIONS))
                        LazyRow(state=versionScroll,horizontalArrangement=Arrangement.spacedBy((16*scale).dp),contentPadding=PaddingValues(vertical=(12*scale).dp,horizontal=(4*scale).dp)) {
                            items(versions,key={ it.id }) { version ->
                                VersionCard(version,selected==version.id,Modifier.width((260*scale).dp).then(control("version_${version.id}"))) { selected=version.id;error="" }
                            }
                        }
                    }
                    if(video.resumeTicks>0) item(key="resume") { Text(Tr.text(UiText.WATCHED_261,MediaUi.percent(video),MediaUi.remaining(video)),color=Cyan,fontSize=(12*scale).sp) }
                    if(error.isNotBlank()) item(key="error") { Text(error,color=Cyan);if(!busy) TvAction(Tr.text(UiText.RETRY_248),onClick=::load) }
                    if(video.people.isNotEmpty() && s!=null) item(key="people") {
                        Section(Tr.text(UiText.CAST_CREW_262))
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            items(video.people,key={ it.id.ifBlank { it.name+it.type } }) { person ->
                                Row(Modifier.width((170*scale).dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    if(person.id.isNotBlank() && person.imageTag.isNotBlank()) {
                                        val width=(42*scale*LocalContext.current.resources.displayMetrics.density).toInt()
                                        CachedImage(app,s,app.api.imageUrl(s,VideoItem(person.id,person.name,"Person",imageTag=person.imageTag),width),width,width,Modifier.size((42*scale).dp).clip(CircleShape))
                                    }
                                    Column { Text(person.name,color=Paper,fontSize=(12*scale).sp);Text(person.role.ifBlank { person.type },color=Muted,fontSize=(10*scale).sp) }
                                }
                            }
                        }
                    }
                    if(related.isNotEmpty() && s!=null) item(key="related") {
                        Section(Tr.text(UiText.RELATED_TITLES_266))
                        LazyRow(state=relatedScroll,horizontalArrangement=Arrangement.spacedBy((8*scale).dp),contentPadding=PaddingValues(3.dp)) {
                            items(related,key={ it.id }) { other -> MediaCard(app,s,other,control("related_${other.id}"),morph=true) { startActivity(Intent(this@DetailActivity,DetailActivity::class.java).putExtra("item_id",other.id)) } }
                        }
                    }
                }
            }
            LaunchedEffect(Unit) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.collect { detailFirst=it.first;detailOffset=it.second } }
            // Wait for Android window focus as well as composition. Preserve the chosen
            // version/action on recreation and the parent card when returning from related titles.
            LaunchedEffect(video?.id,versions.size,related.size,loadFinished,windowFocused,focusPending,busy) {
                if(windowFocused && focusPending && !busy && video!=null && versions.isNotEmpty()) {
                    val desired=focusedControl
                    if(desired.startsWith("related_") && related.isEmpty() && !loadFinished) return@LaunchedEffect
                    withFrameNanos { };withFrameNanos { }
                    if(focusTargets[desired]==null) {
                        val keys=buildList { addAll(listOf("top","title","overview","actions","versions"));if(video.resumeTicks>0) add("resume");if(error.isNotBlank()) add("error");if(video.people.isNotEmpty()) add("people");if(related.isNotEmpty()) add("related") }
                        val key=when { desired.startsWith("version_") -> "versions";desired.startsWith("related_") && related.isNotEmpty() -> "related";else -> "actions" }
                        scroll.scrollToItem(keys.indexOf(key).coerceAtLeast(0));withFrameNanos { };withFrameNanos { }
                    }
                    if(focusTargets[desired]==null) {
                        if(desired.startsWith("version_")) versions.indexOfFirst { "version_${it.id}"==desired }.takeIf { it>=0 }?.let { versionScroll.scrollToItem(it) }
                        if(desired.startsWith("related_")) related.indexOfFirst { "related_${it.id}"==desired }.takeIf { it>=0 }?.let { relatedScroll.scrollToItem(it) }
                        withFrameNanos { };withFrameNanos { }
                    }
                    (focusTargets[desired] ?: focusTargets["detail_play"])?.let { it.requestFocus();focusPending=false }
                }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("focus",focusedControl);outState.putInt("first",detailFirst);outState.putInt("offset",detailOffset);outState.putString("source",selected);outState.putString("player",choice.name);super.onSaveInstanceState(outState) }
}
