package tv.ember.client

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.player.BufferPolicy
import tv.ember.client.player.TvLoadControl

class TvLoadControlTest {
    @org.junit.Before fun silenceAndroidStubLogging() { androidx.media3.common.util.Log.setLogLevel(androidx.media3.common.util.Log.LOG_LEVEL_OFF) }
    private val timeline=androidx.media3.exoplayer.source.SinglePeriodTimeline(90_000_000,true,false,false,null,androidx.media3.common.MediaItem.Builder().build())
    private fun parameters(bufferedUs: Long, speed: Float = 1f) = LoadControl.Parameters(
        PlayerId.UNSET, timeline, MediaPeriodId(timeline.getUidOfPeriod(0)), 0, bufferedUs, speed,
        true, false, C.TIME_UNSET, C.TIME_UNSET
    )
    private fun fullControl(): TvLoadControl {
        val control = TvLoadControl(BufferPolicy(30_000, 90_000, 2000, 2000, 1_048_576, 5000))
        control.onPrepared(PlayerId.UNSET)
        repeat(16) { control.getAllocator(PlayerId.UNSET).allocate() }
        control.markSeek()
        return control
    }
    @Test fun retainedMemoryAloneCannotStartPlaybackAfterSeek() {
        val control = fullControl()
        assertFalse(control.shouldStartPlayback(parameters(0)))
        assertFalse(control.shouldStartPlayback(parameters(50_000)))
        assertTrue(control.shouldStartPlayback(parameters(500_000)))
    }
    @Test fun anEmptyForwardBufferMayUseOnlyABoundedRecoveryReserve() {
        val control = fullControl()
        assertTrue(control.shouldContinueLoading(parameters(0)))
        repeat(4) { control.getAllocator(PlayerId.UNSET).allocate() }
        assertFalse(control.shouldContinueLoading(parameters(0)))
    }
    @Test fun loadingStopsAtTheTargetOnceThereIsPlayableForwardMedia() {
        val control = fullControl()
        assertFalse(control.shouldContinueLoading(parameters(500_000)))
        assertTrue(control.shouldStartPlayback(parameters(500_000)))
    }
    @Test fun playbackSpeedScalesTheRequiredForwardBuffer() {
        val control = fullControl()
        assertFalse(control.shouldStartPlayback(parameters(500_000, 2f)))
        assertTrue(control.shouldContinueLoading(parameters(500_000, 2f)))
        assertTrue(control.shouldStartPlayback(parameters(1_000_000, 2f)))
    }
    @Test fun normalPlaybackAndRebufferMatchDefaultLoadControlAtAllBufferLevels() {
        val policy=BufferPolicy(30000,90000,2000,5000,1048576)
        val control=TvLoadControl(policy)
        val delegate=DefaultLoadControl.Builder().setBufferDurationsMs(policy.minMs,policy.maxMs,policy.startMs,policy.rebufferMs)
            .setTargetBufferBytes(policy.targetBytes).setPrioritizeTimeOverSizeThresholds(false).build()
        control.onPrepared(PlayerId.UNSET);delegate.onPrepared(PlayerId.UNSET)
        for(full in listOf(false,true)) {
            if(full) repeat(16) { control.getAllocator(PlayerId.UNSET).allocate();delegate.getAllocator(PlayerId.UNSET).allocate() }
            for(speed in listOf(1f,2f)) for(buffer in listOf(0L,50000L,500000L,1200000L,5000000L,30000000L,90000000L)) {
                val p=parameters(buffer,speed)
                assertEquals(delegate.shouldContinueLoading(p),control.shouldContinueLoading(p))
                assertEquals(delegate.shouldStartPlayback(p),control.shouldStartPlayback(p))
            }
        }
    }
    @Test fun seekOverrideEndsAfterStarting() {
        val control=fullControl()
        assertTrue(control.shouldStartPlayback(parameters(500000)))
        assertFalse(control.shouldContinueLoading(parameters(0)))
        assertTrue(control.shouldStartPlayback(parameters(0)))
    }
}
