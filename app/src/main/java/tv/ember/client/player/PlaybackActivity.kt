package tv.ember.client.player

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import androidx.compose.runtime.*
import tv.ember.client.ui.*
import android.app.ActivityManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.lifecycle.lifecycleScope
import androidx.activity.addCallback
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import tv.ember.client.data.*
import tv.ember.client.monitor.*
import tv.ember.client.network.HttpClient
import tv.ember.client.network.ReceiveBufferSocketFactory
import tv.ember.client.network.RangePlaybackDataSource
import tv.ember.client.network.RangePlaybackStatus
import tv.ember.client.network.StreamPolicy
import tv.ember.client.cache.*
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSink

@UnstableApi
class PlaybackActivity : TvActivity(), Player.Listener {
    private lateinit var playerView: PlayerView
    private val title=PlaybackText()
    private val mediaInfo=PlaybackText()
    private val status=PlaybackText()
    private val osd=PlaybackText()
    private val debug=PlaybackText()
    private var controllerVisible by mutableStateOf(true)
    private var controllerInteraction by mutableLongStateOf(0L)
    private var controllerFocus="play_pause"
    private var neighbors=EpisodeNeighbors()
    private var neighborsForId=""
    private var neighborJob: Job?=null
    private var changingEpisode=false
    private var introHandled=false
    private var outroHandled=false
    private var outroDeadline=0L
    private var sourceJob: Job?=null
    private var activeDialog by mutableStateOf<TvDialog?>(null)
    private var player by mutableStateOf<ExoPlayer?>(null)
    private var stats = PlayerStatsMonitor()
    private lateinit var network: NetworkMonitor
    private lateinit var cpu: CpuMonitor
    private lateinit var memory: MemoryMonitor
    private var control: TvLoadControl? = null
    private var playbackHttp: okhttp3.OkHttpClient? = null
    private var diskPrefetch: DiskPrefetcher? = null
    private var diskMode = Tr.text(UiText.NOT_STARTED_007)
    private var playerBuildJob: Job? = null
    private var lastPlaybackFailure = Tr.text(UiText.NONE_RECORDED_136)
    private var receiveSocketFactory: ReceiveBufferSocketFactory? = null
    private var transport:HttpTransportMonitor?=null
    private var rangeStatus:RangePlaybackStatus?=null
    private var transferBudget:tv.ember.client.network.StreamTransferBudget?=null
    private var lastNetwork:NetworkSample?=null
    private var session: Session? = null
    private var item: VideoItem? = null
    private var spec: PlaybackSpec? = null
    private var position = 0L
    private var wantedPlay = true
    private var softwareVideo = false
    private var softwareAudio = false
    private val seekRecovery = SeekRecovery()
    private var trackPreferences: TrackSelectionParameters? = null
    private val skippedExternalSubtitles = mutableSetOf<Int>()
    private var retryAttempt = 0
    private var refreshedRejectedUrl = false
    private var lastRejectedUrlRefresh = 0L
    private var forceOriginalRoute = false
    private var retryJob: Job? = null
    private var loadJob: Job? = null
    private var monitorJob: Job? = null
    private var showOsd = false
    private var showDebug = false
    private var active = false
    private var startedReported = false
    private var lastProgressReport = 0L
    private var stalledAt = 0L
    private var previousBuffer = -1L
    private var previousPosition = -1L
    private var registered = false
    private val seekPreview=PlaybackText()
    private var pendingSeek: Long?=null
    private var seekOrigin=0L
    private var lastSeekMove=0L
    private var playbackSpeed=1f
    private val commitSeek=Runnable { commitSeekPreview() }
    private val main = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if(active && retryJob?.isActive == true) { retryJob?.cancel(); recover() }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = app.sessions.load()
        if(session == null) { finish(); return }
        position = savedInstanceState?.getLong("position") ?: intent.getLongExtra("position_ms", 0)
        wantedPlay = savedInstanceState?.getBoolean("playing") ?: true
        controllerVisible=savedInstanceState?.getBoolean("controls") ?: false;controllerFocus=savedInstanceState?.getString("control_focus") ?: "play_pause"
        showOsd = app.settings.osd
        showDebug = app.settings.debugEnabled
        network = NetworkMonitor(this); cpu = CpuMonitor(); memory = MemoryMonitor(this)
        playerView=PlayerView(this).apply {
            useController=false;resizeMode=app.settings.resizeMode
            subtitleView?.setFractionalTextSize(.0533f*app.settings.subtitleScale/100f)
            setKeepContentOnPlayerReset(true);isFocusable=false
        }
        title.text=Tr.text(UiText.OPENING_VIDEO_137)
        tvContent { PlaybackScreen() }
        onBackPressedDispatcher.addCallback(this) {
            when {
                pendingSeek!=null -> cancelSeekPreview()
                outroDeadline>0 -> cancelOutro()
                controllerVisible -> controllerVisible=false
                else -> finish()
            }
        }
    }
    private fun updateMediaInfo() {
        val video=item ?: return
        val source=spec?.version
        mediaInfo.text=(listOf(video.year)+listOfNotNull(source?.streams?.firstOrNull { it.type=="Video" }?.let {
            listOf(if(it.width>=3800 || it.height>=2100) "4K" else if(it.height>0) "${it.height}P" else "",it.videoRange.takeUnless { range -> range in listOf("","SDR","None") }.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
        })+listOf(if(video.runtimeTicks>0) Tr.text(UiText.MIN_012 ,(video.runtimeTicks/600_000_000)) else "")).filter(String::isNotBlank).joinToString("   |   ")
    }
    private var episodeRevision by mutableIntStateOf(0)
    @Composable private fun PlaybackScreen() {
        val revision=episodeRevision
        val actions=listOf(
            PlaybackAction("player_sources",Tr.text(UiText.SOURCES_254)) { chooseSource() },
            PlaybackAction("player_rewind",Tr.text(UiText.REWIND_CONTROL)) { seekControls(-1,0) },
            PlaybackAction("play_pause","▶") { player?.let { it.playWhenReady=!it.playWhenReady } },
            PlaybackAction("player_forward",Tr.text(UiText.FORWARD_CONTROL)) { seekControls(1,0) },
            PlaybackAction("player_subtitles",Tr.text(UiText.SUBTITLE_TRACKS_180)) { chooseTrack(C.TRACK_TYPE_TEXT) },
            PlaybackAction("player_audio",Tr.text(UiText.AUDIO_TRACKS_179)) { chooseTrack(C.TRACK_TYPE_AUDIO) },
            PlaybackAction("player_more",Tr.text(UiText.PLAYBACK_OPTIONS_138)) { showMenu() }
        )
        val episodeActions=if(item?.type=="Episode" && revision>=0) buildList {
            if(neighbors.previous!=null) add(PlaybackAction("previous_episode",Tr.text(UiText.PREVIOUS_EPISODE_139),!changingEpisode) { neighbors.previous?.let(::switchEpisode) })
            if(neighbors.next!=null) add(PlaybackAction("next_episode",Tr.text(UiText.NEXT_EPISODE_140),!changingEpisode) { neighbors.next?.let(::switchEpisode) })
            if(app.settings.introSeconds>0) add(PlaybackAction("skip_intro",Tr.text(UiText.SKIP_INTRO_141),!changingEpisode) { skipOpening() })
            if(app.settings.outroSeconds>0) add(PlaybackAction("skip_outro",Tr.text(UiText.SKIP_OUTRO_142),!changingEpisode) { finishEpisode() })
        } else emptyList()
        FullscreenPlayback(playerView,player,title,mediaInfo,status,osd,debug,seekPreview,controllerVisible,controllerFocus,{ controllerFocus=it },actions,episodeActions,::seekControls)
        val dialogOpen=activeDialog!=null
        LaunchedEffect(controllerVisible,controllerInteraction,dialogOpen) {
            if(controllerVisible && !dialogOpen) { delay(4500);controllerVisible=false }
        }
    }
    private fun seekControls(direction: Int,repeats: Int) {
        val p=player ?: return
        if(!p.isCurrentMediaItemSeekable) return
        val target=SeekPolicy.target(p.currentPosition,p.duration,direction,repeats,app.settings.seekSeconds,app.settings.longSeekSeconds)
        control?.markSeek();p.seekTo(target);position=target;controllerInteraction=SystemClock.elapsedRealtime()
    }
    override fun onStart() {
        super.onStart(); if(session == null) return
        app.imageCache.playback(true)
        active = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!registered) runCatching {
            connectivity.registerNetworkCallback(NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), connectionCallback)
            registered = true
        }
        if(spec == null) loadVideo() else { createPlayer();refreshNeighbors() }
        startMonitor()
    }
    private fun loadVideo(rejectedAddress: String? = null) {
        val s = session ?: return
        val id = intent.getStringExtra("item_id") ?: return
        loadJob?.cancel()
        if(spec==null && rejectedAddress==null) {
            app.launches.take(s,id,intent.getStringExtra("source_id").orEmpty())?.let { (video,next) ->
                item=video;spec=next;title.text=video.name;updateMediaInfo()
                refreshNeighbors()
                if(active) createPlayer()
                return
            }
        }
        setStatus(Tr.text(UiText.FETCHING_ORIGINAL_SOURCE_143))
        loadJob = lifecycleScope.launch {
            try {
                val v = app.api.detail(s, id)
                val sourceId = intent.getStringExtra("source_id").orEmpty()
                val info = app.api.playbackInfo(s, id, sourceId)
                val source = info.versions.firstOrNull { it.id == sourceId } ?: throw IllegalStateException(Tr.text(UiText.SELECTED_SOURCE_IS_UNAVAILABLE_RETURN_AND_144))
                var next = app.api.playbackSpec(s, id, source, info.playSessionId, forceOriginalRoute)
                // If refreshing an unavailable stream returns the same URL, ask the
                // authenticated Emby original-file endpoint for the selected source.
                // Never rewrite a signed CDN URL or infer MKV from a failing MP4 URL.
                if(rejectedAddress != null && next.url == rejectedAddress && !forceOriginalRoute) {
                    val original = app.api.playbackSpec(s, id, source, info.playSessionId, forceOriginal = true)
                    if(original.url != next.url) { forceOriginalRoute = true; next = original }
                }
                item = v; spec = next
                title.text = v.name;updateMediaInfo()
                refreshNeighbors()
                if(active) createPlayer()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { android.util.Log.e("BronyaTVPlayback", "Unable to prepare media", e); setStatus(e.message ?: Tr.text(UiText.CANNOT_FETCH_SOURCE_PRESS_MENU_TO_145)) }
        }
    }
    private fun updateEpisodeButtons() { episodeRevision++ }
    private fun refreshNeighbors() {
        val s=session ?: return;val video=item ?: return
        updateEpisodeButtons()
        if(video.type!="Episode" || video.seriesId.isBlank() || neighborsForId==video.id || neighborJob?.isActive==true) return
        neighborJob=lifecycleScope.launch {
            try {
                val result=app.api.adjacentEpisodes(s,video)
                if(item?.id!=video.id || !active) return@launch
                neighbors=result;neighborsForId=video.id;updateEpisodeButtons()
                if(player?.playbackState==Player.STATE_ENDED && app.settings.autoNextEpisode) result.next?.let(::switchEpisode)
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                if(item?.id==video.id) message(Tr.text(UiText.EPISODE_LIST_UNAVAILABLE_RETRY_IN_PLAYBACK_146))
            }
        }
    }
    private fun switchEpisode(next: VideoItem) {
        if(!active || changingEpisode) return
        val s=session ?: return
        changingEpisode=true;cancelSeekPreview();cancelOutro();updateEpisodeButtons()
        retryJob?.cancel();loadJob?.cancel();neighborJob?.cancel();sourceJob?.cancel();activeDialog?.dismiss()
        val resume=player?.playWhenReady==true
        player?.pause();setStatus(Tr.text(UiText.OPENING_147 ,(next.episodeLabel.ifBlank { next.name })))
        loadJob=lifecycleScope.launch {
            try {
                val (video,playback)=coroutineScope {
                    val detail=async { app.api.detail(s,next.id) }
                    val info=async { app.api.playbackInfo(s,next.id) }
                    detail.await() to info.await()
                }
                val old=spec?.version
                val oldHeight=old?.streams?.firstOrNull { it.type=="Video" }?.height
                val source=playback.versions.filter { it.directPlay && !it.requiresOpening }.let { versions ->
                    versions.firstOrNull { it.container==old?.container && it.streams.firstOrNull { stream -> stream.type=="Video" }?.height==oldHeight } ?: versions.firstOrNull()
                } ?: error(Tr.text(UiText.NO_PLAYABLE_SOURCE_FOR_THIS_EPISODE_148))
                val prepared=app.api.playbackSpec(s,video.id,source,playback.playSessionId)
                if(!active) return@launch
                if(startedReported) report("Stopped")
                diskPrefetch?.close();diskPrefetch=null
                startedReported=false;playerView.player=null;player?.removeListener(this@PlaybackActivity);player?.release();player=null
                // Track overrides reference the old item's groups; language preferences carry to the new item.
                position=0;wantedPlay=true;trackPreferences=null;softwareVideo=false;softwareAudio=false;retryAttempt=0
                forceOriginalRoute=false;refreshedRejectedUrl=false;lastRejectedUrlRefresh=0;lastProgressReport=0
                skippedExternalSubtitles.clear();introHandled=false;outroHandled=false
                neighbors=EpisodeNeighbors();neighborsForId="";item=video;spec=prepared
                intent.putExtra("item_id",video.id).putExtra("source_id",source.id).putExtra("position_ms",0L)
                title.text=listOf(video.name,video.episodeLabel).filter(String::isNotBlank).joinToString(" · ");updateMediaInfo()
                createPlayer();refreshNeighbors()
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                setStatus(e.message ?: Tr.text(UiText.CANNOT_OPEN_EPISODE_PLEASE_RETRY_149))
                if(active && resume) player?.play()
            } finally { changingEpisode=false;updateEpisodeButtons() }
        }
    }
    private fun maybeSkipOpening() {
        val p=player ?: return
        if(item?.type!="Episode" || introHandled || p.duration<=0) return
        // Run once per episode so rewinding manually does not trigger another automatic skip.
        introHandled=true
        SkipPolicy.intro(p.currentPosition,p.duration,app.settings.introSeconds)?.let { control?.markSeek();p.seekTo(it) }
    }
    private fun skipOpening() {
        val p=player ?: return
        val target=SkipPolicy.intro(p.currentPosition,p.duration,app.settings.introSeconds)
        if(target!=null) { introHandled=true;control?.markSeek();p.seekTo(target) } else message(Tr.text(UiText.ALREADY_PAST_THE_INTRO_OR_THE_150))
    }
    private fun maybeSkipEnding(p:ExoPlayer,now:Long) {
        if(changingEpisode || item?.type!="Episode") return
        if(activeDialog?.isShowing==true || !hasWindowFocus()) return
        if(p.playbackState==Player.STATE_ENDED && app.settings.autoNextEpisode && neighbors.next!=null) { switchEpisode(neighbors.next!!);return }
        if(outroDeadline>0) {
            if(!p.isPlaying) { cancelOutro();return }
            if(now>=outroDeadline) { outroDeadline=0;finishEpisode() }
            else setStatus(Tr.text(UiText.NEXT_EPISODE_IN_SEC_BACK_TO_151 ,((outroDeadline-now+999)/1000)))
            return
        }
        if(outroHandled || !p.isPlaying || pendingSeek!=null || !SkipPolicy.outro(p.currentPosition,p.duration,app.settings.outroSeconds)) return
        if(neighborJob?.isActive==true) return
        outroHandled=true
        if(app.settings.autoNextEpisode && neighbors.next!=null) {
            outroDeadline=now+5000;setStatus(Tr.text(UiText.NEXT_EPISODE_IN_SEC_BACK_TO_152))
        } else finishEpisode()
    }
    private fun cancelOutro() { outroDeadline=0;setStatus("") }
    private fun finishEpisode() {
        val p=player ?: return
        outroHandled=true;cancelOutro()
        val next=neighbors.next
        if(app.settings.autoNextEpisode && next!=null) switchEpisode(next)
        else if(p.duration>0) { control?.markSeek();p.seekTo(p.duration) }
    }
    private fun createPlayer(preserveSeekRecovery: Boolean = false) {
        if(!active) return
        if(session == null) return
        val current = spec ?: return
        playerBuildJob?.cancel()
        if(!preserveSeekRecovery) seekRecovery.clear()
        diskPrefetch?.close();diskPrefetch=null
        player?.let { position = it.currentPosition; wantedPlay = it.playWhenReady; trackPreferences = it.trackSelectionParameters; playerView.player = null; it.removeListener(this); it.release() }
        player=null
        playbackHttp?.connectionPool?.evictAll()
        playerBuildJob=lifecycleScope.launch {
            val playlist=current.version.container.lowercase() in listOf("m3u8","hls","dash","mpd") ||
                current.url.substringBefore('?').let { it.endsWith(".m3u8",true) || it.endsWith(".mpd",true) }
            val disk=withContext(Dispatchers.IO) {
                if(playlist) null
                else app.playbackCache.configure(app.settings.diskCacheMb,current.version.bitrate,app.settings.diskAheadSeconds)
            }
            if(active && spec===current) {
                diskMode=if(playlist) Tr.text(UiText.STREAMING_PLAYLIST_USES_THE_PLAYBACK_BUFFER_153) else app.playbackCache.mode
                buildPlayer(current,disk)
            }
        }
    }
    private fun buildPlayer(current: PlaybackSpec,disk: PlaybackDiskCache.Handle?) {
        val s=session ?: return
        val r = Runtime.getRuntime()
        val mem = ActivityManager.MemoryInfo().also { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val policy = BufferPolicy.create(app.settings.snapshot(), r.maxMemory(), r.totalMemory() - r.freeMemory(), mem.lowMemory,current.version.bitrate,disk!=null)
        control = TvLoadControl(policy)
        val currentStats=PlayerStatsMonitor().apply { sourceBitrate=current.version.bitrate };stats=currentStats
        val currentNetwork=NetworkMonitor(this);network=currentNetwork;lastNetwork=null
        val selector = MediaCodecSelector { mime, secure, tunnel ->
            val codecs = DeviceAudioCodecs.query(mime, secure, tunnel)
            codecs.forEach(currentStats::registerCodec)
            if(softwareVideo && MimeTypes.isVideo(mime)) codecs.filter { it.softwareOnly }
            else codecs.sortedBy { if(it.hardwareAccelerated) 0 else 1 }
        }
        val renderers = TvRenderersFactory(this,softwareAudio).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        receiveSocketFactory = ReceiveBufferSocketFactory(app.settings.receiveBufferKb * 1024)
        val streamPlan=StreamPolicy.create(app.settings.streamConnections,current.version.bitrate,r.maxMemory(),r.totalMemory()-r.freeMemory(),mem.lowMemory,disk!=null)
        val prefetchBudget=streamPlan.budgetBytes
        val connections=streamPlan.connections
        val transferBudget=tv.ember.client.network.StreamTransferBudget(connections).also { this.transferBudget=it }
        val transportMonitor=HttpTransportMonitor(receiveSocketFactory);transport=transportMonitor
        val builder=HttpClient.playback.newBuilder().socketFactory(receiveSocketFactory!!)
            .connectionPool(okhttp3.ConnectionPool(connections,30,java.util.concurrent.TimeUnit.SECONDS))
            .eventListenerFactory(transportMonitor)
            .addNetworkInterceptor { chain ->
                val response=chain.proceed(chain.request())
                if(!response.request.url.encodedPath.contains("/Subtitles/",true))
                    currentStats.recordHttp(response.code,response.request.url.toString())
                response
            }
        // HTTP/2 multiplexing shares one TCP receive window. Range workers need independent TCP connections.
        if(connections>1) builder.protocols(listOf(okhttp3.Protocol.HTTP_1_1))
        val transportClient=builder.build()
        // Subtitle documents use the control transport, like PlaybackInfo. A paused single
        // video response can hold its range permit indefinitely while waiting for text.
        val subtitleUrls=current.version.streams.filter { it.type=="Subtitle" && it.external }.map { app.api.subtitleUrl(s,item!!.id,current.version,it) }.toSet()
        val mediaTransfers=transferBudget.interceptor()
        val httpClient=transportClient.newBuilder().addInterceptor { chain ->
            if(chain.request().url.toString() in subtitleUrls) chain.proceed(chain.request()) else mediaTransfers.intercept(chain)
        }.build();playbackHttp=httpClient
        val http=OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(current.headers)
        val range=RangePlaybackStatus(connections,prefetchBudget);rangeStatus=range
        val upstream=androidx.media3.datasource.DataSource.Factory {
            RangePlaybackDataSource(http,httpClient,current.url,current.headers,range).apply { addTransferListener(currentNetwork) }
        }
        val dataSources=if(disk==null) upstream else {
            val prefetchClient=transportClient.newBuilder().dispatcher(okhttp3.Dispatcher())
                .addInterceptor(transferBudget.interceptor(background=true)).build()
            val prefetchRange=RangePlaybackStatus(connections,prefetchBudget)
            val prefetchHttp=OkHttpDataSource.Factory(prefetchClient).setDefaultRequestProperties(current.headers)
            val prefetchSource=androidx.media3.datasource.DataSource.Factory {
                RangePlaybackDataSource(prefetchHttp,prefetchClient,current.url,current.headers,prefetchRange).apply { addTransferListener(currentNetwork) }
            }
            // Unique per player: signed URL changes or changed server files cannot merge stale spans.
            val key="bronya-"+java.util.UUID.randomUUID().toString()
            val writable=CacheDataSource.Factory().setCache(disk.cache).setUpstreamDataSourceFactory(prefetchSource)
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(disk.cache).setFragmentSize(2*DiskCachePlan.MIB))
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val prefetch=DiskPrefetcher(disk,writable,prefetchClient,current.url,key,prefetchRange)
            diskPrefetch=prefetch
            // Foreground is read-only: an unlimited writer lock here would block all read-ahead.
            val readable=CacheDataSource.Factory().setCache(disk.cache).setUpstreamDataSourceFactory(upstream)
                .setCacheWriteDataSinkFactory(null)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .setEventListener(object: CacheDataSource.EventListener {
                    override fun onCacheIgnored(reason:Int) {}
                    override fun onCachedBytesRead(cacheSizeBytes:Long,cachedBytesRead:Long) { prefetch.hitBytes.addAndGet(cachedBytesRead) }
                })
            androidx.media3.datasource.DataSource.Factory { DiskPlaybackDataSource(current.url,readable,upstream,prefetch) }
        }
        val errorPolicy = object : DefaultLoadErrorHandlingPolicy(3) {
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                val code = (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                if(code != null && !RetryPolicy.retryableHttp(code)) return C.TIME_UNSET
                return RetryPolicy.delayMs(info.errorCount - 1)
            }
        }
        val sourceFactory = DefaultMediaSourceFactory(dataSources).setLoadErrorHandlingPolicy(errorPolicy)
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(sourceFactory).setLoadControl(control!!)
            .setSeekBackIncrementMs(app.settings.seekSeconds*1000L).setSeekForwardIncrementMs(app.settings.seekSeconds*1000L).build()
        player = p; p.addListener(this); p.addAnalyticsListener(stats);p.setVideoFrameMetadataListener(stats)
        playerView.findViewById<androidx.media3.ui.DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.setKeyTimeIncrement(app.settings.seekSeconds*1000L)
        p.setAudioAttributes(AudioAttributes.DEFAULT, true)
        p.setHandleAudioBecomingNoisy(true)
        p.trackSelectionParameters = trackPreferences ?: p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(app.settings.audioLanguage.ifBlank { null })
            .setPreferredTextLanguage(app.settings.subtitleLanguage.takeUnless { it.isBlank() || it=="off" })
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,app.settings.subtitleLanguage=="off").build()
        p.setPlaybackSpeed(playbackSpeed)
        val subtitles = current.version.streams.filter { it.type == "Subtitle" && it.external && it.index !in skippedExternalSubtitles && it.codec.lowercase() in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt") }.map { stream ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(app.api.subtitleUrl(s, item!!.id, current.version, stream)))
                .setMimeType(when(stream.codec.lowercase()) { "ass", "ssa" -> MimeTypes.TEXT_SSA; "vtt", "webvtt" -> MimeTypes.TEXT_VTT; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLanguage(stream.language).setLabel(stream.label).setSelectionFlags(if(stream.default) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        val media = MediaItem.Builder().setUri(current.url).setMediaId(item!!.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item!!.name).build()).setSubtitleConfigurations(subtitles).build()
        p.setMediaItem(media, position.coerceAtLeast(0)); playerView.player = p
        setStatus(if(softwareVideo) Tr.text(UiText.TRYING_THE_DEVICE_SOFTWARE_DECODER_154) else Tr.text(UiText.BUFFERING_155))
        p.prepare(); p.playWhenReady = wantedPlay
        stalledAt = SystemClock.elapsedRealtime(); previousBuffer = -1; previousPosition = -1
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
        val p = player ?: return
        when(playbackState) {
            Player.STATE_READY -> {
                retryAttempt = 0; retryJob?.cancel(); setStatus("")
                if(!startedReported) { startedReported = true; report("") }
                maybeSkipOpening()
            }
            Player.STATE_BUFFERING -> setStatus(Tr.text(UiText.BUFFERING_PLAYBACK_WILL_RESUME_WHEN_THE_156))
            Player.STATE_ENDED -> {
                wantedPlay=false
                if(item?.type=="Episode" && app.settings.autoNextEpisode && neighbors.next!=null && !changingEpisode && activeDialog?.isShowing!=true) switchEpisode(neighbors.next!!)
                else setStatus(Tr.text(UiText.PLAYBACK_ENDED_157))
            }
        }
        if(playbackState == Player.STATE_READY) title.text = item?.let { listOf(it.name,it.episodeLabel).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()
    }
    override fun onTracksChanged(tracks: Tracks) {
        val p = player ?: return
        title.text = item?.let { listOf(it.name,it.episodeLabel).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { wantedPlay = playWhenReady; report("Progress") }
    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
        if(reason==Player.DISCONTINUITY_REASON_SEEK) {
            control?.markSeek();position=newPosition.positionMs
            seekRecovery.begin(position,SystemClock.elapsedRealtime());report("Progress")
        }
    }
    override fun onPlayerError(error: PlaybackException) {
        val p = player ?: return
        val failure=error as? ExoPlaybackException
        val failedType=failure?.rendererIndex?.takeIf { it in 0 until p.rendererCount }?.let(p::getRendererType)
        lastPlaybackFailure=listOf(error.errorCodeName,when(failedType) { C.TRACK_TYPE_AUDIO -> Tr.text(UiText.AUDIO_158);C.TRACK_TYPE_VIDEO -> Tr.text(UiText.VIDEO_127);else -> Tr.text(UiText.STREAM_OR_OTHER_159) },
            failure?.rendererFormat?.sampleMimeType.orEmpty(),failure?.rendererName.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
        position = p.currentPosition; wantedPlay = p.playWhenReady
        if(error.errorCode in 4000..4999 && error is ExoPlaybackException &&
            error.type==ExoPlaybackException.TYPE_RENDERER && error.rendererIndex in 0 until p.rendererCount &&
            p.getRendererType(error.rendererIndex)==C.TRACK_TYPE_VIDEO && !softwareVideo) {
            softwareVideo = true; createPlayer(); return
        }
        if(LosslessAudioPolicy.shouldRetryWithFfmpeg(
                failure?.type==ExoPlaybackException.TYPE_RENDERER && failedType==C.TRACK_TYPE_AUDIO,
                failure?.rendererFormat?.sampleMimeType ?: p.audioFormat?.sampleMimeType, softwareAudio)) {
            softwareAudio=true;createPlayer();return
        }
        val http = HttpFailures.find(error)
        if(http != null && http.responseCode in listOf(401, 403, 404, 410)) {
            val current = spec
            val s = session
            val v = item
            val subtitle = if(current != null && s != null && v != null) current.version.streams.firstOrNull {
                it.external && it.type == "Subtitle" && it.index !in skippedExternalSubtitles &&
                    app.api.subtitleUrl(s, v.id, current.version, it) == http.dataSpec.uri.toString()
            } else null
            if(subtitle != null) {
                skippedExternalSubtitles.add(subtitle.index)
                message(Tr.text(UiText.SUBTITLE_REQUEST_FAILED_HTTP_SKIPPED_SUBTITLE_160 ,(http.responseCode)))
                createPlayer(); return
            }
        }
        if(http != null && http.responseCode in listOf(401, 403, 404, 410) &&
            (!refreshedRejectedUrl || SystemClock.elapsedRealtime() - lastRejectedUrlRefresh >= 60_000)) {
            refreshedRejectedUrl = true
            lastRejectedUrlRefresh = SystemClock.elapsedRealtime()
            setStatus(Tr.text(UiText.HTTP_REFRESHING_THE_ORIGINAL_SOURCE_URL_161 ,(http.responseCode)))
            loadVideo(if(http.responseCode in listOf(404, 410)) spec?.url else null); return
        }
        if(http != null && http.responseCode in listOf(404, 410) && !forceOriginalRoute) {
            val s=session; val current=spec; val id=intent.getStringExtra("item_id")
            if(s != null && current != null && id != null) {
                val original=app.api.playbackSpec(s,id,current.version,current.playSessionId,forceOriginal=true)
                if(original.url != current.url) {
                    forceOriginalRoute=true; spec=original
                    setStatus(Tr.text(UiText.HTTP_TRYING_THE_SELECTED_SOURCE_S_162 ,(http.responseCode)))
                    createPlayer(); return
                }
            }
        }
        val retryable = if(http != null) RetryPolicy.retryableHttp(http.responseCode) else error.errorCode in 2000..2999 && error.errorCode !in listOf(2005, 2006, 2007)
        if(retryable) scheduleRetry() else setStatus(if(http != null) HttpFailures.message(http.responseCode,
            http.dataSpec.uri.toString().contains("/Subtitles/", true) || http.dataSpec.uri.toString().substringBefore('?').endsWith(".srt", true))
            else Tr.text(UiText.PLAYBACK_FAILED_PRESS_MENU_TO_RETRY_163 ,(error.errorCodeName)))
    }
    private fun scheduleRetry() {
        if(!active || retryJob?.isActive == true) return
        val wait = RetryPolicy.delayMs(retryAttempt++)
        setStatus(Tr.text(UiText.CONNECTION_INTERRUPTED_RECONNECTING_IN_SEC_164 ,(wait / 1000)))
        retryJob = lifecycleScope.launch { delay(wait); if(active) recover() }
    }
    private fun recover() {
        if(!active) return
        if(spec == null) loadVideo() else {
            val p = player ?: run { createPlayer(); return }
            p.seekTo(position); p.prepare(); p.playWhenReady = wantedPlay
            stalledAt = SystemClock.elapsedRealtime()
        }
    }
    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = lifecycleScope.launch {
            while(isActive && active) {
                val p = player
                if(p != null) {
                    val net = network.sample();lastNetwork=net
                    val now = SystemClock.elapsedRealtime();stats.observe(p,now)
                    if(p.bufferedPosition != previousBuffer || p.currentPosition != previousPosition) stalledAt = now
                    previousBuffer = p.bufferedPosition; previousPosition = p.currentPosition
                    val shouldAdvance=p.playWhenReady && p.playbackSuppressionReason==Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                        p.playerError==null && p.playbackState!=Player.STATE_ENDED && (p.duration<=0 || p.currentPosition<p.duration-1000)
                    val canAdvance=p.playbackState==Player.STATE_READY ||
                        (p.playbackState==Player.STATE_BUFFERING && !p.isLoading && (control?.allocatedBytes ?: 0)>0)
                    val frames=p.videoDecoderCounters?.renderedOutputBufferCount.takeIf { p.videoFormat!=null }
                    if(seekRecovery.shouldRecover(p.currentPosition,now,shouldAdvance,canAdvance,frames)) {
                        android.util.Log.w("BronyaTVPlayback","Seek output stalled; rebuilding renderers at current position")
                        createPlayer(preserveSeekRecovery=true)
                        delay(1000);continue
                    }
                    if(p.playbackState == Player.STATE_BUFFERING && p.playWhenReady && now - stalledAt > 45_000 && retryJob?.isActive != true) {
                        position = p.currentPosition; wantedPlay = p.playWhenReady; createPlayer()
                    }
                    if((showOsd || showDebug) && hasWindowFocus()) {
                        val samples = withContext(Dispatchers.IO) { cpu.sample() to memory.sample() }
                        val c = samples.first; val m = samples.second
                        osd.text = "CPU ${c.percent?.let { "%.1f%%".format(it) } ?: Tr.text(UiText.RESTRICTED_165)}${if(c.processOnly) "（APP）" else ""} · ${c.frequencyMhz?.let { "${it}MHz" } ?: Tr.text(UiText.FREQUENCY_RESTRICTED_166)}\n" +
                            Tr.text(UiText.APP_SINGLE_CORE_EQUIVALENT_F_167).format(c.coreEquivalent)+
                            Tr.text(UiText.MEMORY_USED_MB_AVAILABLE_MB_APP_168 ,(m.totalMb - m.availableMb),(m.totalMb),(m.availableMb),(m.appMb)) +
                            Tr.text(UiText.NETWORK_F_MB_S_AVERAGE_F_169).format(net.bytesPerSecond / 1_048_576.0,net.average / 1_048_576.0) +
                            stats.sourceSummary(spec!!.version)+"\n"+stats.summary(p) +
                            Tr.text(UiText.MEMORY_BUFFER_MB_170 ,((control?.allocatedBytes ?: 0) / 1_048_576),((control?.policy?.targetBytes ?: 0) / 1_048_576))+diskSummary(false)
                        osd.append(Tr.text(UiText.PREFETCH_MB_TCP_RECEIVE_171 ,(net.state),(rangeStatus?.mode),((rangeStatus?.bufferedBytes ?: 0)/1048576),((rangeStatus?.budgetBytes ?: 0)/1048576),(receiveSocketFactory?.effectiveBytes?.takeIf { it > 0 }?.let { "${it / 1024}KB" } ?: Tr.text(UiText.WAITING_FOR_CONNECTION_025))) +
                            if(app.settings.receiveBufferKb > 0) Tr.text(UiText.REQUESTED_KB_172 ,(app.settings.receiveBufferKb)) else Tr.text(UiText.AUTOMATIC_173))
                        osd.visibility = if(showOsd) View.VISIBLE else View.GONE
                        debug.text = stats.debug(p, spec?.url.orEmpty(), session?.server.orEmpty())
                        debug.visibility = if(showDebug) View.VISIBLE else View.GONE
                    } else if(!showOsd && !showDebug) { osd.visibility = View.GONE; debug.visibility = View.GONE }
                    maybeSkipEnding(p,now)
                    if(startedReported && now - lastProgressReport >= 10_000) { report("Progress"); lastProgressReport = now }
                }
                delay(1000)
            }
        }
    }
    private fun report(event: String) {
        val s = session ?: return; val v = item ?: return; val current = spec ?: return
        val pos = player?.currentPosition ?: position; val paused = !(player?.playWhenReady ?: wantedPlay)
        app.progress.update(s,v.id,pos,player?.playbackState==Player.STATE_ENDED)
        reportScope.launch { withTimeoutOrNull(5000) { runCatching { app.api.report(s, event, v.id, current, pos, paused) } } }
    }
    private fun diskSummary(detailed:Boolean):String {
        val disk=diskPrefetch ?: return Tr.text(UiText.DISK_CACHE_174 ,(diskMode))
        val ahead=disk.aheadBytes
        val seconds=spec?.version?.bitrate?.takeIf { it>0 }?.let { ahead*8.0/it }
        return Tr.text(UiText.DISK_AHEAD_MB_175 ,(ahead/1048576))+(seconds?.let { Tr.text(UiText.ABOUT_FS_176).format(it) } ?: "")+
            " · ${disk.state}"+if(detailed) Tr.text(UiText.DISK_USED_MIB_CACHE_READS_MIB_177 ,(disk.usedBytes/1048576),(disk.capacityBytes/1048576),(disk.hitBytes.get()/1048576)) else ""
    }
    private fun setStatus(value: String) { status.text = value; status.visibility = if(value.isBlank()) View.GONE else View.VISIBLE }
    private fun showMenu() {
        if(activeDialog?.isShowing==true || changingEpisode) return
        commitSeekPreview()
        val actions=mutableListOf<Pair<String,()->Unit>>(
            Tr.text(UiText.VIDEO_TRACKS_178) to { chooseTrack(C.TRACK_TYPE_VIDEO) },
            Tr.text(UiText.AUDIO_TRACKS_179) to { chooseTrack(C.TRACK_TYPE_AUDIO) },
            Tr.text(UiText.SUBTITLE_TRACKS_180) to { chooseTrack(C.TRACK_TYPE_TEXT) },
            Tr.text(UiText.PLAYBACK_SPEED_181 ,(playbackSpeed)) to { chooseSpeed() },
            Tr.text(UiText.ASPECT_RATIO_182) to { chooseResize() },
            Tr.text(UiText.SUBTITLE_SIZE_183) to { chooseSubtitleSize() },
            Tr.text(UiText.JUMP_TO_TIME_184) to { chooseTime() },
            Tr.text(UiText.SWITCH_SOURCE_185) to { chooseSource() },
            Tr.text(UiText.PERFORMANCE_OVERLAY_188 ,(if(showOsd) Tr.text(UiText.ON_186) else Tr.text(UiText.OFF_187))) to { showOsd=!showOsd;app.settings.osd=showOsd },
            Tr.text(UiText.RECONNECT_189) to { retryJob?.cancel();player?.let { position=it.currentPosition };refreshedRejectedUrl=false;loadVideo() },
            Tr.text(UiText.USE_EXTERNAL_PLAYER_190) to { externalDialog() },
            Tr.text(UiText.PLAYBACK_DIAGNOSTICS_191) to { showDiagnostics() }
        )
        if(item?.type=="Episode") {
            neighbors.previous?.let { episode -> actions.add(0,Tr.text(UiText.PREVIOUS_EPISODE_192 ,(episode.episodeLabel)) to { switchEpisode(episode) }) }
            neighbors.next?.let { episode -> actions.add(0,Tr.text(UiText.NEXT_EPISODE_193 ,(episode.episodeLabel)) to { switchEpisode(episode) }) }
            actions.add(Tr.text(UiText.RELOAD_EPISODE_LIST_194) to { neighborsForId="";neighborJob?.cancel();neighborJob=null;refreshNeighbors() })
        }
        if(app.settings.debugEnabled) actions.add(Tr.text(UiText.ADVANCED_DEBUG_INFO_195 ,(if(showDebug) Tr.text(UiText.ON_186) else Tr.text(UiText.OFF_187))) to { showDebug=!showDebug })
        val dialog=TvUi.dialog(this).setTitle(Tr.text(UiText.PLAYBACK_OPTIONS_138)).setItems(actions.map { it.first }.toTypedArray()) { d,index ->
            d.dismiss();activeDialog=null;actions[index].second()
        }.create()
        displayDialog(dialog,false)
    }
    private fun displayDialog(dialog: TvDialog,parentMenu: Boolean=true) {
        activeDialog=dialog
        dialog.setOnDismissListener { if(activeDialog===dialog) activeDialog=null }
        dialog.setOnCancelListener {
            if(activeDialog===dialog) activeDialog=null
            sourceJob?.cancel()
            if(parentMenu && active) main.post { if(active) showMenu() }
        }
        dialog.show()
    }
    private fun chooseSpeed() {
        val values=listOf(.5f,.75f,1f,1.25f,1.5f,2f)
        TvUi.dialog(this).setTitle(Tr.text(UiText.PLAYBACK_SPEED_197)).setSingleChoiceItems(values.map { Tr.text(UiText._198 ,(it)) }.toTypedArray(),values.indexOf(playbackSpeed)) { dialog,index ->
            playbackSpeed=values[index];player?.setPlaybackSpeed(playbackSpeed);dialog.dismiss()
        }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create().also { displayDialog(it) }
    }
    private fun chooseResize() {
        val values=listOf(0,4,3)
        TvUi.dialog(this).setTitle(Tr.text(UiText.ASPECT_RATIO_182)).setSingleChoiceItems(arrayOf(Tr.text(UiText.FIT_199),Tr.text(UiText.CROP_TO_FILL_200),Tr.text(UiText.STRETCH_201)),values.indexOf(playerView.resizeMode)) { dialog,index ->
            playerView.resizeMode=values[index];app.settings.resizeMode=values[index];dialog.dismiss()
        }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create().also { displayDialog(it) }
    }
    private fun chooseSubtitleSize() {
        val values=listOf(80,100,120,140)
        TvUi.dialog(this).setTitle(Tr.text(UiText.SUBTITLE_SIZE_183)).setSingleChoiceItems(values.map { "${it}%" }.toTypedArray(),values.indexOf(app.settings.subtitleScale)) { dialog,index ->
            app.settings.subtitleScale=values[index];playerView.subtitleView?.setFractionalTextSize(.0533f*values[index]/100f);dialog.dismiss()
        }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create().also { displayDialog(it) }
    }
    private fun chooseTime() {
        val p=player ?: return
        if(!p.isCurrentMediaItemSeekable) { message(Tr.text(UiText.THIS_SOURCE_DOES_NOT_SUPPORT_SEEKING_202));return }
        val dialog=TvUi.dialog(this).setTitle(Tr.text(UiText.JUMP_TO_TIME_184)).setInput(Tr.text(UiText.HH_MM_SS_OR_MM_SS_203),SeekPolicy.time(p.currentPosition))
            .button(Tr.text(UiText.JUMP_204),false) { d ->
                val target=SeekPolicy.parseTime(d.input)
                if(target==null) d.inputError=Tr.text(UiText.ENTER_A_VALID_TIME_SUCH_AS_205)
                else { control?.markSeek();p.seekTo(if(p.duration>0) target.coerceAtMost((p.duration-1).coerceAtLeast(0)) else target);d.dismiss() }
            }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create()
        displayDialog(dialog)
    }
    private fun chooseSource() {
        val s=session ?: return;val video=item ?: return
        if(sourceJob?.isActive==true) return
        val loading=TvUi.dialog(this).setTitle(Tr.text(UiText.SWITCH_SOURCE_185)).setMessage(Tr.text(UiText.FETCHING_PLAYABLE_VERSIONS_206)).setNegativeButton(Tr.text(UiText.CANCEL_196),null).create()
        displayDialog(loading)
        sourceJob=lifecycleScope.launch {
            try {
                val info=app.api.playbackInfo(s,video.id)
                if(!active || item?.id!=video.id) return@launch
                check(info.versions.isNotEmpty()) { Tr.text(UiText.SERVER_HAS_NO_OTHER_SOURCES_207) }
                loading.dismiss()
                TvUi.dialog(this@PlaybackActivity).setTitle(Tr.text(UiText.SWITCH_SOURCE_KEEP_CURRENT_POSITION_208))
                    .setSingleChoiceItems(info.versions.map { it.label }.toTypedArray(),info.versions.indexOfFirst { it.id==spec?.version?.id }) { dialog,index ->
                        try {
                            val next=app.api.playbackSpec(s,video.id,info.versions[index],info.playSessionId)
                            report("Stopped");startedReported=false;retryJob?.cancel()
                            diskPrefetch?.close();diskPrefetch=null
                            player?.let { position=it.currentPosition;wantedPlay=it.playWhenReady;playerView.player=null;it.removeListener(this@PlaybackActivity);it.release() };player=null
                            trackPreferences=null;softwareVideo=false;softwareAudio=false;forceOriginalRoute=false;refreshedRejectedUrl=false
                            skippedExternalSubtitles.clear();spec=next;updateMediaInfo()
                            intent.putExtra("source_id",next.version.id);createPlayer();dialog.dismiss()
                        } catch(e: Exception) { message(e.message ?: Tr.text(UiText.CANNOT_SWITCH_SOURCE_209)) }
                    }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create().also { displayDialog(it) }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { loading.dismiss();message(e.message ?: Tr.text(UiText.SOURCE_REQUEST_FAILED_210)) }
        }
    }
    private fun showDiagnostics() {
        val dialog=TvUi.dialog(this).setTitle(Tr.text(UiText.PLAYBACK_DIAGNOSTICS_191)).setMessage(Tr.text(UiText.COLLECTING_DIAGNOSTICS_211)).create()
        fun refresh() { lifecycleScope.launch {
            val link=withContext(Dispatchers.IO) { network.linkDetails() }
            val p=player ?: return@launch;val current=spec ?: return@launch;val net=lastNetwork
            val tcp=receiveSocketFactory?.effectiveBytes?.takeIf { it>0 }?.let { "${it/1024}KB" } ?: Tr.text(UiText.WAITING_FOR_CONNECTION_025)
            val rt=Runtime.getRuntime()
            val soc=if(android.os.Build.VERSION.SDK_INT>=31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE
            dialog.message="BronyaTV ${tv.ember.client.BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n"+
                Tr.text(UiText.SOC_HARDWARE_BOARD_AVAILABLE_CPU_CORES_214 ,(soc),(android.os.Build.BOARD),(rt.availableProcessors()))+
                Tr.text(UiText.APP_MAX_HEAP_MIB_USED_HEAP_215 ,(rt.maxMemory()/1048576),((rt.totalMemory()-rt.freeMemory())/1048576))+
                stats.outputSummary(p)+"\n"+stats.displaySummary(windowManager.defaultDisplay)+"\n\n"+stats.details(p,current.version)+Tr.text(UiText.NETWORK_RECEIVE_INCLUDES_PREFETCH_AND_WAITING_216)+
                Tr.text(UiText.CURRENT_F_MB_S_LAST_S_217).format((net?.bytesPerSecond ?: 0)/1048576.0,(net?.average ?: 0)/1048576.0,(net?.peak ?: 0)/1048576.0)+
                Tr.text(UiText.TOTAL_F_MIB_TIME_SINCE_DATA_219 ,(net?.idleMs?.let { "${it}ms" } ?: Tr.text(UiText.NONE_RECEIVED_218))).format((net?.total ?: 0)/1048576.0)+
                Tr.text(UiText.RECEIVE_MODE_SETTING_222 ,(rangeStatus?.mode),(if(app.settings.streamConnections==0) Tr.text(UiText.AUTO_220) else Tr.text(UiText.CONNECTIONS_221 ,(app.settings.streamConnections))))+
                Tr.text(UiText.TOTAL_STREAM_CONNECTIONS,transferBudget?.activeCount ?: 0,transferBudget?.limit ?: 1,transferBudget?.peakCount ?: 0)+
                Tr.text(UiText.RANGE_PREFETCH_MIB_223 ,((rangeStatus?.bufferedBytes ?: 0)/1048576),((rangeStatus?.budgetBytes ?: 0)/1048576))+
                Tr.text(UiText.PLAYBACK_BUFFER_MIB_BACK_BUFFER_S_224 ,((control?.allocatedBytes ?: 0)/1048576),((control?.policy?.targetBytes ?: 0)/1048576),((control?.policy?.backBufferMs ?: 0)/1000.0))+
                diskSummary(true)+Tr.text(UiText.LAST_PLAYBACK_FAILURE_225 ,(lastPlaybackFailure))+
                "TCP SO_RCVBUF ${tcp} · ${if(app.settings.receiveBufferKb>0) Tr.text(UiText.REQUESTED_KB_226 ,(app.settings.receiveBufferKb)) else Tr.text(UiText.SYSTEM_AUTO_227)}\n"+
                Tr.text(UiText.SO_RCVBUF_IS_THE_SYSTEM_REPORTED_228)+
                (transport?.summary() ?: Tr.text(UiText.WAITING_FOR_HTTP_REQUEST_229))+"\n\n"+link+"\n\n"+
                Tr.text(UiText.ORIGINAL_URL_LATEST_STREAM_URL_230 ,(PlayerStatsMonitor.redact(current.url)),(PlayerStatsMonitor.redact(stats.resolvedUrl.ifBlank { current.url })))+
                Tr.text(UiText.SERVER_URL_QUERY_VALUES_ARE_HIDDEN_231 ,(PlayerStatsMonitor.redact(session?.server.orEmpty())))
        } }
        dialog.buttons.add(Triple(Tr.text(UiText.OFF_187),true) { })
        dialog.buttons.add(Triple(Tr.text(UiText.REFRESH_212),false) { refresh() })
        dialog.buttons.add(Triple(Tr.text(UiText.COPY_213),false) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(Tr.text(UiText.BRONYATV_PLAYBACK_DIAGNOSTICS_232),dialog.message))
            message(Tr.text(UiText.DIAGNOSTICS_COPIED_233))
        })
        dialog.setOnShowListener { refresh() };displayDialog(dialog)
    }
    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val options = mutableListOf<Pair<Tracks.Group, Int>>()
        p.currentTracks.groups.filter { it.type == type }.forEach { group -> (0 until group.length).forEach { i -> if(group.isTrackSupported(i)) options += group to i } }
        val prefix = if(type==C.TRACK_TYPE_VIDEO) listOf(Tr.text(UiText.AUTO_220)) else listOf(Tr.text(UiText.AUTO_220),if(type==C.TRACK_TYPE_AUDIO) Tr.text(UiText.MUTE_234) else Tr.text(UiText.OFF_187))
        val names = prefix + options.mapIndexed { number, (g, i) ->
            val f = g.getTrackFormat(i)
            listOf(Tr.text(UiText.TRACK_235 ,(number + 1)), f.label.orEmpty(), f.language.orEmpty(), if(type==C.TRACK_TYPE_TEXT) f.codecs ?: f.sampleMimeType.orEmpty() else f.sampleMimeType.orEmpty(), if(f.height > 0) "${f.height}P" else "", if(f.channelCount > 0) Tr.text(UiText.CHANNELS_104 ,(f.channelCount)) else "").filter { it.isNotBlank() }.joinToString(" · ") + if(g.isTrackSelected(i)) " ✓" else ""
        }
        val disabled=type in p.trackSelectionParameters.disabledTrackTypes
        val override=p.trackSelectionParameters.overrides.values.firstOrNull { it.type==type }
        val selected=when {
            disabled && prefix.size==2 -> 1
            override!=null -> options.indexOfFirst { (g,i) -> g.mediaTrackGroup==override.mediaTrackGroup && i in override.trackIndices }.let { if(it<0) 0 else it+prefix.size }
            else -> 0
        }
        TvUi.dialog(this).setTitle(when(type) { C.TRACK_TYPE_VIDEO -> Tr.text(UiText.VIDEO_TRACKS_178); C.TRACK_TYPE_AUDIO -> Tr.text(UiText.AUDIO_TRACKS_179); else -> Tr.text(UiText.SUBTITLE_TRACKS_180) }).setSingleChoiceItems(names.toTypedArray(),selected) { dialog, index ->
            val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, prefix.size==2 && index == 1)
            if(index >= prefix.size) { val (g, i) = options[index - prefix.size]; b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
            p.trackSelectionParameters = b.build()
            dialog.dismiss()
        }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create().also { displayDialog(it) }
    }
    private fun externalDialog() {
        val choices = tv.ember.client.settings.PlayerChoice.entries.filter { it != tv.ember.client.settings.PlayerChoice.INTERNAL && ExternalPlayers.available(this, it) }
        if(choices.isEmpty()) { message(Tr.text(UiText.VLC_MX_PLAYER_AND_JUST_PLAYER_236)); return }
        TvUi.dialog(this).setTitle(Tr.text(UiText.EXTERNAL_PLAYER_237)).setItems(choices.map { it.label }.toTypedArray()) { _, index ->
            val current=spec ?: return@setItems;val s=session ?: return@setItems;val video=item ?: return@setItems
            sourceJob=lifecycleScope.launch {
                try {
                    val info=app.api.playbackInfo(s,video.id,current.version.id)
                    val source=info.versions.firstOrNull { it.id==current.version.id } ?: error(Tr.text(UiText.VERSION_UNAVAILABLE))
                    val fresh=app.api.playbackSpec(s,video.id,source,info.playSessionId)
                    ExternalPlayers.launch(this@PlaybackActivity,choices[index],fresh,video.name,player?.currentPosition ?: position);finish()
                } catch(e: CancellationException) { throw e } catch(e: Exception) { message(e.message ?: Tr.text(UiText.EXTERNAL_PLAYER_FAILED_TO_START_238)) }
            }
        }.create().also { displayDialog(it) }
    }
    private fun cancelSeekPreview() {
        main.removeCallbacks(commitSeek);pendingSeek=null
        seekPreview.visibility=View.GONE
    }
    private fun commitSeekPreview() {
        main.removeCallbacks(commitSeek)
        val target=pendingSeek ?: return
        pendingSeek=null;seekPreview.visibility=View.GONE
        control?.markSeek();player?.seekTo(target);position=target
    }
    // Framework Activity callback; lint inherits the restriction on AndroidX's internal base class.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && controllerVisible) controllerInteraction=SystemClock.elapsedRealtime()
        val p=player
        if(activeDialog==null && p!=null && ::playerView.isInitialized) {
            val key=event.keyCode
            val directional=key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT) &&
                (!controllerVisible || pendingSeek!=null)
            val dedicated=key in listOf(KeyEvent.KEYCODE_MEDIA_REWIND,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
            if((directional || dedicated) && p.isCurrentMediaItemSeekable) {
                if(event.action==KeyEvent.ACTION_DOWN) {
                    main.removeCallbacks(commitSeek)
                    val now=SystemClock.elapsedRealtime()
                    if(event.repeatCount==0 || now-lastSeekMove>=450) {
                        if(pendingSeek==null) seekOrigin=p.currentPosition
                        val direction=if(key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_MEDIA_REWIND) -1 else 1
                        val target=SeekPolicy.target(pendingSeek ?: p.currentPosition,p.duration,direction,event.repeatCount,app.settings.seekSeconds,app.settings.longSeekSeconds)
                        pendingSeek=target;lastSeekMove=now
                        val delta=target-seekOrigin
                        seekPreview.text="${if(delta>=0) Tr.text(UiText.FORWARD_239) else Tr.text(UiText.REWIND_240)}  ${SeekPolicy.time(target)}  /  ${SeekPolicy.time(p.duration)}\n"+
                            Tr.text(UiText.RELEASE_TO_SEEK_BACK_TO_CANCEL_241 ,(if(delta>=0) "+" else "−"),(SeekPolicy.time(kotlin.math.abs(delta))))
                        seekPreview.visibility=View.VISIBLE
                    }
                } else if(event.action==KeyEvent.ACTION_UP) main.postDelayed(commitSeek,280)
                return true
            }
            if(pendingSeek!=null && key in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER) && event.action==KeyEvent.ACTION_DOWN) {
                commitSeekPreview();return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if(activeDialog==null && !controllerVisible && keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) { controllerVisible=true;controllerInteraction=SystemClock.elapsedRealtime();return true }
        when(keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { showMenu(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { player?.let { it.playWhenReady = !it.playWhenReady }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { player?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { player?.pause(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position)
        outState.putBoolean("playing", player?.playWhenReady ?: wantedPlay)
        outState.putBoolean("controls",controllerVisible);outState.putString("control_focus",controllerFocus)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        cancelSeekPreview()
        app.imageCache.playback(false)
        active=false;retryJob?.cancel();loadJob?.cancel();monitorJob?.cancel();neighborJob?.cancel();sourceJob?.cancel();playerBuildJob?.cancel()
        activeDialog?.dismiss();activeDialog=null;outroDeadline=0
        if(registered) { runCatching { connectivity.unregisterNetworkCallback(connectionCallback) }; registered = false }
        main.removeCallbacksAndMessages(null)
        player?.let { position = it.currentPosition; wantedPlay = it.playWhenReady; trackPreferences = it.trackSelectionParameters }
        if(startedReported) { report("Stopped"); startedReported = false }
        if(::playerView.isInitialized) playerView.player = null
        diskPrefetch?.close();diskPrefetch=null
        player?.removeListener(this);player?.release(); player = null
        seekRecovery.clear()
        playbackHttp?.connectionPool?.evictAll(); playbackHttp = null; receiveSocketFactory = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
    override fun onDestroy() {
        // Finish any last stop notification, then end this scope; no background service.
        reportScope.launch { delay(5500); reportScope.cancel() }
        super.onDestroy()
    }
}
