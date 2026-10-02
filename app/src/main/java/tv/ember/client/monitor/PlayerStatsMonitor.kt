package tv.ember.client.monitor

import android.os.SystemClock
import androidx.media3.common.Format
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
class PlayerStatsMonitor : AnalyticsListener {
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
        codecs[info.name]=when { info.softwareOnly -> "软件";info.hardwareAccelerated -> "硬件";else -> "系统未明确" }
    }
    private fun decoderMode(name:String)=codecs[name] ?: "等待确认"
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
            "实际视频输入 ${format(v)}${color(v)}\n实际音频输入 ${format(a)}\n"+
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
            return depth+when(info.colorTransfer) { 6 -> " · HDR10/PQ";7 -> " · HLG";else -> "" }
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
