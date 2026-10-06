package tv.ember.client.monitor

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
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
        val size=if(width>0 && height>0) "${width}×${height}" else Tr.text(UiText.WAITING_FOR_DIMENSIONS_058)
        val dolby=p.videoFormat?.sampleMimeType=="video/dolby-vision"
        return Tr.text(UiText.DECODED_VIDEO_061 ,(size),(if(rendered) Tr.text(UiText.FIRST_FRAME_RENDERED_059) else Tr.text(UiText.WAITING_FOR_FIRST_FRAME_060)))+
            Tr.text(UiText.COLOR_OUTPUT_062 ,(OutputLabels.video(f.transfer,dolby,codecMimes[videoDecoder].orEmpty(),rendered)))+
            (if(f.hdr10Plus) Tr.text(UiText.HDR_METADATA_063) else "")+Tr.text(UiText.SYSTEM_AUDIO_OUTPUT_064 ,(OutputLabels.audio(audioOutput?.encoding ?: 0)))+
            (audioOutput?.let { Tr.text(UiText.HZ_CHANNELS_065 ,(it.sampleRate),(Integer.bitCount(it.channelConfig))) } ?: "")+
            "\n"+OutputLabels.atmos(audioOutput?.encoding ?: 0,p.audioFormat?.sampleMimeType)
    }
    @Suppress("DEPRECATION")
    fun displaySummary(display:Display?):String {
        if(display==null) return Tr.text(UiText.DISPLAY_MODE_NOT_REPORTED_BY_SYSTEM_066)
        val mode=display.mode
        val hdr=if(Build.VERSION.SDK_INT>=24) display.hdrCapabilities.supportedHdrTypes.map { when(it) {
            1 -> "Dolby Vision";2 -> "HDR10";3 -> "HLG";4 -> "HDR10+";else -> Tr.text(UiText.TYPE_067 ,(it))
        } }.joinToString(" / ").ifBlank { Tr.text(UiText.HDR_CAPABILITIES_NOT_REPORTED_068) } else Tr.text(UiText.NOT_REPORTED_BY_SYSTEM_037)
        return Tr.text(UiText.DISPLAY_MODE_FHZ_069 ,(mode.physicalWidth),(mode.physicalHeight)).format(mode.refreshRate)+
            Tr.text(UiText.DISPLAY_SUPPORTS_CAPABILITIES_CURRENT_SCREEN_HDR_070 ,(hdr))
    }

    var videoDecoder=Tr.text(UiText.WAITING_FOR_DECODER_071);private set
    var audioDecoder=Tr.text(UiText.WAITING_FOR_DECODER_071);private set
    var dropped=0;private set
    private var underruns=0
    private var offsetUs=0L
    private var offsetFrames=0
    @Volatile var httpStatus=Tr.text(UiText.WAITING_FOR_REQUEST_072);private set
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
        codecs[info.name]=when { info.softwareOnly -> Tr.text(UiText.SOFTWARE_073);info.hardwareAccelerated -> Tr.text(UiText.HARDWARE_074);else -> Tr.text(UiText.UNSPECIFIED_BY_SYSTEM_075) }
    }
    private fun decoderMode(name:String)=if(name.startsWith("ffmpeg")) Tr.text(UiText.FFMPEG_SOFTWARE_AUDIO_076) else codecs[name] ?: Tr.text(UiText.AWAITING_CONFIRMATION_077)
    override fun onVideoDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long) { videoDecoder=decoderName }
    override fun onAudioDecoderInitialized(eventTime:AnalyticsListener.EventTime,decoderName:String,initializedTimestampMs:Long,initializationDurationMs:Long) { audioDecoder=decoderName }
    override fun onDroppedVideoFrames(eventTime:AnalyticsListener.EventTime,droppedFrames:Int,elapsedMs:Long) { dropped+=droppedFrames }
    override fun onAudioUnderrun(eventTime:AnalyticsListener.EventTime,bufferSize:Int,bufferSizeMs:Long,elapsedSinceLastFeedMs:Long) { underruns++ }
    override fun onVideoFrameProcessingOffset(eventTime:AnalyticsListener.EventTime,totalProcessingOffsetUs:Long,frameCount:Int) { offsetUs=totalProcessingOffsetUs;offsetFrames=frameCount }
    override fun onLoadCompleted(eventTime:AnalyticsListener.EventTime,loadEventInfo:LoadEventInfo,mediaLoadData:MediaLoadData) {
        if(mediaLoadData.dataType!=androidx.media3.common.C.DATA_TYPE_MEDIA ||
            mediaLoadData.trackType==androidx.media3.common.C.TRACK_TYPE_TEXT || resolvedUrl.isNotBlank()) return
        resolvedUrl=loadEventInfo.uri.toString()
        if(httpStatus==Tr.text(UiText.WAITING_FOR_REQUEST_072)) httpStatus=Tr.text(UiText.XX_SUCCESS_078)
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
        return Tr.text(UiText.SOURCE_080 ,(version.container.uppercase()),(v?.codec ?: Tr.text(UiText.UNKNOWN_079)))+
            (v?.bitDepth?.takeIf { it>0 }?.let { " ${it}bit" } ?: "")+
            (v?.frameRate?.takeIf { it>0 }?.let { " %.2ffps".format(it) } ?: "")
    }
    fun summary(p:ExoPlayer):String {
        val v=p.videoFormat;val a=p.audioFormat
        val buffer=maxOf(0,p.bufferedPosition-p.currentPosition)
        return Tr.text(UiText.TOTAL_SOURCE_BITRATE_BUFFER_FS_081 ,(mbps(sourceBitrate))).format(buffer/1000.0)+
            outputSummary(p)+"\n"+
            Tr.text(UiText.DECODER_INPUT_082 ,(v?.sampleMimeType ?: Tr.text(UiText.WAITING_029)),(v?.width ?: "—"),(v?.height ?: "—"),(color(v)))+
            Tr.text(UiText.VIDEO_AUDIO_CHANNELS_083 ,(decoderMode(videoDecoder)),(videoDecoder),(a?.sampleMimeType ?: Tr.text(UiText.WAITING_029)),(a?.channelCount?.takeIf { it>0 } ?: "—"))+
            Tr.text(UiText.OUTPUT_FFPS_DROPPED_BUFFERING_TIMES_084 ,(dropped),(bufferingCount)).format(renderFps)
    }
    fun cinemaHud(p:ExoPlayer,version:MediaVersion,bytesPerSecond:Long,allocatedBytes:Long):List<Pair<String,String>> {
        val v=p.videoFormat;val source=version.streams.firstOrNull { it.type=="Video" }
        val width=frameOutput.width.takeIf { it>0 } ?: videoSize.width
        val height=frameOutput.height.takeIf { it>0 } ?: videoSize.height
        val renderedFrames=p.videoDecoderCounters?.renderedOutputBufferCount ?: 0
        val total=renderedFrames+dropped
        val audio=audioOutput
        val now=SystemClock.elapsedRealtime()
        val bufferingMs=bufferingTotal+if(bufferingAt>0) now-bufferingAt else 0
        return listOf(
            Tr.text(UiText.HUD_VIDEO_FORMAT) to listOf(v?.codecs?.takeIf(String::isNotBlank) ?: source?.codec.orEmpty(),source?.profile.orEmpty(),source?.videoRange.orEmpty()).filter(String::isNotBlank).joinToString(" · "),
            Tr.text(UiText.HUD_RESOLUTION) to if(width>0 && height>0) "${width} × ${height}"+(v?.frameRate?.takeIf { it>0 }?.let { " @ %.3f fps".format(it) } ?: "") else Tr.text(UiText.WAITING_FOR_DIMENSIONS_058),
            Tr.text(UiText.HUD_BITRATE) to mbps(sourceBitrate)+" / "+"%.2f Mbps".format(bytesPerSecond.coerceAtLeast(0)*8/1_000_000.0),
            Tr.text(UiText.HUD_AUDIO) to OutputLabels.audio(audio?.encoding ?: 0)+(audio?.let { " · ${it.sampleRate} Hz · ${Integer.bitCount(it.channelConfig)} ch" } ?: ""),
            Tr.text(UiText.HUD_DROPPED) to "$dropped / $total ("+"%.2f%%".format(if(total>0) dropped*100.0/total else 0.0)+")",
            Tr.text(UiText.HUD_BUFFER) to "${allocatedBytes/1048576} MiB ("+"%.1f".format((p.bufferedPosition-p.currentPosition).coerceAtLeast(0)/1000.0)+" s)",
            Tr.text(UiText.HUD_VIDEO_DECODER) to "${decoderMode(videoDecoder)} · $videoDecoder",
            Tr.text(UiText.HUD_AUDIO_DECODER) to "${decoderMode(audioDecoder)} · $audioDecoder",
            Tr.text(UiText.HUD_RENDER) to "%.1f fps · %d skipped".format(renderFps,p.videoDecoderCounters?.skippedOutputBufferCount ?: 0),
            Tr.text(UiText.HUD_COLOR) to OutputLabels.video(frameOutput.transfer,v?.sampleMimeType=="video/dolby-vision",codecMimes[videoDecoder].orEmpty(),rendered),
            Tr.text(UiText.HUD_STALLS) to "$bufferingCount / "+"%.1f s · %d".format(bufferingMs/1000.0,underruns),
            Tr.text(UiText.HUD_PLAYER_STATE) to state(p.playbackState)+" · "+"%.2f×".format(p.playbackParameters.speed)
        )
    }
    fun details(p:ExoPlayer,version:MediaVersion):String {
        val v=p.videoFormat;val a=p.audioFormat;val counters=p.videoDecoderCounters
        val now=SystemClock.elapsedRealtime()
        val bufferingMs=bufferingTotal+if(bufferingAt>0) now-bufferingAt else 0
        val sourceVideo=version.streams.firstOrNull { it.type=="Video" }
        return sourceSummary(version)+Tr.text(UiText.SOURCE_NAME_SOURCE_SIZE_085 ,(version.name),(version.sizeBytes.takeIf { it>0 }?.let { "%.1fMiB".format(it/1048576.0) } ?: Tr.text(UiText.NOT_PROVIDED_026)))+
            Tr.text(UiText.SOURCE_VIDEO_PROFILE_TOTAL_SOURCE_BITRATE_086 ,(sourceVideo?.profile?.ifBlank { Tr.text(UiText.NOT_PROVIDED_026) } ?: Tr.text(UiText.NOT_PROVIDED_026)),(mbps(sourceBitrate)))+
            Tr.text(UiText.SOURCE_DECLARES_087 ,(sourceVideo?.width ?: 0),(sourceVideo?.height ?: 0),(sourceVideo?.videoRange?.ifBlank { Tr.text(UiText.NOT_PROVIDED_026) } ?: Tr.text(UiText.NOT_PROVIDED_026)))+
            Tr.text(UiText.ACTUAL_VIDEO_INPUT_ACTUAL_AUDIO_INPUT_088 ,(format(v)),(color(v)),(format(a)))+
            outputSummary(p)+Tr.text(UiText.SURFACE_CANVAS_NOT_SOURCE_RESOLUTION_089 ,(surfaceWidth),(surfaceHeight))+
            Tr.text(UiText.DECODER_MIME_090 ,(codecMimes[videoDecoder] ?: Tr.text(UiText.AWAITING_CONFIRMATION_077)))+
            (audioOutput?.let { Tr.text(UiText.AUDIOTRACK_HZ_CHANNEL_MASK_X_093 ,(it.sampleRate),(it.channelConfig.toString(16)),(if(it.offload) "Offload" else Tr.text(UiText.STANDARD_OUTPUT_091)),(if(it.tunneling) "Tunneling" else Tr.text(UiText.NON_TUNNELED_092))) } ?: Tr.text(UiText.AUDIOTRACK_AWAITING_INITIALIZATION_094))+
            OutputLabels.atmos(audioOutput?.encoding ?: 0,a?.sampleMimeType)+"\n"+
            Tr.text(UiText.VIDEO_DECODER_AUDIO_DECODER_095 ,(decoderMode(videoDecoder)),(videoDecoder),(decoderMode(audioDecoder)),(audioDecoder))+
            Tr.text(UiText.OUTPUT_FFPS_RENDERED_FRAMES_SKIPPED_DROPPED_096 ,(counters?.renderedOutputBufferCount ?: 0),(counters?.skippedOutputBufferCount ?: 0),(dropped)).format(renderFps)+
            Tr.text(UiText.FRAME_PROCESSING_OFFSET_AUDIO_UNDERRUNS_098 ,(if(offsetFrames>0) "%.2fms".format(offsetUs/1000.0/offsetFrames) else Tr.text(UiText.NOT_MEASURED_097)),(underruns))+
            Tr.text(UiText.BUFFERING_TIMES_TOTAL_FS_EXCLUDES_STARTUP_099 ,(bufferingCount)).format(bufferingMs/1000.0)+
            Tr.text(UiText.STATE_LOADING_PLAYING_BUFFER_TREND_100 ,(state(p.playbackState)),(p.isLoading),(p.isPlaying),(history.joinToString(" → ") { "${it/1000}s" }))
    }
    fun debug(p:ExoPlayer,original:String,server:String):String {
        val actual=resolvedUrl.ifBlank { original }
        val host=runCatching { java.net.URI(actual).host }.getOrNull().orEmpty()
        return Tr.text(UiText.STREAM_HTTP_LOADING_101 ,(host),(actual.substringBefore('?').substringAfterLast('/')),(httpStatus),(state(p.playbackState)),(p.isLoading))+
            Tr.text(UiText.BUFFER_TREND_VIDEO_AUDIO_102 ,(history.joinToString(" → ") { "${it/1000}s" }),(videoDecoder),(audioDecoder))
    }
    companion object {
        private fun mbps(value:Long)=if(value>0) "%.1fMbps".format(value/1_000_000.0) else Tr.text(UiText.UNKNOWN_079)
        private fun color(v:Format?):String {
            val info=v?.colorInfo ?: return ""
            val depth=info.lumaBitdepth.takeIf { it>0 }?.let { " · ${it}bit" } ?: ""
            return depth+when(info.colorTransfer) { 6 -> " · PQ / HDR";7 -> " · HLG";else -> "" }
        }
        private fun format(f:Format?):String {
            if(f==null) return Tr.text(UiText.WAITING_FOR_TRACKS_103)
            return listOfNotNull(f.containerMimeType,f.sampleMimeType,f.codecs,
                if(f.width>0 && f.height>0) "${f.width}×${f.height}" else null,
                f.frameRate.takeIf { it>0 }?.let { "%.2ffps".format(it) },
                f.bitrate.takeIf { it>0 }?.let { mbps(it.toLong()) },
                f.channelCount.takeIf { it>0 }?.let { Tr.text(UiText.CHANNELS_104 ,(it)) },
                f.sampleRate.takeIf { it>0 }?.let { "${it}Hz" }).joinToString(" · ")
        }
        fun redact(url:String):String = url.replace(Regex("(?<=://)[^/@\\s]+@"),"***@")
            .replace(Regex("([?&][^=&#\\s]+)=([^&#\\s]*)"),"$1=***").substringBefore('#')
        fun state(s:Int)=when(s) { Player.STATE_BUFFERING -> "BUFFERING";Player.STATE_READY -> "READY";Player.STATE_ENDED -> "ENDED";else -> "IDLE" }
    }
}
