package tv.ember.client.monitor

import android.os.SystemClock
import android.media.MediaFormat
import android.view.Display
import android.os.Build
import androidx.media3.common.Format
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import tv.ember.client.data.MediaVersion
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class PlayerStatsMonitor : AnalyticsListener, VideoFrameMetadataListener {
    private data class FrameOutput(val width:Int=0,val height:Int=0,val transfer:Int=-1,val hdr10Plus:Boolean=false)
    @Volatile private var frameOutput=FrameOutput()
    private var lastMediaFormat: MediaFormat?=null
    private var videoSize=VideoSize.UNKNOWN
    private var surfaceWidth=0
    private var surfaceHeight=0
    private var rendered=false
    private var audioOutput: AudioSink.AudioTrackConfig?=null
    private val audioTracks=ArrayDeque<AudioSink.AudioTrackConfig>()
    private val codecMimes=ConcurrentHashMap<String,String>()
    override fun onVideoFrameAboutToBeRendered(presentationTimeUs:Long,releaseTimeNs:Long,format:Format,mediaFormat:MediaFormat?) {
        // This callback runs on the playback thread. Read a new output format once, not once per frame.
        if(mediaFormat==null || mediaFormat===lastMediaFormat) return
        lastMediaFormat=mediaFormat
        fun value(key:String)=runCatching { if(mediaFormat.containsKey(key)) mediaFormat.getInteger(key) else -1 }.getOrDefault(-1)
        val width=value("width");val height=value("height")
        val left=value("crop-left");val right=value("crop-right");val top=value("crop-top");val bottom=value("crop-bottom")
        frameOutput=FrameOutput(if(left>=0 && right>=left) right-left+1 else width,
            if(top>=0 && bottom>=top) bottom-top+1 else height,value("color-transfer"),mediaFormat.containsKey("hdr10-plus-info"))
    }
    override fun onVideoSizeChanged(eventTime:AnalyticsListener.EventTime,videoSize:VideoSize) { this.videoSize=videoSize }
    override fun onSurfaceSizeChanged(eventTime:AnalyticsListener.EventTime,width:Int,height:Int) { surfaceWidth=width;surfaceHeight=height }
    override fun onRenderedFirstFrame(eventTime:AnalyticsListener.EventTime,output:Any,renderTimeMs:Long) { rendered=true }
    override fun onAudioTrackInitialized(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig) {
        audioTracks.addLast(audioTrackConfig);audioOutput=audioTrackConfig
    }
    override fun onAudioTrackReleased(eventTime:AnalyticsListener.EventTime,audioTrackConfig:AudioSink.AudioTrackConfig) {
        // Release callbacks may arrive after a new track starts, with a newly constructed config object.
        val old=audioTracks.firstOrNull { it.encoding==audioTrackConfig.encoding && it.sampleRate==audioTrackConfig.sampleRate &&
            it.channelConfig==audioTrackConfig.channelConfig && it.tunneling==audioTrackConfig.tunneling &&
            it.offload==audioTrackConfig.offload && it.bufferSize==audioTrackConfig.bufferSize }
        if(old!=null) audioTracks.remove(old)
        audioOutput=audioTracks.lastOrNull()
    }
    fun outputSummary(p:ExoPlayer):String {
        val f=frameOutput
        val width=f.width.takeIf { it>0 } ?: videoSize.width
        val height=f.height.takeIf { it>0 } ?: videoSize.height
        val size=if(width>0 && height>0) "${width}×${height}" else "等待尺寸"
        val dolby=p.videoFormat?.sampleMimeType=="video/dolby-vision"
        return "实际解码画面 $size · ${if(rendered) "已输出首帧" else "等待首帧"}\n"+
            "色彩输出 ${OutputLabels.video(f.transfer,dolby,codecMimes[videoDecoder].orEmpty(),rendered)}"+
            (if(f.hdr10Plus) " · HDR10+ 元数据" else "")+"\n系统音频输出 ${OutputLabels.audio(audioOutput?.encoding ?: 0)}"+
            (audioOutput?.let { " · ${it.sampleRate}Hz · ${Integer.bitCount(it.channelConfig)} 声道" } ?: "")+
            "\n"+OutputLabels.atmos(audioOutput?.encoding ?: 0,p.audioFormat?.sampleMimeType)
    }
    @Suppress("DEPRECATION")
    fun displaySummary(display:Display?):String {
        if(display==null) return "显示模式：系统未提供"
        val mode=display.mode
        val hdr=if(Build.VERSION.SDK_INT>=24) display.hdrCapabilities.supportedHdrTypes.map { when(it) {
            1 -> "Dolby Vision";2 -> "HDR10";3 -> "HLG";4 -> "HDR10+";else -> "类型 $it"
        } }.joinToString(" / ").ifBlank { "未报告 HDR 能力" } else "系统未提供"
        return "显示模式 ${mode.physicalWidth}×${mode.physicalHeight} @ %.2fHz\n".format(mode.refreshRate)+
            "显示器支持 $hdr（能力信息）\n屏幕当前 HDR / Dolby Vision 模式：系统未提供可靠确认"
    }

    var videoDecoder="等待解码器";private set
    var audioDecoder="等待解码器";private set
    var dropped=0;private set
    private var underruns=0
    private var offsetUs=0L
    private var offsetFrames=0
    @Volatile var httpStatus="等待请求";private set
    @Volatile var resolvedUrl="";private set
    var sourceBitrate=0L
    private val codecs=ConcurrentHashMap<String,String>()
    private val history=ArrayDeque<Long>()
    private var lastFrames=0
    private var lastFrameAt=SystemClock.elapsedRealtime()
    private var renderFps=0.0
    private var hadReady=false
    private var bufferingAt=0L
    private var bufferingTotal=0L
    private var bufferingCount=0
    fun registerCodec(info:MediaCodecInfo) {
        codecMimes[info.name]=info.codecMimeType
        codecs[info.name]=when { info.softwareOnly -> "软件";info.hardwareAccelerated -> "硬件";else -> "系统未明确" }
    }
    private fun decoderMode(name:String)=if(name.startsWith("ffmpeg")) "FFmpeg 软件音频" else codecs[name] ?: "等待确认"
    override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long) { videoDecoder=decoderName }
    override fun onAudioDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long) { audioDecoder=decoderName }
    override fun onDroppedVideoFrames(eventTime:AnalyticsListener.EventTime,droppedFrames:Int,elapsedMs:Long) { dropped+=droppedFrames }
    override fun onAudioUnderrun(eventTime:AnalyticsListener.EventTime,bufferSize:Int,bufferSizeMs:Long,elapsedSinceLastFeedMs:Long) { underruns++ }
    override fun onVideoFrameProcessingOffset(eventTime:AnalyticsListener.EventTime,totalProcessingOffsetUs:Long,frameCount:Int) { offsetUs=totalProcessingOffsetUs;offsetFrames=frameCount }
    override fun onLoadCompleted(eventTime:AnalyticsListener.EventTime,loadEventInfo:LoadEventInfo,mediaLoadData:MediaLoadData) {
        if(mediaLoadData.dataType!=androidx.media3.common.C.DATA_TYPE_MEDIA ||
            mediaLoadData.trackType==androidx.media3.common.C.TRACK_TYPE_TEXT || resolvedUrl.isNotBlank()) return
        resolvedUrl=loadEventInfo.uri.toString()
        if(httpStatus=="等待请求") httpStatus="2xx 成功"
    }
    fun recordHttp(code:Int,url:String) { httpStatus=code.toString();resolvedUrl=url }
    override fun onLoadError(eventTime:AnalyticsListener.EventTime,loadEventInfo:LoadEventInfo,mediaLoadData:MediaLoadData,error:IOException,wasCanceled:Boolean) {
        resolvedUrl=loadEventInfo.uri.toString()
        httpStatus=tv.ember.client.player.HttpFailures.find(error)?.responseCode?.toString() ?: error.javaClass.simpleName
    }
    fun observe(p:ExoPlayer,now:Long) {
        val counters=p.videoDecoderCounters
        counters?.ensureUpdated()
        val frames=counters?.renderedOutputBufferCount ?: 0
        renderFps=(frames-lastFrames).coerceAtLeast(0)*1000.0/(now-lastFrameAt).coerceAtLeast(1)
        lastFrames=frames;lastFrameAt=now
        if(p.playbackState==Player.STATE_READY) hadReady=true
        val buffering=hadReady && p.playbackState==Player.STATE_BUFFERING && p.playWhenReady
        if(buffering && bufferingAt==0L) { bufferingAt=now;bufferingCount++ }
        if(!buffering && bufferingAt>0L) { bufferingTotal+=now-bufferingAt;bufferingAt=0 }
        history.addLast(maxOf(0,p.bufferedPosition-p.currentPosition));if(history.size>8) history.removeFirst()
    }
    fun sourceSummary(version:MediaVersion):String {
        val v=version.streams.firstOrNull { it.type=="Video" }
        return "片源 ${version.container.uppercase()} · ${v?.codec ?: "未知"}"+
            (v?.bitDepth?.takeIf { it>0 }?.let { " ${it}bit" } ?: "")+
            (v?.frameRate?.takeIf { it>0 }?.let { " %.2ffps".format(it) } ?: "")
    }
    fun summary(p:ExoPlayer):String {
        val v=p.videoFormat;val a=p.audioFormat
        val buffer=maxOf(0,p.bufferedPosition-p.currentPosition)
        return "片源总码率 ${mbps(sourceBitrate)} · 缓冲 %.1fs\n".format(buffer/1000.0)+
            outputSummary(p)+"\n"+
            "解码输入 ${v?.sampleMimeType ?: "等待"} · ${v?.width ?: "—"}×${v?.height ?: "—"}${color(v)}\n"+
            "视频 ${decoderMode(videoDecoder)} · $videoDecoder\n音频 ${a?.sampleMimeType ?: "等待"} · ${a?.channelCount?.takeIf { it>0 } ?: "—"} 声道\n"+
            "输出 %.1ffps · 丢帧 $dropped · 缓冲 $bufferingCount 次".format(renderFps)
    }
    fun details(p:ExoPlayer,version:MediaVersion):String {
        val v=p.videoFormat;val a=p.audioFormat;val counters=p.videoDecoderCounters
        val now=SystemClock.elapsedRealtime()
        val bufferingMs=bufferingTotal+if(bufferingAt>0) now-bufferingAt else 0
        val sourceVideo=version.streams.firstOrNull { it.type=="Video" }
        return sourceSummary(version)+"\n片源名称 ${version.name}\n片源大小 ${version.sizeBytes.takeIf { it>0 }?.let { "%.1fMiB".format(it/1048576.0) } ?: "未提供"}"+
            "\n片源视频 Profile ${sourceVideo?.profile?.ifBlank { "未提供" } ?: "未提供"}\n片源总码率 ${mbps(sourceBitrate)}（包含音频等）\n"+
            "片源声明 ${sourceVideo?.width ?: 0}×${sourceVideo?.height ?: 0} · ${sourceVideo?.videoRange?.ifBlank { "未提供" } ?: "未提供"}\n"+
            "实际视频输入 ${format(v)}${color(v)}\n实际音频输入 ${format(a)}\n"+
            outputSummary(p)+"\nSurface ${surfaceWidth}×${surfaceHeight}（画布，不是片源分辨率）\n"+
            "解码器 MIME ${codecMimes[videoDecoder] ?: "等待确认"}\n"+
            (audioOutput?.let { "AudioTrack ${it.sampleRate}Hz · 声道掩码 0x${it.channelConfig.toString(16)} · ${if(it.offload) "Offload" else "标准输出"} · ${if(it.tunneling) "Tunneling" else "非隧道"}\n" } ?: "AudioTrack 等待初始化\n")+
            OutputLabels.atmos(audioOutput?.encoding ?: 0,a?.sampleMimeType)+"\n"+
            "实际视频解码器 ${decoderMode(videoDecoder)} · $videoDecoder\n实际音频解码器 ${decoderMode(audioDecoder)} · $audioDecoder\n"+
            "输出 %.1ffps · 已渲染 ${counters?.renderedOutputBufferCount ?: 0} 帧 · 跳过 ${counters?.skippedOutputBufferCount ?: 0} · 丢帧 $dropped\n".format(renderFps)+
            "帧处理偏移 ${if(offsetFrames>0) "%.2fms".format(offsetUs/1000.0/offsetFrames) else "未测"} · 音频欠载 $underruns 次\n"+
            "缓冲 $bufferingCount 次 · 累计 %.1fs（排除首次启动，含拖动后缓冲）\n".format(bufferingMs/1000.0)+
            "状态 ${state(p.playbackState)} · loading=${p.isLoading} · playing=${p.isPlaying}\n缓冲趋势 ${history.joinToString(" → ") { "${it/1000}s" }}"
    }
    fun debug(p:ExoPlayer,original:String,server:String):String {
        val actual=resolvedUrl.ifBlank { original }
        val host=runCatching { java.net.URI(actual).host }.getOrNull().orEmpty()
        return "取流 $host / ${actual.substringBefore('?').substringAfterLast('/')}\nHTTP $httpStatus · ${state(p.playbackState)} · loading=${p.isLoading}\n"+
            "缓冲趋势 ${history.joinToString(" → ") { "${it/1000}s" }}\n视频 $videoDecoder\n音频 $audioDecoder"
    }
    companion object {
        private fun mbps(value:Long)=if(value>0) "%.1fMbps".format(value/1_000_000.0) else "未知"
        private fun color(v:Format?):String {
            val info=v?.colorInfo ?: return ""
            val depth=info.lumaBitdepth.takeIf { it>0 }?.let { " · ${it}bit" } ?: ""
            return depth+when(info.colorTransfer) { 6 -> " · PQ / HDR";7 -> " · HLG";else -> "" }
        }
        private fun format(f:Format?):String {
            if(f==null) return "等待轨道"
            return listOfNotNull(f.containerMimeType,f.sampleMimeType,f.codecs,
                if(f.width>0 && f.height>0) "${f.width}×${f.height}" else null,
                f.frameRate.takeIf { it>0 }?.let { "%.2ffps".format(it) },
                f.bitrate.takeIf { it>0 }?.let { mbps(it.toLong()) },
                f.channelCount.takeIf { it>0 }?.let { "${it}声道" },
                f.sampleRate.takeIf { it>0 }?.let { "${it}Hz" }).joinToString(" · ")
        }
        fun redact(url:String):String = url.replace(Regex("(?<=://)[^/@\\s]+@"),"***@")
            .replace(Regex("([?&][^=&#\\s]+)=([^&#\\s]*)"),"$1=***").substringBefore('#')
        fun state(s:Int)=when(s) { Player.STATE_BUFFERING -> "BUFFERING";Player.STATE_READY -> "READY";Player.STATE_ENDED -> "ENDED";else -> "IDLE" }
    }
}
