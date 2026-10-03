package tv.ember.client.player

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

@UnstableApi
class TvRenderersFactory(context: Context, private val softwareAudio: Boolean = false) : DefaultRenderersFactory(context) {
    override fun buildAudioRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, audioSink: AudioSink, eventHandler: Handler,
        eventListener: AudioRendererEventListener, out: ArrayList<Renderer>
    ) {
        val pcmSink = object : ForwardingAudioSink(audioSink) {
            override fun supportsFormat(format: Format) = !LosslessAudioPolicy.decodeToPcm(format.sampleMimeType) && super.supportsFormat(format)
            override fun getFormatSupport(format: Format) = if(LosslessAudioPolicy.decodeToPcm(format.sampleMimeType))
                AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)
            override fun getFormatOffloadSupport(format: Format) = if(LosslessAudioPolicy.decodeToPcm(format.sampleMimeType))
                AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)
        }
        // Capability selection chooses MediaCodec first. FFmpeg handles unsupported formats.
        // Runtime vendor failures rebuild with FFmpeg first, preserving position and track preferences.
        if(softwareAudio) out.add(FfmpegAudioRenderer(eventHandler, eventListener, pcmSink))
        super.buildAudioRenderers(context, EXTENSION_RENDERER_MODE_OFF, mediaCodecSelector,
            enableDecoderFallback, pcmSink, eventHandler, eventListener, out)
        if(!softwareAudio) out.add(FfmpegAudioRenderer(eventHandler, eventListener, pcmSink))
    }
}
