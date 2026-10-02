package tv.ember.client

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.monitor.PlayerStatsMonitor
import tv.ember.client.player.TvRenderersFactory
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class DtsAudioDeviceTest {
    @Test fun nativeDtsAudioProducesPcmAndResumesAfterSeek() {
        val fixture=InstrumentationRegistry.getArguments().getString("dtsFixture")
        assumeNotNull(fixture)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        tv.ember.client.i18n.AppLanguage.save(context,"zh")
        val file=File(requireNotNull(fixture))
        assertTrue("Generate an AVC/DTS MKV and copy it into the app cache first",file.isFile)
        assertTrue(FfmpegLibrary.isAvailable())
        assertTrue(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_DTS))
        assertTrue(FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_DTS_HD))
        val stats=PlayerStatsMonitor()
        lateinit var player:ExoPlayer
        instrumentation.runOnMainSync {
            player=ExoPlayer.Builder(context,TvRenderersFactory(context)).build()
            player.addAnalyticsListener(stats)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare();player.play()
        }
        fun await(position:Long) {
            val deadline=SystemClock.elapsedRealtime()+20_000
            while(SystemClock.elapsedRealtime()<deadline) {
                var ready=false
                instrumentation.runOnMainSync {
                    assertNull("Playback failure",player.playerError)
                    ready=player.isPlaying && player.currentPosition>=position &&
                        stats.outputSummary(player).contains("系统音频输出 PCM") &&
                        (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0)>0
                }
                if(ready) return
                Thread.sleep(100)
            }
            fail("Native DTS audio did not advance after playback/seek")
        }
        try {
            await(1000)
            instrumentation.runOnMainSync {
                assertEquals(MimeTypes.AUDIO_DTS,player.audioFormat?.sampleMimeType)
                assertTrue(stats.audioDecoder,stats.audioDecoder.contains("ffmpeg",true))
                player.seekTo(6000)
            }
            await(6500)
        } finally { instrumentation.runOnMainSync { player.release() } }
    }
}
