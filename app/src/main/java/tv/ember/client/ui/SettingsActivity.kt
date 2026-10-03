package tv.ember.client.ui

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.ember.client.cache.DiskCachePlan
import tv.ember.client.player.ExternalPlayers
import tv.ember.client.settings.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SettingsActivity: TvActivity() {
    private var taps=0
    private var category=-1
    private var dashboardFocus=0
    private var scroll: ScrollView?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this,object: androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(category>=0) { category=-1;scroll=null;render() } else finish() }
        })
        category=savedInstanceState?.getInt("category") ?: -1;dashboardFocus=savedInstanceState?.getInt("dashboardFocus") ?: 0;render()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("category",category);outState.putInt("dashboardFocus",dashboardFocus);super.onSaveInstanceState(outState) }
    private fun render(focus: String?=currentFocus?.tag as? String) {
        if(category<0) { renderDashboard();return }
        val oldScroll=if(focus?.startsWith("tab")!=true) scroll?.scrollY ?: 0 else 0
        val root=paddedColumn()
        val heading=TvUi.row(this)
        heading.addView(TvUi.back(this) { category=-1;scroll=null;render() })
        heading.addView(TvUi.text(this,Tr.text(UiText.SETTINGS_268),28f),LinearLayout.LayoutParams(-2,-2).apply { marginStart=TvUi.dp(root,16) })
        TvUi.add(root,heading,bottom=24)
        val body=TvUi.row(this).apply { gravity=Gravity.TOP }
        val side=TvUi.column(this)
        listOf(Tr.text(UiText.PLAYBACK_333),Tr.text(UiText.REMOTE_CONTROL_334),Tr.text(UiText.NETWORK_CACHE_335),Tr.text(UiText.AUDIO_SUBTITLES_336),Tr.text(UiText.ACCOUNT_INFO_337)).forEachIndexed { index,name ->
            TvUi.add(side,TvUi.navigationButton(this,name,index==category) { category=index;scroll=null;render("tab${index}") }.apply {
                tag="tab${index}";isSelected=index==category;gravity=Gravity.START or Gravity.CENTER_VERTICAL
                textSize=14f
            },bottom=8)
        }
        body.addView(side,LinearLayout.LayoutParams(TvUi.dp(root,180),-1).apply { marginEnd=TvUi.dp(root,32) })
        val scroller=ScrollView(this).apply { clipToPadding=false };scroll=scroller
        val content=TvUi.column(this).apply { setPadding(TvUi.dp(this,4),TvUi.dp(this,4),TvUi.dp(this,8),TvUi.dp(this,18)) }
        scroller.addView(content);body.addView(scroller,LinearLayout.LayoutParams(0,-1,1f))
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f));setContentView(TvUi.shell(this,Tr.text(UiText.SETTINGS_268),::navigate,root))
        val p=app.settings
        fun setting(key: String,label: String,value: String,action: ()->Unit) {
            val row=TvUi.row(this).apply {
                tag=key;isFocusable=true;isFocusableInTouchMode=true;background=TvUi.focusBackground()
                setPadding(TvUi.dp(this,18),TvUi.dp(this,14),TvUi.dp(this,18),TvUi.dp(this,14))
                minimumHeight=TvUi.dp(this,54)
            }
            val title=TvUi.text(this,label,16f)
            val detail=TvUi.text(this,"${value}  ›",14f,TvUi.muted)
            row.addView(title,LinearLayout.LayoutParams(0,-2,1f));row.addView(detail)
            TvUi.focusOnTouch(row)
            row.setOnClickListener { action() }
            row.setOnFocusChangeListener { _,focused -> title.setTextColor(if(focused) TvUi.bg else TvUi.text);detail.setTextColor(if(focused) TvUi.bg else TvUi.muted) }
            TvUi.add(content,row,bottom=9)
        }
        fun hint(value: String) { TvUi.add(content,TvUi.text(this,value,13f,TvUi.muted).apply { setPadding(TvUi.dp(this,4),0,0,0) },bottom=14) }
        fun seconds(key: String,title: String,value: Int,values: List<Int>,save: (Int)->Unit) {
            setting(key,title,Tr.text(UiText.SEC_013 ,(value))) { select(title,values.map { Tr.text(UiText.SEC_013 ,(it)) },values.indexOf(value)) { save(values[it]);render(key) } }
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
                val diskInfo=TvUi.text(this,Tr.text(UiText.READING_CACHE_USAGE_364),13f,TvUi.muted)
                TvUi.add(content,diskInfo)
                lifecycleScope.launch {
                    val snapshot=withContext(Dispatchers.IO) { app.playbackCache.snapshot() }
                    diskInfo.text=Tr.text(UiText.DISK_USED_MB_AVAILABLE_MB_365 ,(snapshot.usedBytes/1048576),(snapshot.availableBytes/1048576))
                }
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
                        app.sessions.clear();app.launches.clear();PosterLoader.clear()
                        startActivity(Intent(this,LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()
                    }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
                }
            }
        }
        root.post {
            val target=root.findViewWithTag<View>(focus ?: "tab${category}")
            (target ?: side.getChildAt(category)).requestFocus()
            if(focus!=null && !focus.startsWith("tab")) scroller.scrollTo(0,oldScroll)
        }
    }
    private fun navigate(name: String) {
        if(name==Tr.text(UiText.SETTINGS_268)) { category=-1;render();return }
        startActivity(Intent(this,MainActivity::class.java).putExtra("navigate",name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish()
    }
    private fun renderDashboard() {
        val surface=TvUi.backdrop(this)
        val session=app.sessions.load();val item=MediaUi.backdropItem
        if(session!=null && item!=null) PosterLoader.load(lifecycleScope,surface.findViewWithTag("backdrop"),app.api.landscapeUrl(session,item,true),session,true)
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
        val compose=androidx.compose.ui.platform.ComposeView(this).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SettingsDashboard(tiles,dashboardFocus,TvUi.scale(this@SettingsActivity),
                    onFocused={ dashboardFocus=it },
                    onOpen={ index -> category=index;this@SettingsActivity.scroll=null;render("tab$index") },
                    onFocusSidebar={ window.decorView.findViewWithTag<View>("nav_${Tr.text(UiText.SETTINGS_268)}")?.requestFocus() })
            }
        }
        surface.addView(compose,FrameLayout.LayoutParams(-1,-1))
        // Horizontal LinearLayout's baseline probe measures weighted children with infinite height.
        // Compose scrolling requires a bounded viewport even during that preliminary measurement.
        setContentView(TvUi.shell(this,Tr.text(UiText.SETTINGS_268),::navigate,surface).apply { isBaselineAligned=false })
    }

    private fun duration(title: String,value: Int,save: (Int)->Unit) {
        val input=TvUi.input(this,Tr.text(UiText.SEC_406)).apply { inputType=InputType.TYPE_CLASS_NUMBER;setText("${value}");selectAll() }
        val dialog=TvUi.dialog(this).setTitle(title).setMessage(Tr.text(UiText.ENTER_SECONDS_DISABLES_SKIPPING_MAXIMUM_407))
            .setView(input).setPositiveButton(Tr.text(UiText.SAVE_408),null).setNegativeButton(Tr.text(UiText.CANCEL_196),null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds=input.text.toString().toIntOrNull()
                if(seconds==null || seconds !in 0..600) input.error=Tr.text(UiText.ENTER_A_NUMBER_FROM_TO_409)
                else { dialog.dismiss();save(seconds) }
            }
        };dialog.show()
    }
    private fun select(title: String,values: List<String>,checked: Int,action: (Int)->Unit) {
        TvUi.dialog(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(),checked.coerceAtLeast(0)) { dialog,which -> dialog.dismiss();action(which) }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
    }
}
