package tv.ember.client

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.monitor.PlayerStatsMonitor
import tv.ember.client.player.TvRenderersFactory

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class LosslessAudioDeviceTest {
    @Test fun unsupportedDeviceTrueHdAndDtsDecodeToPcmAndSeekWithFfmpeg() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        tv.ember.client.i18n.AppLanguage.save(context,"en")
        assertTrue(FfmpegLibrary.supportsFormat("audio/mlp"))
        val base=InstrumentationRegistry.getArguments().getString("losslessFixtureBase") ?: "/sdcard/Android/data/tv.ember.client/files"
        for((file,mime) in listOf("truehd.mka" to MimeTypes.AUDIO_TRUEHD,"dts.mka" to MimeTypes.AUDIO_DTS)) {
            val stats=PlayerStatsMonitor()
            lateinit var player:ExoPlayer
            instrumentation.runOnMainSync {
                // Force the unsupported-device path; no firmware-specific decoder is available in the emulator.
                val factory=TvRenderersFactory(context).setMediaCodecSelector { _,_,_-> emptyList() }
                player=ExoPlayer.Builder(context,factory).build()
                player.addAnalyticsListener(stats);player.setMediaItem(MediaItem.fromUri("$base/$file"));player.prepare();player.play()
            }
            fun await(position: Long) {
                val until=SystemClock.elapsedRealtime()+30000
                var observation=""
                while(SystemClock.elapsedRealtime()<until) {
                    var ready=false
                    instrumentation.runOnMainSync {
                        assertNull(player.playerError)
                        observation="state=${player.playbackState}, pos=${player.currentPosition}, mime=${player.audioFormat?.sampleMimeType}, decoder=${stats.audioDecoder}, buffers=${player.audioDecoderCounters?.renderedOutputBufferCount}, output=${stats.outputSummary(player)}"
                        ready=player.isPlaying && player.currentPosition>=position &&
                            (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0)>0 && stats.outputSummary(player).contains("System audio output PCM")
                    }
                    if(ready) return
                    Thread.sleep(100)
                }
                fail("$file did not produce PCM at $position: $observation")
            }
            try {
                await(1000)
                instrumentation.runOnMainSync {
                    assertEquals(mime,player.audioFormat?.sampleMimeType);assertTrue(stats.audioDecoder.contains("ffmpeg",true))
                    assertTrue(stats.details(player,tv.ember.client.data.MediaVersion.parse(org.json.JSONObject().put("Id","test"))).contains("FFmpeg"))
                    player.seekTo(6000)
                }
                await(6500)
            } finally { instrumentation.runOnMainSync { player.release() } }
        }
    }
}
