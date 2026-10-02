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
import android.widget.TextView
import android.widget.ScrollView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
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
import tv.ember.client.ui.TvActivity
import tv.ember.client.ui.TvUi

@UnstableApi
class PlaybackActivity : TvActivity(), Player.Listener {
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var osd: TextView
    private lateinit var debug: TextView
    private lateinit var menu: android.widget.Button
    private var player: ExoPlayer? = null
    private var stats = PlayerStatsMonitor()
    private lateinit var network: NetworkMonitor
    private lateinit var cpu: CpuMonitor
    private lateinit var memory: MemoryMonitor
    private var control: TvLoadControl? = null
    private var playbackHttp: okhttp3.OkHttpClient? = null
    private var receiveSocketFactory: ReceiveBufferSocketFactory? = null
    private var transport:HttpTransportMonitor?=null
    private var rangeStatus:RangePlaybackStatus?=null
    private var lastNetwork:NetworkSample?=null
    private var session: Session? = null
    private var item: VideoItem? = null
    private var spec: PlaybackSpec? = null
    private var position = 0L
    private var wantedPlay = true
    private var softwareMode = false
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
        playerView = PlayerView(this).apply {
            controllerShowTimeoutMs = 4500
            setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false)
            setShowFastForwardButton(true); setShowRewindButton(true)
            setKeepContentOnPlayerReset(true)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                title.visibility = visibility
                if(::menu.isInitialized) menu.visibility = visibility
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        title = TvUi.text(this, "正在打开视频…", 20f).apply { setShadowLayer(4f, 0f, 1f, android.graphics.Color.BLACK) }
        root.addView(title, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { topMargin = 26; marginStart = 36 })
        status = TvUi.text(this, "", 18f).apply { setBackgroundColor(0xB0000000.toInt()); setPadding(20, 10, 20, 10); visibility = View.GONE }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 70 })
        osd = overlay(13f)
        root.addView(osd, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = 24; marginEnd = 28 })
        debug = overlay(11f)
        root.addView(debug, FrameLayout.LayoutParams(TvUi.dp(root, 520), -2, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = 100; marginStart = 28 })
        menu = TvUi.button(this, "播放选项") { showMenu() }
        root.addView(menu, FrameLayout.LayoutParams(-2, TvUi.dp(root, 43), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 18 })
        setContentView(root)
        playerView.requestFocus()
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
        if(spec == null) loadVideo() else createPlayer()
        startMonitor()
    }
    private fun loadVideo(rejectedAddress: String? = null) {
        val s = session ?: return
        val id = intent.getStringExtra("item_id") ?: return
        loadJob?.cancel()
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
                if(active) createPlayer()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { android.util.Log.e("EmberPlayback", "Unable to prepare media", e); setStatus(e.message ?: "无法获取片源；按菜单键重试") }
        }
    }
    private fun createPlayer() {
        if(!active) return
        val s = session ?: return; val current = spec ?: return
        player?.let { position = it.currentPosition; wantedPlay = it.playWhenReady; trackPreferences = it.trackSelectionParameters; playerView.player = null; it.release() }
        playbackHttp?.connectionPool?.evictAll()
        val r = Runtime.getRuntime()
        val mem = ActivityManager.MemoryInfo().also { (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val policy = BufferPolicy.create(app.settings.snapshot(), r.maxMemory(), r.totalMemory() - r.freeMemory(), mem.lowMemory)
        control = TvLoadControl(policy)
        val currentStats=PlayerStatsMonitor().apply { sourceBitrate=current.version.bitrate };stats=currentStats
        val currentNetwork=NetworkMonitor(this);network=currentNetwork;lastNetwork=null
        val selector = MediaCodecSelector { mime, secure, tunnel ->
            val codecs = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunnel)
            codecs.forEach(currentStats::registerCodec)
            if(softwareMode) codecs.filter { it.softwareOnly } else codecs.sortedBy { if(it.hardwareAccelerated) 0 else 1 }
        }
        val renderers = DefaultRenderersFactory(this).setEnableDecoderFallback(true).setMediaCodecSelector(selector)
        receiveSocketFactory = ReceiveBufferSocketFactory(app.settings.receiveBufferKb * 1024)
        val prefetchBudget=minOf(16L*1024*1024,r.maxMemory()/8,(r.maxMemory()-r.totalMemory()+r.freeMemory()).coerceAtLeast(0)/8).toInt()
        val requested=app.settings.streamConnections
        val connections=if(mem.lowMemory || prefetchBudget<65536*(requested+1)) 1 else requested
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
        val dataSources=androidx.media3.datasource.DataSource.Factory {
            RangePlaybackDataSource(http,httpClient,current.url,current.headers,range).apply { addTransferListener(currentNetwork) }
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
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000).build()
        player = p; p.addListener(this); p.addAnalyticsListener(stats)
        p.setAudioAttributes(AudioAttributes.DEFAULT, true)
        p.setHandleAudioBecomingNoisy(true)
        p.trackSelectionParameters = trackPreferences ?: p.trackSelectionParameters.buildUpon().setPreferredTextLanguage("zh").build()
        val subtitles = current.version.streams.filter { it.type == "Subtitle" && it.external && it.index !in skippedExternalSubtitles && it.codec.lowercase() in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt") }.map { stream ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(app.api.subtitleUrl(s, item!!.id, current.version, stream)))
                .setMimeType(when(stream.codec.lowercase()) { "ass", "ssa" -> MimeTypes.TEXT_SSA; "vtt", "webvtt" -> MimeTypes.TEXT_VTT; else -> MimeTypes.APPLICATION_SUBRIP })
                .setLanguage(stream.language).setLabel(stream.label).setSelectionFlags(if(stream.default) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        val media = MediaItem.Builder().setUri(current.url).setMediaId(item!!.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item!!.name).build()).setSubtitleConfigurations(subtitles).build()
        p.setMediaItem(media, position.coerceAtLeast(0)); playerView.player = p
        setStatus(if(softwareMode) "正在尝试设备软件解码器…" else "正在缓冲…")
        p.prepare(); p.playWhenReady = wantedPlay
        stalledAt = SystemClock.elapsedRealtime(); previousBuffer = -1; previousPosition = -1
    }
    override fun onPlaybackStateChanged(playbackState: Int) {
        val p = player ?: return
        when(playbackState) {
            Player.STATE_READY -> {
                retryAttempt = 0; retryJob?.cancel(); setStatus("")
                if(!startedReported) { startedReported = true; report("") }
            }
            Player.STATE_BUFFERING -> setStatus("正在缓冲，网络恢复后将继续播放…")
            Player.STATE_ENDED -> { wantedPlay = false; setStatus("播放结束 · 按返回键选择其他视频") }
        }
        if(playbackState == Player.STATE_READY) title.text = "${item?.name.orEmpty()}  ·  ${p.audioFormat?.sampleMimeType.orEmpty()}"
    }
    override fun onTracksChanged(tracks: Tracks) {
        val p = player ?: return
        title.text = "${item?.name.orEmpty()}  ·  ${p.audioFormat?.sampleMimeType.orEmpty()}"
    }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { wantedPlay = playWhenReady; report("Progress") }
    override fun onPlayerError(error: PlaybackException) {
        val p = player ?: return
        position = p.currentPosition; wantedPlay = p.playWhenReady
        if(error.errorCode in 4000..4999 && !softwareMode) {
            softwareMode = true; createPlayer(); return
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
                    if(p.playbackState == Player.STATE_BUFFERING && p.playWhenReady && now - stalledAt > 45_000 && retryJob?.isActive != true) {
                        position = p.currentPosition; wantedPlay = p.playWhenReady; createPlayer()
                    }
                    if(showOsd || showDebug) {
                        val samples = withContext(Dispatchers.IO) { cpu.sample() to memory.sample() }
                        val c = samples.first; val m = samples.second
                        osd.text = "CPU ${c.percent?.let { "%.1f%%".format(it) } ?: "受限"}${if(c.processOnly) "（APP）" else ""} · ${c.frequencyMhz?.let { "${it}MHz" } ?: "频率受限"}\n" +
                            "内存 已用 ${m.totalMb - m.availableMb} / ${m.totalMb}MB\n可用 ${m.availableMb}MB · APP ${m.appMb}MB\n" +
                            "网络 %.2f MB/s · 均值 %.2f MB/s\n".format(net.bytesPerSecond / 1_048_576.0,net.average / 1_048_576.0) +
                            stats.sourceSummary(spec!!.version)+"\n"+stats.summary(p) +
                            "\n缓冲内存 ${(control?.allocatedBytes ?: 0) / 1_048_576} / ${(control?.policy?.targetBytes ?: 0) / 1_048_576}MB"
                        osd.append("\n${net.state} · ${rangeStatus?.mode}\n预取 ${(rangeStatus?.bufferedBytes ?: 0)/1048576} / ${(rangeStatus?.budgetBytes ?: 0)/1048576}MB\nTCP 接收 ${receiveSocketFactory?.effectiveBytes?.takeIf { it > 0 }?.let { "${it / 1024}KB" } ?: "等待连接"}" +
                            if(app.settings.receiveBufferKb > 0) "（请求 ${app.settings.receiveBufferKb}KB）" else "（自动）")
                        osd.visibility = if(showOsd) View.VISIBLE else View.GONE
                        debug.text = stats.debug(p, spec?.url.orEmpty(), session?.server.orEmpty())
                        debug.visibility = if(showDebug) View.VISIBLE else View.GONE
                    } else { osd.visibility = View.GONE; debug.visibility = View.GONE }
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
    private fun setStatus(value: String) { status.text = value; status.visibility = if(value.isBlank()) View.GONE else View.VISIBLE }
    private fun showMenu() {
        val options = mutableListOf("视频轨道", "音频轨道", "字幕轨道", "显示性能信息：${if(showOsd) "开启" else "关闭"}", "重新连接", "使用外部播放器", "播放诊断详情")
        if(app.settings.debugEnabled) options.add("高级调试信息：${if(showDebug) "开启" else "关闭"}")
        AlertDialog.Builder(this).setTitle("播放选项").setItems(options.toTypedArray()) { _, index -> when(index) {
            0 -> chooseTrack(C.TRACK_TYPE_VIDEO)
            1 -> chooseTrack(C.TRACK_TYPE_AUDIO)
            2 -> chooseTrack(C.TRACK_TYPE_TEXT)
            3 -> { showOsd = !showOsd; app.settings.osd = showOsd }
            4 -> { retryJob?.cancel(); player?.let { position = it.currentPosition }; refreshedRejectedUrl = false; loadVideo() }
            5 -> externalDialog()
            6 -> showDiagnostics()
            7 -> showDebug = !showDebug
        } }.show()
    }
    private fun showDiagnostics() {
        val text=TvUi.text(this,"正在收集诊断信息…",13f).apply {
            typeface=android.graphics.Typeface.MONOSPACE;setPadding(22,14,22,14)
        }
        val scroll=ScrollView(this).apply { addView(text);isFocusable=true;isFocusableInTouchMode=true }
        val dialog=AlertDialog.Builder(this).setTitle("播放诊断详情").setView(scroll)
            .setPositiveButton("关闭",null).setNeutralButton("刷新",null).setNegativeButton("复制",null).create()
        fun refresh() { lifecycleScope.launch {
            val link=withContext(Dispatchers.IO) { network.linkDetails() }
            val p=player ?: return@launch;val current=spec ?: return@launch;val net=lastNetwork
            val tcp=receiveSocketFactory?.effectiveBytes?.takeIf { it>0 }?.let { "${it/1024}KB" } ?: "等待连接"
            text.text="Ember TV ${tv.ember.client.BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n"+
                stats.details(p,current.version)+"\n\n网络接收（包含预取，含等待时间，非测速）\n"+
                "当前 %.2f MB/s · 近5秒 %.2f MB/s · 近30秒峰值 %.2f MB/s\n".format((net?.bytesPerSecond ?: 0)/1048576.0,(net?.average ?: 0)/1048576.0,(net?.peak ?: 0)/1048576.0)+
                "累计 %.1f MiB · 距离收到数据 ${net?.idleMs?.let { "${it}ms" } ?: "未收到"}\n".format((net?.total ?: 0)/1048576.0)+
                "接收方式 ${rangeStatus?.mode} · 设置 ${app.settings.streamConnections} 路\n"+
                "分段预取 ${(rangeStatus?.bufferedBytes ?: 0)/1048576} / ${(rangeStatus?.budgetBytes ?: 0)/1048576}MiB\n"+
                "播放缓冲 ${(control?.allocatedBytes ?: 0)/1048576} / ${(control?.policy?.targetBytes ?: 0)/1048576}MiB\n"+
                "TCP SO_RCVBUF $tcp · ${if(app.settings.receiveBufferKb>0) "请求 ${app.settings.receiveBufferKb}KB" else "系统自动"}\n"+
                "SO_RCVBUF 是最近连接的系统报告值；不是服务端看到的 TCP 广告窗口，也不是全部连接总和。\n"+
                (transport?.summary() ?: "等待 HTTP 请求")+"\n\n"+link+"\n\n"+
                "原始地址 ${PlayerStatsMonitor.redact(current.url)}\n最近取流地址 ${PlayerStatsMonitor.redact(stats.resolvedUrl.ifBlank { current.url })}\n"+
                "服务器 ${PlayerStatsMonitor.redact(session?.server.orEmpty())}\n地址中的查询值已隐藏。"
        } }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh() }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Ember TV 播放诊断",text.text))
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
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels*0.86).toInt(),(resources.displayMetrics.heightPixels*0.88).toInt())
    }
    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val options = mutableListOf<Pair<Tracks.Group, Int>>()
        p.currentTracks.groups.filter { it.type == type }.forEach { group -> (0 until group.length).forEach { i -> if(group.isTrackSupported(i)) options += group to i } }
        val names = listOf("自动", "关闭") + options.mapIndexed { number, (g, i) ->
            val f = g.getTrackFormat(i)
            listOf("轨道 ${number + 1}", f.label.orEmpty(), f.language.orEmpty(), if(type==C.TRACK_TYPE_TEXT) f.codecs ?: f.sampleMimeType.orEmpty() else f.sampleMimeType.orEmpty(), if(f.height > 0) "${f.height}P" else "", if(f.channelCount > 0) "${f.channelCount}声道" else "").filter { it.isNotBlank() }.joinToString(" · ") + if(g.isTrackSelected(i)) " ✓" else ""
        }
        AlertDialog.Builder(this).setTitle(when(type) { C.TRACK_TYPE_VIDEO -> "视频轨道"; C.TRACK_TYPE_AUDIO -> "音频轨道"; else -> "字幕轨道" }).setItems(names.toTypedArray()) { _, index ->
            val b = p.trackSelectionParameters.buildUpon().clearOverridesOfType(type).setTrackTypeDisabled(type, index == 1)
            if(index >= 2) { val (g, i) = options[index - 2]; b.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
            p.trackSelectionParameters = b.build()
        }.show()
    }
    private fun externalDialog() {
        val choices = tv.ember.client.settings.PlayerChoice.entries.filter { it != tv.ember.client.settings.PlayerChoice.INTERNAL && ExternalPlayers.available(this, it) }
        if(choices.isEmpty()) { message("未检测到 VLC、MX Player 或 Just Player"); return }
        AlertDialog.Builder(this).setTitle("外部播放器").setItems(choices.map { it.label }.toTypedArray()) { _, index ->
            val current = spec ?: return@setItems
            try {
                val pos = player?.currentPosition ?: position
                ExternalPlayers.launch(this, choices[index], current, item?.name.orEmpty(), pos)
                finish()
            } catch(e: Exception) { message(e.message ?: "外部播放器启动失败") }
        }.show()
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
        active = false; retryJob?.cancel(); loadJob?.cancel(); monitorJob?.cancel()
        if(registered) { runCatching { connectivity.unregisterNetworkCallback(connectionCallback) }; registered = false }
        main.removeCallbacksAndMessages(null)
        player?.let { position = it.currentPosition; wantedPlay = it.playWhenReady; trackPreferences = it.trackSelectionParameters }
        if(startedReported) { report("Stopped"); startedReported = false }
        if(::playerView.isInitialized) playerView.player = null
        player?.release(); player = null
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
