package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import tv.ember.client.i18n.*
import tv.ember.client.settings.*
import tv.ember.client.cache.*
import tv.ember.client.player.ExternalPlayers

private data class SettingEntry(val key: String,val label: String,val value: String="",val action: (() ->Unit)?=null)
class SettingsActivity: TvActivity() {
    private var category by mutableIntStateOf(-1)
    private var revision by mutableIntStateOf(0)
    private var dashboardFocus=-1
    private var contentReturn:FocusRequester?=null
    private var lastFocus=""
    private var editorFirst=0
    private var editorOffset=0
    private var taps=0
    private var imageBytes by mutableLongStateOf(0)
    private var diskUsage by mutableStateOf("")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        category=savedInstanceState?.getInt("category",-1) ?: -1;dashboardFocus=savedInstanceState?.getInt("dashboard",-1) ?: -1
        lastFocus=savedInstanceState?.getString("focus").orEmpty();editorFirst=savedInstanceState?.getInt("first",0) ?: 0;editorOffset=savedInstanceState?.getInt("offset",0) ?: 0
        tvContent {
            TvShell(Tr.text(UiText.SETTINGS_268),::navigate,MediaUi.backdropItem,onContentFocus={ contentReturn?.let { it.requestFocus();true } ?: false }) {
                if(category<0) Dashboard() else key(category) { Editor() }
            }
        }
        onBackPressedDispatcher.addCallback(this) { if(category>=0) { category=-1;lastFocus="";editorFirst=0;editorOffset=0 } else finish() }
        refreshUsage()
    }
    private fun refreshUsage() { lifecycleScope.launch {
        imageBytes=app.imageCache.usedBytes()
        val snapshot=withContext(Dispatchers.IO) { app.playbackCache.snapshot() }
        diskUsage=Tr.text(UiText.DISK_USED_MB_AVAILABLE_MB_365,snapshot.usedBytes/1048576,snapshot.availableBytes/1048576)
    } }
    private fun render(focus: String?=null) { lastFocus=focus ?: lastFocus;revision++ }
    @Composable private fun Editor() {
        val scale=LocalTvScale.current;val stamp=revision
        val entries=entries(stamp)
        val state=rememberLazyListState(editorFirst,editorOffset)
        val requests=remember { mutableMapOf<String,FocusRequester>() }
        Column(Modifier.fillMaxSize().padding((18*scale).dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) { Text(Tr.text(UiText.SETTINGS_268),color=Paper,fontSize=(28*scale).sp,lineHeight=(34*scale).sp);TvClock() }
            LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp),contentPadding=PaddingValues(vertical=12.dp)) {
                itemsIndexed(listOf(UiText.PLAYBACK_333,UiText.REMOTE_CONTROL_334,UiText.NETWORK_CACHE_335,UiText.AUDIO_SUBTITLES_336,UiText.ACCOUNT_INFO_337),key={ i,_-> i }) { i,label ->
                    val request=remember { FocusRequester() };requests["tab$i"]=request
                    TvAction(Tr.text(label),"tab$i",Modifier.focusRequester(request),selected=category==i,onFocus={ lastFocus="tab$i";contentReturn=request }) { category=i;lastFocus="tab$i";editorFirst=0;editorOffset=0;refreshUsage() }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),state=state,contentPadding=PaddingValues(3.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(entries,key={ it.key }) { entry ->
                    if(entry.action==null) Text(entry.label,color=Muted,fontSize=(12*scale).sp)
                    else {
                        val request=remember(entry.key) { FocusRequester() }
                        DisposableEffect(entry.key) { requests[entry.key]=request;onDispose { if(requests[entry.key]===request) requests.remove(entry.key) } }
                        TvAction(entry.label+"    "+entry.value,entry.key,Modifier.fillMaxWidth().focusRequester(request)
                            .focusProperties { if(entry.key==entries.firstOrNull { it.action!=null }?.key) up=requests["tab$category"] ?: FocusRequester.Default },
                            onFocus={ lastFocus=entry.key;contentReturn=request },onClick=entry.action)
                    }
                }
            }
        }
        LaunchedEffect(category,revision) { withFrameNanos {};requests[lastFocus.ifBlank { "tab$category" }]?.requestFocus() }
        LaunchedEffect(category) { snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }.collect { editorFirst=it.first;editorOffset=it.second } }
    }
    private fun entries(@Suppress("UNUSED_PARAMETER") stamp: Int): List<SettingEntry> {
        val result=mutableListOf<SettingEntry>();val p=app.settings
        fun setting(key: String,label: String,value: String,action: ()->Unit) { result+=SettingEntry(key,label,value,action) }
        fun hint(value: String) { result+=SettingEntry("hint_${result.size}",value) }
        fun seconds(key: String,title: String,value: Int,values: List<Int>,save: (Int)->Unit) {
            setting(key,title,Tr.text(UiText.SEC_013,value)) { select(title,values.map { Tr.text(UiText.SEC_013,it) },values.indexOf(value)) { save(values[it]);render(key) } }
        }
        when(category) {
            0 -> {
                setting("player",Tr.text(UiText.DEFAULT_PLAYER_338),p.player.label) {
                    select(Tr.text(UiText.DEFAULT_PLAYER_338),PlayerChoice.entries.map { it.label+if(ExternalPlayers.available(this,it)) "" else Tr.text(UiText.NOT_INSTALLED_259) },PlayerChoice.entries.indexOf(p.player)) {
                        val selected=PlayerChoice.entries[it]
                        if(ExternalPlayers.available(this,selected)) { p.player=selected;render("player") } else message(Tr.text(UiText.INSTALL_FIRST_339 ,(selected.label)))
                    }
                }
                val resizeValues=listOf(0,4,3);val labels=listOf(Tr.text(UiText.FIT_199),Tr.text(UiText.CROP_TO_FILL_200),Tr.text(UiText.STRETCH_201))
                setting("resize",Tr.text(UiText.ASPECT_RATIO_182),labels[resizeValues.indexOf(p.resizeMode)]) { select(Tr.text(UiText.ASPECT_RATIO_182),labels,resizeValues.indexOf(p.resizeMode)) { p.resizeMode=resizeValues[it];render("resize") } }
                setting("autonext",Tr.text(UiText.AUTO_PLAY_NEXT_EPISODE_340),if(p.autoNextEpisode) Tr.text(UiText.ON_186) else Tr.text(UiText.OFF_187)) { p.autoNextEpisode=!p.autoNextEpisode;render("autonext") }
                setting("intro",Tr.text(UiText.SKIP_INTRO_141),if(p.introSeconds==0) Tr.text(UiText.OFF_187) else Tr.text(UiText.SEC_013 ,(p.introSeconds))) {
                    duration(Tr.text(UiText.SKIP_INTRO_141),p.introSeconds) { p.introSeconds=it;render("intro") }
                }
                setting("outro",Tr.text(UiText.SKIP_OUTRO_142),if(p.outroSeconds==0) Tr.text(UiText.OFF_187) else Tr.text(UiText.SEC_013 ,(p.outroSeconds))) {
                    duration(Tr.text(UiText.SKIP_OUTRO_142),p.outroSeconds) { p.outroSeconds=it;render("outro") }
                }
                hint(Tr.text(UiText.INTRO_OUTRO_SKIPPING_APPLIES_TO_EPISODES_341))
            }
            1 -> {
                seconds("seek",Tr.text(UiText.SHORT_PRESS_FORWARD_REWIND_342),p.seekSeconds,listOf(5,10,20,30,60)) { p.seekSeconds=it }
                seconds("long_seek",Tr.text(UiText.LONG_PRESS_SEEK_STEP_343),p.longSeekSeconds,listOf(10,15,20,30,45,60)) { p.longSeekSeconds=it }
                hint(Tr.text(UiText.DURING_VIDEO_PLAYBACK_PRESS_LEFT_RIGHT_344))
            }
            2 -> {
                val counts=listOf(0,1,2,4,8)
                setting("connections",Tr.text(UiText.PARALLEL_RECEIVE_345),when(p.streamConnections) { 0 -> Tr.text(UiText.AUTO_220);1 -> Tr.text(UiText.SINGLE_CONNECTION_123);else -> Tr.text(UiText.CONNECTIONS_221 ,(p.streamConnections)) }) {
                    select(Tr.text(UiText.PARALLEL_RECEIVE_345),listOf(Tr.text(UiText.AUTO_220),Tr.text(UiText.SINGLE_CONNECTION_123),Tr.text(UiText.CONNECTIONS_346),Tr.text(UiText.CONNECTIONS_347),Tr.text(UiText.CONNECTIONS_348)),counts.indexOf(p.streamConnections)) { p.streamConnections=counts[it];render("connections") }
                }
                hint(Tr.text(UiText.MULTIPLE_CONNECTIONS_CAN_HELP_WITH_HIGH_349))
                val windows=listOf(0,256,512,1024,2048,4096)
                setting("receive",Tr.text(UiText.NETWORK_RECEIVE_BUFFER_350),if(p.receiveBufferKb==0) Tr.text(UiText.SYSTEM_AUTO_227) else "${p.receiveBufferKb}KB") {
                    select(Tr.text(UiText.NETWORK_RECEIVE_BUFFER_350),windows.map { if(it==0) Tr.text(UiText.SYSTEM_AUTO_RECOMMENDED_351) else "${it}KB" },windows.indexOf(p.receiveBufferKb)) { p.receiveBufferKb=windows[it];render("receive") }
                }
                hint(Tr.text(UiText.SYSTEM_AUTO_ALLOWS_NETWORK_WINDOW_TUNING_352))
                setting("mode",Tr.text(UiText.BUFFER_MODE_353),p.mode.label) { select(Tr.text(UiText.BUFFER_MODE_353),BufferMode.entries.map { it.label },BufferMode.entries.indexOf(p.mode)) { p.mode=BufferMode.entries[it];render("mode") } }
                val sizes=listOf(0,16,32,64,128,256,512,1024,2048)
                fun size(n: Int)=when(n) { 0 -> Tr.text(UiText.AUTO_220);1024 -> "1GB";2048 -> "2GB";else -> "${n}MB" }
                setting("memory",Tr.text(UiText.MEMORY_CACHE_LIMIT_354),size(p.bufferMb)) { select(Tr.text(UiText.MEMORY_CACHE_LIMIT_354),sizes.map(::size),sizes.indexOf(p.bufferMb)) { p.bufferMb=sizes[it];render("memory") } }
                fun diskSize(n:Int)=when(n) { 0 -> Tr.text(UiText.OFF_187);-1 -> Tr.text(UiText.AUTO_UP_TO_MB_355);in 1024..8192 -> "${n/1024}GB";else -> "${n}MB" }
                setting("disk_capacity",Tr.text(UiText.DISK_CACHE_CAPACITY_356),diskSize(p.diskCacheMb)) {
                    select(Tr.text(UiText.DISK_CACHE_CAPACITY_356),DiskCachePlan.sizesMb.map(::diskSize),DiskCachePlan.sizesMb.indexOf(p.diskCacheMb)) {
                        p.diskCacheMb=DiskCachePlan.sizesMb[it];render("disk_capacity")
                    }
                }
                seconds("disk_ahead",Tr.text(UiText.DISK_READ_AHEAD_CACHE_010),p.diskAheadSeconds,DiskCachePlan.aheadSeconds) { p.diskAheadSeconds=it }
                setting("disk_clear",Tr.text(UiText.CLEAR_DISK_CACHE_357),Tr.text(UiText.DELETE_CACHED_VIDEO_DATA_358)) {
                    TvUi.dialog(this).setTitle(Tr.text(UiText.CLEAR_DISK_CACHE_359)).setMessage(Tr.text(UiText.ACCOUNT_AND_PLAYBACK_SETTINGS_WILL_BE_360))
                        .setPositiveButton(Tr.text(UiText.CLEAR_361)) { _,_-> lifecycleScope.launch {
                            runCatching { withContext(Dispatchers.IO) { app.playbackCache.clear() } }
                                .onSuccess { message(Tr.text(UiText.DISK_CACHE_CLEARED_362));render("disk_clear") }
                                .onFailure { message(Tr.text(UiText.COULD_NOT_CLEAR_CACHE_TRY_AGAIN_363)) }
                        } }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
                }
                hint(diskUsage)
                setting("image_capacity",Tr.text(UiText.IMAGE_CACHE_CAPACITY),if(p.imageCacheMb==0) Tr.text(UiText.OFF_187) else "${p.imageCacheMb} MB") {
                    val choices=listOf(0,64,128,256,512,1024)
                    select(Tr.text(UiText.IMAGE_CACHE_CAPACITY),choices.map { if(it==0) Tr.text(UiText.OFF_187) else "$it MB" },choices.indexOf(p.imageCacheMb)) {
                        p.imageCacheMb=choices[it];lifecycleScope.launch { app.imageCache.resize();refreshUsage() };render("image_capacity")
                    }
                }
                hint(Tr.text(UiText.IMAGE_CACHE_USED,imageBytes/1048576))
                setting("image_clear",Tr.text(UiText.IMAGE_CACHE_CLEAR),Tr.text(UiText.IMAGE_CACHE)) {
                    TvUi.dialog(this).setTitle(Tr.text(UiText.IMAGE_CACHE_CLEAR)).setMessage(Tr.text(UiText.IMAGE_CACHE_HINT))
                        .setPositiveButton(Tr.text(UiText.CLEAR_361)) { _,_-> lifecycleScope.launch { app.imageCache.clear();refreshUsage();render("image_clear") } }
                        .setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
                }
                hint(Tr.text(UiText.IMAGE_CACHE_HINT))
                hint(Tr.text(UiText.DISK_CAPACITY_IS_LIMITED_BY_AVAILABLE_366))
                seconds("prebuffer",Tr.text(UiText.PREBUFFER_DURATION_367),p.prebuffer,listOf(2,5,10,15,30,60)) { p.prebuffer=it }
                seconds("backbuffer",Tr.text(UiText.BACK_BUFFER_368),p.backBufferSeconds,listOf(0,5,15,30)) { p.backBufferSeconds=it }
                hint(Tr.text(UiText.MEMORY_SUPPORTS_IMMEDIATE_PLAYBACK_DISK_SUPPORTS_369))
            }
            3 -> {
                val av=listOf("","zh","en","ja");val al=listOf(Tr.text(UiText.AUTO_220),Tr.text(UiText.CHINESE_370),Tr.text(UiText.ENGLISH_371),Tr.text(UiText.JAPANESE_372))
                setting("audio",Tr.text(UiText.PREFERRED_AUDIO_373),al[av.indexOf(p.audioLanguage).coerceAtLeast(0)]) { select(Tr.text(UiText.PREFERRED_AUDIO_373),al,av.indexOf(p.audioLanguage).coerceAtLeast(0)) { p.audioLanguage=av[it];render("audio") } }
                val tv=listOf("","zh","en","ja","off");val tl=listOf(Tr.text(UiText.AUTO_220),Tr.text(UiText.CHINESE_370),Tr.text(UiText.ENGLISH_371),Tr.text(UiText.JAPANESE_372),Tr.text(UiText.OFF_BY_DEFAULT_374))
                setting("subtitle",Tr.text(UiText.SUBTITLE_PREFERENCE_375),tl[tv.indexOf(p.subtitleLanguage).coerceAtLeast(0)]) { select(Tr.text(UiText.SUBTITLE_PREFERENCE_375),tl,tv.indexOf(p.subtitleLanguage).coerceAtLeast(0)) { p.subtitleLanguage=tv[it];render("subtitle") } }
                val sizes=listOf(80,100,120,140)
                setting("subtitle_size",Tr.text(UiText.SUBTITLE_SIZE_183),"${p.subtitleScale}%") { select(Tr.text(UiText.SUBTITLE_SIZE_183),sizes.map { "${it}%" },sizes.indexOf(p.subtitleScale)) { p.subtitleScale=sizes[it];render("subtitle_size") } }
                hint(Tr.text(UiText.CHOOSE_AUDIO_SUBTITLES_PLAYBACK_SPEED_AND_376))
            }
            4 -> {
                setting("interface_language",Tr.text(UiText.INTERFACE_LANGUAGE),if(tv.ember.client.i18n.AppLanguage.read(this)=="zh") "简体中文" else "English") { TvUi.chooseLanguage(this) }
                setting("osd",Tr.text(UiText.SHOW_PERFORMANCE_OVERLAY_377),if(p.osd) Tr.text(UiText.ON_186) else Tr.text(UiText.OFF_187)) { p.osd=!p.osd;render("osd") }
                hint(Tr.text(UiText.DIAGNOSTICS_INCLUDE_THE_SOURCE_DECODED_VIDEO_378))
                if(p.debugEnabled) setting("debug",Tr.text(UiText.ADVANCED_DEBUG_379),Tr.text(UiText.ON_186)) { p.debugEnabled=false;taps=0;render("debug") }
                setting("about","BronyaTV",tv.ember.client.BuildConfig.VERSION_NAME) {
                    taps++;if(taps>=7) { p.debugEnabled=true;message(Tr.text(UiText.ADVANCED_DEBUG_ENABLED_FOR_THIS_SESSION_380));render("about") }
                    else if(taps>=4) message(Tr.text(UiText.TAP_MORE_TIMES_TO_ENABLE_DEBUG_381 ,(7-taps)))
                }
                setting("logout",Tr.text(UiText.SIGN_OUT_382),app.sessions.load()?.userName.orEmpty()) {
                    TvUi.dialog(this).setTitle(Tr.text(UiText.SIGN_OUT_OF_THIS_ACCOUNT_383)).setPositiveButton(Tr.text(UiText.SIGN_OUT_384)) { _,_->
                        app.sessions.clear();app.launches.clear();app.progress.clear();app.imageCache.trim(true);MediaUi.backdropItem=null
                        startActivity(Intent(this,LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()
                    }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
                }
            }
        }
        return result
    }
    private fun navigate(name: String) {
        if(name==Tr.text(UiText.SETTINGS_268)) { category=-1;return }
        navigateTo(name);finish()
    }
    @Composable private fun Dashboard() {
        @Suppress("UNUSED_VARIABLE") val stamp=revision
        val session=app.sessions.load()
        val p=app.settings
        fun enabled(value: Boolean)=if(value) Tr.text(UiText.ON_186) else Tr.text(UiText.OFF_187)
        fun language(value: String)=when(value) { "zh" -> Tr.text(UiText.CHINESE_370);"en" -> Tr.text(UiText.ENGLISH_371);"ja" -> Tr.text(UiText.JAPANESE_372);"off" -> Tr.text(UiText.OFF_BY_DEFAULT_374);"" -> Tr.text(UiText.AUTO_220);else -> value }
        val tiles=listOf(
            SettingsTile(Tr.text(UiText.PLAYBACK_333),0,TvUi.accent,listOf(Tr.text(UiText.DEFAULT_PLAYER_338) to p.player.label,Tr.text(UiText.ASPECT_RATIO_182) to when(p.resizeMode) { 4 -> Tr.text(UiText.CROP_TO_FILL_200);3 -> Tr.text(UiText.STRETCH_201);else -> Tr.text(UiText.FIT_199) },Tr.text(UiText.AUTO_PLAY_NEXT_EPISODE_340) to enabled(p.autoNextEpisode),Tr.text(UiText.INTRO_OUTRO_SKIP_385) to Tr.text(UiText.SEC_386 ,(p.introSeconds),(p.outroSeconds)))),
            SettingsTile(Tr.text(UiText.AUDIO_SUBTITLES_336),3,0xFFFF4B86.toInt(),listOf(Tr.text(UiText.PREFERRED_AUDIO_373) to language(p.audioLanguage),Tr.text(UiText.SUBTITLE_PREFERENCE_375) to language(p.subtitleLanguage),Tr.text(UiText.SUBTITLE_SIZE_183) to "${p.subtitleScale}%",Tr.text(UiText.AUDIO_AND_SUBTITLES_387) to Tr.text(UiText.CHANGE_DURING_PLAYBACK_388))),
            SettingsTile(Tr.text(UiText.NETWORK_CACHE_335),2,0xFF35A2FF.toInt(),listOf(Tr.text(UiText.PARALLEL_RECEIVE_345) to if(p.streamConnections==0) Tr.text(UiText.AUTO_220) else Tr.text(UiText.CONNECTIONS_221 ,(p.streamConnections)),Tr.text(UiText.MEMORY_CACHE_389) to if(p.bufferMb==0) Tr.text(UiText.AUTO_220) else "${p.bufferMb} MB",Tr.text(UiText.DISK_CACHE_390) to when(p.diskCacheMb) { -1 -> Tr.text(UiText.AUTO_220);0 -> Tr.text(UiText.OFF_187);else -> "${p.diskCacheMb} MB" },Tr.text(UiText.READ_AHEAD_391) to Tr.text(UiText.SEC_013 ,(p.diskAheadSeconds)))),
            SettingsTile(Tr.text(UiText.REMOTE_CONTROL_334),1,0xFF80D13A.toInt(),listOf(Tr.text(UiText.SHORT_PRESS_SEEK_392) to Tr.text(UiText.SEC_013 ,(p.seekSeconds)),Tr.text(UiText.LONG_PRESS_STEP_393) to Tr.text(UiText.SEC_013 ,(p.longSeekSeconds)),Tr.text(UiText.LEFT_RIGHT_394) to Tr.text(UiText.PREVIEW_SEEK_395),Tr.text(UiText.BACK_BUTTON_396) to Tr.text(UiText.CANCEL_PREVIEW_397))),
            SettingsTile(Tr.text(UiText.INTERFACE_DIAGNOSTICS_398),4,0xFFFF983D.toInt(),listOf(Tr.text(UiText.INTERFACE_LANGUAGE) to if(tv.ember.client.i18n.AppLanguage.read(this)=="zh") "简体中文" else "English",Tr.text(UiText.PERFORMANCE_OVERLAY_399) to enabled(p.osd),Tr.text(UiText.MOVIE_BACKDROP_401) to Tr.text(UiText.SERVER_IMAGES_402),Tr.text(UiText.VERSION_403) to tv.ember.client.BuildConfig.VERSION_NAME)),
            SettingsTile(Tr.text(UiText.ACCOUNT_INFO_337),4,0xFFAE59FF.toInt(),listOf(Tr.text(UiText.ACCOUNT_279) to session?.userName.orEmpty(),Tr.text(UiText.SERVICE_404) to "Emby",Tr.text(UiText.APPLICATION_405) to "BronyaTV",Tr.text(UiText.VERSION_403) to tv.ember.client.BuildConfig.VERSION_NAME))
        )
        val rail=LocalRailFocus.current
        val scale=LocalTvScale.current;val first=remember { FocusRequester() }
        SettingsDashboard(tiles,dashboardFocus,scale,onFocused={ dashboardFocus=it },onOpen={ index -> category=index;lastFocus="tab$index";editorFirst=0;editorOffset=0;refreshUsage() },onFocusSidebar=rail,onReturnFocus={ contentReturn=it }) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy((20*scale).dp)) {
                QuickSettingBlock(Tr.text(UiText.DEFAULT_PLAYER_338),Modifier.weight(1f)) {
                    Row(horizontalArrangement=Arrangement.spacedBy((6*scale).dp)) {
                        PlayerChoice.entries.take(3).forEachIndexed { i,value ->
                            val request=if(i==0) first else remember(value) { FocusRequester() }
                            TvAction(value.label,"quick_player_${value.name}",Modifier.weight(1f).focusRequester(request),selected=p.player==value,onFocus={ contentReturn=request }) {
                                if(ExternalPlayers.available(this@SettingsActivity,value)) { p.player=value;revision++ }
                                else message(Tr.text(UiText.INSTALL_FIRST_339,value.label))
                            }
                        }
                    }
                }
                QuickSettingBlock(Tr.text(UiText.MEMORY_CACHE_LIMIT_354),Modifier.weight(1f)) {
                    LazyRow(horizontalArrangement=Arrangement.spacedBy((6*scale).dp)) {
                        items((listOf(0,64,128,256,512)+p.bufferMb).distinct()) { size ->
                            val request=remember(size) { FocusRequester() }
                            TvAction(if(size==0) Tr.text(UiText.AUTO_220) else "$size MB","quick_buffer_$size",Modifier.focusRequester(request),selected=p.bufferMb==size,onFocus={ contentReturn=request }) { p.bufferMb=size;revision++ }
                        }
                    }
                }
            }
            Spacer(Modifier.height((16*scale).dp))
            QuickSettingBlock(Tr.text(UiText.PARALLEL_RECEIVE_345),Modifier.fillMaxWidth()) {
                Row(horizontalArrangement=Arrangement.spacedBy((6*scale).dp)) {
                    listOf(0,1,2,4,8).forEach { count ->
                        val request=remember(count) { FocusRequester() }
                        TvAction(if(count==0) Tr.text(UiText.AUTO_220) else Tr.text(UiText.CONNECTIONS_221,count),"quick_connections_$count",Modifier.focusRequester(request),selected=p.streamConnections==count,onFocus={ contentReturn=request }) {
                            p.streamConnections=count;revision++;message(Tr.text(UiText.SETTINGS_NEXT_PLAY))
                        }
                    }
                }
            }
            Spacer(Modifier.height((16*scale).dp))
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy((20*scale).dp)) {
                val autoFocus=remember { FocusRequester() };val osdFocus=remember { FocusRequester() }
                QuickSettingToggle(Tr.text(UiText.AUTO_PLAY_NEXT_EPISODE_340),p.autoNextEpisode,"quick_autonext",Modifier.weight(1f).focusRequester(autoFocus).onFocusChanged { if(it.isFocused) contentReturn=autoFocus }) { p.autoNextEpisode=!p.autoNextEpisode;revision++ }
                QuickSettingToggle(Tr.text(UiText.PERFORMANCE_OVERLAY_399),p.osd,"quick_osd",Modifier.weight(1f).focusRequester(osdFocus).onFocusChanged { if(it.isFocused) contentReturn=osdFocus }) { p.osd=!p.osd;revision++ }
            }
        }
        LaunchedEffect(Unit) { if(dashboardFocus<0) first.requestFocus() }
    }
    private fun duration(title: String,value: Int,save: (Int)->Unit) {
        TvUi.dialog(this).setTitle(title).setMessage(Tr.text(UiText.ENTER_SECONDS_DISABLES_SKIPPING_MAXIMUM_407)).setInput(Tr.text(UiText.SEC_406),"$value")
            .button(Tr.text(UiText.SAVE_408),false) { dialog ->
                val seconds=dialog.input.toIntOrNull()
                if(seconds==null || seconds !in 0..600) dialog.inputError=Tr.text(UiText.ENTER_A_NUMBER_FROM_TO_409)
                else { dialog.dismiss();save(seconds) }
            }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
    }
    private fun select(title: String,values: List<String>,checked: Int,action: (Int)->Unit) {
        TvUi.dialog(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(),checked) { dialog,index -> dialog.dismiss();action(index) }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("category",category);outState.putInt("dashboard",dashboardFocus);outState.putString("focus",lastFocus);outState.putInt("first",editorFirst);outState.putInt("offset",editorOffset);super.onSaveInstanceState(outState)
    }
}
