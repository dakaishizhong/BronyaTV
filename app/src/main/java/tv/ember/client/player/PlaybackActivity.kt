package tv.ember.client.player

import android.app.ActivityManager
import android.app.AlertDialog
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
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Button
import tv.ember.client.R
import android.widget.TextView
import android.widget.ScrollView
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
import tv.ember.client.ui.TvActivity
import tv.ember.client.ui.TvUi
import tv.ember.client.cache.*
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSink

@UnstableApi
class PlaybackActivity : TvActivity(), Player.Listener {
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var osd: TextView
    private lateinit var debug: TextView
    private lateinit var menu: Button
    private lateinit var episodeBar: LinearLayout
    private lateinit var previousEpisode: Button
    private lateinit var nextEpisode: Button
    private lateinit var skipIntro: Button
    private lateinit var skipOutro: Button
    private var neighbors=EpisodeNeighbors()
    private var neighborsForId=""
    private var neighborJob: Job?=null
    private var changingEpisode=false
    private var introHandled=false
    private var outroHandled=false
    private var outroDeadline=0L
    private var sourceJob: Job?=null
    private var activeDialog: AlertDialog?=null
    private var player: ExoPlayer? = null
    private var stats = PlayerStatsMonitor()
    private lateinit var network: NetworkMonitor
    private lateinit var cpu: CpuMonitor
    private lateinit var memory: MemoryMonitor
    private var control: TvLoadControl? = null
    private var playbackHttp: okhttp3.OkHttpClient? = null
    private var diskPrefetch: DiskPrefetcher? = null
    private var diskMode = "未开始"
    private var playerBuildJob: Job? = null
    private var lastPlaybackFailure = "未记录"
    private var receiveSocketFactory: ReceiveBufferSocketFactory? = null
    private var transport:HttpTransportMonitor?=null
    private var rangeStatus:RangePlaybackStatus?=null
    private var lastNetwork:NetworkSample?=null
    private var session: Session? = null
    private var item: VideoItem? = null
    private var spec: PlaybackSpec? = null
    private var position = 0L
    private var wantedPlay = true
    private var softwareVideo = false
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
    private lateinit var seekPreview: TextView
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
        showOsd = app.settings.osd
        showDebug = app.settings.debugEnabled
        network = NetworkMonitor(this); cpu = CpuMonitor(); memory = MemoryMonitor(this)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        playerView = (layoutInflater.inflate(R.layout.bronya_player_view,null) as PlayerView).apply {
            resizeMode=app.settings.resizeMode
            subtitleView?.setFractionalTextSize(.0533f*app.settings.subtitleScale/100f)
            controllerShowTimeoutMs = 4500
            setShowSubtitleButton(false); setShowNextButton(false); setShowPreviousButton(false)
            setShowFastForwardButton(true); setShowRewindButton(true)
            setKeepContentOnPlayerReset(true)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                if(::title.isInitialized) title.visibility=visibility
                if(::episodeBar.isInitialized) episodeBar.visibility=visibility
            })
        }
        listOf(androidx.media3.ui.R.id.exo_rew,androidx.media3.ui.R.id.exo_play_pause,androidx.media3.ui.R.id.exo_ffwd)
            .forEach { id -> playerView.findViewById<View>(id)?.let(TvUi::focusOnTouch) }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        title = TvUi.text(this, "正在打开视频…", 20f).apply { setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK) }
        root.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = TvUi.dp(root,20); marginStart = TvUi.dp(root,24) })
        status = TvUi.text(this, "", 18f).apply { setBackgroundColor(0xB0000000.toInt()); setPadding(20, 10, 20, 10); visibility = View.GONE }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = TvUi.dp(root,116) })
        osd = overlay(12f).apply { maxWidth=TvUi.dp(this,330) }
        root.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = 24; marginEnd = 28 })
        debug = overlay(11f)
        root.addView(debug, FrameLayout.LayoutParams(TvUi.dp(root, 520), -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = 100; marginStart = 28 })
        episodeBar=TvUi.row(this)
        menu=TvUi.button(this,"播放选项") { showMenu() }
        previousEpisode=TvUi.button(this,"上一集") { neighbors.previous?.let(::switchEpisode) }
        nextEpisode=TvUi.button(this,"下一集") { neighbors.next?.let(::switchEpisode) }
        skipIntro=TvUi.button(this,"跳过片头") { skipOpening() }
        skipOutro=TvUi.button(this,"跳过片尾") { finishEpisode() }
        listOf(menu,previousEpisode,nextEpisode,skipIntro,skipOutro).forEach {
            episodeBar.addView(it,LinearLayout.LayoutParams(-2,TvUi.dp(root,42)).apply { marginEnd=TvUi.dp(root,8) })
        }
        root.addView(episodeBar,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START).apply { topMargin=TvUi.dp(root,56);marginStart=TvUi.dp(root,24) })
        updateEpisodeButtons()
        seekPreview=TvUi.text(this,"",22f).apply {
            background=TvUi.box(0xE8172230.toInt(),22f,TvUi.accent)
            setPadding(TvUi.dp(this,28),TvUi.dp(this,16),TvUi.dp(this,28),TvUi.dp(this,16))
            gravity=Gravity.CENTER;visibility=View.GONE
        }
        root.addView(seekPreview,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=TvUi.dp(root,100) })
        setContentView(root)
        playerView.requestFocus()
        onBackPressedDispatcher.addCallback(this) {
            when {
                pendingSeek!=null -> cancelSeekPreview()
                outroDeadline>0 -> cancelOutro()
                playerView.isControllerFullyVisible -> playerView.hideController()
                else -> finish()
            }
        }
    }
    private fun overlay(size: Float) = TvUi.text(this, "", size).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(16, 12, 16, 12); setBackgroundColor(0xB8101722.toInt()); visibility = View.GONE
        isFocusable = false
    }
    override fun onStart() {
        super.onStart(); if(session == null) return
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
                item=video;spec=next;title.text=video.name
                refreshNeighbors()
                if(active) createPlayer()
                return
            }
        }
        setStatus("正在获取原始片源…")
        loadJob = lifecycleScope.launch {
            try {
                val v = app.api.detail(s, id)
                val sourceId = intent.getStringExtra("source_id").orEmpty()
                val info = app.api.playbackInfo(s, id, sourceId)
                val source = info.versions.firstOrNull { it.id == sourceId } ?: throw IllegalStateException("所选片源已不可用，请返回重新选择")
                var next = app.api.playbackSpec(s, id, source, info.playSessionId, forceOriginalRoute)
                // If refreshing an unavailable stream returns the same URL, ask the
                // authenticated Emby original-file endpoint for the selected source.
                // Never rewrite a signed CDN URL or infer MKV from a failing MP4 URL.
                if(rejectedAddress != null && next.url == rejectedAddress && !forceOriginalRoute) {
                    val original = app.api.playbackSpec(s, id, source, info.playSessionId, forceOriginal = true)
                    if(original.url != next.url) { forceOriginalRoute = true; next = original }
                }
                item = v; spec = next
                title.text = v.name
                refreshNeighbors()
                if(active) createPlayer()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { android.util.Log.e("BronyaTVPlayback", "Unable to prepare media", e); setStatus(e.message ?: "无法获取片源；按菜单键重试") }
        }
    }
    private fun updateEpisodeButtons() {
        if(!::previousEpisode.isInitialized) return
        val episode=item?.type=="Episode"
        previousEpisode.visibility=if(episode && neighbors.previous!=null) View.VISIBLE else View.GONE
        nextEpisode.visibility=if(episode && neighbors.next!=null) View.VISIBLE else View.GONE
        skipIntro.visibility=if(episode && app.settings.introSeconds>0) View.VISIBLE else View.GONE
        skipOutro.visibility=if(episode && app.settings.outroSeconds>0) View.VISIBLE else View.GONE
        listOf(previousEpisode,nextEpisode,skipIntro,skipOutro).forEach { it.isEnabled=!changingEpisode }
    }
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
                if(item?.id==video.id) message("剧集列表暂时无法加载，可在播放选项中重试")
            }
        }
    }
    private fun switchEpisode(next: VideoItem) {
        if(!active || changingEpisode) return
        val s=session ?: return
        changingEpisode=true;cancelSeekPreview();cancelOutro();updateEpisodeButtons()
        retryJob?.cancel();loadJob?.cancel();neighborJob?.cancel();sourceJob?.cancel();activeDialog?.dismiss()
        val resume=player?.playWhenReady==true
        player?.pause();setStatus("正在打开 ${next.episodeLabel.ifBlank { next.name }}…")
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
                } ?: error("本集没有可播放的片源")
                val prepared=app.api.playbackSpec(s,video.id,source,playback.playSessionId)
                if(!active) return@launch
                if(startedReported) report("Stopped")
                diskPrefetch?.close();diskPrefetch=null
                startedReported=false;playerView.player=null;player?.removeListener(this@PlaybackActivity);player?.release();player=null
                // Track overrides reference the old item's groups; language preferences carry to the new item.
                position=0;wantedPlay=true;trackPreferences=null;softwareVideo=false;retryAttempt=0
                forceOriginalRoute=false;refreshedRejectedUrl=false;lastRejectedUrlRefresh=0;lastProgressReport=0
                skippedExternalSubtitles.clear();introHandled=false;outroHandled=false
                neighbors=EpisodeNeighbors();neighborsForId="";item=video;spec=prepared
                intent.putExtra("item_id",video.id).putExtra("source_id",source.id).putExtra("position_ms",0L)
                title.text=listOf(video.name,video.episodeLabel).filter(String::isNotBlank).joinToString(" · ")
                createPlayer();refreshNeighbors()
            } catch(e: CancellationException) { throw e } catch(e: Exception) {
                setStatus(e.message ?: "无法打开剧集，请重试")
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
        if(target!=null) { introHandled=true;control?.markSeek();p.seekTo(target) } else message("已过片头或当前时长不足")
    }
    private fun maybeSkipEnding(p:ExoPlayer,now:Long) {
        if(changingEpisode || item?.type!="Episode") return
        if(activeDialog?.isShowing==true || !hasWindowFocus()) return
        if(p.playbackState==Player.STATE_ENDED && app.settings.autoNextEpisode && neighbors.next!=null) { switchEpisode(neighbors.next!!);return }
        if(outroDeadline>0) {
            if(!p.isPlaying) { cancelOutro();return }
            if(now>=outroDeadline) { outroDeadline=0;finishEpisode() }
            else setStatus("${(outroDeadline-now+999)/1000} 秒后播放下一集 · 按返回取消")
            return
        }
        if(outroHandled || !p.isPlaying || pendingSeek!=null || !SkipPolicy.outro(p.currentPosition,p.duration,app.settings.outroSeconds)) return
        if(neighborJob?.isActive==true) return
        outroHandled=true
        if(app.settings.autoNextEpisode && neighbors.next!=null) {
            outroDeadline=now+5000;setStatus("5 秒后播放下一集 · 按返回取消")
        } else finishEpisode()
    }
    private fun cancelOutro() { outroDeadline=0;if(::status.isInitialized) setStatus("") }
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
                diskMode=if(playlist) "当前流媒体列表使用播放缓冲" else app.playbackCache.mode
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
            val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
            codecs.forEach(currentStats::registerCodec)
            if(softwareVideo && MimeTypes.isVideo(mime)) codecs.filter { it.softwareOnly }
            else codecs.sortedBy { if(it.hardwareAccelerated) 0 else 1 }
        }
        val renderers = TvRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        receiveSocketFactory = ReceiveBufferSocketFactory(app.settings.receiveBufferKb * 1024)
        val streamPlan=StreamPolicy.create(app.settings.streamConnections,current.version.bitrate,r.maxMemory(),r.totalMemory()-r.freeMemory(),mem.lowMemory,disk!=null)
        val prefetchBudget=streamPlan.budgetBytes
        val connections=streamPlan.connections
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
        val httpClient=builder.build();playbackHttp=httpClient
        val http=OkHttpDataSource.Factory(httpClient).setDefaultRequestProperties(current.headers)
        val range=RangePlaybackStatus(connections,prefetchBudget);rangeStatus=range
        val upstream=androidx.media3.datasource.DataSource.Factory {
            RangePlaybackDataSource(http,httpClient,current.url,current.headers,range).apply { addTransferListener(currentNetwork) }
        }
        val dataSources=if(disk==null) upstream else {
            val prefetchClient=httpClient.newBuilder().dispatcher(okhttp3.Dispatcher()).build()
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
                .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE or CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
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
        setStatus(if(softwareVideo) "正在尝试设备软件解码器…" else "正在缓冲…")
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
            Player.STATE_BUFFERING -> setStatus("正在缓冲，网络恢复后将继续播放…")
            Player.STATE_ENDED -> {
                wantedPlay=false
                if(item?.type=="Episode" && app.settings.autoNextEpisode && neighbors.next!=null && !changingEpisode && activeDialog?.isShowing!=true) switchEpisode(neighbors.next!!)
                else setStatus("播放结束")
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
        lastPlaybackFailure=listOf(error.errorCodeName,when(failedType) { C.TRACK_TYPE_AUDIO -> "音频";C.TRACK_TYPE_VIDEO -> "视频";else -> "取流或其他" },
            failure?.rendererFormat?.sampleMimeType.orEmpty(),failure?.rendererName.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
        position = p.currentPosition; wantedPlay = p.playWhenReady
        if(error.errorCode in 4000..4999 && error is ExoPlaybackException &&
            error.type==ExoPlaybackException.TYPE_RENDERER && error.rendererIndex in 0 until p.rendererCount &&
            p.getRendererType(error.rendererIndex)==C.TRACK_TYPE_VIDEO && !softwareVideo) {
            softwareVideo = true; createPlayer(); return
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
                message("字幕请求失败（HTTP ${http.responseCode}），已跳过该字幕并继续播放")
                createPlayer(); return
            }
        }
        if(http != null && http.responseCode in listOf(401, 403, 404, 410) &&
            (!refreshedRejectedUrl || SystemClock.elapsedRealtime() - lastRejectedUrlRefresh >= 60_000)) {
            refreshedRejectedUrl = true
            lastRejectedUrlRefresh = SystemClock.elapsedRealtime()
            setStatus("HTTP ${http.responseCode}，正在重新获取原始片源地址…")
            loadVideo(if(http.responseCode in listOf(404, 410)) spec?.url else null); return
        }
        if(http != null && http.responseCode in listOf(404, 410) && !forceOriginalRoute) {
            val s=session; val current=spec; val id=intent.getStringExtra("item_id")
            if(s != null && current != null && id != null) {
                val original=app.api.playbackSpec(s,id,current.version,current.playSessionId,forceOriginal=true)
                if(original.url != current.url) {
                    forceOriginalRoute=true; spec=original
                    setStatus("HTTP ${http.responseCode}，正在尝试所选片源的原文件地址…")
                    createPlayer(); return
                }
            }
        }
        val retryable = if(http != null) RetryPolicy.retryableHttp(http.responseCode) else error.errorCode in 2000..2999 && error.errorCode !in listOf(2005, 2006, 2007)
        if(retryable) scheduleRetry() else setStatus(if(http != null) HttpFailures.message(http.responseCode,
            http.dataSpec.uri.toString().contains("/Subtitles/", true) || http.dataSpec.uri.toString().substringBefore('?').endsWith(".srt", true))
            else "播放失败：${error.errorCodeName}。按菜单键重试、选择其他轨道或外部播放器。")
    }
    private fun scheduleRetry() {
        if(!active || retryJob?.isActive == true) return
        val wait = RetryPolicy.delayMs(retryAttempt++)
        setStatus("连接中断，${wait / 1000} 秒后自动重连…")
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
                        osd.text = "CPU ${c.percent?.let { "%.1f%%".format(it) } ?: "受限"}${if(c.processOnly) "（APP）" else ""} · ${c.frequencyMhz?.let { "${it}MHz" } ?: "频率受限"}\n" +
                            "APP 单核等效 %.1f%%\n".format(c.coreEquivalent)+
                            "内存 已用 ${m.totalMb - m.availableMb} / ${m.totalMb}MB\n可用 ${m.availableMb}MB · APP ${m.appMb}MB\n" +
                            "网络 %.2f MB/s · 均值 %.2f MB/s\n".format(net.bytesPerSecond / 1_048_576.0,net.average / 1_048_576.0) +
                            stats.sourceSummary(spec!!.version)+"\n"+stats.summary(p) +
                            "\n缓冲内存 ${(control?.allocatedBytes ?: 0) / 1_048_576} / ${(control?.policy?.targetBytes ?: 0) / 1_048_576}MB"+diskSummary(false)
                        osd.append("\n${net.state} · ${rangeStatus?.mode}\n预取 ${(rangeStatus?.bufferedBytes ?: 0)/1048576} / ${(rangeStatus?.budgetBytes ?: 0)/1048576}MB\nTCP 接收 ${receiveSocketFactory?.effectiveBytes?.takeIf { it > 0 }?.let { "${it / 1024}KB" } ?: "等待连接"}" +
                            if(app.settings.receiveBufferKb > 0) "（请求 ${app.settings.receiveBufferKb}KB）" else "（自动）")
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
        reportScope.launch { withTimeoutOrNull(5000) { runCatching { app.api.report(s, event, v.id, current, pos, paused) } } }
    }
    private fun diskSummary(detailed:Boolean):String {
        val disk=diskPrefetch ?: return "\n磁盘缓存 $diskMode"
        val ahead=disk.aheadBytes
        val seconds=spec?.version?.bitrate?.takeIf { it>0 }?.let { ahead*8.0/it }
        return "\n磁盘前向 ${ahead/1048576}MB"+(seconds?.let { "（约 %.1fs）".format(it) } ?: "")+
            " · ${disk.state}"+if(detailed) "\n磁盘占用 ${disk.usedBytes/1048576} / ${disk.capacityBytes/1048576}MiB · 命中读取 ${disk.hitBytes.get()/1048576}MiB" else ""
    }
    private fun setStatus(value: String) { status.text = value; status.visibility = if(value.isBlank()) View.GONE else View.VISIBLE }
    private fun showMenu() {
        if(activeDialog?.isShowing==true || changingEpisode) return
        commitSeekPreview()
        val actions=mutableListOf<Pair<String,()->Unit>>(
            "视频轨道" to { chooseTrack(C.TRACK_TYPE_VIDEO) },
            "音频轨道" to { chooseTrack(C.TRACK_TYPE_AUDIO) },
            "字幕轨道" to { chooseTrack(C.TRACK_TYPE_TEXT) },
            "播放速度：${playbackSpeed} 倍" to { chooseSpeed() },
            "画面比例" to { chooseResize() },
            "字幕大小" to { chooseSubtitleSize() },
            "跳转到指定时间" to { chooseTime() },
            "切换片源" to { chooseSource() },
            "显示性能信息：${if(showOsd) "开启" else "关闭"}" to { showOsd=!showOsd;app.settings.osd=showOsd },
            "重新连接" to { retryJob?.cancel();player?.let { position=it.currentPosition };refreshedRejectedUrl=false;loadVideo() },
            "使用外部播放器" to { externalDialog() },
            "播放诊断详情" to { showDiagnostics() }
        )
        if(item?.type=="Episode") {
            neighbors.previous?.let { episode -> actions.add(0,"上一集 · ${episode.episodeLabel}" to { switchEpisode(episode) }) }
            neighbors.next?.let { episode -> actions.add(0,"下一集 · ${episode.episodeLabel}" to { switchEpisode(episode) }) }
            actions.add("重新加载剧集列表" to { neighborsForId="";neighborJob?.cancel();neighborJob=null;refreshNeighbors() })
        }
        if(app.settings.debugEnabled) actions.add("高级调试信息：${if(showDebug) "开启" else "关闭"}" to { showDebug=!showDebug })
        val dialog=TvUi.dialog(this).setTitle("播放选项").setItems(actions.map { it.first }.toTypedArray()) { d,index ->
            d.dismiss();activeDialog=null;actions[index].second()
        }.create()
        displayDialog(dialog,false)
    }
    private fun displayDialog(dialog:AlertDialog,parentMenu:Boolean=true) {
        activeDialog=dialog
        dialog.setOnDismissListener { if(activeDialog===dialog) activeDialog=null }
        dialog.setOnCancelListener {
            if(activeDialog===dialog) activeDialog=null
            sourceJob?.cancel()
            if(parentMenu && active) main.post { if(active) showMenu() }
        }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.takeIf { it.text=="取消" }?.setOnClickListener { dialog.cancel() }
    }
    private fun chooseSpeed() {
        val values=listOf(.5f,.75f,1f,1.25f,1.5f,2f)
        TvUi.dialog(this).setTitle("播放速度").setSingleChoiceItems(values.map { "${it} 倍" }.toTypedArray(),values.indexOf(playbackSpeed)) { dialog,index ->
            playbackSpeed=values[index];player?.setPlaybackSpeed(playbackSpeed);dialog.dismiss()
        }.setNegativeButton("取消",null).create().also { displayDialog(it) }
    }
    private fun chooseResize() {
        val values=listOf(0,4,3)
        TvUi.dialog(this).setTitle("画面比例").setSingleChoiceItems(arrayOf("适应屏幕","裁切填满","拉伸"),values.indexOf(playerView.resizeMode)) { dialog,index ->
            playerView.resizeMode=values[index];app.settings.resizeMode=values[index];dialog.dismiss()
        }.setNegativeButton("取消",null).create().also { displayDialog(it) }
    }
    private fun chooseSubtitleSize() {
        val values=listOf(80,100,120,140)
        TvUi.dialog(this).setTitle("字幕大小").setSingleChoiceItems(values.map { "${it}%" }.toTypedArray(),values.indexOf(app.settings.subtitleScale)) { dialog,index ->
            app.settings.subtitleScale=values[index];playerView.subtitleView?.setFractionalTextSize(.0533f*values[index]/100f);dialog.dismiss()
        }.setNegativeButton("取消",null).create().also { displayDialog(it) }
    }
    private fun chooseTime() {
        val p=player ?: return
        if(!p.isCurrentMediaItemSeekable) { message("当前片源不支持跳转");return }
        val field=TvUi.input(this,"时:分:秒 或 分:秒").apply { setText(SeekPolicy.time(p.currentPosition)) }
        val dialog=TvUi.dialog(this).setTitle("跳转到指定时间").setView(field).setPositiveButton("跳转",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val target=SeekPolicy.parseTime(field.text.toString())
                if(target==null) { field.error="请输入有效时间，如 01:23:45 或 23:45";field.requestFocus() }
                else { control?.markSeek();p.seekTo(if(p.duration>0) target.coerceAtMost((p.duration-1).coerceAtLeast(0)) else target);dialog.dismiss() }
            }
        }
        displayDialog(dialog)
    }
    private fun chooseSource() {
        val s=session ?: return;val video=item ?: return
        if(sourceJob?.isActive==true) return
        val loading=TvUi.dialog(this).setTitle("切换片源").setMessage("正在获取可播放版本…").setNegativeButton("取消",null).create()
        displayDialog(loading)
        sourceJob=lifecycleScope.launch {
            try {
                val info=app.api.playbackInfo(s,video.id)
                if(!active || item?.id!=video.id) return@launch
                check(info.versions.isNotEmpty()) { "服务器没有提供其他片源" }
                loading.dismiss()
                TvUi.dialog(this@PlaybackActivity).setTitle("切换片源 · 保留当前进度")
                    .setSingleChoiceItems(info.versions.map { it.label }.toTypedArray(),info.versions.indexOfFirst { it.id==spec?.version?.id }) { dialog,index ->
                        try {
                            val next=app.api.playbackSpec(s,video.id,info.versions[index],info.playSessionId)
                            report("Stopped");startedReported=false;retryJob?.cancel()
                            diskPrefetch?.close();diskPrefetch=null
                            player?.let { position=it.currentPosition;wantedPlay=it.playWhenReady;playerView.player=null;it.removeListener(this@PlaybackActivity);it.release() };player=null
                            trackPreferences=null;softwareVideo=false;forceOriginalRoute=false;refreshedRejectedUrl=false
                            skippedExternalSubtitles.clear();spec=next
                            intent.putExtra("source_id",next.version.id);createPlayer();dialog.dismiss()
                        } catch(e: Exception) { message(e.message ?: "无法切换片源") }
                    }.setNegativeButton("取消",null).create().also { displayDialog(it) }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { loading.dismiss();message(e.message ?: "片源请求失败") }
        }
    }
    private fun showDiagnostics() {
        val text=TvUi.text(this,"正在收集诊断信息…",13f).apply {
            typeface=android.graphics.Typeface.MONOSPACE;setPadding(22,14,22,14)
        }
        val scroll=ScrollView(this).apply { addView(text);isFocusable=true;isFocusableInTouchMode=true }
        val dialog=TvUi.dialog(this).setTitle("播放诊断详情").setView(scroll)
            .setPositiveButton("关闭",null).setNeutralButton("刷新",null).setNegativeButton("复制",null).create()
        fun refresh() { lifecycleScope.launch {
            val link=withContext(Dispatchers.IO) { network.linkDetails() }
            val p=player ?: return@launch;val current=spec ?: return@launch;val net=lastNetwork
            val tcp=receiveSocketFactory?.effectiveBytes?.takeIf { it>0 }?.let { "${it/1024}KB" } ?: "等待连接"
            val rt=Runtime.getRuntime()
            val soc=if(android.os.Build.VERSION.SDK_INT>=31) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE
            text.text="BronyaTV ${tv.ember.client.BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n"+
                "SoC/硬件 $soc · Board ${android.os.Build.BOARD} · 可用 CPU 核 ${rt.availableProcessors()}\n"+
                "APP 最大堆 ${rt.maxMemory()/1048576}MiB · 已用堆 ${(rt.totalMemory()-rt.freeMemory())/1048576}MiB（不等于系统可用内存）\n"+
                stats.outputSummary(p)+"\n"+stats.displaySummary(windowManager.defaultDisplay)+"\n\n"+stats.details(p,current.version)+"\n\n网络接收（包含预取，含等待时间，非测速）\n"+
                "当前 %.2f MB/s · 近5秒 %.2f MB/s · 近30秒峰值 %.2f MB/s\n".format((net?.bytesPerSecond ?: 0)/1048576.0,(net?.average ?: 0)/1048576.0,(net?.peak ?: 0)/1048576.0)+
                "累计 %.1f MiB · 距离收到数据 ${net?.idleMs?.let { "${it}ms" } ?: "未收到"}\n".format((net?.total ?: 0)/1048576.0)+
                "接收方式 ${rangeStatus?.mode} · 设置 ${if(app.settings.streamConnections==0) "自动" else "${app.settings.streamConnections} 路"}\n"+
                "分段预取 ${(rangeStatus?.bufferedBytes ?: 0)/1048576} / ${(rangeStatus?.budgetBytes ?: 0)/1048576}MiB\n"+
                "播放缓冲 ${(control?.allocatedBytes ?: 0)/1048576} / ${(control?.policy?.targetBytes ?: 0)/1048576}MiB · 回退保留 ${(control?.policy?.backBufferMs ?: 0)/1000.0}s\n"+
                diskSummary(true)+"\n最近播放故障 $lastPlaybackFailure\n"+
                "TCP SO_RCVBUF $tcp · ${if(app.settings.receiveBufferKb>0) "请求 ${app.settings.receiveBufferKb}KB" else "系统自动"}\n"+
                "SO_RCVBUF 是最近连接的系统报告值；不是服务端看到的 TCP 广告窗口，也不是全部连接总和。\n"+
                (transport?.summary() ?: "等待 HTTP 请求")+"\n\n"+link+"\n\n"+
                "原始地址 ${PlayerStatsMonitor.redact(current.url)}\n最近取流地址 ${PlayerStatsMonitor.redact(stats.resolvedUrl.ifBlank { current.url })}\n"+
                "服务器 ${PlayerStatsMonitor.redact(session?.server.orEmpty())}\n地址中的查询值已隐藏。"
        } }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("BronyaTV 播放诊断",text.text))
                message("诊断信息已复制")
            }
            refresh();scroll.requestFocus()
        }
        dialog.setOnKeyListener { _,key,event ->
            if(event.action!=KeyEvent.ACTION_DOWN) false
            else when {
                key==KeyEvent.KEYCODE_DPAD_UP -> {
                    scroll.requestFocus();scroll.scrollBy(0,-(scroll.height/3).coerceAtLeast(80));true
                }
                key==KeyEvent.KEYCODE_DPAD_DOWN && scroll.hasFocus() -> {
                    if(scroll.canScrollVertically(1)) scroll.scrollBy(0,(scroll.height/3).coerceAtLeast(80))
                    else dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus()
                    true
                }
                else -> false
            }
        }
        displayDialog(dialog)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*0.86).toInt(),(resources.displayMetrics.heightPixels*0.88).toInt())
    }
    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val options = mutableListOf<Pair<Tracks.Group, Int>>()
        p.currentTracks.groups.filter { it.type == type }.forEach { group -> (0 until group.length).forEach { i -> if(group.isTrackSupported(i)) options += group to i } }
        val prefix = if(type==C.TRACK_TYPE_VIDEO) listOf("自动") else listOf("自动",if(type==C.TRACK_TYPE_AUDIO) "静音" else "关闭")
        val names = prefix + options.mapIndexed { number, (g, i) ->
            val f = g.getTrackFormat(i)
            listOf("轨道 ${number + 1}", f.label.orEmpty(), f.language.orEmpty(), if(type==C.TRACK_TYPE_TEXT) f.codecs ?: f.sampleMimeType.orEmpty() else f.sampleMimeType.orEmpty(), if(f.height > 0) "${f.height}P" else "", if(f.channelCount > 0) "${f.channelCount}声道" else "").filter { it.isNotBlank() }.joinToString(" · ") + if(g.isTrackSelected(i)) " ✓" else ""
        }
        val disabled=type in p.trackSelectionParameters.disabledTrackTypes
        val override=p.trackSelectionParameters.overrides.values.firstOrNull { it.type==type }
        val selected=when {
            disabled && prefix.size==2 -> 1
            override!=null -> options.indexOfFirst { (g,i) -> g.mediaTrackGroup==override.mediaTrackGroup && i in override.trackIndices }.let { if(it<0) 0 else it+prefix.size }
            else -> 0
        }
        TvUi.dialog(this).setTitle(when(type) { C.TRACK_TYPE_VIDEO -> "视频轨道"; C.TRACK_TYPE_AUDIO -> "音频轨道"; else -> "字幕轨道" }).setSingleChoiceItems(names.toTypedArray(),selected) { dialog, index ->
            val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, prefix.size==2 && index == 1)
            if(index >= prefix.size) { val (g, i) = options[index - prefix.size]; b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
            p.trackSelectionParameters = b.build()
            dialog.dismiss()
        }.setNegativeButton("取消",null).create().also { displayDialog(it) }
    }
    private fun externalDialog() {
        val choices = tv.ember.client.settings.PlayerChoice.entries.filter { it != tv.ember.client.settings.PlayerChoice.INTERNAL && ExternalPlayers.available(this, it) }
        if(choices.isEmpty()) { message("未检测到 VLC、MX Player 或 Just Player"); return }
        TvUi.dialog(this).setTitle("外部播放器").setItems(choices.map { it.label }.toTypedArray()) { _, index ->
            val current = spec ?: return@setItems
            try {
                val pos = player?.currentPosition ?: position
                ExternalPlayers.launch(this, choices[index], current, item?.name.orEmpty(), pos)
                finish()
            } catch(e: Exception) { message(e.message ?: "外部播放器启动失败") }
        }.create().also { displayDialog(it) }
    }
    private fun cancelSeekPreview() {
        main.removeCallbacks(commitSeek);pendingSeek=null
        if(::seekPreview.isInitialized) seekPreview.visibility=View.GONE
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
        val p=player
        if(p!=null && ::playerView.isInitialized) {
            val key=event.keyCode
            val directional=key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT) &&
                (!playerView.isControllerFullyVisible || pendingSeek!=null)
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
                        seekPreview.text="${if(delta>=0) "快进" else "快退"}  ${SeekPolicy.time(target)}  /  ${SeekPolicy.time(p.duration)}\n"+
                            "${if(delta>=0) "+" else "−"}${SeekPolicy.time(kotlin.math.abs(delta))} · 松开跳转 · 返回取消"
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
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        cancelSeekPreview()
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
